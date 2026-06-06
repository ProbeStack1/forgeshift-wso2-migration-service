package com.forgeshift.wso2.migration.deck;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.KongConsumer;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.domain.kong.KongRoute;
import com.forgeshift.wso2.migration.domain.kong.KongService;
import com.forgeshift.wso2.migration.domain.kong.KongTarget;
import com.forgeshift.wso2.migration.domain.kong.KongUpstream;
import com.forgeshift.wso2.migration.translator.TranslatedApi;
import com.forgeshift.wso2.migration.translator.TranslatedApiProduct;
import com.forgeshift.wso2.migration.translator.TranslatedConsumer;
import com.forgeshift.wso2.migration.translator.TranslatedMediationPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generator: writes realistic decK files to target/deck-sample/repo for a real push test. */
class DeckSampleGenerationTest {

    @Test
    void generateRealisticSample() throws IOException {
        MigrationProperties props = new MigrationProperties();
        DeckYamlBuilder yaml = new DeckYamlBuilder(props);

        String src = "api-uuid-1";
        List<String> tags = List.of("wso2-source-id:" + src, "migrated-by:forgeshift-wso2-migrator");

        KongService svc = KongService.builder().name("pizzashack-1-0-0")
                .protocol("https").host("api.pizzashack.com").port(443).path("/").retries(5)
                .connect_timeout(60000).read_timeout(60000).write_timeout(60000).tags(tags).build();
        KongRoute menu = KongRoute.builder().name("pizzashack-1-0-0-get-menu")
                .protocols(List.of("http", "https")).methods(List.of("GET"))
                .paths(List.of("/pizzashack/1.0.0/menu")).strip_path(true).tags(tags).build();
        KongRoute order = KongRoute.builder().name("pizzashack-1-0-0-post-order")
                .protocols(List.of("http", "https")).methods(List.of("POST"))
                .paths(List.of("/pizzashack/1.0.0/order")).strip_path(true).tags(tags).build();
        KongPlugin jwt = KongPlugin.builder().name("jwt").tags(tags).build();
        KongPlugin rl = KongPlugin.builder().name("rate-limiting")
                .config(Map.of("minute", 1000, "policy", "local")).tags(tags).build();
        KongUpstream up = KongUpstream.builder().name("pizzashack-1-0-0-upstream").algorithm("round-robin").tags(tags).build();
        KongTarget t1 = KongTarget.builder().target("be1.pizzashack.com:443").weight(100).tags(tags).build();
        KongTarget t2 = KongTarget.builder().target("be2.pizzashack.com:443").weight(100).tags(tags).build();

        TranslatedApi api = TranslatedApi.builder()
                .wso2SourceId(src).wso2SourceName("PizzaShackAPI").wso2SourceVersion("1.0.0")
                .service(svc).routes(List.of(menu, order)).servicePlugins(List.of(jwt))
                .routePlugins(Map.of("pizzashack-1-0-0-get-menu", List.of(rl)))
                .upstream(up).targets(List.of(t1, t2)).build();

        KongConsumer consumer = KongConsumer.builder().username("mobile-app").custom_id("app-123")
                .tags(List.of("wso2-source-id:app-123", "migrated-by:forgeshift-wso2-migrator")).build();
        TranslatedConsumer tc = TranslatedConsumer.builder().wso2SourceId("app-123").wso2SourceName("Mobile App")
                .consumer(consumer).consumerPlugins(List.of(KongPlugin.builder().name("rate-limiting")
                        .config(Map.of("minute", 500, "policy", "local"))
                        .tags(List.of("wso2-source-id:app-123")).build())).build();

        KongRoute productRoute = KongRoute.builder().name("gold-menu")
                .protocols(List.of("http", "https")).methods(List.of("GET"))
                .paths(List.of("/gold/menu")).strip_path(true).tags(List.of("wso2-source-id:prod-1")).build();
        TranslatedApiProduct product = TranslatedApiProduct.builder().wso2SourceId("prod-1").wso2SourceName("GoldProduct")
                .routes(List.of(TranslatedApiProduct.ProductRoute.builder().route(productRoute).memberApiId(src).build())).build();

        TranslatedMediationPolicy med = TranslatedMediationPolicy.builder()
                .wso2SourceId(src + ":seq:out").wso2SourceName("addHeaderOut").targetApiId(src)
                .plugin(KongPlugin.builder().name("post-function")
                        .config(Map.of("header_filter", List.of("kong.response.set_header('X-Migrated','true')")))
                        .tags(List.of("wso2-source-id:" + src + ":seq:out")).build()).build();

        Map<String, String> files = yaml.buildFiles("dev",
                List.of(api), List.of(tc), List.of(), List.of(product), List.of(med));

        Path repo = Path.of("target/deck-sample/repo");
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = repo.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        assertTrue(files.containsKey("kong/dev/api-pizzashack-1-0-0.yaml"));
    }
}
