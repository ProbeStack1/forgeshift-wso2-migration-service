package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import com.forgeshift.wso2.migration.dto.Wso2MigrationHistoryDetailRecord;
import com.forgeshift.wso2.migration.dto.Wso2MigrationHistoryDetailResponse;
import com.forgeshift.wso2.migration.dto.Wso2MigrationHistorySummaryItem;
import com.forgeshift.wso2.migration.repository.MigrationJobRepository;
import com.forgeshift.wso2.migration.repository.MigrationReportRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the migration history summary — the WSO2 mirror of the Apigee migration
 * service's {@code MigrationHistoryServiceImpl}.
 *
 * <p>Returns one summary row per unique {@code (requestTransactionId + revision)},
 * stamped with that run's earliest {@code createdDateTime}, newest first.
 * WSO2 migration jobs are scoped by {@code (companyName, wso2Tenant)} — there is
 * no per-job environment, so {@code environment} is echoed into each row for
 * response parity rather than used to filter.
 */
@Service
public class Wso2MigrationHistoryService {

    private final MigrationJobRepository jobRepository;
    private final MigrationReportRepository reportRepository;

    public Wso2MigrationHistoryService(MigrationJobRepository jobRepository,
                                       MigrationReportRepository reportRepository) {
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
    }

    public List<Wso2MigrationHistorySummaryItem> getMigrationHistorySummary(
            String companyName, String wso2Tenant, String environment) {

        List<MigrationJob> allRecords = jobRepository.findByCompanyNameAndWso2Tenant(companyName, wso2Tenant);

        // Filter out records without a transaction id. (Unlike Apigee, a WSO2
        // migration job's sourceRevision is only populated once the run resolves
        // its discovery snapshot, so it is NOT required here — otherwise pending
        // or revision-less jobs would be dropped entirely.)
        List<MigrationJob> validRecords = allRecords.stream()
                .filter(r -> r.getRequestTransactionId() != null)
                .collect(Collectors.toList());

        // Group by requestTransactionId + revision
        Map<String, List<MigrationJob>> grouped = validRecords.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getRequestTransactionId() + "||" + r.getSourceRevision()));

        List<Wso2MigrationHistorySummaryItem> result = new ArrayList<>();

        for (Map.Entry<String, List<MigrationJob>> entry : grouped.entrySet()) {
            List<MigrationJob> records = entry.getValue();

            // find earliest createdAt within the run
            MigrationJob first = records.stream()
                    .filter(r -> r.getCreatedAt() != null)
                    .min(Comparator.comparing(MigrationJob::getCreatedAt))
                    .orElse(records.get(0));

            result.add(Wso2MigrationHistorySummaryItem.builder()
                    .requestTransactionId(first.getRequestTransactionId())
                    .revision(first.getSourceRevision())
                    .createdDateTime(first.getCreatedAt())
                    .companyName(first.getCompanyName())
                    .wso2Tenant(first.getWso2Tenant())
                    .environment(environment)
                    .controlPlaneId(first.getControlPlaneId())
                    .konnectBaseUrl(first.getKonnectBaseUrl())
                    .build());
        }

        // Sort by date desc (latest first), nulls last
        result.sort(Comparator.comparing(
                Wso2MigrationHistorySummaryItem::getCreatedDateTime,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return result;
    }

    /**
     * Detail / drill-down for a single migration run — the WSO2 mirror of the
     * Apigee {@code getMigrationHistoryDetail}. Given a run's
     * {@code requestTransactionId} (optionally narrowed by {@code revision}),
     * returns one summary-detail record per resource type, sourced from the
     * job's {@code resourceProgress} and the {@code MigrationReport.outcomes}.
     */
    public Wso2MigrationHistoryDetailResponse getMigrationHistoryDetail(
            String requestTransactionId, Integer revision) {

        List<MigrationJob> jobs = jobRepository.findByRequestTransactionId(requestTransactionId);
        // requestTransactionId already uniquely identifies the run; only use
        // revision to narrow when the job actually recorded a sourceRevision.
        // A WSO2 job's sourceRevision is often null (set only once a discovery
        // snapshot is resolved), so a null-revision job must NOT be excluded here.
        if (revision != null) {
            jobs = jobs.stream()
                    .filter(j -> j.getSourceRevision() == null || revision.equals(j.getSourceRevision()))
                    .collect(Collectors.toList());
        }

        List<Wso2MigrationHistoryDetailRecord> records = new ArrayList<>();

        for (MigrationJob job : jobs) {
            Map<String, MigrationJob.ResourceProgress> progress =
                    job.getResourceProgress() != null ? job.getResourceProgress() : Map.of();

            MigrationReport report = reportRepository.findByMigrationJobId(job.getId()).orElse(null);

            if (report != null && report.getOutcomes() != null && !report.getOutcomes().isEmpty()) {
                // Prefer the report's per-type outcomes (they carry skipped + failedSourceIds).
                for (MigrationReport.ResourceOutcome outcome : report.getOutcomes()) {
                    records.add(buildDetailRecord(job, outcome.getResourceType(),
                            outcome, progress.get(outcome.getResourceType())));
                }
            } else {
                // No report yet — fall back to the job's per-type progress.
                for (Map.Entry<String, MigrationJob.ResourceProgress> e : progress.entrySet()) {
                    records.add(buildDetailRecord(job, e.getKey(), null, e.getValue()));
                }
            }
        }

        return Wso2MigrationHistoryDetailResponse.builder().records(records).build();
    }

    private Wso2MigrationHistoryDetailRecord buildDetailRecord(
            MigrationJob job, String resourceType,
            MigrationReport.ResourceOutcome outcome,
            MigrationJob.ResourceProgress progress) {

        var builder = Wso2MigrationHistoryDetailRecord.builder()
                .requestTransactionId(job.getRequestTransactionId())
                .revision(job.getSourceRevision())
                .companyName(job.getCompanyName())
                .wso2Tenant(job.getWso2Tenant())
                .controlPlaneId(job.getControlPlaneId())
                .resourceType(resourceType)
                .createdDateTime(job.getCreatedAt());

        if (outcome != null) {
            builder.translated(outcome.getTranslated())
                    .deployed(outcome.getDeployed())
                    .unchanged(outcome.getUnchanged())
                    .failed(outcome.getFailed())
                    .skipped(outcome.getSkipped())
                    .failedSourceIds(outcome.getFailedSourceIds());
        } else if (progress != null) {
            builder.translated(progress.getTranslated())
                    .deployed(progress.getDeployed())
                    .unchanged(progress.getUnchanged())
                    .failed(progress.getFailed());
        }

        if (progress != null) {
            builder.state(progress.getState())
                    .lastError(progress.getLastError())
                    .startedAt(progress.getStartedAt())
                    .completedAt(progress.getCompletedAt());
        }

        return builder.build();
    }
}
