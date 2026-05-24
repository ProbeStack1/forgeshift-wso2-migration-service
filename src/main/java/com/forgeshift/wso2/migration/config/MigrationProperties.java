package com.forgeshift.wso2.migration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** All tunables for the migration service. Bound from {@code forgeshift.migration.*}. */
@Data
@ConfigurationProperties(prefix = "forgeshift.migration")
public class MigrationProperties {

    private String jobsCollection = "migration_jobs";
    private String entityMappingsCollection = "entity_mappings";
    private String reportsCollection = "migration_reports";
    private String auditCollection = "migration_audit_info";

    private String discoveryCollectionPrefix = "discovery_wso2_";
    private String discoveryRevisionsCollection = "discovery_revisions";

    private String wso2ProfilesCollection = "profiles";
    private String kongKonnectProfilesCollection = "kong_konnect_profiles";

    private Translation translation = new Translation();
    private Konnect konnect = new Konnect();
    private Tenant tenant = new Tenant();

    @Data
    public static class Translation {
        private int defaultThrottleRpm = 10000;
        private int defaultTimeoutMs = 60000;
        /** Prefix for the wso2-source-id tag stamped on every Kong entity. */
        private String tagPrefix = "wso2-source-id";
        private String migratedByTag = "migrated-by:forgeshift-wso2-migrator";
        /** WSO2 throttling tier name → requests per minute. */
        private Map<String, Integer> throttlingTierMap = defaultThrottlingMap();

        private static Map<String, Integer> defaultThrottlingMap() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("Bronze", 10);
            m.put("Silver", 50);
            m.put("Gold", 200);
            m.put("Platinum", 500);
            m.put("Unlimited", 10000);
            return m;
        }
    }

    @Data
    public static class Konnect {
        private String baseUrlFallback = "https://us.api.konghq.com";
        private String accessTokenFallback;
        private String controlPlaneIdFallback;
        private int requestTimeoutSeconds = 30;
        private boolean trustSelfSigned;
        private String servicesPath = "/services";
        private String routesPath = "/routes";
        private String upstreamsPath = "/upstreams";
        private String targetsPath = "/upstreams/{upstreamId}/targets";
        private String consumersPath = "/consumers";
        private String pluginsPath = "/plugins";
    }

    @Data
    public static class Tenant {
        private String headerName = "X-Partner-Id";
        private String defaultTenant = "probestack";
    }
}
