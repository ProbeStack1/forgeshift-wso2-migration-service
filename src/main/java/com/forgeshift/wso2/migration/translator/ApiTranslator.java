package com.forgeshift.wso2.migration.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeshift.wso2.migration.ai.TargetMode;
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
 *   <li>{@code securityScheme} lists exactly {@code "oauth2"} → jwt plugin</li>
 *   <li>{@code securityScheme} lists exactly {@code "api_key"} → key-auth plugin
 *       (the WSO2 {@code oauth_basic_auth_api_key_*} flag is NOT the api_key scheme)</li>
 *   <li>{@code corsConfiguration.corsConfigurationEnabled == true} → cors plugin</li>
 *   <li>{@code responseCachingEnabled} → proxy-cache plugin</li>
 *   <li>{@code apiPolicies} / {@code operations[].operationPolicies} (AM 4.x operation policies:
 *       add/set/remove/rename header, add/remove query param, rewrite path/method) →
 *       request-transformer / response-transformer (see {@link OperationPolicyTranslator})</li>
 *   <li>WSO2 tags → entity tags (in addition to the wso2-source-id audit tag)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTranslator {

    private final MigrationProperties props;
    private final ThrottlingTierResolver tierResolver;
    /** Stateless + dependency-free → instantiated directly so the {@code @RequiredArgsConstructor}
     *  signature (props, tierResolver) is unchanged for existing callers/tests. */
    private final CustomScopeRolePluginBuilder scopeBuilder = new CustomScopeRolePluginBuilder();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ALL_CORS_METHODS = List.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT");

    /** Back-compat / fallback path — translate from the discovery JSON payload alone. */
    public TranslatedApi translate(DiscoverySnapshot snap) {
        return translate(snap, null, null);
    }

    /** Two-source reconcile (export ZIP + discovery snapshot); no assessment source. */
    public TranslatedApi translate(DiscoverySnapshot snap, Wso2ApiBundle bundle) {
        return translate(snap, bundle, null);
    }

    /**
     * Translate one WSO2 API to Kong objects, reconciling up to three views of the
     * API config in precedence order: export ZIP (freshest, pulled at migration
     * time) → discovery snapshot → assessment-staged JSON (read from GCS). Each
     * field is taken from the first source that actually has a value, so a higher
     * source wins but a lower one fills any gap — a partial/failed bundle never
     * loses a field. Swagger paths fill route gaps when {@code operations[]} is
     * empty. Sequences and certificates are surfaced as warnings — they require
     * manual review and are not auto-translated in this phase.
     *
     * @param assessmentApiJson the assessment's stored API config, or null when GCS
     *                          is disabled / the object is absent (source skipped).
     */
    public TranslatedApi translate(DiscoverySnapshot snap, Wso2ApiBundle bundle,
                                   Map<String, Object> assessmentApiJson) {
        return translate(snap, bundle, assessmentApiJson, TargetMode.SERVERLESS_INLINE);
    }

    /**
     * As {@link #translate(DiscoverySnapshot, Wso2ApiBundle, Map)} but for a known target gateway
     * {@code mode}. In {@code CUSTOM_PLUGIN} mode the API's WSO2 OAuth2 scopes are enforced via the
     * deterministic {@code forgeshift-oauth-scope} custom plugin (Target 1). Serverless mode is
     * byte-for-byte unchanged.
     */
    public TranslatedApi translate(DiscoverySnapshot snap, Wso2ApiBundle bundle,
                                   Map<String, Object> assessmentApiJson, TargetMode mode) {
        return translate(snap, bundle, assessmentApiJson, mode, Map.of());
    }

    /**
     * As {@link #translate(DiscoverySnapshot, Wso2ApiBundle, Map, TargetMode)} but with the fetched
     * custom operation-policy Synapse bodies ({@code opPolicyDefs}, keyed by policyId/policyName). In
     * {@code CUSTOM_PLUGIN} mode a recognised custom op-policy (e.g. a JS risk-scoring policy) is then
     * migrated to its pre-built Kong custom plugin via the catalog instead of becoming a manual-review
     * warning. Pass an empty map for serverless mode / when WSO2 op-policy content is unavailable.
     */
    public TranslatedApi translate(DiscoverySnapshot snap, Wso2ApiBundle bundle,
                                   Map<String, Object> assessmentApiJson, TargetMode mode,
                                   Map<String, String> opPolicyDefs) {
        Map<String, Object> bundleApi = bundle != null && bundle.getApiJson() != null
                ? bundle.getApiJson() : null;

        // Field-level reconcile across the available views of this API. Sources are
        // listed highest-precedence first: each field is taken from the first source
        // that actually has a value, so the export ZIP wins but the discovery
        // snapshot fills any gap, and the assessment's stored config fills whatever
        // remains — a partial or failed bundle never loses a field. Null sources are
        // skipped by mergeApiSources, so an absent assessment config is a no-op.
        List<String> provenance = new ArrayList<>();
        LinkedHashMap<String, Map<String, Object>> sources = new LinkedHashMap<>();
        sources.put("export ZIP", bundleApi);
        sources.put("discovery snapshot", snap.getPayload());
        sources.put("assessment config", assessmentApiJson);
        Map<String, Object> p = mergeApiSources(sources, provenance);
        String apiName = snap.getSourceName() != null ? snap.getSourceName() : str(p.get("name"));
        String apiVersion = snap.getSourceVersion() != null ? snap.getSourceVersion() : str(p.get("version"));
        String safeName = slug(apiName) + "-" + slug(apiVersion);
        String context = str(p.get("context"));
        if (!StringUtils.hasText(context)) context = "/" + slug(apiName);

        List<String> tags = audtTags(snap);
        List<String> wso2Tags = stringList(p.get("tags"));
        if (wso2Tags != null) tags.addAll(wso2Tags);
        tags.replaceAll(ApiTranslator::safeTag);   // Kong tags reject "/" and ","
        // Kong also requires tags to be UNIQUE within an entity — de-dup (preserve order).
        List<String> dedupedTags = new ArrayList<>(new LinkedHashSet<>(tags));
        tags.clear();
        tags.addAll(dedupedTags);

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

        // --- Route: ONE per API at the WSO2 context --------------------------
        // WSO2 forwards <gateway>/<context>/<version>/<resource> to <backend>/<resource>:
        // it strips the context (+version) and keeps the resource path. The faithful Kong
        // equivalent is a SINGLE route at the context with strip_path=true — Kong strips
        // only the matched context prefix, so the resource path AND any path params
        // (e.g. /items/{id}) flow through to the backend unchanged.
        //
        // The previous per-resource routes used paths=[context+resource] with
        // strip_path=true, which stripped the resource too and forwarded "/" to the
        // backend (every call hit the upstream root instead of the real resource).
        List<KongRoute> routes = new ArrayList<>();
        Map<String, List<KongPlugin>> routePlugins = new HashMap<>();
        List<Map<String, Object>> ops = listOfMaps(p.get("operations"));
        if ((ops == null || ops.isEmpty()) && bundle != null) {
            ops = operationsFromSwagger(bundle.getSwaggerJson());
        }
        // Collect the distinct verbs (so only methods WSO2 exposes are allowed), the
        // resource templates (kept as audit tags), and the strictest per-operation
        // throttling tier (collapsed to one route-scoped rate-limit since there is now
        // a single route per API).
        // Real per-minute limits for this tenant's tiers (discovered WSO2 policy values overlaid on
        // the configured fallback map) — drives every rate-limiting plugin built for this API.
        Map<String, Integer> tierRpm = tierResolver.effectiveTierRpm(snap.getCompanyName(), snap.getWso2Tenant());
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        List<String> resourceTags = new ArrayList<>();
        String strictestTier = null;
        Integer strictestRpm = null;
        if (ops != null) {
            for (Map<String, Object> op : ops) {
                String verb = upper(str(op.get("verb")));
                String target = str(op.get("target"));
                if (StringUtils.hasText(verb)) methods.add(verb);
                if (StringUtils.hasText(target)) resourceTags.add("wso2-resource:" + target);
                String tier = str(op.get("throttlingPolicy"));
                if (StringUtils.hasText(tier) && !"Unlimited".equalsIgnoreCase(tier)) {
                    Integer rpm = tierRpm.get(tier);
                    if (rpm == null) rpm = props.getTranslation().getDefaultThrottleRpm();
                    if (strictestRpm == null || rpm < strictestRpm) {
                        strictestRpm = rpm;
                        strictestTier = tier;
                    }
                }
            }
        }
        if (methods.isEmpty()) {
            methods.addAll(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"));
        }
        String routeName = safeName + "--all";
        KongRoute route = KongRoute.builder()
                .name(routeName)
                .protocols(protocols)
                .methods(new ArrayList<>(methods))
                .paths(List.of(kongRoutePath(context)))
                .strip_path(true)
                .service(Map.of("name", safeName))
                .tags(tagsWith(tags, resourceTags.toArray(new String[0])))
                .build();
        routes.add(route);
        if (strictestRpm != null) {
            routePlugins.computeIfAbsent(routeName, k -> new ArrayList<>())
                    .add(rateLimit(strictestRpm, tagsWith(tags, "wso2-tier:" + strictestTier)));
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
            boolean rateLimited = false;
            List<String> unresolvedTiers = new ArrayList<>();
            for (String policy : policies) {
                if ("Unlimited".equalsIgnoreCase(policy)) {
                    continue; // Unlimited = no throttling
                }
                Integer rpm = tierRpm.get(policy);
                if (rpm != null) {
                    svcPlugins.add(rateLimit(rpm, tagsWith(tags, "wso2-tier:" + policy)));
                    rateLimited = true;
                    break; // only one API-level rate-limit
                }
                unresolvedTiers.add(policy);
            }
            // A non-Unlimited tier that resolves to no rpm (custom tier not in the discovered tier
            // definitions / fallback map) would otherwise leave the API UNTHROTTLED on Kong with no
            // trace. Surface it so throttling is never silently lost.
            if (!rateLimited && !unresolvedTiers.isEmpty()) {
                warnings.add("API " + apiName + ": throttling tier(s) " + unresolvedTiers
                        + " could not be resolved to a request rate (no matching discovered tier definition) — "
                        + "NO rate-limiting plugin was emitted; configure Kong rate-limiting manually.");
            }
        }

        // 2) Security — match scheme tokens EXACTLY (see Wso2SecuritySchemes: the WSO2
        // mandatory/optional flag contains the substring "api_key" but is NOT that scheme).
        List<String> security = stringList(p.get("securityScheme"));
        if (security != null) {
            boolean oauth = Wso2SecuritySchemes.hasOauth2(security);
            boolean apiKey = Wso2SecuritySchemes.hasApiKey(security);
            // BOTH schemes + "_optional" → either credential is accepted (OR). Express it with Kong's
            // anonymous fallback so a request that satisfies one plugin isn't rejected by the other.
            boolean orAuth = Wso2SecuritySchemes.isEitherAuthAccepted(security);
            if (oauth) {
                // Key consumers by the client-id claim (WSO2 puts it in azp), not the default
                // iss — every WSO2 token shares one iss, so iss-keying would collapse all
                // consumers onto one credential and break per-app identity/rate-limits. This
                // matches the per-consumer jwt_secrets emitted by CredentialTranslator.
                Map<String, Object> jwtCfg = new LinkedHashMap<>();
                jwtCfg.put("key_claim_name", props.getCredentials().getJwtKeyClaim());
                jwtCfg.put("claims_to_verify", List.of("exp"));
                if (orAuth) jwtCfg.put("anonymous", Wso2SecuritySchemes.ANONYMOUS_CONSUMER_ID);
                svcPlugins.add(KongPlugin.builder().name("jwt").config(jwtCfg).enabled(true).tags(tags).build());
            }
            if (apiKey) {
                Map<String, Object> kaCfg = orAuth
                        ? new LinkedHashMap<>(Map.of("anonymous", Wso2SecuritySchemes.ANONYMOUS_CONSUMER_ID))
                        : null;
                svcPlugins.add(KongPlugin.builder().name("key-auth").config(kaCfg).enabled(true).tags(tags).build());
            }
            // Mutual-TLS has no declarative Kong plugin equivalent and is NOT auto-migrated. Without a
            // warning the API's mandatory client-certificate posture would vanish silently (the route
            // would still carry jwt/key-auth, but the mTLS requirement is gone).
            boolean mtls = security.stream().anyMatch(s -> s != null
                    && (s.trim().equalsIgnoreCase("mutualssl") || s.trim().equalsIgnoreCase("mutualssl_mandatory")));
            if (mtls) {
                warnings.add("API " + apiName + ": mutual-TLS (mutualssl) is NOT auto-migrated — Kong has no "
                        + "declarative mtls equivalent and client CA certificates can't travel in the bundle. "
                        + "MANUAL STEP to preserve the mandatory client-certificate check: (1) upload the API's "
                        + "trusted client CA certificate to Kong as a ca_certificate, then (2) add the Kong "
                        + "'mtls-auth' plugin to this service referencing that ca_certificate. Until you do, the "
                        + "route is protected only by its token/key auth, NOT by client certificates.");
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

        // 4b) AM 4.x operation policies (apiPolicies + operations[].operationPolicies) → request/
        // response-transformer for the header/query/path/method built-ins; the rest become warnings.
        OperationPolicyTranslator.Result opPolicies =
                OperationPolicyTranslator.translate(p, apiName, tags, mode, opPolicyDefs);
        svcPlugins.addAll(opPolicies.getPlugins());
        warnings.addAll(opPolicies.getWarnings());

        // 4c) OAuth2 scope enforcement (Target 1) — only when targeting a custom-plugin-capable CP.
        // WSO2 binds required scopes to operations; today only jwt is emitted, so the scope check is
        // silently dropped. The reusable forgeshift-oauth-scope plugin re-checks the bearer JWT's
        // scope claim per operation and 403s on a miss. The asset is uploaded by DeckBundleDeployer.
        if (mode == TargetMode.CUSTOM_PLUGIN) {
            scopeBuilder.buildConfig(context, ops).ifPresent(cfg -> {
                svcPlugins.add(KongPlugin.builder()
                        .name(CustomScopeRolePluginBuilder.PLUGIN_NAME)
                        .config(cfg).enabled(true).tags(tags).build());
                warnings.add("API " + apiName + ": OAuth2 scope enforcement migrated to the '"
                        + CustomScopeRolePluginBuilder.PLUGIN_NAME + "' custom plugin (verify the scope claim + rules).");
            });
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
        // Kong requires tags to be UNIQUE within an entity. With one route per API, two
        // operations can share a target (e.g. GET /posts + POST /posts) which would add the
        // same wso2-resource:<target> tag twice — a LinkedHashSet de-dups (preserving order).
        LinkedHashSet<String> out = new LinkedHashSet<>(base);
        for (String e : extras) if (e != null) out.add(safeTag(e));
        return new ArrayList<>(out);
    }

    /**
     * Kong tags can't contain the reserved delimiters {@code /} or {@code ,} (and can't
     * lead/trail with whitespace). The {@code wso2-resource:<uriTemplate>} tag in particular
     * carries a path like {@code /items/{id}} — sanitise it so {@code deck validate} accepts it.
     */
    static String safeTag(String tag) {
        return tag == null ? null : tag.replace('/', '_').replace(',', '_').trim();
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

    /** Kong path style: ensure a leading slash before joining with the API context. */
    private static String normalizeTemplate(String uriTemplate) {
        return uriTemplate.startsWith("/") ? uriTemplate : "/" + uriTemplate;
    }

    /**
     * Kong Gateway 3.x route paths do not accept WSO2/OpenAPI template segments
     * like {@code /items/{id}} as plain paths. Convert templated paths to regex
     * paths with the required {@code ~/} prefix (Kong rejects {@code ~^...} — after
     * the {@code ~} the regex must begin with {@code /}) while leaving ordinary
     * prefix routes unchanged.
     */
    static String kongRoutePath(String path) {
        if (!StringUtils.hasText(path) || !path.contains("{")) {
            return path;
        }
        // "~" marks a regex path; the path itself already starts with "/", giving the
        // required "~/" prefix. (A leading "^" anchor — "~^" — is rejected by Kong 3.x.)
        StringBuilder out = new StringBuilder("~");
        int i = 0;
        while (i < path.length()) {
            char ch = path.charAt(i);
            if (ch == '{') {
                int end = path.indexOf('}', i + 1);
                if (end > i + 1) {
                    String rawName = path.substring(i + 1, end);
                    String name = rawName.replaceAll("[^A-Za-z0-9_]", "_");
                    if (name.isBlank() || Character.isDigit(name.charAt(0))) {
                        name = "param_" + name;
                    }
                    out.append("(?<").append(name).append(">[^/]+)");
                    i = end + 1;
                    continue;
                }
            }
            if ("\\.[]{}()+-*?^$|".indexOf(ch) >= 0) {
                out.append('\\');
            }
            out.append(ch);
            i++;
        }
        out.append('$');
        return out.toString();
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
