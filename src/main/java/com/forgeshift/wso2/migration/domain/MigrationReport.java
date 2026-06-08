package com.forgeshift.wso2.migration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Final per-job report: per-resource outcomes, per-item warnings, the dry-run
 * diff (when applicable), and the deploy summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("migration_reports")
public class MigrationReport {

    @Id
    private String id;

    @Indexed private String migrationJobId;
    @Indexed private String companyName;
    @Indexed private String wso2Tenant;

    private String controlPlaneId;
    private boolean dryRun;

    /** What we planned, by resource type. */
    private List<ResourceOutcome> outcomes;

    /** Items the translator couldn't handle. */
    private List<Warning> warnings;

    /** Summary of the dry-run diff when dryRun==true. */
    private DiffSummary diff;

    /** Per-API translated Kong detail (service name, route paths, plugin names) for downstream views. */
    private List<ApiKongDetail> apiKongDetails;

    /** decK bundle outputs (populated when deck delivery is enabled). */
    private String bundleDownloadUrl;
    private String bundlePath;
    private String controlPlaneName;
    private String kongConfigPath;
    private String deckMode;

    /** Auto-commit outcome (populated when deck.git.enabled and a repo/token is available). */
    private String gitRepo;
    private String gitBranch;
    private String gitCommitSha;
    private String gitCommitUrl;
    private Integer gitFilesPushed;
    private String gitError;

    /** decK apply outcome from the pipeline callback (POST /migrations/{id}/deck-result). */
    private Integer deckApplyErrorCount;
    private List<String> deckApplyErrors;
    /** Full captured stderr/diagnostics from the failing pipeline step (validate or apply) — so even
     *  non-apply failures (where deckApplyErrors is empty) have their reason queryable here. */
    private String deckApplyStderr;

    /** Structured tree (only when includeDependencies=true): each SELECTED resource + the
     *  dependencies pulled in for it, by name, each flagged if it was already in Kong (skipped). */
    private List<DependencyMigration> dependencyMigrations;

    private Instant generatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceOutcome {
        private String resourceType;     // apis / applications / ...
        private int translated;
        private int deployed;
        private int unchanged;
        private int failed;
        private int skipped;
        private List<String> failedSourceIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Warning {
        private String resourceType;
        private String wso2SourceId;
        private String wso2SourceName;
        private String code;             // UNSUPPORTED_MEDIATION, MISSING_SCOPE, REQUIRES_REVIEW, ...
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffSummary {
        private int created;
        private int updated;
        private int unchanged;
        private int wouldFail;
        private List<String> sampleCreate;
        private List<String> sampleUpdate;
    }

    /** Translated Kong objects for one WSO2 API, persisted so downstream views can show Kong detail. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiKongDetail {
        private String wso2SourceId;
        private String wso2SourceName;
        private String kongServiceName;
        private List<String> routePaths;
        private List<String> plugins;
    }

    /** A SELECTED resource and the dependencies auto-pulled in for it (dependency-aware migration). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DependencyMigration {
        private String resourceType;        // apis / applications / subscriptions / apiproducts / ...
        private String wso2SourceId;
        private String name;
        private boolean alreadyInKong;      // true = skipped (already present in Kong)
        private List<Dep> dependencies;     // the resources pulled in BECAUSE of this one

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Dep {
            private String resourceType;
            private String wso2SourceId;
            private String name;
            private boolean alreadyInKong;
        }
    }
}
