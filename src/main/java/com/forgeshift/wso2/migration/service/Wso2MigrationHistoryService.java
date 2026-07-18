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
                            outcome, progress.get(outcome.getResourceType()), report));
                }
            } else {
                // No report yet — fall back to the job's per-type progress.
                for (Map.Entry<String, MigrationJob.ResourceProgress> e : progress.entrySet()) {
                    records.add(buildDetailRecord(job, e.getKey(), null, e.getValue(), report));
                }
            }
        }

        return Wso2MigrationHistoryDetailResponse.builder().records(records).build();
    }

    private Wso2MigrationHistoryDetailRecord buildDetailRecord(
            MigrationJob job, String resourceType,
            MigrationReport.ResourceOutcome outcome,
            MigrationJob.ResourceProgress progress,
            MigrationReport report) {

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

        builder.resources(buildResourceItems(resourceType, outcome, report));
        return builder.build();
    }

    /**
     * Per-RESOURCE breakdown for one type — so the UI can render "BodyTransformApi" (+ its Kong
     * service/routes/plugins) instead of "N/A". APIs are sourced from the report's rich
     * {@code apiKongDetails}; every other type from {@code dependencyMigrations} (which carries a
     * name per resource of any type). Warnings and the type's failed-source-ids set the status.
     * Returns an empty list for older runs whose report predates these fields.
     */
    private List<Wso2MigrationHistoryDetailRecord.ResourceItem> buildResourceItems(
            String resourceType, MigrationReport.ResourceOutcome outcome, MigrationReport report) {
        if (report == null) {
            return List.of();
        }
        java.util.Set<String> failedIds = outcome != null && outcome.getFailedSourceIds() != null
                ? new java.util.HashSet<>(outcome.getFailedSourceIds()) : java.util.Set.of();

        // wso2SourceId -> first warning message, for surfacing gaps per resource.
        Map<String, String> warningById = new java.util.HashMap<>();
        if (report.getWarnings() != null) {
            for (MigrationReport.Warning w : report.getWarnings()) {
                if (w.getWso2SourceId() != null) {
                    warningById.putIfAbsent(w.getWso2SourceId(), w.getMessage());
                }
            }
        }
        // wso2SourceId -> "already in Kong" flag, from the dependency tree.
        Map<String, Boolean> alreadyInKongById = new java.util.HashMap<>();
        Map<String, String> depNameById = new java.util.HashMap<>();
        if (report.getDependencyMigrations() != null) {
            for (MigrationReport.DependencyMigration dm : report.getDependencyMigrations()) {
                if (dm.getWso2SourceId() == null) continue;
                if (resourceTypeMatches(resourceType, dm.getResourceType())) {
                    alreadyInKongById.put(dm.getWso2SourceId(), dm.isAlreadyInKong());
                    depNameById.put(dm.getWso2SourceId(), dm.getName());
                }
            }
        }

        List<Wso2MigrationHistoryDetailRecord.ResourceItem> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        // APIs: the report's per-API Kong details are the richest source (name + service + routes + plugins).
        if ("apis".equalsIgnoreCase(resourceType) && report.getApiKongDetails() != null) {
            for (MigrationReport.ApiKongDetail a : report.getApiKongDetails()) {
                if (a.getWso2SourceId() != null && !seen.add(a.getWso2SourceId())) continue;
                items.add(Wso2MigrationHistoryDetailRecord.ResourceItem.builder()
                        .sourceId(a.getWso2SourceId())
                        .sourceName(a.getWso2SourceName())
                        .status(statusFor(a.getWso2SourceId(), failedIds, alreadyInKongById))
                        .kongServiceName(a.getKongServiceName())
                        .routePaths(a.getRoutePaths())
                        .plugins(a.getPlugins())
                        .warning(warningById.get(a.getWso2SourceId()))
                        .build());
            }
        }

        // Everything else (and any API only present in the dependency tree): name from depMigrations.
        for (Map.Entry<String, String> e : depNameById.entrySet()) {
            if (!seen.add(e.getKey())) continue;
            items.add(Wso2MigrationHistoryDetailRecord.ResourceItem.builder()
                    .sourceId(e.getKey())
                    .sourceName(e.getValue())
                    .status(statusFor(e.getKey(), failedIds, alreadyInKongById))
                    .warning(warningById.get(e.getKey()))
                    .build());
        }
        return items;
    }

    private static boolean resourceTypeMatches(String recordType, String depType) {
        if (recordType == null || depType == null) return false;
        return recordType.equalsIgnoreCase(depType)
                || recordType.equalsIgnoreCase(depType + "s")
                || depType.equalsIgnoreCase(recordType + "s");
    }

    private static String statusFor(String sourceId, java.util.Set<String> failedIds,
                                    Map<String, Boolean> alreadyInKongById) {
        if (sourceId != null && failedIds.contains(sourceId)) {
            return "FAILED";
        }
        if (Boolean.TRUE.equals(alreadyInKongById.get(sourceId))) {
            return "UNCHANGED";
        }
        return "MIGRATED";
    }
}
