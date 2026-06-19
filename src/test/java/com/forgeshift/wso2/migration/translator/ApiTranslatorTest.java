package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.TargetMode;
import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTranslatorTest {

    @Test
    void buildsOneRouteAtContextSoBackendResourcePathIsPreserved() {
        MigrationProperties props = new MigrationProperties();
        ApiTranslator translator = new ApiTranslator(props, new ThrottlingTierResolver(null, props));

        DiscoverySnapshot snap = DiscoverySnapshot.builder()
                .sourceId("api1")
                .sourceName("UserAPI")
                .sourceVersion("1.0.0")
                .payload(Map.of(
                        "name", "UserAPI",
                        "version", "1.0.0",
                        "context", "/users",
                        "endpointConfig", Map.of("production_endpoints",
                                Map.of("url", "https://backend.example.com")),
                        "operations", List.of(
                                Map.of("verb", "GET", "target", "/items"),
                                Map.of("verb", "POST", "target", "/items"),
                                Map.of("verb", "GET", "target", "/items/{id}"))))
                .build();

        TranslatedApi api = translator.translate(snap);

        // ONE route per API at the WSO2 context FOLLOWED BY the version, with strip_path=true so
        // Kong strips "/users/1.0.0" and forwards "/items" (and "/items/42" for path params) to the
        // backend — matching WSO2, which strips BOTH the context and the version. (A bare-context
        // route "/users" forwarded the surplus "/1.0.0/items" or collapsed to backend root "/",
        // which bare-host backends answer with a 200 + HTML landing page.)
        assertEquals(1, api.getRoutes().size());
        var route = api.getRoutes().get(0);
        assertEquals(List.of("/users/1.0.0"), route.getPaths());
        assertTrue(route.getStrip_path());

        // Only the verbs WSO2 exposes are allowed (de-duplicated).
        assertEquals(2, route.getMethods().size());
        assertTrue(route.getMethods().contains("GET"));
        assertTrue(route.getMethods().contains("POST"));

        // Kong tags can't contain "/" — resource templates are kept as sanitised audit tags.
        assertTrue(route.getTags().stream().noneMatch(t -> t.contains("/")), "route tags must not contain '/'");
        assertTrue(route.getTags().contains("wso2-resource:_items"));
        assertTrue(route.getTags().contains("wso2-resource:_items_{id}"));

        // Kong requires UNIQUE tags within an entity. GET /items + POST /items share the
        // target /items, so wso2-resource:_items must appear exactly once (not duplicated).
        long distinct = route.getTags().stream().distinct().count();
        assertEquals(route.getTags().size(), distinct, "route tags must be unique (no duplicates)");
        assertEquals(1, route.getTags().stream().filter("wso2-resource:_items"::equals).count());
    }

    @Test
    void contextRoutePath_plainContext_appendsConcreteVersionSegment() {
        // /users + 1.0.0 -> /users/1.0.0 ; strip_path then strips context+version, forwards the resource.
        assertEquals("/users/1.0.0", ApiTranslator.contextRoutePath("/users", "1.0.0"));
    }

    @Test
    void contextRoutePath_literalVersionToken_isSubstituted_notDoubleAppended() {
        // /bank/customers/{version} : the {version} token IS the version slot — substitute it,
        // do NOT append a second version segment (that would over-strip the resource).
        assertEquals("/bank/customers/1.0.0",
                ApiTranslator.contextRoutePath("/bank/customers/{version}", "1.0.0"));
    }

    @Test
    void contextRoutePath_literalVersionLabelContext_stillAppendsApiVersion() {
        // /open-banking/v3.1 has a version LABEL but no {version} token; WSO2 still appends the
        // concrete apiVersion in the invocation URL, so the route must include it.
        assertEquals("/open-banking/v3.1/3.1.0",
                ApiTranslator.contextRoutePath("/open-banking/v3.1", "3.1.0"));
    }

    @Test
    void contextRoutePath_trailingSlash_doesNotDoubleSlashBeforeVersion() {
        assertEquals("/users/1.0.0", ApiTranslator.contextRoutePath("/users/", "1.0.0"));
    }

    @Test
    void contextRoutePath_missingVersion_fallsBackToBareContext() {
        assertEquals("/users", ApiTranslator.contextRoutePath("/users", null));
    }

    private static ApiTranslator translator() {
        MigrationProperties props = new MigrationProperties();
        return new ApiTranslator(props, new ThrottlingTierResolver(null, props));
    }

    private static ApiTranslator perResourceTranslator() {
        MigrationProperties props = new MigrationProperties();
        props.getDeck().setRouteGranularity(MigrationProperties.Deck.RouteGranularity.PER_RESOURCE);
        return new ApiTranslator(props, new ThrottlingTierResolver(null, props));
    }

    private static DiscoverySnapshot perResourceApi(String backendUrl, List<Map<String, Object>> ops,
                                                    Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("name", "CustomAPI");
        payload.put("version", "1.0.0");
        payload.put("context", "/custom");
        payload.put("endpointConfig", Map.of("production_endpoints", Map.of("url", backendUrl)));
        if (ops != null) payload.put("operations", ops);
        if (extra != null) payload.putAll(extra);
        return DiscoverySnapshot.builder().sourceId("custom").sourceName("CustomAPI").sourceVersion("1.0.0")
                .payload(payload).build();
    }

    @Test
    void perResource_oneRoutePerOperation_stripPathFalse_replaceUriIsResource() {
        TranslatedApi api = perResourceTranslator().translate(perResourceApi("https://backend.example.com",
                List.of(Map.of("verb", "GET", "target", "/get"),
                        Map.of("verb", "GET", "target", "/user-agent")), null));

        assertEquals(2, api.getRoutes().size(), "one Kong route per WSO2 operation");
        var get = api.getRoutes().stream().filter(r -> r.getPaths().contains("/custom/1.0.0/get")).findFirst().orElseThrow();
        assertEquals(List.of("/custom/1.0.0/get"), get.getPaths());
        assertFalse(get.getStrip_path(), "per-resource path INCLUDES the resource → strip_path must be false");
        assertEquals(List.of("GET"), get.getMethods());
        var rt = api.getRoutePlugins().get(get.getName()).stream()
                .filter(pl -> pl.getName().equals("request-transformer")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> replace = (Map<String, Object>) rt.getConfig().get("replace");
        assertEquals("/get", replace.get("uri"), "forward only the resource so the SAME curl works as on WSO2");
    }

    @Test
    void perResource_templatedTarget_regexPath_andCaptureInReplaceUri() {
        TranslatedApi api = perResourceTranslator().translate(perResourceApi("https://backend.example.com",
                List.of(Map.of("verb", "GET", "target", "/anything/{id}")), null));
        var route = api.getRoutes().get(0);
        // kongRoutePath regex-escapes the '.' in the version segment.
        assertEquals(List.of("~/custom/1\\.0\\.0/anything/(?<id>[^/]+)$"), route.getPaths());
        var rt = api.getRoutePlugins().get(route.getName()).stream()
                .filter(pl -> pl.getName().equals("request-transformer")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> replace = (Map<String, Object>) rt.getConfig().get("replace");
        assertEquals("/anything/$(uri_captures.id)", replace.get("uri"));
    }

    @Test
    void perResource_sharedTargetTwoVerbs_twoDistinctRoutesSamePath() {
        TranslatedApi api = perResourceTranslator().translate(perResourceApi("https://backend.example.com",
                List.of(Map.of("verb", "GET", "target", "/items"),
                        Map.of("verb", "POST", "target", "/items")), null));
        assertEquals(2, api.getRoutes().size());
        long distinctNames = api.getRoutes().stream().map(r -> r.getName()).distinct().count();
        assertEquals(2, distinctNames, "route names must embed the verb to stay unique");
        assertTrue(api.getRoutes().stream().allMatch(r -> r.getPaths().equals(List.of("/custom/1.0.0/items"))));
        assertTrue(api.getRoutes().stream().anyMatch(r -> r.getMethods().equals(List.of("GET"))));
        assertTrue(api.getRoutes().stream().anyMatch(r -> r.getMethods().equals(List.of("POST"))));
    }

    @Test
    void perResource_nonRootBackendPath_foldedIntoReplaceUri() {
        TranslatedApi api = perResourceTranslator().translate(perResourceApi("https://b.example.com/api",
                List.of(Map.of("verb", "GET", "target", "/get")), null));
        var rt = api.getRoutePlugins().get(api.getRoutes().get(0).getName()).stream()
                .filter(pl -> pl.getName().equals("request-transformer")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> replace = (Map<String, Object>) rt.getConfig().get("replace");
        assertEquals("/api/get", replace.get("uri"), "backend base path must be preserved like PREFIX mode");
    }

    @Test
    void perResource_opPolicyHeaderTransform_mergedIntoRouteRequestTransformer_notShadowed() {
        Map<String, Object> apiPolicies = Map.of("request", List.of(
                Map.of("policyName", "addHeader",
                        "parameters", Map.of("headerName", "X-Trace", "headerValue", "on"))));
        TranslatedApi api = perResourceTranslator().translate(perResourceApi("https://b.example.com",
                List.of(Map.of("verb", "GET", "target", "/get")),
                Map.of("apiPolicies", apiPolicies)));
        // No service-scoped request-transformer (it would be shadowed by the route-scoped one).
        assertFalse(pluginNames(api).contains("request-transformer"),
                "op-policy request-transformer must move to the route, not stay service-scoped");
        var rt = api.getRoutePlugins().get(api.getRoutes().get(0).getName()).stream()
                .filter(pl -> pl.getName().equals("request-transformer")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> add = (Map<String, Object>) rt.getConfig().get("add");
        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) add.get("headers");
        assertTrue(headers.contains("X-Trace:on"), "op-policy header transform must survive on the route");
        @SuppressWarnings("unchecked")
        Map<String, Object> replace = (Map<String, Object>) rt.getConfig().get("replace");
        assertEquals("/get", replace.get("uri"), "the same request-transformer also forwards the resource");
    }

    @Test
    void perResource_corsEnabled_optionsAddedToEachRoute_forPreflight() {
        TranslatedApi api = perResourceTranslator().translate(perResourceApi("https://b.example.com",
                List.of(Map.of("verb", "GET", "target", "/get")),
                Map.of("corsConfiguration", Map.of("corsConfigurationEnabled", true))));
        assertTrue(pluginNames(api).contains("cors"), "cors stays service-scoped");
        assertTrue(api.getRoutes().get(0).getMethods().contains("OPTIONS"),
                "OPTIONS must be added so a CORS preflight matches a route");
    }

    @Test
    void perResource_perOpTier_eachRouteGetsItsOwnRateLimit() {
        TranslatedApi api = perResourceTranslator().translate(perResourceApi("https://b.example.com",
                List.of(Map.of("verb", "GET", "target", "/get", "throttlingPolicy", "Bronze"),
                        Map.of("verb", "POST", "target", "/post", "throttlingPolicy", "Gold")), null));
        var getRoute = api.getRoutes().stream().filter(r -> r.getMethods().equals(List.of("GET"))).findFirst().orElseThrow();
        var postRoute = api.getRoutes().stream().filter(r -> r.getMethods().equals(List.of("POST"))).findFirst().orElseThrow();
        var getRl = api.getRoutePlugins().get(getRoute.getName()).stream()
                .filter(pl -> pl.getName().equals("rate-limiting")).findFirst().orElseThrow();
        var postRl = api.getRoutePlugins().get(postRoute.getName()).stream()
                .filter(pl -> pl.getName().equals("rate-limiting")).findFirst().orElseThrow();
        assertEquals(10, getRl.getConfig().get("minute"), "Bronze → 10 rpm on the GET route");
        assertEquals(200, postRl.getConfig().get("minute"), "Gold → 200 rpm on the POST route");
    }

    @Test
    void perResource_noOperations_fallsBackToSinglePrefixRoute() {
        TranslatedApi api = perResourceTranslator().translate(
                perResourceApi("https://b.example.com", null, null));
        assertEquals(1, api.getRoutes().size());
        assertEquals(List.of("/custom/1.0.0"), api.getRoutes().get(0).getPaths());
        assertTrue(api.getRoutes().get(0).getStrip_path(), "no-ops fallback keeps the prefix strip_path=true route");
    }

    private static DiscoverySnapshot apiWithSecurity(List<String> securityScheme) {
        return DiscoverySnapshot.builder()
                .sourceId("apiSec").sourceName("SecAPI").sourceVersion("1.0.0")
                .payload(Map.of(
                        "name", "SecAPI", "version", "1.0.0", "context", "/secapi",
                        "endpointConfig", Map.of("production_endpoints", Map.of("url", "https://b.example.com")),
                        "securityScheme", securityScheme))
                .build();
    }

    private static List<String> pluginNames(com.forgeshift.wso2.migration.translator.TranslatedApi api) {
        return api.getServicePlugins() == null ? List.of()
                : api.getServicePlugins().stream().map(KongPlugin::getName).toList();
    }

    @Test
    void oauth2WithMandatoryFlag_emitsJwtOnly_neverStrayKeyAuth() {
        // The bug: oauth_basic_auth_api_key_mandatory contains the substring "api_key" → a contains()
        // check wrongly bolted key-auth onto OAuth2 APIs, breaking real OAuth2 (jwt) callers.
        TranslatedApi api = translator().translate(
                apiWithSecurity(List.of("oauth_basic_auth_api_key_mandatory", "oauth2")));
        assertTrue(pluginNames(api).contains("jwt"), "oauth2 → jwt");
        assertFalse(pluginNames(api).contains("key-auth"), "the mandatory flag must NOT add key-auth");
    }

    @Test
    void apiKeyWithMandatoryFlag_emitsKeyAuthOnly() {
        TranslatedApi api = translator().translate(
                apiWithSecurity(List.of("api_key", "oauth_basic_auth_api_key_mandatory")));
        assertTrue(pluginNames(api).contains("key-auth"), "api_key → key-auth");
        assertFalse(pluginNames(api).contains("jwt"), "no oauth2 scheme → no jwt");
    }

    @Test
    void bothSchemesOptional_emitsBothWithAnonymousFallback() {
        // oauth2 + api_key + optional → either credential is accepted (OR), expressed with Kong's
        // anonymous fallback on each auth plugin.
        TranslatedApi api = translator().translate(
                apiWithSecurity(List.of("oauth2", "api_key", "oauth_basic_auth_api_key_optional")));
        var byName = api.getServicePlugins().stream()
                .filter(pl -> pl.getName().equals("jwt") || pl.getName().equals("key-auth")).toList();
        assertEquals(2, byName.size(), "both auth plugins present");
        for (KongPlugin pl : byName) {
            assertEquals(Wso2SecuritySchemes.ANONYMOUS_CONSUMER_ID, pl.getConfig().get("anonymous"),
                    pl.getName() + " must carry the anonymous fallback for OR-auth");
        }
    }

    @Test
    void bothSchemesMandatory_emitsBothWithoutAnonymous_andSemantics() {
        TranslatedApi api = translator().translate(
                apiWithSecurity(List.of("oauth2", "api_key", "oauth_basic_auth_api_key_mandatory")));
        for (KongPlugin pl : api.getServicePlugins()) {
            if (pl.getName().equals("jwt") || pl.getName().equals("key-auth")) {
                assertTrue(pl.getConfig() == null || pl.getConfig().get("anonymous") == null,
                        pl.getName() + " must NOT carry anonymous when both are mandatory (AND)");
            }
        }
    }

    private static DiscoverySnapshot apiWith(List<String> securityScheme, List<String> policies) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("name", "TierAPI");
        payload.put("version", "1.0.0");
        payload.put("context", "/tier");
        payload.put("endpointConfig", Map.of("production_endpoints", Map.of("url", "https://b.example.com")));
        if (securityScheme != null) payload.put("securityScheme", securityScheme);
        if (policies != null) payload.put("policies", policies);
        return DiscoverySnapshot.builder().sourceId("tier").sourceName("TierAPI").sourceVersion("1.0.0")
                .payload(payload).build();
    }

    @Test
    void mutualSsl_emitsJwt_andWarning_butNoMtlsPlugin() {
        // mutual-TLS has no declarative Kong plugin; it must be surfaced as a manual-review warning,
        // never silently dropped (the API would otherwise lose its mandatory client-cert posture).
        TranslatedApi api = translator().translate(
                apiWithSecurity(List.of("oauth2", "mutualssl", "mutualssl_mandatory")));
        assertTrue(pluginNames(api).contains("jwt"), "oauth2 → jwt");
        assertFalse(pluginNames(api).stream().anyMatch(n -> n.contains("mtls")), "no declarative mtls plugin exists");
        assertTrue(api.getWarnings().stream().anyMatch(w -> w.contains("mutual-TLS") && w.contains("mtls-auth")),
                "mutualssl must be surfaced as a manual-review warning, never silent");
    }

    @Test
    void customTierThatDoesNotResolve_emitsNoRateLimit_butWarns() {
        // policies has only a custom tier (not in the fallback map / discovered tiers) + Unlimited →
        // the API would be silently UNTHROTTLED on Kong. It must warn instead of dropping silently.
        TranslatedApi api = translator().translate(apiWith(List.of("oauth2"), List.of("BankGold", "Unlimited")));
        assertFalse(pluginNames(api).contains("rate-limiting"), "unresolved tier → no rate-limiting plugin");
        assertTrue(api.getWarnings().stream()
                        .anyMatch(w -> w.contains("could not be resolved") && w.contains("BankGold")),
                "an unresolved throttle tier must be warned, never silently dropped");
    }

    @Test
    void customTierWithStandardFallback_rateLimited_noUnresolvedWarning() {
        // [BankGold (unresolved), Gold (resolves to 200), Unlimited] → rate-limiting from Gold, and NO
        // "could not be resolved" warning because a rate limit WAS applied.
        TranslatedApi api = translator().translate(apiWith(List.of("oauth2"), List.of("BankGold", "Gold", "Unlimited")));
        assertTrue(pluginNames(api).contains("rate-limiting"), "standard Gold tier resolves → rate-limiting");
        assertFalse(api.getWarnings().stream().anyMatch(w -> w.contains("could not be resolved")),
                "a resolved fallback tier means no unresolved-tier warning");
    }

    private static DiscoverySnapshot apiWithScopes() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("name", "PaymentsAPI");
        payload.put("version", "1.0.0");
        payload.put("context", "/payments");
        payload.put("endpointConfig", Map.of("production_endpoints", Map.of("url", "https://b.example.com")));
        payload.put("securityScheme", List.of("oauth2"));
        payload.put("operations", List.of(
                Map.of("verb", "GET", "target", "/balance", "scopes", List.of("payments:read")),
                Map.of("verb", "POST", "target", "/transfer", "scopes", List.of("payments:write"))));
        return DiscoverySnapshot.builder().sourceId("pay").sourceName("PaymentsAPI").sourceVersion("1.0.0")
                .payload(payload).build();
    }

    @Test
    void customPluginMode_emitsScopeEnforcer_alongsideJwt_withPerMethodRules() {
        TranslatedApi api = translator().translate(apiWithScopes(), null, null, TargetMode.CUSTOM_PLUGIN);
        assertTrue(pluginNames(api).contains("jwt"), "oauth2 still emits jwt");
        assertTrue(pluginNames(api).contains(CustomScopeRolePluginBuilder.PLUGIN_NAME),
                "custom-plugin mode closes the scope silent-drop with the scope-enforcer plugin");
        KongPlugin scope = api.getServicePlugins().stream()
                .filter(p -> p.getName().equals(CustomScopeRolePluginBuilder.PLUGIN_NAME)).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) scope.getConfig().get("rules");
        assertEquals(2, rules.size(), "one rule per distinct scope-set (read vs write)");
        assertTrue(api.getWarnings().stream().anyMatch(w -> w.contains("scope enforcement")),
                "the report should note the scope enforcement was migrated to a custom plugin");
    }

    @Test
    void serverlessMode_neverEmitsScopeEnforcer_evenWhenScopesPresent() {
        // The existing (serverless) path is unchanged: scopes stay a silent drop, no custom plugin.
        assertFalse(pluginNames(translator().translate(apiWithScopes()))
                .contains(CustomScopeRolePluginBuilder.PLUGIN_NAME));
        assertFalse(pluginNames(translator().translate(apiWithScopes(), null, null, TargetMode.SERVERLESS_INLINE))
                .contains(CustomScopeRolePluginBuilder.PLUGIN_NAME));
    }
}
