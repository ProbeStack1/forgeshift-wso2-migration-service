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
    private final ThrottlingTierResolver tierResolver;
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
        baseTags.replaceAll(ApiTranslator::safeTag);   // Kong tags reject "/" and ","

        List<String> warnings = new ArrayList<>();

        // Product-level plugins — ONLY the policies/security this product actually has.
        List<KongPlugin> productPlugins = buildProductPlugins(p, baseTags,
                tierResolver.effectiveTierRpm(snap.getCompanyName(), snap.getWso2Tenant()));
        // AM 4.x operation policies declared on the product → request/response-transformer (applied to
        // every product route). Operations live under member APIs, so only product-level apiPolicies map here.
        OperationPolicyTranslator.Result productOpPolicies =
                OperationPolicyTranslator.translate(p, productName, baseTags);
        productPlugins.addAll(productOpPolicies.getPlugins());
        warnings.addAll(productOpPolicies.getWarnings());

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
                    // No operations captured for this member — fall back to a catch-all at the product
                    // CONTEXT with strip_path=true, so whatever resource the caller appends is forwarded
                    // to the member backend (instead of a made-up /<context>/<member> path that strips to
                    // "/"). With several no-op members this is ambiguous (same prefix) — hence the warning.
                    warnings.add("API Product '" + productName + "' member '" + memberName + "' has no "
                            + "operations in the snapshot — routed as a catch-all at the product context '"
                            + context + "'; verify per-resource routing manually.");
                    routes.add(route(productSlug + "--" + slug(memberName) + "--all",
                            ApiTranslator.kongRoutePath(context),
                            List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"),
                            tagsWith(baseTags, "wso2-member-api:" + memberId), memberId,
                            copies(productPlugins), true));
                    continue;
                }
                for (Map<String, Object> op : ops) {
                    String verb = upper(str(op.get("verb")));
                    String target = str(op.get("target"));
                    if (!StringUtils.hasText(verb) || !StringUtils.hasText(target)) continue;
                    String tier = str(op.get("throttlingPolicy"));
                    // Match the product context + member resource (Kong 3.x: templated paths /items/{id}
                    // become ~/ regex paths) so the right member API + verb is selected.
                    String path = ApiTranslator.kongRoutePath(joinPaths(context, normalize(target)));

                    List<KongPlugin> routePlugins = copies(productPlugins);
                    if (StringUtils.hasText(tier) && !"Unlimited".equalsIgnoreCase(tier)) {
                        Integer rpm = props.getTranslation().getThrottlingTierMap().get(tier);
                        if (rpm == null) rpm = props.getTranslation().getDefaultThrottleRpm();
                        routePlugins.add(rateLimit(rpm, tagsWith(baseTags, "wso2-tier:" + tier)));
                    }
                    // A product has NO single service, so this route nests under the MEMBER API's service.
                    // WSO2 strips the product context and forwards only the member resource (e.g.
                    // /retail-banking/accounts/42 -> accounts backend /accounts/42). Kong's strip_path
                    // strips the WHOLE matched path (context + resource) -> it would forward "/" to the
                    // member backend, which bare-host backends answer with a 200 + HTML landing page. So
                    // keep strip_path=FALSE and rewrite the upstream URI to the member resource via a
                    // request-transformer, mapping path params to the route's named captures
                    // (/anything/{id} -> /anything/$(uri_captures.id)).
                    setUpstreamResourceUri(routePlugins, target, baseTags);
                    routes.add(route(
                            productSlug + "--" + slug(memberName) + "--" + verb.toLowerCase() + slug(target),
                            path, List.of(verb),
                            tagsWith(baseTags, "wso2-member-api:" + memberId,
                                    "wso2-resource:" + target, "wso2-verb:" + verb),
                            memberId, routePlugins, false));
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

    private List<KongPlugin> buildProductPlugins(Map<String, Object> p, List<String> baseTags, Map<String, Integer> tierRpm) {
        List<KongPlugin> plugins = new ArrayList<>();

        // 1) Product throttling tier (policies array) → rate-limiting
        List<String> policies = stringList(p.get("policies"));
        if (policies != null) {
            for (String policy : policies) {
                Integer rpm = tierRpm.get(policy);
                if (rpm != null && !"Unlimited".equalsIgnoreCase(policy)) {
                    plugins.add(rateLimit(rpm, tagsWith(baseTags, "wso2-tier:" + policy)));
                    break;
                }
            }
        }

        // 2) Security — match scheme tokens EXACTLY (the WSO2 mandatory/optional flag contains the
        // substring "api_key" but is NOT that scheme; see Wso2SecuritySchemes). Use the same azp jwt
        // keying the API uses so the product context route keys per-consumer like the member API does.
        List<String> security = stringList(p.get("securityScheme"));
        if (security != null) {
            boolean oauth = Wso2SecuritySchemes.hasOauth2(security);
            boolean apiKey = Wso2SecuritySchemes.hasApiKey(security);
            boolean orAuth = Wso2SecuritySchemes.isEitherAuthAccepted(security);
            if (oauth) {
                Map<String, Object> jwtCfg = new LinkedHashMap<>();
                jwtCfg.put("key_claim_name", props.getCredentials().getJwtKeyClaim());
                jwtCfg.put("claims_to_verify", List.of("exp"));
                if (orAuth) jwtCfg.put("anonymous", Wso2SecuritySchemes.ANONYMOUS_CONSUMER_ID);
                plugins.add(plugin("jwt", jwtCfg, baseTags));
            }
            if (apiKey) {
                Map<String, Object> kaCfg = orAuth
                        ? new LinkedHashMap<>(Map.of("anonymous", Wso2SecuritySchemes.ANONYMOUS_CONSUMER_ID))
                        : null;
                plugins.add(plugin("key-auth", kaCfg, baseTags));
            }
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
                                                    List<String> tags, String memberId,
                                                    List<KongPlugin> plugins, boolean stripPath) {
        KongRoute r = KongRoute.builder()
                .name(name)
                .protocols(DEFAULT_PROTOCOLS)
                .methods(methods)
                .paths(List.of(path))
                .strip_path(stripPath)
                .tags(tags)
                .build();
        return TranslatedApiProduct.ProductRoute.builder()
                .route(r)
                .memberApiId(memberId)
                .plugins(plugins)
                .build();
    }

    /**
     * Ensure a product route forwards the MEMBER RESOURCE (not the product context) to the member
     * backend. The route uses {@code strip_path=false} (Kong would otherwise strip the whole matched
     * context+resource and forward "/"), so a {@code request-transformer} rewrites the upstream URI to
     * the resource, mapping any path params to the route's named captures. If the route already carries
     * a {@code request-transformer} (e.g. from a product-level operation policy) the {@code replace.uri}
     * is merged into it rather than adding a colliding second instance of the plugin.
     */
    @SuppressWarnings("unchecked")
    private static void setUpstreamResourceUri(List<KongPlugin> plugins, String target, List<String> tags) {
        String uri = productUpstreamUri(target);
        for (KongPlugin pl : plugins) {
            if ("request-transformer".equals(pl.getName())) {
                Map<String, Object> cfg = pl.getConfig() == null
                        ? new LinkedHashMap<>() : new LinkedHashMap<>(pl.getConfig());
                Object existing = cfg.get("replace");
                Map<String, Object> replace = existing instanceof Map
                        ? new LinkedHashMap<>((Map<String, Object>) existing) : new LinkedHashMap<>();
                replace.put("uri", uri);
                cfg.put("replace", replace);
                pl.setConfig(cfg);
                return;
            }
        }
        Map<String, Object> replace = new LinkedHashMap<>();
        replace.put("uri", uri);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("replace", replace);
        plugins.add(plugin("request-transformer", cfg, tags));
    }

    /**
     * Build the {@code request-transformer} {@code replace.uri} that reconstructs a member resource path
     * from a product route: a static target is returned as-is ({@code /get}); path params map to the same
     * named captures {@link ApiTranslator#kongRoutePath} emits ({@code /anything/{id}} →
     * {@code /anything/$(uri_captures.id)}).
     */
    static String productUpstreamUri(String target) {
        String t = StringUtils.hasText(target) ? (target.startsWith("/") ? target : "/" + target) : "/";
        if (!t.contains("{")) {
            return t;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            char ch = t.charAt(i);
            if (ch == '{') {
                int end = t.indexOf('}', i + 1);
                if (end > i + 1) {
                    out.append("$(uri_captures.")
                            .append(ApiTranslator.captureName(t.substring(i + 1, end)))
                            .append(")");
                    i = end + 1;
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
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
        for (String e : extras) if (e != null) out.add(ApiTranslator.safeTag(e));
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
