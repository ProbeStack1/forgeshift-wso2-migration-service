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
    void convertsWso2TemplatePathsToKong3RegexPaths() {
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
                                Map.of("verb", "GET", "target", "/items/{id}"))))
                .build();

        TranslatedApi api = translator.translate(snap);

        List<String> paths = api.getRoutes().stream()
                .flatMap(route -> route.getPaths().stream())
                .toList();
        assertTrue(paths.contains("/users/items"));
        assertTrue(paths.contains("~/users/items/(?<id>[^/]+)$"));
        assertEquals(2, paths.size());
    }
}
