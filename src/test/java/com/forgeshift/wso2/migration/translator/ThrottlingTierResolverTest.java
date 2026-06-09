package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshotReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ThrottlingTierResolverTest {

    @Test
    void parsesRpmFromDescriptionAndNormalisesWindow() {
        assertEquals(1000, ThrottlingTierResolver.parseRpm("Allows 1000 requests per minute"));
        assertEquals(500, ThrottlingTierResolver.parseRpm("Allows 500 request(s) per minute"));
        assertEquals(6000, ThrottlingTierResolver.parseRpm("Allows 100 requests per second")); // 100/s → 6000/min
        // AI tier: take the "requests per minute" clause, not the token quota
        assertEquals(10, ThrottlingTierResolver.parseRpm("Allows 1000 total tokens and 10 requests per minute"));
        assertNull(ThrottlingTierResolver.parseRpm("Allows unlimited requests"));
        assertNull(ThrottlingTierResolver.parseRpm(null));
    }

    @Test
    void nullReaderFallsBackToConfiguredMap() {
        MigrationProperties props = new MigrationProperties();
        ThrottlingTierResolver resolver = new ThrottlingTierResolver(null, props);

        Map<String, Integer> map = resolver.effectiveTierRpm("probestack", "carbon.super");
        assertEquals(10, map.get("Bronze"));   // configured fallback default
    }

    @Test
    void discoveredSubscriptionLimitsOverrideTheConfiguredMap() {
        MigrationProperties props = new MigrationProperties();
        DiscoverySnapshotReader stub = new DiscoverySnapshotReader(null, null) {
            @Override
            public List<DiscoverySnapshot> findLatestRevision(String company, String tenant, String resourceType) {
                return List.of(
                        policy("Bronze", "subscription", "Allows 1000 requests per minute"),
                        policy("Gold", "subscription", "Allows 5000 requests per minute"));
            }
        };
        ThrottlingTierResolver resolver = new ThrottlingTierResolver(stub, props);

        Map<String, Integer> map = resolver.effectiveTierRpm("probestack", "carbon.super");
        assertEquals(1000, map.get("Bronze"));   // real WSO2 value, not the configured 10
        assertEquals(5000, map.get("Gold"));      // real WSO2 value, not the configured 200
        assertEquals(10000, map.get("Unlimited")); // untouched configured fallback still present
    }

    private static DiscoverySnapshot policy(String name, String policyType, String description) {
        return DiscoverySnapshot.builder()
                .sourceName(name)
                .payload(Map.of("__policyType", policyType, "description", description))
                .build();
    }
}
