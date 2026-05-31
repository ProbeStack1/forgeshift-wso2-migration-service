package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.dto.Wso2MigrationHistorySummaryItem;
import com.forgeshift.wso2.migration.repository.MigrationJobRepository;
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

    public Wso2MigrationHistoryService(MigrationJobRepository jobRepository) {
        this.jobRepository = jobRepository;
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
}
