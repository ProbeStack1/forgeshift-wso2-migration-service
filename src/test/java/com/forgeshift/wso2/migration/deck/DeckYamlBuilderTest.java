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
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckYamlBuilderTest {

    private final DeckYamlBuilder builder = new DeckYamlBuilder(new MigrationProperties());

    @Test
    void nestsRoutesPluginsAndUpstreamUnderTheService() {
        KongService svc = KongService.builder().name("petstore-2-0").protocol("https")
                .host("api.example.com").port(443).path("/")
                .tags(List.of("wso2-source-id:abc")).build();
        KongRoute route = KongRoute.builder().name("petstore-2-0-get")
                .protocols(List.of("http", "https")).methods(List.of("GET"))
                .paths(List.of("/petstore/pet")).strip_path(true)
                .tags(List.of("wso2-source-id:abc")).build();
        KongPlugin rlimit = KongPlugin.builder().name("rate-limiting")
                .config(Map.of("minute", 200)).tags(List.of("wso2-source-id:abc")).build();
        KongUpstream up = KongUpstream.builder().name("petstore-2-0-upstream").algorithm("round-robin").build();
        KongTarget tgt = KongTarget.builder().target("api.example.com:443").weight(100).build();

        TranslatedApi api = TranslatedApi.builder()
                .wso2SourceId("abc").wso2SourceName("petstore").service(svc)
                .routes(new ArrayList<>(List.of(route)))
                .servicePlugins(new ArrayList<>(List.of(KongPlugin.builder().name("jwt").build())))
                .routePlugins(new HashMap<>(Map.of("petstore-2-0-get", List.of(rlimit))))
                .upstream(up).targets(new ArrayList<>(List.of(tgt)))
                .build();

        Map<String, Object> root = parse(builder.build(List.of(api), List.of(), List.of(), List.of(), List.of()));

        assertEquals("3.0", root.get("_format_version"));
        List<?> services = (List<?>) root.get("services");
        assertEquals(1, services.size());
        Map<?, ?> s0 = (Map<?, ?>) services.get(0);
        assertEquals("petstore-2-0", s0.get("name"));

        List<?> routes = (List<?>) s0.get("routes");
        Map<?, ?> r0 = (Map<?, ?>) routes.get(0);
        assertFalse(r0.containsKey("service"), "nested route must not carry a service FK");
        assertEquals("rate-limiting", ((Map<?, ?>) ((List<?>) r0.get("plugins")).get(0)).get("name"));
        assertEquals("jwt", ((Map<?, ?>) ((List<?>) s0.get("plugins")).get(0)).get("name"));

        Map<?, ?> u0 = (Map<?, ?>) ((List<?>) root.get("upstreams")).get(0);
        assertEquals("api.example.com:443", ((Map<?, ?>) ((List<?>) u0.get("targets")).get(0)).get("target"));
    }

    @Test
    void productRoutesAndMediationPluginsNestUnderTheirMemberService() {
        // Product-context routes and mediation plugins must be NESTED under the member service (not
        // emitted as top-level entities referencing it by {name:X}). A cross-file top-level route makes
        // decK apply insert the service twice ("entity already exists") even though it validates/renders.
        KongService svc = KongService.builder().name("orders-1-0").host("h").port(80).protocol("http").build();
        KongRoute ownRoute = KongRoute.builder().name("orders-own").paths(List.of("/o")).build();
        TranslatedApi api = TranslatedApi.builder().wso2SourceId("api1").service(svc)
                .routes(new ArrayList<>(List.of(ownRoute))).build();

        KongRoute prodRoute = KongRoute.builder().name("prod-route").paths(List.of("/p")).build();
        TranslatedApiProduct product = TranslatedApiProduct.builder()
                .wso2SourceId("prod1")
                .routes(List.of(TranslatedApiProduct.ProductRoute.builder()
                        .route(prodRoute).memberApiId("api1").build()))
                .build();

        KongPlugin serverless = KongPlugin.builder().name("post-function")
                .config(Map.of("access", List.of("x"))).build();
        TranslatedMediationPolicy med = TranslatedMediationPolicy.builder()
                .wso2SourceId("api1:seq:out").targetApiId("api1").plugin(serverless).build();

        Map<String, Object> root = parse(
                builder.build(List.of(api), List.of(), List.of(), List.of(product), List.of(med)));

        // No top-level routes/plugins — everything is nested under the one service.
        assertFalse(root.containsKey("routes"), "product routes must be nested, not top-level");
        assertFalse(root.containsKey("plugins"), "mediation plugins must be nested, not top-level");

        Map<?, ?> s0 = (Map<?, ?>) ((List<?>) root.get("services")).get(0);
        List<?> routes = (List<?>) s0.get("routes");
        assertEquals(2, routes.size(), "service owns its own route + the product-context route");
        for (Object ro : routes) {
            assertFalse(((Map<?, ?>) ro).containsKey("service"), "nested route carries no service FK");
        }
        assertTrue(routes.stream().anyMatch(ro -> "prod-route".equals(((Map<?, ?>) ro).get("name"))),
                "product route nested under the member service");
        Map<?, ?> p0 = (Map<?, ?>) ((List<?>) s0.get("plugins")).get(0);
        assertEquals("post-function", p0.get("name"), "mediation plugin nested under the service");
        assertFalse(p0.containsKey("service"), "nested plugin carries no service FK");
    }

    @Test
    void emptyPlanStillHasFormatVersionAndNoEntityKeys() {
        Map<String, Object> root = parse(builder.build(List.of(), List.of(), List.of(), List.of(), List.of()));
        assertEquals("3.0", root.get("_format_version"));
        assertFalse(root.containsKey("services"));
    }

    @Test
    void singleApiMigrationIsolatesFilesUnderAPerApiDir() {
        KongService svc = KongService.builder().name("orders-1-0").host("h").port(80).protocol("http")
                .tags(List.of("wso2-source-id:api1")).build();
        TranslatedApi api = TranslatedApi.builder().wso2SourceId("api1").service(svc).build();

        Map<String, String> files = builder.buildFiles("dev", List.of(api),
                List.of(), List.of(), List.of(), List.of());

        // per-api-dir defaults on → the single API's files are isolated under kong/dev/<slug>/ so the
        // pipeline applies only this API (an unrelated API's leftover file can't block it).
        assertTrue(files.containsKey("kong/dev/orders-1-0/api-orders-1-0.yaml"),
                "single-API migration should isolate under kong/dev/<api>/");
        Map<String, Object> root = parse(files.get("kong/dev/orders-1-0/api-orders-1-0.yaml"));
        assertEquals("3.0", root.get("_format_version"));
        assertEquals(1, ((List<?>) root.get("services")).size());
    }

    @Test
    void flatLayoutWhenPerApiDirDisabled() {
        MigrationProperties flat = new MigrationProperties();
        flat.getDeck().setPerApiDir(false);
        DeckYamlBuilder flatBuilder = new DeckYamlBuilder(flat);
        KongService svc = KongService.builder().name("orders-1-0").host("h").port(80).protocol("http")
                .tags(List.of("wso2-source-id:api1")).build();
        TranslatedApi api = TranslatedApi.builder().wso2SourceId("api1").service(svc).build();

        Map<String, String> files = flatBuilder.buildFiles("dev", List.of(api),
                List.of(), List.of(), List.of(), List.of());
        assertTrue(files.containsKey("kong/dev/api-orders-1-0.yaml"),
                "with per-api-dir off, files stay flat under kong/dev/");
    }

    @Test
    void omitsAllEntityIdsByDefaultSoDeckMatchesByName() {
        KongService svc = KongService.builder().name("petstore-2-0").protocol("https")
                .host("api.example.com").port(443).path("/").build();
        KongRoute route = KongRoute.builder().name("petstore-2-0-get")
                .methods(List.of("GET")).paths(List.of("/petstore/pet")).build();
        KongPlugin rlimit = KongPlugin.builder().name("rate-limiting").config(Map.of("minute", 200)).build();
        KongUpstream up = KongUpstream.builder().name("petstore-2-0-upstream").algorithm("round-robin").build();
        KongTarget tgt = KongTarget.builder().target("api.example.com:443").weight(100).build();
        TranslatedApi api = TranslatedApi.builder()
                .wso2SourceId("abc").service(svc)
                .routes(new ArrayList<>(List.of(route)))
                .servicePlugins(new ArrayList<>(List.of(KongPlugin.builder().name("jwt").build())))
                .routePlugins(new HashMap<>(Map.of("petstore-2-0-get", List.of(rlimit))))
                .upstream(up).targets(new ArrayList<>(List.of(tgt)))
                .build();

        Map<String, Object> root = parse(builder.build(List.of(api), List.of(), List.of(), List.of(), List.of()));
        assertNoIdAnywhere(root);
    }

    @Test
    void pinsDeterministicIdsWhenEmitEntityIdsIsOn() {
        MigrationProperties p = new MigrationProperties();
        p.getDeck().setEmitEntityIds(true);
        DeckYamlBuilder withIds = new DeckYamlBuilder(p);

        KongService svc = KongService.builder().name("orders-1-0").host("h").port(80).protocol("http").build();
        TranslatedApi api = TranslatedApi.builder().wso2SourceId("api1").service(svc).build();

        Map<String, Object> root = parse(withIds.build(List.of(api), List.of(), List.of(), List.of(), List.of()));
        Map<?, ?> s0 = (Map<?, ?>) ((List<?>) root.get("services")).get(0);
        Object id = s0.get("id");
        assertTrue(id instanceof String && !((String) id).isBlank(), "id should be pinned when the flag is on");

        // deterministic: a second build of the same input yields the same id
        Map<String, Object> root2 = parse(withIds.build(List.of(api), List.of(), List.of(), List.of(), List.of()));
        assertEquals(id, ((Map<?, ?>) ((List<?>) root2.get("services")).get(0)).get("id"));
    }

    @Test
    void pinsRealKongIdsSoApplyUpdatesInsteadOfConflicting() {
        // An API + consumer already in Kong: passing their real Kong ids must stamp them on the
        // top-level service/consumer so `deck gateway apply` matches by id and UPDATES (no conflict).
        KongService svc = KongService.builder().name("seed05apikeybronze-1-0-0").host("httpbin.org")
                .port(443).protocol("https").tags(List.of("wso2-source-id:api-5")).build();
        TranslatedApi api = TranslatedApi.builder().wso2SourceId("api-5").service(svc).build();
        KongConsumer kc = KongConsumer.builder().username("defaultapplication").custom_id("app-d").build();
        TranslatedConsumer consumer = TranslatedConsumer.builder()
                .wso2SourceId("app-d").wso2SourceName("DefaultApplication").consumer(kc).build();

        Map<String, String> kongIds = Map.of(
                "api-5|SERVICE", "11111111-1111-1111-1111-111111111111",
                "app-d|CONSUMER", "22222222-2222-2222-2222-222222222222");

        Map<String, Object> root = parse(builder.build(
                List.of(api), List.of(consumer), List.of(), List.of(), List.of(), kongIds));

        Map<?, ?> s0 = (Map<?, ?>) ((List<?>) root.get("services")).get(0);
        assertEquals("11111111-1111-1111-1111-111111111111", s0.get("id"), "service gets its real Kong id");
        Map<?, ?> c0 = (Map<?, ?>) ((List<?>) root.get("consumers")).get(0);
        assertEquals("22222222-2222-2222-2222-222222222222", c0.get("id"), "consumer gets its real Kong id");
    }

    @Test
    void prefersNameMatchOverSourceIdTag_soOrphanIdsDontWin() {
        // An older migration run left an orphan service that still carries THIS API's wso2-source-id
        // tag. The live Kong scan therefore offers two ids: an ambiguous "api-5|SERVICE" (could be the
        // orphan) and the unambiguous "SERVICE|name:<realName>". The name match must win — otherwise
        // apply pins the orphan id and the real service looks new → "entity already exists".
        KongService svc = KongService.builder().name("seed05apikeybronze-1-0-0").host("httpbin.org")
                .port(443).protocol("https").build();
        TranslatedApi api = TranslatedApi.builder().wso2SourceId("api-5").service(svc).build();
        KongConsumer kc = KongConsumer.builder().username("defaultapplication").custom_id("app-d").build();
        TranslatedConsumer consumer = TranslatedConsumer.builder()
                .wso2SourceId("app-d").wso2SourceName("DefaultApplication").consumer(kc).build();

        Map<String, String> kongIds = Map.of(
                "api-5|SERVICE", "00000000-0000-0000-0000-0000000orphan",                  // ambiguous tag (orphan)
                "SERVICE|name:seed05apikeybronze-1-0-0", "11111111-1111-1111-1111-111111111111",  // unique name (real)
                "CONSUMER|username:defaultapplication", "22222222-2222-2222-2222-222222222222");

        Map<String, Object> root = parse(builder.build(
                List.of(api), List.of(consumer), List.of(), List.of(), List.of(), kongIds));

        Map<?, ?> s0 = (Map<?, ?>) ((List<?>) root.get("services")).get(0);
        assertEquals("11111111-1111-1111-1111-111111111111", s0.get("id"),
                "service matched by unique name, not the orphan-sharing source-id tag");
        Map<?, ?> c0 = (Map<?, ?>) ((List<?>) root.get("consumers")).get(0);
        assertEquals("22222222-2222-2222-2222-222222222222", c0.get("id"), "consumer matched by username");
    }

    @Test
    void omitsIdForEntitiesNotYetInKong() {
        // No mapping for this API → no id emitted → apply CREATES it (and never deletes others).
        KongService svc = KongService.builder().name("new-api-1-0").host("h").port(80).protocol("http").build();
        TranslatedApi api = TranslatedApi.builder().wso2SourceId("api-new").service(svc).build();

        Map<String, Object> root = parse(builder.build(
                List.of(api), List.of(), List.of(), List.of(), List.of(), Map.of("other|SERVICE", "x")));

        Map<?, ?> s0 = (Map<?, ?>) ((List<?>) root.get("services")).get(0);
        assertFalse(s0.containsKey("id"), "unmapped entity carries no id so apply creates it");
    }

    @Test
    void emitsConsumerCredentialsNestedUnderTheConsumer() {
        KongConsumer kc = KongConsumer.builder()
                .username("seed05app").custom_id("app-uuid-5")
                .jwt_secrets(new ArrayList<>(List.of(new HashMap<>(Map.of(
                        "key", "ck-abcdef123", "algorithm", "RS256",
                        "rsa_public_key", "${WSO2_KM_PUBLIC_KEY_RESIDENT_KEY_MANAGER}")))))
                .keyauth_credentials(new ArrayList<>(List.of(new HashMap<>(Map.of(
                        "key", "${WSO2_CRED_SEED05APP_PRODUCTION_KEY}")))))
                .build();
        TranslatedConsumer tc = TranslatedConsumer.builder()
                .wso2SourceId("app-uuid-5").wso2SourceName("Seed05App").consumer(kc).build();

        Map<String, Object> root = parse(builder.build(List.of(), List.of(tc), List.of(), List.of(), List.of()));

        Map<?, ?> c0 = (Map<?, ?>) ((List<?>) root.get("consumers")).get(0);
        assertEquals("seed05app", c0.get("username"));
        Map<?, ?> jwt = (Map<?, ?>) ((List<?>) c0.get("jwt_secrets")).get(0);
        assertEquals("ck-abcdef123", jwt.get("key"));
        assertEquals("${WSO2_KM_PUBLIC_KEY_RESIDENT_KEY_MANAGER}", jwt.get("rsa_public_key"));
        Map<?, ?> ka = (Map<?, ?>) ((List<?>) c0.get("keyauth_credentials")).get(0);
        assertEquals("${WSO2_CRED_SEED05APP_PRODUCTION_KEY}", ka.get("key"));
    }

    /** Recursively assert no map in the parsed YAML carries an {@code id} key. */
    private static void assertNoIdAnywhere(Object node) {
        if (node instanceof Map<?, ?> m) {
            assertFalse(m.containsKey("id"), "no entity should carry an 'id' when emit-entity-ids is off");
            for (Object v : m.values()) assertNoIdAnywhere(v);
        } else if (node instanceof List<?> l) {
            for (Object v : l) assertNoIdAnywhere(v);
        }
    }

    private static Map<String, Object> parse(String yaml) {
        return new Yaml().load(yaml);
    }
}
