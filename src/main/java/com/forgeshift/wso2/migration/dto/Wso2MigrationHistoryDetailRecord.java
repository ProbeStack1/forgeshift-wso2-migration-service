package com.forgeshift.wso2.migration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * One per-resource-type "summary detail" row for a single WSO2 &rarr; Kong
 * Konnect migration run.
 *
 * <p>The WSO2 equivalent of an Apigee {@code MigrationDocument} record. Apigee
 * stores one document per individual migrated resource; WSO2 stores per-resource
 * <em>type</em> outcomes (in the job's {@code resourceProgress} and the
 * {@code MigrationReport.outcomes}), so each record here is a summary detail for
 * one resource type (apis / applications / subscriptions / ...).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Wso2MigrationHistoryDetailRecord {

    private String requestTransactionId;
    private Integer revision;
    private String companyName;
    private String wso2Tenant;
    private String controlPlaneId;

    private String resourceType;
    private String state;          // RUNNING / COMPLETED / FAILED / SKIPPED

    private int translated;
    private int deployed;
    private int unchanged;
    private int failed;
    private int skipped;
    private List<String> failedSourceIds;

    private String lastError;
    private Instant startedAt;
    private Instant completedAt;

    private Instant createdDateTime;
}
