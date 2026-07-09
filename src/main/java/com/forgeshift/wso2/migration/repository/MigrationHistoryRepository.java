package com.forgeshift.wso2.migration.repository;

import com.forgeshift.wso2.migration.domain.MigrationHistoryEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MigrationHistoryRepository extends MongoRepository<MigrationHistoryEntry, String> {

    /** Rows stuck in a status past a cutoff — drives the repair sweep for orphaned DEPLOYING rows. */
    List<MigrationHistoryEntry> findByStatusAndMigratedAtBefore(String status, Instant cutoff);

    List<MigrationHistoryEntry> findByCompanyNameAndWso2TenantOrderByMigratedAtDesc(
            String companyName, String wso2Tenant);

    List<MigrationHistoryEntry> findByCompanyNameAndWso2TenantAndResourceTypeOrderByMigratedAtDesc(
            String companyName, String wso2Tenant, String resourceType);

    List<MigrationHistoryEntry> findByCompanyNameAndWso2TenantAndResourceTypeAndSourceIdIn(
            String companyName, String wso2Tenant, String resourceType, Collection<String> sourceIds);

    /** All rows written by one run — used by the deck-result callback to flip DEPLOYING rows. */
    List<MigrationHistoryEntry> findByMigrationId(String migrationId);

    /** How many of a run's rows are still (or already) in a status — drives run-level aggregation. */
    long countByMigrationIdAndStatus(String migrationId, String status);
}
