package com.forgeshift.wso2.migration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One row per migration run. State machine + per-resource progress + counts. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("migration_jobs")
public class MigrationJob {

    @Id
    private String id;

    @Indexed private String companyName;
    @Indexed private String wso2Tenant;

    @Indexed private MigrationState state;

    /** Which discovery snapshot this migration sources from. */
    private String sourceDiscoveryId;
    private Integer sourceRevision;

    /** Konnect target. */
    private String controlPlaneId;
    private String konnectBaseUrl;

    /** Resource types in scope for this run (apis, applications, subscriptions, throttlingpolicies, keymanagers). */
    private List<String> resourceTypes;

    /** Transient (not persisted): dependency-migration tree from DependencyExpander, copied into the MigrationReport. */
    @org.springframework.data.annotation.Transient
    private List<MigrationReport.DependencyMigration> dependencyMigrations;

    /** Dry-run = translate + diff only, no Konnect writes. */
    private boolean dryRun;

    @Builder.Default
    private Map<String, ResourceProgress> resourceProgress = new java.util.HashMap<>();

    @Builder.Default
    private Counts counts = new Counts();

    private String createdBy;
    private String lastError;
    private String requestTransactionId;

    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;
    private Instant completedAt;

    @Version
    private Long version;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceProgress {
        private String state;          // RUNNING, COMPLETED, FAILED, SKIPPED
        private int translated;
        private int deployed;
        private int unchanged;
        private int failed;
        private String lastError;
        private Instant startedAt;
        private Instant completedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Counts {
        @Builder.Default private int totalTranslated = 0;
        @Builder.Default private int totalDeployed = 0;
        @Builder.Default private int totalUnchanged = 0;
        @Builder.Default private int totalFailed = 0;
        @Builder.Default private int totalSkipped = 0;
    }
}
