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

        // Stamp the mapping-id tag onto the payload so adoption-on-conflict
        // works for child entities too. The tag value MUST match
        // {tagPrefix}:{wso2SourceId} — that's what findByWso2SourceTag
        // searches for when a POST collides with an existing entity left
        // over from a prior partial run.
        injectMappingTag(payload,
                props.getTranslation().getTagPrefix() + ":" + wso2SourceId);

        // Konnect's tag validator rejects '/' and ',' (regex
        // ^(?:[\x21-\x2B\x2D\x2E\x30-\x7E\p{N}\p{L}]+...). WSO2 operation
        // targets like "/anything" become tags such as "wso2-resource:/anything"
        // — those must be sanitised here before they hit the wire. Done at
        // the boundary so any new tag source benefits without changes.
        sanitizeTags(payload);

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
                // PUT (full replacement) is what Konnect accepts on every
                // entity type. PATCH returns 405 on consumers, which broke
                // re-runs after the first successful migration.
                Map<String, Object> body = put(creds, endpoint + "/" + kongUuid, payload);
                if (body == null) {
                    return KonnectUpsertResult.builder().action("FAILED").kongUuid(kongUuid)
                            .errorMessage("PUT returned no body").build();
                }
                action = "UPDATED";
            } else {
                try {
                    Map<String, Object> body = post(creds, endpoint, payload);
                    if (body == null || body.get("id") == null) {
                        return KonnectUpsertResult.builder().action("FAILED")
                                .errorMessage("POST returned no id").build();
                    }
                    kongUuid = body.get("id").toString();
                    action = "CREATED";
                } catch (WebClientResponseException postFail) {
                    // Konnect already has an entity with this name/username from
                    // a prior run whose mapping never landed. Try two recoveries
                    // before giving up:
                    //   (1) tag lookup via the mapping-id tag we now stamp on
                    //       every payload — works for entities created by
                    //       v2+ of this service.
                    //   (2) name lookup via GET {endpoint}/{name} — falls back
                    //       to the entity's natural unique key (name/username
                    //       /target) so we can still adopt legacy entities that
                    //       were created before the mapping-id-tag fix.
                    if (isUniqueConstraintError(postFail)) {
                        String adopted = null;
                        if (StringUtils.hasText(wso2SourceId)) {
                            adopted = findByWso2SourceTag(creds, endpoint, wso2SourceId);
                            if (adopted != null) {
                                log.info("Adopted existing Kong {} via tag lookup: wso2SourceId={} kongUuid={}",
                                        type, wso2SourceId, adopted);
                            }
                        }
                        if (adopted == null) {
                            String entityName = extractEntityName(payload, type);
                            if (StringUtils.hasText(entityName)) {
                                if (type == KongEntityType.PLUGIN && StringUtils.hasText(parentUuid)) {
                                    adopted = findPluginByParentAndName(creds, parentUuid, entityName);
                                    if (adopted != null) {
                                        log.info("Adopted existing Kong PLUGIN via parent+name lookup: name={} parent={} kongUuid={}",
                                                entityName, parentUuid, adopted);
                                    }
                                } else if (type != KongEntityType.PLUGIN) {
                                    adopted = findByName(creds, endpoint, entityName);
                                    if (adopted != null) {
                                        log.info("Adopted existing Kong {} via name lookup: name={} kongUuid={}",
                                                type, entityName, adopted);
                                    }
                                }
                            }
                        }
                        if (adopted != null) {
                            kongUuid = adopted;
                            action = "UNCHANGED";
                        } else {
                            throw postFail;
                        }
                    } else {
                        throw postFail;
                    }
                }
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
     * Konnect 400 / 409 with a {@code "(type: unique) constraint failed"}
     * body — typically because a service/consumer/upstream with the same
     * name/username already exists in the control plane.
     */
    private boolean isUniqueConstraintError(WebClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code != 400 && code != 409) return false;
        String body = e.getResponseBodyAsString();
        return body != null && body.contains("(type: unique) constraint failed");
    }

    /**
     * Look up an existing Kong entity by its natural unique key (name for
     * service/route/upstream, username for consumer, target for target). The
     * Konnect endpoint accepts either id or name in the path segment, so
     * {@code GET /services/{name}} returns the entity if it exists.
     *
     * <p>Returns null when the entity isn't found or the entity type has no
     * unique name (plugins).
     */
    @SuppressWarnings("unchecked")
    private String findByName(KongKonnectCredentials creds, String endpoint, String name) {
        try {
            Map<String, Object> body = (Map<String, Object>) webClient.get()
                    .uri(endpoint + "/" + name)
                    .header("Authorization", "Bearer " + creds.getKonnectAccessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds()))
                    .block();
            if (body == null) return null;
            Object id = body.get("id");
            return id == null ? null : id.toString();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            log.warn("Name-based lookup failed for {}: {} {}", name,
                    e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("Name-based lookup failed for {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Reflectively pluck the entity's natural name field. For most types
     * this is also the unique key Kong uses; for plugins the name (e.g.
     * "jwt") is unique per-parent (service / route / consumer) — combined
     * with {@code parentUuid} that's enough to find an existing plugin.
     */
    private static String extractEntityName(Object payload, KongEntityType type) {
        if (payload == null) return null;
        String fieldName = switch (type) {
            case CONSUMER -> "username";
            case TARGET -> "target";
            default -> "name";   // service / route / upstream / plugin
        };
        try {
            java.lang.reflect.Field field = payload.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(payload);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Find an existing plugin attached to {@code parentUuid} (service /
     * route / consumer) with the given plugin name. Used when a POST hits
     * {@code unique-plugin-per-entity} — Kong already has a plugin of that
     * type bound to the parent and we want to adopt it.
     *
     * <p>Konnect doesn't accept a combined {@code parent.id} filter on
     * {@code /plugins}, so we filter by name server-side and walk the
     * result page to match parent client-side.
     */
    @SuppressWarnings("unchecked")
    private String findPluginByParentAndName(KongKonnectCredentials creds,
                                             String parentUuid, String pluginName) {
        try {
            String url = trimTrailingSlash(creds.getKonnectBaseUrl())
                    + "/v2/control-planes/" + creds.getControlPlaneId()
                    + "/core-entities" + props.getKonnect().getPluginsPath()
                    + "?name=" + pluginName;
            Map<String, Object> body = (Map<String, Object>) webClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + creds.getKonnectAccessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds()))
                    .block();
            if (body == null || !(body.get("data") instanceof List<?> items)) return null;
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> m)) continue;
                for (String parentField : List.of("service", "route", "consumer")) {
                    Object parent = m.get(parentField);
                    if (parent instanceof Map<?, ?> pm) {
                        Object pid = pm.get("id");
                        if (pid != null && parentUuid.equals(pid.toString())) {
                            Object id = m.get("id");
                            return id == null ? null : id.toString();
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Plugin parent+name lookup failed (parent={} name={}): {}",
                    parentUuid, pluginName, e.getMessage());
            return null;
        }
    }

    /**
     * Look up an existing Kong entity by the {@code wso2-source-id:<uuid>} tag
     * we stamp on everything we migrate. Returns the first match's id, or
     * null when no entity has that tag yet.
     */
    @SuppressWarnings("unchecked")
    private String findByWso2SourceTag(KongKonnectCredentials creds, String endpoint, String wso2SourceId) {
        try {
            String url = endpoint + "?tags=" + props.getTranslation().getTagPrefix() + ":" + wso2SourceId;
            Map<String, Object> body = (Map<String, Object>) webClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + creds.getKonnectAccessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds()))
                    .block();
            if (body == null) return null;
            Object data = body.get("data");
            if (!(data instanceof List<?> items) || items.isEmpty()) return null;
            Object first = items.get(0);
            if (first instanceof Map<?, ?> m) {
                Object id = ((Map<?, ?>) m).get("id");
                return id == null ? null : id.toString();
            }
            return null;
        } catch (Exception e) {
            log.warn("Tag-based lookup failed for wso2SourceId={}: {}", wso2SourceId, e.getMessage());
            return null;
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
    private Map<String, Object> put(KongKonnectCredentials creds, String fullUrl, Object body) {
        return (Map<String, Object>) webClient.put()
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

    /**
     * Read-only "what's already in Kong?" probe. Lists every migrated SERVICE and CONSUMER
     * (filtered by the {@code migrated-by} tag, paginated) and returns the set of WSO2 source
     * ids recorded in their {@code wso2-source-id:<id>} tags. The dependency-aware migration
     * uses this as the authoritative half of the skip decision (Kong present → skip).
     */
    public java.util.Set<String> collectMigratedSourceIds(KongKonnectCredentials creds) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (creds == null || !StringUtils.hasText(creds.getKonnectAccessToken())
                || !StringUtils.hasText(creds.getControlPlaneId())) {
            return ids;
        }
        for (KongEntityType type : List.of(KongEntityType.SERVICE, KongEntityType.CONSUMER)) {
            try {
                collectTaggedSourceIds(creds, entityPath(creds, type, null), ids);
            } catch (Exception e) {
                log.warn("Kong presence scan failed for {}: {}", type, e.getMessage());
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private void collectTaggedSourceIds(KongKonnectCredentials creds, String endpoint, java.util.Set<String> out) {
        String migratedBy = props.getTranslation().getMigratedByTag();
        String prefix = props.getTranslation().getTagPrefix() + ":";
        String url = endpoint + "?tags=" + migratedBy + "&size=1000";
        int guard = 0;
        while (url != null && guard++ < 50) {
            Map<String, Object> body = webClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + creds.getKonnectAccessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds()))
                    .block();
            if (body == null) return;
            if (body.get("data") instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> m && m.get("tags") instanceof List<?> tags) {
                        for (Object tag : tags) {
                            if (tag != null && tag.toString().startsWith(prefix)) {
                                out.add(tag.toString().substring(prefix.length()));
                            }
                        }
                    }
                }
            }
            // Konnect returns a non-blank `offset` while more pages remain.
            Object offset = body.get("offset");
            url = (offset instanceof String s && !s.isBlank())
                    ? endpoint + "?tags=" + migratedBy + "&size=1000&offset=" + s
                    : null;
        }
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
            case CA_CERTIFICATE -> base + props.getKonnect().getCaCertificatesPath();
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

    /**
     * Reflectively append {@code mappingTag} to the payload's {@code tags}
     * list (creating it if absent, no-op if already present). The Kong entity
     * POJOs (KongService, KongRoute, KongUpstream, KongTarget, KongConsumer,
     * KongPlugin) all expose a {@code tags} field; reflection avoids needing
     * a common Taggable interface across the domain layer.
     *
     * <p>If a payload doesn't have a {@code tags} field, this is a silent
     * no-op — entity types with no tag support just won't participate in
     * tag-based adoption (currently none exist, but this keeps the method
     * resilient).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void injectMappingTag(Object payload, String mappingTag) {
        if (payload == null || !StringUtils.hasText(mappingTag)) return;
        try {
            java.lang.reflect.Field field = payload.getClass().getDeclaredField("tags");
            field.setAccessible(true);
            Object current = field.get(payload);
            java.util.List<String> next;
            if (current instanceof java.util.List<?> list) {
                if (list.contains(mappingTag)) return;        // already present
                next = new java.util.ArrayList<>(list.size() + 1);
                for (Object t : list) if (t != null) next.add(t.toString());
            } else {
                next = new java.util.ArrayList<>(1);
            }
            next.add(mappingTag);
            field.set(payload, next);
        } catch (NoSuchFieldException nsf) {
            // Payload type has no tags field — fine, just skip.
        } catch (Exception e) {
            log.warn("Could not inject mapping tag '{}' onto {}: {}",
                    mappingTag, payload.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Konnect's tag validator regex
     * ({@code ^(?:[\x21-\x2B\x2D\x2E\x30-\x7E\p{N}\p{L}]+...}) excludes
     * {@code /}, {@code ,}, and other code points outside the printable
     * ASCII subset. WSO2 operation targets like {@code "/order/{id}"}
     * become tags such as {@code "wso2-resource:/order/{id}"} — those
     * crash the entire route POST with a {@code tags[N]} validation error.
     *
     * <p>Replace the two known offenders in every tag with safe printable
     * equivalents: {@code /} → {@code -}, {@code ,} → {@code _}. Done
     * in-place on the payload's tags list. Idempotent.
     */
    @SuppressWarnings("unchecked")
    private static void sanitizeTags(Object payload) {
        if (payload == null) return;
        try {
            java.lang.reflect.Field field = payload.getClass().getDeclaredField("tags");
            field.setAccessible(true);
            Object current = field.get(payload);
            if (!(current instanceof java.util.List<?> list) || list.isEmpty()) return;
            java.util.List<String> cleaned = new java.util.ArrayList<>(list.size());
            for (Object t : list) {
                if (t == null) continue;
                cleaned.add(t.toString().replace('/', '-').replace(',', '_'));
            }
            field.set(payload, cleaned);
        } catch (NoSuchFieldException nsf) {
            // No tags field — nothing to sanitise.
        } catch (Exception e) {
            log.warn("Could not sanitise tags on {}: {}",
                    payload.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static String trimTrailingSlash(String s) {
        if (!StringUtils.hasText(s)) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
