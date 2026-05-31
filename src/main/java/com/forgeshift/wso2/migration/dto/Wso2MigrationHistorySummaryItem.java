package com.forgeshift.wso2.migration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Summary row for one WSO2 &rarr; Kong Konnect migration run.
 *
 * <p>The WSO2 equivalent of the Apigee migration service's
 * {@code MigrationHistorySummaryItem}: one entry per unique
 * {@code (requestTransactionId + revision)}, stamped with the run's earliest
 * {@code createdDateTime}, carrying the source (WSO2 tenant / environment) and
 * target (Kong Konnect control plane) context. Apigee's Edge/Apigee-X source
 * fields collapse to the single WSO2 tenant, and {@code kongCtrlPlanId} /
 * {@code kongRegion} map to {@code controlPlaneId} / {@code konnectBaseUrl}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Wso2MigrationHistorySummaryItem {

    private String requestTransactionId;
    private Integer revision;
    private Instant createdDateTime;

    private String companyName;

    /** Source context (WSO2). Mirrors Apigee edgeOrg / edgeEnv. */
    private String wso2Tenant;
    private String environment;

    /** Target context (Kong Konnect). Mirrors Apigee kongCtrlPlanId / kongRegion. */
    private String controlPlaneId;
    private String konnectBaseUrl;
}
