package com.forgeshift.wso2.migration.repository;

import com.forgeshift.wso2.migration.domain.MigrationReport;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MigrationReportRepository extends MongoRepository<MigrationReport, String> {

    Optional<MigrationReport> findByMigrationJobId(String migrationJobId);
}
