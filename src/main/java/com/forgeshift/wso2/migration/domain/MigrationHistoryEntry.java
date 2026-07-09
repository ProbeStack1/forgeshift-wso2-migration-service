package com.forgeshift.wso2.migration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The per-resource migration checklist: ONE row per (company, tenant, resourceType, sourceId),
 * upserted on every migration run that includes the resource — unlike {@code migration_jobs},
 * which is one row per RUN. This is what answers "has CustomPolicyApi been migrated?" for the
 * frontend, and — with one repo per migration — WHICH repo/commit holds its latest bundle.
 *
 * <p>Lifecycle: rows are created {@code IN_PROGRESS} as soon as the migration is triggered
 * (each with a fresh per-resource {@code trackingId}; the {@code migrationId} is shared by the
 * whole run). The pipeline reports per-resource outcomes to {@code POST /wso2/migration-status}
 * with the trackingId(s), which flips the row to {@code MIGRATED} / {@code FAILED} and stores
 * Kong's response. On the REST path the row is written directly as {@code MIGRATED} /
 * {@code FAILED}. A re-migration updates the same row (new run/trackingId/repo/commit,
 * {@code timesMigrated}+1) rather than adding a second one.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("migration_history")
public class MigrationHistoryEntry {

    /** Composite: {@code company|tenant|resourceType|sourceId} — the natural upsert key. */
    @Id
    private String id;

    @Indexed private String companyName;
    @Indexed private String wso2Tenant;

    /** apis | applications | certificates | apiproducts. */
    @Indexed private String resourceType;

    /** The WSO2 source id (API uuid, application id, cert alias, product id). */
    @Indexed private String sourceId;
    private String sourceName;
    private String sourceVersion;

    /** IN_PROGRESS → MIGRATED | FAILED. The check endpoint treats only MIGRATED as migrated. */
    @Indexed private String status;

    /** The LATEST run that included this resource (drill down via /wso2/history/detail). */
    @Indexed private String migrationId;
    private String requestTransactionId;

    /** Unique per resource per run — the pipeline reports this resource's outcome against it. */
    @Indexed private String trackingId;

    /** Konnect target of the latest run. */
    private String controlPlaneId;

    /** Where the latest bundle lives — the key fields for one-repo-per-migration. */
    private String gitRepo;
    private String gitBranch;
    private String gitCommitSha;
    private String gitCommitUrl;

    private String migratedBy;
    /** When the latest run recorded this resource. */
    private Instant migratedAt;
    /** Set once, on the first run that ever recorded this resource. */
    private Instant firstMigratedAt;

    /** Number of migration runs that included this resource (1 on first migration). */
    private int timesMigrated;

    /** Failure detail of the latest run, when status = FAILED. */
    private String lastError;

    /** Kong's response for this resource, as reported by the pipeline's status call (truncated). */
    private String kongResponse;
    /** When the pipeline reported this resource's outcome. */
    private Instant statusReportedAt;

    public static String compositeId(String companyName, String wso2Tenant,
                                     String resourceType, String sourceId) {
        return companyName + "|" + wso2Tenant + "|" + resourceType + "|" + sourceId;
    }
}
