package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.client.KonnectAdminClient;
import com.forgeshift.wso2.migration.domain.EntityMapping;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import com.forgeshift.wso2.migration.repository.EntityMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which WSO2 source ids are "already in Kong" (so the migration skips them). Checks BOTH
 * the {@code entity_mappings} DB and Kong itself (by the {@code wso2-source-id} tag). Per the
 * agreed rule, <b>Kong is authoritative</b>:
 *
 * <pre>
 *   DB ✓  Kong ✓ -> skip
 *   DB ✗  Kong ✓ -> skip   (entity_mappings drifted — Kong is the truth)
 *   DB ✓  Kong ✗ -> migrate (entity_mappings is stale — Kong lost it; re-create)
 *   DB ✗  Kong ✗ -> migrate (new)
 * </pre>
 *
 * i.e. <b>skip == present in Kong</b>; the DB is consulted only to surface drift in the logs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KongPresenceChecker {

    private final EntityMappingRepository mappingRepo;
    private final KonnectAdminClient konnectAdminClient;

    /** @return the subset of {@code sourceIds} that are already present in Kong (skip these). */
    public Set<String> alreadyInKong(KongKonnectCredentials creds, String companyName, String wso2Tenant,
                                     Collection<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) return Set.of();

        Set<String> inKong = konnectAdminClient.collectMigratedSourceIds(creds);
        Set<String> inDb = mappingRepo
                .findByCompanyNameAndWso2TenantAndWso2SourceIdIn(companyName, wso2Tenant, sourceIds)
                .stream().map(EntityMapping::getWso2SourceId).collect(Collectors.toSet());

        Set<String> skip = new LinkedHashSet<>();
        for (String id : sourceIds) {
            boolean kong = inKong.contains(id);
            boolean db = inDb.contains(id);
            if (kong) {
                skip.add(id);
                if (!db) log.info("[dependency] {} present in Kong but missing from entity_mappings (drift) — skipping", id);
            } else if (db) {
                log.info("[dependency] {} in entity_mappings but NOT in Kong (stale) — will migrate", id);
            }
        }
        return skip;
    }
}
