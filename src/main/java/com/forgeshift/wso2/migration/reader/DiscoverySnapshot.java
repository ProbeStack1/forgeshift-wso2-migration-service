package com.forgeshift.wso2.migration.reader;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.Map;

/**
 * Read-only projection of one document in any {@code discovery_wso2_*}
 * collection written by the discovery service. We map only the fields we
 * need; Mongo allows extra fields on the document side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoverySnapshot {

    @Id
    private String id;

    private String companyName;
    private String wso2Tenant;
    private String discoveryId;
    private Integer revision;
    private String resourceType;
    private String sourceId;
    private String sourceName;
    private String sourceVersion;
    private Map<String, Object> payload;
    private Map<String, String> metadata;
    private Instant snapshotAt;
}
