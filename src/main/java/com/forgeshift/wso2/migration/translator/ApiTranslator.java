package com.forgeshift.wso2.migration.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeshift.wso2.migration.bundle.Wso2ApiBundle;
import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.domain.kong.KongRoute;
import com.forgeshift.wso2.migration.domain.kong.KongService;
import com.forgeshift.wso2.migration.domain.kong.KongTarget;
import com.forgeshift.wso2.migration.domain.kong.KongUpstream;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * WSO2 API → Kong Service + Route(s) + Upstream + Target(s) + per-API plugins.
 *
 * <p>Translation rules mirror the Archive Python script
 * ({@code Archive/migrate_wso2_to_konnect.py}):
 * <ul>
 *   <li>API name + version → Service name (lowercase, hyphenated)</li>
 *   <li>{@code endpointConfig.production_endpoints.url} → Service host/port/protocol/path</li>
 *   <li>API context concatenated with each resource uriTemplate → Route paths</li>
 *   <li>Resource verbs → Route methods (one Route per resource for traceability)</li>
 *   <li>{@code transport} list → Service + Route protocols</li>
 *   <li>{@code policies} containing a tier name → rate-limiting plugin
 *       with the RPM from the configured tier map</li>
 *   <li>{@code securityScheme} contains "oauth2" → jwt plugin</li>
 *   <li>{@code securityScheme} contains "api_key" → key-auth plugin</li>
 *   <li>{@code corsConfiguration.corsConfigurationEnabled == true} → cors plugin</li>
 *   <li>{@code responseCachingEnabled} → proxy-cache plugin</li>
 *   <li>WSO2 tags → entity tags (in addition to the wso2-source-id audit tag)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTranslator {

    private final MigrationProperties props;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ALL_CORS_METHODS = List.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT");

    /** Back-compat / fallback path — translate from the discovery JSON payload alone. */
    public TranslatedApi translate(DiscoverySnapshot snap) {
        return translate(snap, null);
    }

    /**
     * Translate one WSO2 API to Kong objects. When {@code bundle} is non-null
     * its {@code apiJson} takes precedence over {@code snap.payload} (bundle
     * data is pulled fresh at migration time, so it can't be stale). Swagger
     * paths fill in route gaps when {@code operations[]} is empty.
     * Sequences and certificates are surfaced as warnings — they require
     * manual review and are not auto-translated in this phase.
     */
    public TranslatedApi translate(DiscoverySnapshot snap, Wso2ApiBundle bundle) {
        Map<String, Object> bundleApi = bundle != null && bundle.getApiJson() != null
                ? bundle.getApiJson() : null;

        // Field-level reconcile across the available views of this API. Sources are
        // listed highest-precedence first: each field is taken from the first source
        // that actually has a value, so the export ZIP wins but the discovery
        // snapshot fills any gap — a partial or failed bundle never loses a field.
        // A third source (e.g. the assessment's stored config) can be added here.
        List<String> provenance = new ArrayList<>();
        LinkedHashMap<String, Map<String, Object>> sources = new LinkedHashMap<>();
        sources.put("export ZIP", bundleApi);
        sources.put("discovery snapshot", snap.getPayload());
        Map<String, Object> p = mergeApiSources(sources, provenance);
        String apiName = snap.getSourceName() != null ? snap.getSourceName() : str(p.get("name"));
        String apiVersion = snap.getSourceVersion() != null ? snap.getSourceVersion() : str(p.get("version"));
        String safeName = slug(apiName) + "-" + slug(apiVersion);
        String context = str(p.get("context"));
        if (!StringUtils.hasText(context)) context = "/" + slug(apiName);

        List<String> tags = audtTags(snap);
        List<String> wso2Tags = stringList(p.get("tags"));
        if (wso2Tags != null) tags.addAll(wso2Tags);

        // --- Service ----------------------------------------------------------
        KongService service = buildService(safeName, p, tags);

        // --- Upstream + Targets when load-balancing is involved --------------
        EndpointInfo prod = extractEndpoints(p);
        KongUpstream upstream = null;
        List<KongTarget> targets = new ArrayList<>();
        if (prod.urls.size() > 1) {
            String upstreamName = safeName + "-upstream";
            upstream = KongUpstream.builder()
                    .name(upstreamName)
                    .algorithm("round-robin")
                    .tags(tags)
                    .build();
            for (String url : prod.urls) {
                HostPort hp = parseHostPort(url);
                if (hp != null) {
                    targets.add(KongTarget.builder()
                            .target(hp.host + ":" + hp.port)
                            .weight(100)
                            .tags(tags)
                            .build());
                }
            }
            // point the service at the upstream by host
            service.setHost(upstreamName);
            service.setPort(null);
        }

        // --- Routes (one per resource) ---------------------------------------
        List<String> protocols = stringList(p.get("transport"));
        if (protocols == null || protocols.isEmpty()) protocols = List.of("http", "https");

        TranslatedApi.TranslatedApiBuilder out = TranslatedApi.builder()
                .wso2SourceId(snap.getSourceId())
                .wso2SourceName(apiName)
                .wso2SourceVersion(apiVersion)
                .service(service)
                .upstream(upstream)
                .targets(targets);

        List<KongRoute> routes = new ArrayList<>();
        Map<String, List<KongPlugin>> routePlugins = new HashMap<>();
        List<Map<String, Object>> ops = listOfMaps(p.get("operations"));
        // When the api.json carries no operations[] but we have a swagger spec
        // in the bundle, synthesise operations from the swagger paths so each
        // verb still gets its own Kong route.
        if ((ops == null || ops.isEmpty()) && bundle != null) {
            ops = operationsFromSwagger(bundle.getSwaggerJson());
        }
        if (ops == null || ops.isEmpty()) {
            // Fallback: one route at the context, all methods
            KongRoute r = KongRoute.builder()
                    .name(safeName + "--root")
                    .protocols(protocols)
                    .methods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"))
                    .paths(List.of(context))
                    .strip_path(true)
                    .service(Map.of("name", safeName))
                    .tags(tags)
                    .build();
            routes.add(r);
        } else {
            for (Map<String, Object> op : ops) {
                String verb = upper(str(op.get("verb")));
                String target = str(op.get("target"));
                String tier = str(op.get("throttlingPolicy"));
                if (!StringUtils.hasText(verb) || !StringUtils.hasText(target)) continue;

                String path = joinPaths(context, normalizeTemplate(target));
                String routeName = safeName + "--" + verb.toLowerCase() + slug(target);
                KongRoute r = KongRoute.builder()
                        .name(routeName)
                        .protocols(protocols)
                        .methods(List.of(verb))
                        .paths(List.of(path))
                        .strip_path(true)
                        .service(Map.of("name", safeName))
                        .tags(tagsWith(tags, "wso2-resource:" + target, "wso2-verb:" + verb))
                        .build();
                routes.add(r);

                // Per-resource throttling tier becomes a route-scoped rate-limiting plugin
                if (StringUtils.hasText(tier) && !"Unlimited".equalsIgnoreCase(tier)) {
                    Integer rpm = props.getTranslation().getThrottlingTierMap().get(tier);
                    if (rpm == null) rpm = props.getTranslation().getDefaultThrottleRpm();
                    KongPlugin rl = rateLimit(rpm, tagsWith(tags, "wso2-tier:" + tier));
                    routePlugins.computeIfAbsent(routeName, k -> new ArrayList<>()).add(rl);
                }
            }
        }
        out.routes(routes);
        out.routePlugins(routePlugins);

        // --- Service-level plugins -------------------------------------------
        List<KongPlugin> svcPlugins = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!provenance.isEmpty()) {
            warnings.add("API " + apiName + ": " + provenance.size()
                    + " field(s) filled from a fallback source because the export ZIP lacked them — "
                    + provenance + ".");
        }

        // 1) API-level throttling (policies array)
        List<String> policies = stringList(p.get("policies"));
        if (policies != null) {
            for (String policy : policies) {
                Integer rpm = props.getTranslation().getThrottlingTierMap().get(policy);
                if (rpm != null && !"Unlimited".equalsIgnoreCase(policy)) {
                    svcPlugins.add(rateLimit(rpm, tagsWith(tags, "wso2-tier:" + policy)));
                    break; // only one API-level rate-limit
                }
            }
        }

        // 2) Security
        List<String> security = stringList(p.get("securityScheme"));
        if (security != null) {
            boolean oauth = security.stream().anyMatch(s -> s != null && s.toLowerCase().contains("oauth2"));
            boolean apiKey = security.stream().anyMatch(s -> s != null && s.toLowerCase().contains("api_key"));
            if (oauth) {
                svcPlugins.add(KongPlugin.builder().name("jwt").enabled(true).tags(tags).build());
            }
            if (apiKey) {
                svcPlugins.add(KongPlugin.builder().name("key-auth").enabled(true).tags(tags).build());
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
            svcPlugins.add(KongPlugin.builder().name("cors").config(cfg).enabled(true).tags(tags).build());
        }

        // 4) Response cache
        if (Boolean.TRUE.equals(p.get("responseCachingEnabled"))) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("response_code", List.of(200));
            cfg.put("request_method", List.of("GET"));
            int ttl = p.get("cacheTimeout") instanceof Number n ? n.intValue() : 300;
            cfg.put("cache_ttl", ttl);
            cfg.put("strategy", "memory");
            svcPlugins.add(KongPlugin.builder().name("proxy-cache").config(cfg).enabled(true).tags(tags).build());
        }

        // 5) Custom mediation: out of scope for MVP - emit a warning
        if (p.containsKey("mediationPolicies")
                && p.get("mediationPolicies") instanceof Collection<?> c && !c.isEmpty()) {
            warnings.add("API " + apiName + " has " + c.size() + " mediation policies which require manual review (not translated in MVP).");
        }

        // 6) Bundle-derived warnings — sequences and certs require manual review.
        if (bundle != null) {
            for (Map.Entry<String, String> seq : bundle.getSequences().entrySet()) {
                String preview = seq.getValue() == null ? ""
                        : seq.getValue().length() > 200
                            ? seq.getValue().substring(0, 200) + "..."
                            : seq.getValue();
                warnings.add("WSO2 sequence " + seq.getKey()
                        + " for API " + apiName + " requires manual review. Preview: " + preview);
            }
            for (Wso2ApiBundle.CertificateRef cert : bundle.getEndpointCerts()) {
                warnings.add("Endpoint certificate " + cert.getFileName()
                        + " (" + cert.getSizeBytes() + " bytes) for API " + apiName
                        + " — Kong ssl object setup is manual in this phase.");
            }
            for (Wso2ApiBundle.CertificateRef cert : bundle.getClientCerts()) {
                warnings.add("Client (mTLS) certificate " + cert.getFileName()
                        + " (" + cert.getSizeBytes() + " bytes) for API " + apiName
                        + " — Kong mtls-auth setup is manual in this phase.");
            }
        }

        out.servicePlugins(svcPlugins);
        out.warnings(warnings);
        return out.build();
    }

    /**
     * Synthesise an {@code operations[]} list from a swagger 2.0 / OpenAPI 3
     * paths map. Returns null when nothing usable is found — caller falls
     * back to a single root route.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operationsFromSwagger(Map<String, Object> swagger) {
        if (swagger == null || swagger.isEmpty()) return null;
        Object paths = swagger.get("paths");
        if (!(paths instanceof Map<?, ?> pm)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<?, ?> entry : pm.entrySet()) {
            String path = entry.getKey() == null ? null : entry.getKey().toString();
            if (path == null) continue;
            if (!(entry.getValue() instanceof Map<?, ?> ops)) continue;
            for (Map.Entry<?, ?> op : ops.entrySet()) {
                String verb = op.getKey() == null ? null : op.getKey().toString();
                if (verb == null) continue;
                String v = verb.toUpperCase();
                if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").contains(v)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("verb", v);
                row.put("target", path);
                out.add(row);
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ---------------- helpers ----------------

    private KongService buildService(String safeName, Map<String, Object> p, List<String> tags) {
        EndpointInfo prod = extractEndpoints(p);
        String url = prod.urls.isEmpty() ? null : prod.urls.get(0);
        HostPort hp = url == null ? null : parseHostPort(url);
        String protocol = hp != null ? hp.scheme : "https";
        String host = hp != null ? hp.host : "MISSING";
        Integer port = hp != null ? hp.port : null;
        String path = hp != null ? hp.path : null;

        return KongService.builder()
                .name(safeName)
                .protocol(protocol)
                .host(host)
                .port(port)
                .path(path)
                .connect_timeout(props.getTranslation().getDefaultTimeoutMs())
                .read_timeout(props.getTranslation().getDefaultTimeoutMs())
                .write_timeout(props.getTranslation().getDefaultTimeoutMs())
                .retries(5)
                .enabled(true)
                .tags(tags)
                .build();
    }

    private KongPlugin rateLimit(int rpm, List<String> tags) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("minute", rpm);
        cfg.put("policy", "local");
        cfg.put("fault_tolerant", true);
        cfg.put("hide_client_headers", false);
        return KongPlugin.builder().name("rate-limiting").config(cfg).enabled(true).tags(tags).build();
    }

    private EndpointInfo extractEndpoints(Map<String, Object> p) {
        EndpointInfo info = new EndpointInfo();
        Map<String, Object> mc = coerceEndpointConfig(p.get("endpointConfig"));
        if (mc == null) return info;
        Object prod = mc.get("production_endpoints");
        if (prod instanceof Map<?, ?> pm) {
            Object url = ((Map<?, ?>) pm).get("url");
            if (url != null) info.urls.add(url.toString());
        } else if (prod instanceof List<?> pl) {
            for (Object e : pl) {
                if (e instanceof Map<?, ?> em) {
                    Object u = ((Map<?, ?>) em).get("url");
                    if (u != null) info.urls.add(u.toString());
                }
            }
        }
        return info;
    }

    /**
     * WSO2 Publisher API returns {@code endpointConfig} as a serialised JSON
     * string, not a nested object. The exported bundle's {@code api.json}
     * returns it as a real object. Accept both shapes (and gracefully give up
     * on anything we can't parse).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceEndpointConfig(Object ec) {
        if (ec == null) return null;
        if (ec instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (ec instanceof String s && !s.isBlank()) {
            try {
                Object parsed = JSON.readValue(s, Object.class);
                if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
            } catch (Exception e) {
                log.warn("endpointConfig string is not valid JSON: {}", e.getMessage());
            }
        }
        return null;
    }

    private HostPort parseHostPort(String url) {
        try {
            URI u = new URI(url);
            HostPort hp = new HostPort();
            hp.scheme = u.getScheme() != null ? u.getScheme() : "https";
            hp.host = u.getHost();
            hp.port = u.getPort() == -1 ? ("https".equalsIgnoreCase(hp.scheme) ? 443 : 80) : u.getPort();
            hp.path = StringUtils.hasText(u.getPath()) ? u.getPath() : null;
            return hp;
        } catch (URISyntaxException e) {
            log.warn("Could not parse backend URL '{}': {}", url, e.getMessage());
            return null;
        }
    }

    private List<String> audtTags(DiscoverySnapshot snap) {
        List<String> t = new ArrayList<>();
        t.add(props.getTranslation().getTagPrefix() + ":" + snap.getSourceId());
        t.add(props.getTranslation().getMigratedByTag());
        return t;
    }

    private List<String> tagsWith(List<String> base, String... extras) {
        List<String> out = new ArrayList<>(base);
        for (String e : extras) if (e != null) out.add(e);
        return out;
    }

    private static String slug(String s) {
        if (!StringUtils.hasText(s)) return "_";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String upper(String s) { return s == null ? null : s.toUpperCase(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String joinPaths(String a, String b) {
        if (!StringUtils.hasText(a)) return b == null ? "/" : b;
        if (!StringUtils.hasText(b)) return a;
        if (a.endsWith("/") && b.startsWith("/")) return a + b.substring(1);
        if (!a.endsWith("/") && !b.startsWith("/")) return a + "/" + b;
        return a + b;
    }

    /** Kong path style: leave {var} placeholders alone (Kong supports them in 3.x). */
    private static String normalizeTemplate(String uriTemplate) {
        return uriTemplate.startsWith("/") ? uriTemplate : "/" + uriTemplate;
    }

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

    /**
     * Merge several views of the same API into one config map. {@code sources} is
     * ordered highest-precedence first; each field is taken from the first source
     * that has a meaningful (non-null / non-blank / non-empty) value, so lower
     * sources only fill gaps. Fields not taken from the top source are recorded in
     * {@code provenance} so the report can explain where each value came from.
     */
    private static Map<String, Object> mergeApiSources(
            LinkedHashMap<String, Map<String, Object>> sources, List<String> provenance) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, String> origin = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> src : sources.entrySet()) {
            if (src.getValue() == null) continue;
            for (Map.Entry<String, Object> e : src.getValue().entrySet()) {
                if (isMeaningful(e.getValue()) && !isMeaningful(merged.get(e.getKey()))) {
                    merged.put(e.getKey(), e.getValue());
                    origin.put(e.getKey(), src.getKey());
                }
            }
        }
        if (!sources.isEmpty()) {
            String top = sources.keySet().iterator().next();
            for (Map.Entry<String, String> o : origin.entrySet()) {
                if (!top.equals(o.getValue())) {
                    provenance.add(o.getKey() + " (from " + o.getValue() + ")");
                }
            }
        }
        return merged;
    }

    private static boolean isMeaningful(Object v) {
        if (v == null) return false;
        if (v instanceof CharSequence cs) return !cs.toString().isBlank();
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    private static class EndpointInfo { List<String> urls = new ArrayList<>(); }
    private static class HostPort { String scheme; String host; int port; String path; }
}
