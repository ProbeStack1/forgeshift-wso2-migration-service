package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiProductTranslatorTest {

    private static ApiProductTranslator translator() {
        MigrationProperties props = new MigrationProperties();
        return new ApiProductTranslator(props, new ThrottlingTierResolver(null, props));
    }

    private static DiscoverySnapshot retailBankingProduct() {
        // Multi-resource product (like the seeded RetailBankingProduct) under context /retail-banking,
        // re-exposing member-API operations including a path-param resource.
        Map<String, Object> payload = Map.of(
                "name", "RetailBankingProduct",
                "version", "1.0.0",
                "context", "/retail-banking",
                "policies", List.of("Unlimited"),
                "apis", List.of(Map.of(
                        "apiId", "acc-1",
                        "name", "AccountsAPI",
                        "operations", List.of(
                                Map.of("verb", "GET", "target", "/accounts"),
                                Map.of("verb", "GET", "target", "/accounts/{id}")))));
        return DiscoverySnapshot.builder()
                .sourceId("prod1").sourceName("RetailBankingProduct").sourceVersion("1.0.0")
                .payload(payload).build();
    }

    private static KongPlugin requestTransformer(TranslatedApiProduct.ProductRoute pr) {
        if (pr.getPlugins() == null) return null;
        return pr.getPlugins().stream()
                .filter(pl -> "request-transformer".equals(pl.getName()))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static String replaceUri(KongPlugin rt) {
        Object replace = rt.getConfig().get("replace");
        return (String) ((Map<String, Object>) replace).get("uri");
    }

    @Test
    void productRoutes_doNotStripTheResource_andRewriteUpstreamToTheMemberResource() {
        TranslatedApiProduct product = translator().translate(retailBankingProduct());

        assertEquals(2, product.getRoutes().size(), "one route per member operation");

        // Static resource: /retail-banking/accounts -> member backend /accounts
        TranslatedApiProduct.ProductRoute statik = product.getRoutes().stream()
                .filter(pr -> pr.getRoute().getPaths().equals(List.of("/retail-banking/accounts")))
                .findFirst().orElseThrow();
        // strip_path MUST be false — true would strip the whole context+resource and forward "/"
        // (the 200 + HTML backend-root bug).
        assertFalse(statik.getRoute().getStrip_path(), "product route must not strip the resource");
        KongPlugin rt1 = requestTransformer(statik);
        assertNotNull(rt1, "a request-transformer must rewrite the upstream URI to the member resource");
        assertEquals("/accounts", replaceUri(rt1));

        // Path-param resource: matched as an anchored regex (kongRoutePath escapes the '-' in the
        // context), upstream rebuilt from the named capture.
        TranslatedApiProduct.ProductRoute templated = product.getRoutes().stream()
                .filter(pr -> pr.getRoute().getPaths().equals(List.of("~/retail\\-banking/accounts/(?<id>[^/]+)$")))
                .findFirst().orElseThrow();
        assertFalse(templated.getRoute().getStrip_path());
        assertEquals("/accounts/$(uri_captures.id)", replaceUri(requestTransformer(templated)));
        assertTrue(templated.getRoute().getMethods().contains("GET"));
    }

    @Test
    void productUpstreamUri_staticTarget_returnedAsIs() {
        assertEquals("/get", ApiProductTranslator.productUpstreamUri("/get"));
        assertEquals("/get", ApiProductTranslator.productUpstreamUri("get"));
    }

    @Test
    void productUpstreamUri_pathParams_mapToNamedCaptures() {
        assertEquals("/anything/$(uri_captures.id)",
                ApiProductTranslator.productUpstreamUri("/anything/{id}"));
        assertEquals("/a/$(uri_captures.x)/b/$(uri_captures.y)",
                ApiProductTranslator.productUpstreamUri("/a/{x}/b/{y}"));
    }

    @Test
    void noOperationMember_fallsBackToContextCatchAll_withWarning() {
        Map<String, Object> payload = Map.of(
                "name", "EmptyProduct", "version", "1.0.0", "context", "/empty-product",
                "apis", List.of(Map.of("apiId", "m-1", "name", "MemberApi")));   // no operations
        TranslatedApiProduct product = translator().translate(DiscoverySnapshot.builder()
                .sourceId("prodE").sourceName("EmptyProduct").sourceVersion("1.0.0").payload(payload).build());

        assertEquals(1, product.getRoutes().size());
        TranslatedApiProduct.ProductRoute pr = product.getRoutes().get(0);
        // Catch-all at the product context, strip_path=true so the caller's resource flows through.
        assertEquals(List.of("/empty-product"), pr.getRoute().getPaths());
        assertTrue(pr.getRoute().getStrip_path());
        assertTrue(product.getWarnings().stream().anyMatch(w -> w.contains("no operations")),
                "a no-operations member must be surfaced as a warning");
    }
}
