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

    private String wso2ProfilesCollection = "wso2_profiles";
    private String kongKonnectProfilesCollection = "kong_konnect_profiles";

    private Translation translation = new Translation();
    private Konnect konnect = new Konnect();
    private Tenant tenant = new Tenant();
    private Wso2 wso2 = new Wso2();
    private BundleDownload bundleDownload = new BundleDownload();

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

    /**
     * WSO2 source-side tuning for the bundle-download phase. Credentials
     * themselves come from the {@code wso2_profiles} collection per request.
     */
    @Data
    public static class Wso2 {
        /**
         * OAuth scopes requested in the password-grant token call. Includes
         * {@code apim:api_import_export} because the Publisher export endpoint
         * is gated by it — without that scope, the call returns
         * {@code 500 / code 903220 "Failed to get API"}.
         */
        private String publisherScope =
                "apim:api_view apim:api_create apim:api_publish apim:api_import_export";
        private String tokenPath = "/oauth2/token";
        /**
         * APIM Publisher REST API export endpoint. WSO2 4.x exposes this as
         * {@code /apis/export?apiId=&format=&preserveStatus=} — query-string
         * style, not a path with the id substituted in. The client appends
         * the apiId query param at call time.
         */
        private String exportPath = "/api/am/publisher/v4/apis/export";
        /** Export format hint passed as a query param. */
        private String exportFormat = "JSON";
        /** When true, the exported ZIP keeps the lifecycle status the API was in. */
        private boolean preserveStatus = true;
        private int timeoutSeconds = 60;
    }

    @Data
    public static class BundleDownload {
        private String tempDir = System.getProperty("java.io.tmpdir") + "/wso2-migration-bundles";
        private boolean cleanupAfterMigration = true;
    }
}
