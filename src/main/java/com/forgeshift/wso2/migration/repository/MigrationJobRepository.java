package com.forgeshift.wso2.migration.repository;

import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MigrationJobRepository extends MongoRepository<MigrationJob, String> {

    Page<MigrationJob> findByCompanyName(String companyName, Pageable pageable);

    Page<MigrationJob> findByCompanyNameAndWso2Tenant(String companyName, String wso2Tenant, Pageable pageable);

    List<MigrationJob> findByCompanyNameAndWso2Tenant(String companyName, String wso2Tenant);

    Page<MigrationJob> findByState(MigrationState state, Pageable pageable);
}
