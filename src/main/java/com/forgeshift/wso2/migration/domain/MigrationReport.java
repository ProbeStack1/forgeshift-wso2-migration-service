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
}
