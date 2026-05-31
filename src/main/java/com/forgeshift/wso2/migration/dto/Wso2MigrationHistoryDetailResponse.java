package com.forgeshift.wso2.migration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for the migration history detail endpoint — the WSO2 mirror of the
 * Apigee {@code MigrationHistoryDetailResponse} ({@code { records: [...] }}).
 * Carries the per-resource-type summary-detail records for one migration run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Wso2MigrationHistoryDetailResponse {

    private List<Wso2MigrationHistoryDetailRecord> records;
}
