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

    @Test
    void parsesSlashStyleRates() {
        // WSO2 custom-tier descriptions look like "BankGold (5000 req/min)" — no "requests per".
        assertEquals(5000, ThrottlingTierResolver.parseRpm("BankGold (5000 req/min)"));
        assertEquals(1000, ThrottlingTierResolver.parseRpm("BankSilver (1000 req/min)"));
        assertEquals(120, ThrottlingTierResolver.parseRpm("Allows 2 req/sec")); // 2/s → 120/min
    }

    @Test
    void resolvesNestedDefaultLimit_wso2_4x_shape() {
        // The real WSO2 4.x shape: defaultLimit.requestCount is a NESTED object, not a scalar — the
        // number that previously fell through to the (unparseable) description lives one level deeper.
        MigrationProperties props = new MigrationProperties();
        DiscoverySnapshotReader stub = new DiscoverySnapshotReader(null, null) {
            @Override
            public List<DiscoverySnapshot> findLatestRevision(String c, String t, String r) {
                return List.of(policyNested("BankGold", 5000, "min", 1));
            }
        };
        ThrottlingTierResolver resolver = new ThrottlingTierResolver(stub, props);
        assertEquals(5000, resolver.effectiveTierRpm("probestack", "carbon.super").get("BankGold"));
    }

    private static DiscoverySnapshot policy(String name, String policyType, String description) {
        return DiscoverySnapshot.builder()
                .sourceName(name)
                .payload(Map.of("__policyType", policyType, "description", description))
                .build();
    }

    private static DiscoverySnapshot policyNested(String name, int count, String timeUnit, int unitTime) {
        Map<String, Object> requestCount = Map.of("requestCount", count, "timeUnit", timeUnit, "unitTime", unitTime);
        Map<String, Object> defaultLimit = Map.of("type", "REQUESTCOUNTLIMIT", "requestCount", requestCount);
        return DiscoverySnapshot.builder()
                .sourceName(name)
                .payload(Map.of("__policyType", "subscription", "defaultLimit", defaultLimit,
                        "description", name + " (" + count + " req/min)"))
                .build();
    }
}
