package com.forgeshift.wso2.migration.client;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.EntityMapping;
import com.forgeshift.wso2.migration.domain.kong.KongEntityType;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import com.forgeshift.wso2.migration.repository.EntityMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client for the Konnect Admin API plus the in-house idempotency layer.
 *
 * <p>Every write goes through {@link #upsert(...)}: the client looks up the
 * source-to-Kong mapping in {@code entity_mappings}; if a Kong UUID is
 * recorded, it does a PATCH; otherwise it does a POST and persists the
 * returned UUID for next time. This gives us idempotent re-runs without
 * relying on decK.
 *
 * <p>Failed entity creations never cache a UUID, so a retry will POST again.
 */
@Slf4j
@Component
public class KonnectAdminClient {

    private final WebClient webClient;
    private final MigrationProperties props;
    private final EntityMappingRepository mappingRepo;

    public KonnectAdminClient(@Qualifier("konnectWebClient") WebClient webClient,
                              MigrationProperties props,
                              EntityMappingRepository mappingRepo) {
        this.webClient = webClient;
        this.props = props;
        this.mappingRepo = mappingRepo;
    }

    /**
     * Idempotent upsert for any Kong entity.
     *
     * @param creds          resolved Konnect connection
     * @param type           entity type (drives the path)
     * @param parentUuid     null for top-level entities; the parent's Kong UUID for nested ones
     *                       (e.g. target → upstreamId; plugin scoped to a route → routeId)
     * @param companyName    audit metadata
     * @param wso2Tenant     audit metadata
     * @param migrationJobId audit metadata
     * @param wso2SourceId   the stable WSO2 id this Kong entity represents
     * @param tags           tags to stamp on the entity (includes the wso2-source-id tag)
     * @param payload        the Jackson-serialisable Kong entity body
     */
    public KonnectUpsertResult upsert(KongKonnectCredentials creds,
                                      KongEntityType type,
                                      String parentUuid,
                                      String companyName, String wso2Tenant,
                                      String migrationJobId, String wso2SourceId,
                                      List<String> tags,
                                      Object payload) {
        if (creds == null || !StringUtils.hasText(creds.getKonnectAccessToken())
                || !StringUtils.hasText(creds.getControlPlaneId())) {
            return KonnectUpsertResult.builder()
                    .action("FAILED")
                    .errorMessage("Konnect credentials incomplete (need accessToken + controlPlaneId).")
                    .build();
        }

        // Look up an existing mapping by (controlPlaneId, wso2SourceId, type, parent).
        Optional<EntityMapping> existing = mappingRepo
                .findByControlPlaneIdAndWso2SourceIdAndKongEntityTypeAndParentKongUuid(
                        creds.getControlPlaneId(), wso2SourceId, type.name(), nullSafe(parentUuid));

        String endpoint = entityPath(creds, type, parentUuid);

        try {
            String kongUuid;
            String action;
            if (existing.isPresent() && StringUtils.hasText(existing.get().getKongUuid())) {
                kongUuid = existing.get().getKongUuid();
                Map<String, Object> body = patch(creds, endpoint + "/" + kongUuid, payload);
                if (body == null) {
                    return KonnectUpsertResult.builder().action("FAILED").kongUuid(kongUuid)
                            .errorMessage("PATCH returned no body").build();
                }
                action = "UPDATED";
            } else {
                Map<String, Object> body = post(creds, endpoint, payload);
                if (body == null || body.get("id") == null) {
                    return KonnectUpsertResult.builder().action("FAILED")
                            .errorMessage("POST returned no id").build();
                }
                kongUuid = body.get("id").toString();
                action = "CREATED";
            }

            persistMapping(creds, type, parentUuid, companyName, wso2Tenant,
                    migrationJobId, wso2SourceId, tags, kongUuid, existing.orElse(null));

            return KonnectUpsertResult.builder().action(action).kongUuid(kongUuid).build();

        } catch (WebClientResponseException e) {
            return KonnectUpsertResult.builder().action("FAILED")
                    .errorMessage("Konnect " + e.getStatusCode() + ": " + e.getResponseBodyAsString())
                    .build();
        } catch (Exception e) {
            return KonnectUpsertResult.builder().action("FAILED")
                    .errorMessage(e.getMessage()).build();
        }
    }

    /**
     * Dry-run analog: tells you whether the upsert would CREATE / UPDATE /
     * (probably) UNCHANGED, without doing any writes.
     */
    public String diff(KongKonnectCredentials creds, KongEntityType type, String parentUuid,
                       String wso2SourceId) {
        Optional<EntityMapping> existing = mappingRepo
                .findByControlPlaneIdAndWso2SourceIdAndKongEntityTypeAndParentKongUuid(
                        creds.getControlPlaneId(), wso2SourceId, type.name(), nullSafe(parentUuid));
        if (existing.isEmpty() || !StringUtils.hasText(existing.get().getKongUuid())) return "CREATE";
        // Could fetch the entity here and field-diff against the planned payload.
        // MVP: assume UPDATE; the orchestrator surfaces "unchanged" stats from
        // the real run via the UPSERT action == UPDATED + 200 OK with no diff.
        return "UPDATE";
    }

    // ---------------- HTTP plumbing ----------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(KongKonnectCredentials creds, String fullUrl, Object body) {
        return (Map<String, Object>) webClient.post()
                .uri(fullUrl)
                .header("Authorization", "Bearer " + creds.getKonnectAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds()))
                .block();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> patch(KongKonnectCredentials creds, String fullUrl, Object body) {
        return (Map<String, Object>) webClient.patch()
                .uri(fullUrl)
                .header("Authorization", "Bearer " + creds.getKonnectAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds()))
                .block();
    }

    private String entityPath(KongKonnectCredentials creds, KongEntityType type, String parentUuid) {
        String base = trimTrailingSlash(creds.getKonnectBaseUrl())
                + "/v2/control-planes/" + creds.getControlPlaneId() + "/core-entities";
        return switch (type) {
            case SERVICE   -> base + props.getKonnect().getServicesPath();
            case ROUTE     -> base + props.getKonnect().getRoutesPath();
            case UPSTREAM  -> base + props.getKonnect().getUpstreamsPath();
            case CONSUMER  -> base + props.getKonnect().getConsumersPath();
            case PLUGIN    -> base + props.getKonnect().getPluginsPath();
            case TARGET    -> {
                if (!StringUtils.hasText(parentUuid)) {
                    throw new IllegalArgumentException("TARGET requires parentUuid (the upstream id)");
                }
                yield base + props.getKonnect().getTargetsPath().replace("{upstreamId}", parentUuid);
            }
        };
    }

    private void persistMapping(KongKonnectCredentials creds, KongEntityType type, String parentUuid,
                                String companyName, String wso2Tenant,
                                String migrationJobId, String wso2SourceId,
                                List<String> tags, String kongUuid, EntityMapping existing) {
        Instant now = Instant.now();
        EntityMapping row = existing != null ? existing : EntityMapping.builder()
                .id(compositeId(creds.getControlPlaneId(), wso2SourceId, type, parentUuid))
                .companyName(companyName)
                .wso2Tenant(wso2Tenant)
                .controlPlaneId(creds.getControlPlaneId())
                .wso2SourceId(wso2SourceId)
                .kongEntityType(type.name())
                .parentKongUuid(nullSafe(parentUuid))
                .migrationJobId(migrationJobId)
                .createdAt(now)
                .build();
        row.setKongUuid(kongUuid);
        row.setTags(tags);
        row.setMigrationJobId(migrationJobId);
        row.setUpdatedAt(now);
        mappingRepo.save(row);
    }

    private static String compositeId(String cpId, String sourceId, KongEntityType type, String parentUuid) {
        return cpId + "|" + sourceId + "|" + type + "|" + nullSafe(parentUuid);
    }

    private static String nullSafe(String s) { return StringUtils.hasText(s) ? s : "_"; }

    private static String trimTrailingSlash(String s) {
        if (!StringUtils.hasText(s)) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
