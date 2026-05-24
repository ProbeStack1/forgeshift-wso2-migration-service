package com.forgeshift.wso2.migration.repository;

import com.forgeshift.wso2.migration.domain.EntityMapping;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EntityMappingRepository extends MongoRepository<EntityMapping, String> {

    Optional<EntityMapping> findByControlPlaneIdAndWso2SourceIdAndKongEntityTypeAndParentKongUuid(
            String controlPlaneId, String wso2SourceId, String kongEntityType, String parentKongUuid);

    List<EntityMapping> findByMigrationJobId(String migrationJobId);

    List<EntityMapping> findByCompanyNameAndWso2TenantAndKongEntityType(
            String companyName, String wso2Tenant, String kongEntityType);
}
