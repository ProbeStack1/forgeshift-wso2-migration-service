package com.forgeshift.wso2.migration.dto;

import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationState;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class MigrationJobResponse {
    private String id;
    private String companyName;
    private String wso2Tenant;
    private MigrationState state;
    private String sourceDiscoveryId;
    private Integer sourceRevision;
    private String controlPlaneId;
    private String konnectBaseUrl;
    private List<String> resourceTypes;
    private boolean dryRun;
    private Map<String, MigrationJob.ResourceProgress> resourceProgress;
    private MigrationJob.Counts counts;
    private String createdBy;
    private String requestTransactionId;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public static MigrationJobResponse from(MigrationJob j) {
        return MigrationJobResponse.builder()
                .id(j.getId())
                .companyName(j.getCompanyName())
                .wso2Tenant(j.getWso2Tenant())
                .state(j.getState())
                .sourceDiscoveryId(j.getSourceDiscoveryId())
                .sourceRevision(j.getSourceRevision())
                .controlPlaneId(j.getControlPlaneId())
                .konnectBaseUrl(j.getKonnectBaseUrl())
                .resourceTypes(j.getResourceTypes())
                .dryRun(j.isDryRun())
                .resourceProgress(j.getResourceProgress())
                .counts(j.getCounts())
                .createdBy(j.getCreatedBy())
                .requestTransactionId(j.getRequestTransactionId())
                .lastError(j.getLastError())
                .createdAt(j.getCreatedAt())
                .updatedAt(j.getUpdatedAt())
                .completedAt(j.getCompletedAt())
                .build();
    }
}
