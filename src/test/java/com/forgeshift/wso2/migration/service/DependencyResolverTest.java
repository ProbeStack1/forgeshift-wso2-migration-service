package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DependencyResolverTest {

    private final DependencyResolver resolver = new DependencyResolver();

    @Test
    void apiPullsItsSubscriptionsAndProducts() {
        DiscoverySnapshot api = DiscoverySnapshot.builder().sourceId("api-1").sourceName("PaymentAPI").build();
        Map<String, Map<String, List<String>>> graph = Map.of(
                "PaymentAPI", Map.of(
                        "subscriptions", List.of("sub-1", "sub-2"),
                        "apiProducts", List.of("prod-1")));

        Map<String, Set<String>> deps = resolver.resolveDirect(Map.of("apis", List.of(api)), graph);

        assertEquals(Set.of("sub-1", "sub-2"), deps.get("subscriptions"));
        assertEquals(Set.of("prod-1"), deps.get("apiproducts"));   // graph "apiProducts" -> migration "apiproducts"
        assertNull(deps.get("applications"));                       // apps resolved later, from the subs
    }

    @Test
    void applicationPullsItsSubscriptionsAndApis() {
        DiscoverySnapshot app = DiscoverySnapshot.builder().sourceId("app-1").sourceName("MobileApp").build();
        Map<String, Map<String, List<String>>> graph = Map.of(
                "MobileApp", Map.of(
                        "subscriptions", List.of("sub-1"),
                        "apis", List.of("api-1", "api-2")));

        Map<String, Set<String>> deps = resolver.resolveDirect(Map.of("applications", List.of(app)), graph);

        assertEquals(Set.of("sub-1"), deps.get("subscriptions"));
        assertEquals(Set.of("api-1", "api-2"), deps.get("apis"));
    }

    @Test
    void subscriptionPullsItsApiAndAppFromPayload() {
        DiscoverySnapshot sub = DiscoverySnapshot.builder().sourceId("sub-1").sourceName("MobileApp -> PaymentAPI")
                .payload(Map.of("apiId", "api-1", "applicationId", "app-1")).build();

        Map<String, Set<String>> deps = resolver.resolveDirect(Map.of("subscriptions", List.of(sub)), Map.of());

        assertEquals(Set.of("api-1"), deps.get("apis"));
        assertEquals(Set.of("app-1"), deps.get("applications"));
    }

    @Test
    void apiProductPullsItsMemberApis() {
        DiscoverySnapshot product = DiscoverySnapshot.builder().sourceId("prod-1").sourceName("SeedProduct")
                .payload(Map.of("apis", List.of(
                        Map.of("apiId", "api-1", "name", "PaymentAPI"),
                        Map.of("id", "api-2", "name", "UserAPI"))))   // both "apiId" and "id" accepted
                .build();

        Map<String, Set<String>> deps = resolver.resolveDirect(Map.of("apiproducts", List.of(product)), Map.of());

        assertEquals(Set.of("api-1", "api-2"), deps.get("apis"));
    }

    @Test
    void mediationPolicyPullsItsApiFromMetadata() {
        DiscoverySnapshot med = DiscoverySnapshot.builder().sourceId("med-1").sourceName("seed-log")
                .metadata(Map.of("apiId", "api-9")).build();

        Map<String, Set<String>> deps = resolver.resolveDirect(Map.of("mediationpolicies", List.of(med)), Map.of());

        assertEquals(Set.of("api-9"), deps.get("apis"));
    }

    @Test
    void caseInsensitiveNameMatchAndUnknownApiYieldsNothing() {
        DiscoverySnapshot api = DiscoverySnapshot.builder().sourceId("api-1").sourceName("paymentapi").build();
        Map<String, Map<String, List<String>>> graph = Map.of(
                "PaymentAPI", Map.of("subscriptions", List.of("sub-9")));

        assertEquals(Set.of("sub-9"),
                resolver.resolveDirect(Map.of("apis", List.of(api)), graph).get("subscriptions"));

        DiscoverySnapshot unknown = DiscoverySnapshot.builder().sourceId("x").sourceName("NotInGraph").build();
        assertEquals(Map.of(), resolver.resolveDirect(Map.of("apis", List.of(unknown)), graph));
    }
}
