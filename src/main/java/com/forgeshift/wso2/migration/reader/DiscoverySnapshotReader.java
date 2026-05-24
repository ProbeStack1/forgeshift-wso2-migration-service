package com.forgeshift.wso2.migration.reader;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Reads WSO2 discovery snapshots from the {@code discovery_wso2_<resource>}
 * collections written by the discovery service. This is the only point of
 * coupling to the discovery service - by collection name, not HTTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoverySnapshotReader {

    private final MongoTemplate mongoTemplate;
    private final MigrationProperties props;

    /**
     * Fetch every snapshot for one resource type at the given (discoveryId,
     * revision). When revision is null, picks whichever revision the
     * discoveryId belongs to.
     */
    public List<DiscoverySnapshot> findByDiscoveryId(String companyName, String wso2Tenant,
                                                     String resourceType, String discoveryId) {
        String collection = props.getDiscoveryCollectionPrefix() + resourceType;
        Query q = Query.query(Criteria.where("companyName").is(companyName)
                .and("wso2Tenant").is(wso2Tenant)
                .and("discoveryId").is(discoveryId));
        return mongoTemplate.find(q, DiscoverySnapshot.class, collection);
    }

    /**
     * When the caller doesn't know a discoveryId, fetch the most recent
     * revision for (companyName, wso2Tenant, resourceType) and return its
     * snapshots.
     */
    public List<DiscoverySnapshot> findLatestRevision(String companyName, String wso2Tenant, String resourceType) {
        String collection = props.getDiscoveryCollectionPrefix() + resourceType;
        Query findMaxRev = Query.query(Criteria.where("companyName").is(companyName)
                        .and("wso2Tenant").is(wso2Tenant))
                .with(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "revision"))
                .limit(1);
        DiscoverySnapshot top = mongoTemplate.findOne(findMaxRev, DiscoverySnapshot.class, collection);
        if (top == null) return List.of();

        Query allAtRev = Query.query(Criteria.where("companyName").is(companyName)
                .and("wso2Tenant").is(wso2Tenant)
                .and("revision").is(top.getRevision()));
        return mongoTemplate.find(allAtRev, DiscoverySnapshot.class, collection);
    }

    /** One snapshot by composite id - used during deep drill-downs. */
    public Optional<DiscoverySnapshot> findById(String resourceType, String id) {
        String collection = props.getDiscoveryCollectionPrefix() + resourceType;
        return Optional.ofNullable(mongoTemplate.findById(id, DiscoverySnapshot.class, collection));
    }

    public long countByDiscoveryId(String companyName, String wso2Tenant,
                                    String resourceType, String discoveryId) {
        String collection = props.getDiscoveryCollectionPrefix() + resourceType;
        return mongoTemplate.count(
                Query.query(Criteria.where("companyName").is(companyName)
                        .and("wso2Tenant").is(wso2Tenant)
                        .and("discoveryId").is(discoveryId)),
                collection);
    }
}
