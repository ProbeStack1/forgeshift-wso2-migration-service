package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTranslatorTest {

    @Test
    void buildsOneRouteAtContextSoBackendResourcePathIsPreserved() {
        ApiTranslator translator = new ApiTranslator(new MigrationProperties());

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
    }
}
