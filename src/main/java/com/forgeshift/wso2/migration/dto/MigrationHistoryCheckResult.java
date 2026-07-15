package com.forgeshift.wso2.migration.dto;

import com.forgeshift.wso2.migration.domain.MigrationHistoryEntry;
import com.forgeshift.wso2.migration.service.MigrationHistoryService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One per requested sourceId: migrated or not, plus the latest run/repo details when it is. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationHistoryCheckResult {

    private String sourceId;

    /** True only when the latest run reached MIGRATED (IN_PROGRESS / FAILED / never → false). */
    private boolean migrated;

    /** null = never migrated; else IN_PROGRESS | MIGRATED | FAILED. */
    private String status;

    /** The latest run's per-resource tracking id (the pipeline reports against it). */
    private String trackingId;

    private String sourceName;
    private String sourceVersion;
    private String migrationId;
    private String requestTransactionId;

    /** Discovery revision this resource was migrated from — same revision ⇒ nothing new to assess. */
    private Integer sourceRevision;
    private String sourceDiscoveryId;
    private String controlPlaneId;
    private String gitRepo;
    private String gitBranch;
    private String gitCommitSha;
    private String gitCommitUrl;
    private String migratedBy;
    private Instant migratedAt;
    private Instant firstMigratedAt;
    private int timesMigrated;
    private String lastError;

    public static MigrationHistoryCheckResult of(String sourceId, MigrationHistoryEntry e) {
        // A row without a status is the "never migrated" placeholder from the service.
        if (e == null || e.getStatus() == null) {
            return MigrationHistoryCheckResult.builder().sourceId(sourceId).migrated(false).build();
        }
        return MigrationHistoryCheckResult.builder()
                .sourceId(sourceId)
                .migrated(MigrationHistoryService.STATUS_MIGRATED.equals(e.getStatus()))
                .status(e.getStatus())
                .trackingId(e.getTrackingId())
                .sourceName(e.getSourceName())
                .sourceVersion(e.getSourceVersion())
                .migrationId(e.getMigrationId())
                .requestTransactionId(e.getRequestTransactionId())
                .sourceRevision(e.getSourceRevision())
                .sourceDiscoveryId(e.getSourceDiscoveryId())
                .controlPlaneId(e.getControlPlaneId())
                .gitRepo(e.getGitRepo())
                .gitBranch(e.getGitBranch())
                .gitCommitSha(e.getGitCommitSha())
                .gitCommitUrl(e.getGitCommitUrl())
                .migratedBy(e.getMigratedBy())
                .migratedAt(e.getMigratedAt())
                .firstMigratedAt(e.getFirstMigratedAt())
                .timesMigrated(e.getTimesMigrated())
                .lastError(e.getLastError())
                .build();
    }
}
