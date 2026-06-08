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
    private String gitProfilesCollection = "git_profiles";

    private Translation translation = new Translation();
    private Konnect konnect = new Konnect();
    private Tenant tenant = new Tenant();
    private Wso2 wso2 = new Wso2();
    private BundleDownload bundleDownload = new BundleDownload();
    private Deck deck = new Deck();
    private Dependency dependency = new Dependency();

    /** Dependency-aware migration: auto-include dependencies + skip what's already in Kong. */
    @Data
    public static class Dependency {
        /** Master switch; when false the includeDependencies request flag is ignored. */
        private boolean enabled = true;
        /** Collection (owned by the assessment service) holding resourceDependencies per assessment run. */
        private String assessmentResourceInfoCollection = "wso2_assessment_resource_info";
        /** When true, drop resources already present in Kong (by wso2-source-id tag) from the run. */
        private boolean excludeAlreadyMigrated = true;
    }

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
        private String caCertificatesPath = "/ca-certificates";
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

    /** decK bundle delivery (replaces direct Konnect REST writes when {@code enabled}). */
    @Data
    public static class Deck {
        private boolean enabled = true;
        private String formatVersion = "3.0";
        private boolean transform = true;
        private String envName = "dev";
        private String pipelineTemplateRef = "ProbeStack1/pipeline-template-poc/.github/workflows/kong.yaml@main";
        private String deckMode = "apply";
        private String konnectAddr = "https://us.api.konghq.com";
        private String kongConfigPathTemplate = "kong/{env}/kong.yaml";
        /** Directory the per-API files live in (decK merges every file in it). */
        private String kongConfigDirTemplate = "kong/{env}";
        private String konnectSecretName = "KONNECT_TOKEN";
        /**
         * TEST-ONLY: deliver the Konnect token to the pipeline as a plaintext GitHub Actions
         * VARIABLE (auto-set by the service once the repo exists) instead of an encrypted
         * secret. The generated workflow then reads {@code ${{ vars.<name> }}} rather than
         * {@code ${{ secrets.<name> }}}. INSECURE — the value is visible in the repo's Actions
         * settings; switch to a real secret for production.
         */
        private boolean konnectTokenViaVariable = false;
        private String controlPlaneNameFallback = "";
        private String bundleDir = System.getProperty("java.io.tmpdir") + "/kong-bundles";
        private String downloadBaseUrl = "";
        /** Public base URL of THIS service so the pipeline can POST results back to /migrations/{id}/deck-result. */
        private String callbackBaseUrl = "";
        /** If no deck-apply callback arrives within this many minutes, a DEPLOYING_TO_KONG job is marked TIMED_OUT. */
        private int applyTimeoutMinutes = 15;
        private String storage = "temp";
        private Git git = new Git();

        /** Auto-commit of the generated decK files to the Kong-config git repo (GitHub Contents API). */
        @Data
        public static class Git {
            private boolean enabled = false;
            private String apiBaseUrl = "https://api.github.com";
            /** owner/repo fallback when the Konnect profile doesn't carry one. */
            private String repo = "";
            private String branch = "main";
            /** PAT fallback when the profile doesn't carry one. */
            private String token = "";
            /** Collection that holds per-company git provider profiles (org + PAT). */
            private String profilesCollection = "git_profiles";
            private String authorName = "forgeshift-wso2-migrator";
            private String authorEmail = "migrator@forgeshift.local";
            private String commitMessageTemplate = "[wso2-migration] job {jobId} ({company}/{tenant})";
        }
    }
}
