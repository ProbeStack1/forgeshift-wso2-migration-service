package com.forgeshift.wso2.migration.translator;

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

        // ONE route per API, anchored at the WSO2 context with strip_path=true so Kong
        // strips only "/users" and forwards "/items" (and "/items/42" for path params)
        // to the backend — matching WSO2 gateway forwarding. (The old per-resource
        // routes used paths=[/users/items] + strip_path=true and wrongly forwarded "/".)
        assertEquals(1, api.getRoutes().size());
        var route = api.getRoutes().get(0);
        assertEquals(List.of("/users"), route.getPaths());
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

    private static ApiTranslator translator() {
        MigrationProperties props = new MigrationProperties();
        return new ApiTranslator(props, new ThrottlingTierResolver(null, props));
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
}
