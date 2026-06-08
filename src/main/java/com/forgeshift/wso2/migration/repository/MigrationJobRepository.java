package com.forgeshift.wso2.migration.repository;

import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface MigrationJobRepository extends MongoRepository<MigrationJob, String> {

    /** Jobs stuck in a state past a cutoff — used to time out DEPLOYING_TO_KONG jobs with no callback. */
    List<MigrationJob> findByStateAndUpdatedAtBefore(MigrationState state, Instant cutoff);

    Page<MigrationJob> findByCompanyName(String companyName, Pageable pageable);

    Page<MigrationJob> findByCompanyNameAndWso2Tenant(String companyName, String wso2Tenant, Pageable pageable);

    List<MigrationJob> findByCompanyNameAndWso2Tenant(String companyName, String wso2Tenant);

    List<MigrationJob> findByRequestTransactionId(String requestTransactionId);

    Page<MigrationJob> findByState(MigrationState state, Pageable pageable);
}
