package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.domain.kong.KongRoute;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WSO2 API Product → Kong routes (re-exposing member-API operations under the
 * product's context) plus the product's policies/security as <em>route-scoped</em>
 * plugins.
 *
 * <p>Only the policies/security the product <b>actually has in WSO2</b> are
 * translated — a product with none gets plain routes. Because a product has no
 * single Kong service, its product-level plugins are replicated (fresh copies)
 * onto each of the product's own routes. Per-operation throttling tiers add a
 * route-scoped rate-limit to that one route. Member API services are resolved
 * from {@code entity_mappings} at deploy time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiProductTranslator {

    private final MigrationProperties props;
    private static final List<String> DEFAULT_PROTOCOLS = List.of("http", "https");
    private static final List<String> ALL_CORS_METHODS = List.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT");

    public TranslatedApiProduct translate(DiscoverySnapshot snap) {
        Map<String, Object> p = snap.getPayload() != null ? snap.getPayload() : Collections.emptyMap();
        String productName = snap.getSourceName() != null ? snap.getSourceName() : str(p.get("name"));
        String productVersion = snap.getSourceVersion() != null ? snap.getSourceVersion() : str(p.get("version"));
        String context = str(p.get("context"));
        if (!StringUtils.hasText(context)) context = "/" + slug(productName);
        String productSlug = slug(productName)
                + (StringUtils.hasText(productVersion) ? "-" + slug(productVersion) : "");

        List<String> baseTags = new ArrayList<>();
        baseTags.add(props.getTranslation().getTagPrefix() + ":" + snap.getSourceId());
        baseTags.add(props.getTranslation().getMigratedByTag());
        baseTags.add("wso2-api-product:" + productName);

        List<String> warnings = new ArrayList<>();

        // Product-level plugins — ONLY the policies/security this product actually has.
        List<KongPlugin> productPlugins = buildProductPlugins(p, baseTags);

        List<TranslatedApiProduct.ProductRoute> routes = new ArrayList<>();

        List<Map<String, Object>> memberApis = listOfMaps(p.get("apis"));
        if (memberApis == null || memberApis.isEmpty()) {
            warnings.add("API Product '" + productName + "' has no member APIs in the snapshot — nothing to route.");
        } else {
            for (Map<String, Object> m : memberApis) {
                String memberId = firstNonNull(m, "apiId", "id");
                String memberName = str(m.get("name"));
                if (memberId == null) {
                    warnings.add("API Product '" + productName + "' has a member API with no id — skipped.");
                    continue;
                }
                List<Map<String, Object>> ops = listOfMaps(m.get("operations"));
                if (ops == null || ops.isEmpty()) {
                    String path = joinPaths(context, "/" + slug(memberName));
                    routes.add(route(productSlug + "--" + slug(memberName) + "--all", path,
                            List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"),
                            tagsWith(baseTags, "wso2-member-api:" + memberId), memberId,
                            copies(productPlugins)));
                    continue;
                }
                for (Map<String, Object> op : ops) {
                    String verb = upper(str(op.get("verb")));
                    String target = str(op.get("target"));
                    if (!StringUtils.hasText(verb) || !StringUtils.hasText(target)) continue;
                    String tier = str(op.get("throttlingPolicy"));
                    // Kong 3.x: templated paths (/items/{id}) must become ~/ regex paths.
                    String path = ApiTranslator.kongRoutePath(joinPaths(context, normalize(target)));

                    List<KongPlugin> routePlugins = copies(productPlugins);
                    if (StringUtils.hasText(tier) && !"Unlimited".equalsIgnoreCase(tier)) {
                        Integer rpm = props.getTranslation().getThrottlingTierMap().get(tier);
                        if (rpm == null) rpm = props.getTranslation().getDefaultThrottleRpm();
                        routePlugins.add(rateLimit(rpm, tagsWith(baseTags, "wso2-tier:" + tier)));
                    }
                    routes.add(route(
                            productSlug + "--" + slug(memberName) + "--" + verb.toLowerCase() + slug(target),
                            path, List.of(verb),
                            tagsWith(baseTags, "wso2-member-api:" + memberId,
                                    "wso2-resource:" + target, "wso2-verb:" + verb),
                            memberId, routePlugins));
                }
            }
        }

        return TranslatedApiProduct.builder()
                .wso2SourceId(snap.getSourceId())
                .wso2SourceName(productName)
                .wso2SourceVersion(productVersion)
                .routes(routes)
                .warnings(warnings)
                .build();
    }

    // ---------------- plugin building (same rules ApiTranslator uses for APIs) ----------------

    private List<KongPlugin> buildProductPlugins(Map<String, Object> p, List<String> baseTags) {
        List<KongPlugin> plugins = new ArrayList<>();

        // 1) Product throttling tier (policies array) → rate-limiting
        List<String> policies = stringList(p.get("policies"));
        if (policies != null) {
            for (String policy : policies) {
                Integer rpm = props.getTranslation().getThrottlingTierMap().get(policy);
                if (rpm != null && !"Unlimited".equalsIgnoreCase(policy)) {
                    plugins.add(rateLimit(rpm, tagsWith(baseTags, "wso2-tier:" + policy)));
                    break;
                }
            }
        }

        // 2) Security: oauth2 → jwt, api_key → key-auth
        List<String> security = stringList(p.get("securityScheme"));
        if (security != null) {
            boolean oauth = security.stream().anyMatch(s -> s != null && s.toLowerCase().contains("oauth2"));
            boolean apiKey = security.stream().anyMatch(s -> s != null && s.toLowerCase().contains("api_key"));
            if (oauth) plugins.add(plugin("jwt", null, baseTags));
            if (apiKey) plugins.add(plugin("key-auth", null, baseTags));
        }

        // 3) CORS
        Map<String, Object> cors = mapOf(p.get("corsConfiguration"));
        if (cors != null && Boolean.TRUE.equals(cors.get("corsConfigurationEnabled"))) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("origins", List.of("*"));
            cfg.put("methods", ALL_CORS_METHODS);
            cfg.put("headers", List.of("*"));
            cfg.put("exposed_headers", List.of("*"));
            cfg.put("credentials", false);
            plugins.add(plugin("cors", cfg, baseTags));
        }

        // 4) Response cache → proxy-cache
        if (Boolean.TRUE.equals(p.get("responseCachingEnabled"))) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("response_code", List.of(200));
            cfg.put("request_method", List.of("GET"));
            int ttl = p.get("cacheTimeout") instanceof Number n ? n.intValue() : 300;
            cfg.put("cache_ttl", ttl);
            cfg.put("strategy", "memory");
            plugins.add(plugin("proxy-cache", cfg, baseTags));
        }

        return plugins;
    }

    private KongPlugin rateLimit(int rpm, List<String> tags) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("minute", rpm);
        cfg.put("policy", "local");
        cfg.put("fault_tolerant", true);
        cfg.put("hide_client_headers", false);
        return KongPlugin.builder().name("rate-limiting").config(cfg).enabled(true).tags(tags).build();
    }

    private static KongPlugin plugin(String name, Map<String, Object> config, List<String> baseTags) {
        return KongPlugin.builder()
                .name(name)
                .config(config)
                .enabled(true)
                .tags(new ArrayList<>(baseTags))
                .build();
    }

    /** Fresh per-route copies so the deployer's per-route tag injection never mutates a shared instance. */
    private static List<KongPlugin> copies(List<KongPlugin> src) {
        List<KongPlugin> out = new ArrayList<>(src.size());
        for (KongPlugin pl : src) {
            out.add(KongPlugin.builder()
                    .name(pl.getName())
                    .config(pl.getConfig())
                    .enabled(pl.getEnabled())
                    .tags(pl.getTags() == null ? null : new ArrayList<>(pl.getTags()))
                    .build());
        }
        return out;
    }

    private TranslatedApiProduct.ProductRoute route(String name, String path, List<String> methods,
                                                    List<String> tags, String memberId, List<KongPlugin> plugins) {
        KongRoute r = KongRoute.builder()
                .name(name)
                .protocols(DEFAULT_PROTOCOLS)
                .methods(methods)
                .paths(List.of(path))
                .strip_path(true)
                .tags(tags)
                .build();
        return TranslatedApiProduct.ProductRoute.builder()
                .route(r)
                .memberApiId(memberId)
                .plugins(plugins)
                .build();
    }

    // ---------------- helpers ----------------

    private static String slug(String s) {
        if (!StringUtils.hasText(s)) return "_";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String joinPaths(String a, String b) {
        if (!StringUtils.hasText(a)) return b == null ? "/" : b;
        if (!StringUtils.hasText(b)) return a;
        if (a.endsWith("/") && b.startsWith("/")) return a + b.substring(1);
        if (!a.endsWith("/") && !b.startsWith("/")) return a + "/" + b;
        return a + b;
    }

    private static String normalize(String uriTemplate) {
        return uriTemplate.startsWith("/") ? uriTemplate : "/" + uriTemplate;
    }

    private static List<String> tagsWith(List<String> base, String... extras) {
        List<String> out = new ArrayList<>(base);
        for (String e : extras) if (e != null) out.add(e);
        return out;
    }

    private static String firstNonNull(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StringUtils.hasText(v.toString())) return v.toString();
        }
        return null;
    }

    private static String upper(String s) { return s == null ? null : s.toUpperCase(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof Collection<?> c) {
            List<String> out = new ArrayList<>(c.size());
            for (Object x : c) if (x != null) out.add(x.toString());
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object o) {
        if (o instanceof Collection<?> c) {
            List<Map<String, Object>> out = new ArrayList<>(c.size());
            for (Object x : c) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            return out;
        }
        return null;
    }
}
