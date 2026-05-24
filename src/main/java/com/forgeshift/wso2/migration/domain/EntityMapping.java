package com.forgeshift.wso2.migration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Persisted source-to-target id mapping. Drives idempotent upserts on
 * re-runs: before every Konnect write, KonnectAdminClient looks up the
 * (controlPlaneId, wso2SourceId, kongEntityType, parentKongUuid) tuple
 * here; if a kongUuid is present, the write is a PATCH, otherwise a POST.
 *
 * Mirrors the Apigee migration service's mapping table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("entity_mappings")
@CompoundIndexes({
        @CompoundIndex(name = "idx_lookup",
                def = "{'controlPlaneId': 1, 'wso2SourceId': 1, 'kongEntityType': 1, 'parentKongUuid': 1}",
                unique = true)
})
public class EntityMapping {

    @Id
    private String id;

    @Indexed private String companyName;
    @Indexed private String wso2Tenant;

    /** Konnect control plane this entity lives in. */
    private String controlPlaneId;

    /** Stable WSO2-side id: api uuid / application uuid / subscription uuid / etc. */
    @Indexed
    private String wso2SourceId;

    /** Slug: SERVICE / ROUTE / UPSTREAM / TARGET / CONSUMER / PLUGIN. */
    private String kongEntityType;

    /** Kong-side UUID returned by POST. */
    private String kongUuid;

    /** Parent Kong UUID when the entity is nested (route → service, plugin → route, target → upstream). */
    private String parentKongUuid;

    /** Tags written on the entity, for audit + recovery scans by tag. */
    private List<String> tags;

    /** Job that created this mapping. */
    @Indexed private String migrationJobId;

    private Instant createdAt;
    private Instant updatedAt;
}
