package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import lombok.Builder;
import lombok.Data;

/**
 * Combined response for synchronous migration calls — caller gets the
 * terminal {@link MigrationJob} state AND the per-resource
 * {@link MigrationReport} in a single round-trip, eliminating the need
 * to follow up with {@code GET /migrations/{id}} +
 * {@code GET /migrations/{id}/report}.
 *
 * <p>{@code report} may be {@code null} when the job ended in {@code FAILED}
 * before {@code writeReport} got a chance to run, or when the synchronous
 * wait timed out (in which case {@code job.state} will not be terminal).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationResultResponse {
    private MigrationJobResponse job;
    private MigrationReport report;

    public static MigrationResultResponse from(MigrationJob job, MigrationReport report) {
        return MigrationResultResponse.builder()
                .job(MigrationJobResponse.from(job))
                .report(report)
                .build();
    }
}
