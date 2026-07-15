package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.deck.BundleResult;
import com.forgeshift.wso2.migration.domain.MigrationHistoryEntry;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationState;
import com.forgeshift.wso2.migration.repository.MigrationHistoryRepository;
import com.forgeshift.wso2.migration.repository.MigrationJobRepository;
import com.forgeshift.wso2.migration.translator.TranslatedApi;
import com.forgeshift.wso2.migration.translator.TranslatedApiProduct;
import com.forgeshift.wso2.migration.translator.TranslatedCertificate;
import com.forgeshift.wso2.migration.translator.TranslatedConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maintains the {@code migration_history} checklist — one row per migrated WSO2 resource,
 * created IN_PROGRESS the moment a migration is triggered and resolved per resource by the
 * pipeline's status callback (see {@link MigrationHistoryEntry} for the lifecycle). Every
 * write is best-effort: a history failure must never fail the migration itself.
 *
 * <p>Identity model per the org spec: one {@code migrationId} (the job id) shared by every
 * resource of a run, plus a unique {@code trackingId} per resource per run. Status flips are
 * atomic {@code updateMulti/updateFirst} calls filtered on migrationId (+ trackingId) <b>at
 * write time</b>, so a late pipeline callback can never clobber a row that a newer
 * re-migration has already taken over. {@link #repairStuckInProgress()} is the safety net for
 * rows whose flip was missed entirely — the job was deleted mid-flight, the status call never
 * arrived, or a one-shot finalize hit a transient DB error.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationHistoryService {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_MIGRATED = "MIGRATED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TYPE_APIS = "apis";
    public static final String TYPE_APPLICATIONS = "applications";
    public static final String TYPE_CERTIFICATES = "certificates";
    public static final String TYPE_APIPRODUCTS = "apiproducts";

    /** Grace before an IN_PROGRESS row with a terminal/missing job is repaired — long enough for
     *  the recording loop and the normal finalize to run first. */
    private static final long REPAIR_GRACE_MINUTES = 2;

    /** Cap on the stored Kong response body so one giant apply report can't bloat the collection. */
    private static final int KONG_RESPONSE_MAX_CHARS = 20_000;

    private final MigrationHistoryRepository repository;
    private final MigrationJobRepository jobRepository;
    private final MongoTemplate mongoTemplate;

    /**
     * Phase A — the moment the UI triggers a migration with explicit API ids: create one
     * IN_PROGRESS row per API immediately (names are filled in later, after translation).
     * Assigns the run's shared migrationId and a fresh trackingId per API.
     */
    public void startRun(MigrationJob job, Collection<String> apiSourceIds) {
        if (apiSourceIds == null) {
            return;
        }
        for (String sourceId : apiSourceIds) {
            upsertQuietly(job, null, TYPE_APIS, sourceId, null, null, STATUS_IN_PROGRESS, null);
        }
    }

    /**
     * Phase B — after translation, before the bundle is built: make sure EVERY resource of the
     * run (including dependencies discovered during translation: consumers, certs, products,
     * member APIs) has an IN_PROGRESS row with a trackingId, and return the tracking map the
     * bundle builder embeds into the pipeline. Rows already created by {@link #startRun} for
     * this run keep their trackingId and are not double-counted.
     *
     * @return {@code "<resourceType>:<sourceId>" → trackingId} for every resource of the run.
     */
    public Map<String, String> registerRun(MigrationJob job,
                                           List<TranslatedApi> apis, List<TranslatedConsumer> consumers,
                                           List<TranslatedCertificate> certificates,
                                           List<TranslatedApiProduct> products) {
        Map<String, String> tracking = new LinkedHashMap<>();
        for (TranslatedApi t : apis) {
            String tid = upsertQuietly(job, null, TYPE_APIS, t.getWso2SourceId(), t.getWso2SourceName(),
                    t.getWso2SourceVersion(), STATUS_IN_PROGRESS, null);
            putTracking(tracking, TYPE_APIS, t.getWso2SourceId(), tid);
        }
        for (TranslatedConsumer c : consumers) {
            String tid = upsertQuietly(job, null, TYPE_APPLICATIONS, c.getWso2SourceId(),
                    c.getWso2SourceName(), null, STATUS_IN_PROGRESS, null);
            putTracking(tracking, TYPE_APPLICATIONS, c.getWso2SourceId(), tid);
        }
        for (TranslatedCertificate tc : certificates) {
            String tid = upsertQuietly(job, null, TYPE_CERTIFICATES, tc.getWso2SourceId(),
                    tc.getWso2SourceName(), null, STATUS_IN_PROGRESS, null);
            putTracking(tracking, TYPE_CERTIFICATES, tc.getWso2SourceId(), tid);
        }
        for (TranslatedApiProduct p : products) {
            String tid = upsertQuietly(job, null, TYPE_APIPRODUCTS, p.getWso2SourceId(),
                    p.getWso2SourceName(), p.getWso2SourceVersion(), STATUS_IN_PROGRESS, null);
            putTracking(tracking, TYPE_APIPRODUCTS, p.getWso2SourceId(), tid);
        }
        // Reconcile Phase-A strays: a row this run created at trigger time whose resource never
        // made it into translation (id not found in the discovery snapshot) has no pipeline leg
        // and would otherwise sit IN_PROGRESS until the repair sweep wrongly marks it MIGRATED
        // from the completed job. Fail it now, explicitly. Matching is by composite _id built
        // from the TRANSLATED lists (not the tracking map), so a transiently failed upsert above
        // can never make the run disown — and fail — its own row.
        try {
            List<String> expectedIds = new ArrayList<>();
            apis.forEach(t -> expectedIds.add(MigrationHistoryEntry.compositeId(
                    job.getCompanyName(), job.getWso2Tenant(), TYPE_APIS, t.getWso2SourceId())));
            consumers.forEach(c -> expectedIds.add(MigrationHistoryEntry.compositeId(
                    job.getCompanyName(), job.getWso2Tenant(), TYPE_APPLICATIONS, c.getWso2SourceId())));
            certificates.forEach(c -> expectedIds.add(MigrationHistoryEntry.compositeId(
                    job.getCompanyName(), job.getWso2Tenant(), TYPE_CERTIFICATES, c.getWso2SourceId())));
            products.forEach(p -> expectedIds.add(MigrationHistoryEntry.compositeId(
                    job.getCompanyName(), job.getWso2Tenant(), TYPE_APIPRODUCTS, p.getWso2SourceId())));
            Query q = new Query(Criteria.where("migrationId").is(job.getId())
                    .and("status").is(STATUS_IN_PROGRESS)
                    .and("_id").nin(expectedIds));
            Update u = new Update()
                    .set("status", STATUS_FAILED)
                    .set("lastError", "resource was not found in the discovery snapshot — "
                            + "re-run discovery and migrate again");
            long n = mongoTemplate.updateMulti(q, u, MigrationHistoryEntry.class).getModifiedCount();
            if (n > 0) {
                log.warn("migration_history: {} trigger-time row(s) of job {} had no matching "
                        + "discovery snapshot — marked FAILED.", n, job.getId());
            }
        } catch (Exception e) {
            log.warn("migration_history stray reconcile failed for job {} (repair sweep may "
                    + "misreport unmatched trigger rows): {}", job.getId(), e.getMessage());
        }
        return tracking;
    }

    /**
     * After the bundle push: stamp the run's rows with where the bundle lives (repo, branch,
     * commit). When no pipeline will report back ({@code awaitingPipeline=false}) the run is
     * done — flip the still-IN_PROGRESS rows straight to MIGRATED.
     */
    public void attachRunArtifacts(MigrationJob job, BundleResult bundle, boolean awaitingPipeline) {
        try {
            Query q = new Query(Criteria.where("migrationId").is(job.getId()));
            Update u = new Update()
                    .set("gitRepo", bundle.getGitRepo())
                    .set("gitBranch", bundle.getGitBranch())
                    .set("gitCommitSha", bundle.getGitCommitSha())
                    .set("gitCommitUrl", bundle.getGitCommitUrl());
            mongoTemplate.updateMulti(q, u, MigrationHistoryEntry.class);
            if (!awaitingPipeline) {
                flipRows(job.getId(), false, null);
            }
        } catch (Exception e) {
            log.warn("migration_history artifact stamp failed for job {} (migration unaffected): {}",
                    job.getId(), e.getMessage());
        }
    }

    /**
     * Record every resource of a REST-path (direct Konnect API) run, where the per-resource
     * outcome is already known: ids in the corresponding failed-list get FAILED, the rest MIGRATED.
     */
    public void recordDirectRun(MigrationJob job,
                                List<TranslatedApi> apis, List<String> failedApiIds,
                                List<TranslatedConsumer> consumers, List<String> failedConsumerIds,
                                List<TranslatedCertificate> certificates, List<String> failedCertIds,
                                List<TranslatedApiProduct> products, List<String> failedProductIds) {
        Set<String> fa = safeSet(failedApiIds);
        Set<String> fc = safeSet(failedConsumerIds);
        Set<String> fx = safeSet(failedCertIds);
        Set<String> fp = safeSet(failedProductIds);
        for (TranslatedApi t : apis) {
            boolean failed = fa.contains(t.getWso2SourceId());
            upsertQuietly(job, null, TYPE_APIS, t.getWso2SourceId(), t.getWso2SourceName(),
                    t.getWso2SourceVersion(), failed ? STATUS_FAILED : STATUS_MIGRATED,
                    failed ? "one or more entities failed to deploy" : null);
        }
        for (TranslatedConsumer c : consumers) {
            boolean failed = fc.contains(c.getWso2SourceId());
            upsertQuietly(job, null, TYPE_APPLICATIONS, c.getWso2SourceId(), c.getWso2SourceName(), null,
                    failed ? STATUS_FAILED : STATUS_MIGRATED,
                    failed ? "one or more entities failed to deploy" : null);
        }
        for (TranslatedCertificate tc : certificates) {
            boolean failed = fx.contains(tc.getWso2SourceId());
            upsertQuietly(job, null, TYPE_CERTIFICATES, tc.getWso2SourceId(), tc.getWso2SourceName(), null,
                    failed ? STATUS_FAILED : STATUS_MIGRATED,
                    failed ? "certificate failed to deploy" : null);
        }
        for (TranslatedApiProduct p : products) {
            boolean failed = fp.contains(p.getWso2SourceId());
            upsertQuietly(job, null, TYPE_APIPRODUCTS, p.getWso2SourceId(), p.getWso2SourceName(),
                    p.getWso2SourceVersion(), failed ? STATUS_FAILED : STATUS_MIGRATED,
                    failed ? "one or more product routes failed" : null);
        }
    }

    /**
     * The pipeline's per-resource status callback: flip each reported trackingId of this run.
     * Atomic per row, filtered on migrationId + trackingId at write time. FAILED is sticky
     * within a run: a success report can only flip a row that is still IN_PROGRESS, while a
     * failure also overrides an earlier optimistic MIGRATED (a product spanning several
     * pipeline legs fails if ANY of its legs failed).
     */
    public void reportStatus(MigrationJob job, Collection<String> trackingIds, boolean failed,
                             String kongResponse, String error) {
        String response = truncate(kongResponse);
        for (String trackingId : trackingIds) {
            if (!StringUtils.hasText(trackingId)) {
                continue;
            }
            try {
                Criteria c = Criteria.where("migrationId").is(job.getId())
                        .and("trackingId").is(trackingId.trim())
                        .and("status").in(failed
                                ? List.of(STATUS_IN_PROGRESS, STATUS_MIGRATED)
                                : List.of(STATUS_IN_PROGRESS));
                Update u = new Update()
                        .set("status", failed ? STATUS_FAILED : STATUS_MIGRATED)
                        .set("lastError", failed ? error : null)
                        .set("kongResponse", response)
                        .set("statusReportedAt", Instant.now());
                mongoTemplate.updateFirst(new Query(c), u, MigrationHistoryEntry.class);
            } catch (Exception e) {
                log.warn("migration_history status update failed for tracking {} of job {} "
                        + "(repair sweep will retry): {}", trackingId, job.getId(), e.getMessage());
            }
        }
    }

    /**
     * Fail every IN_PROGRESS row of the run whose trackingId is carried by NO pipeline leg —
     * a translated resource that produced no deployable decK content (empty-PEM certificate,
     * product with all member APIs missing, API without a service). Such a row can never be
     * reported by the pipeline; left open it blocks run aggregation until a false TIMED_OUT.
     */
    public void failUncarried(MigrationJob job, Collection<String> carriedTrackingIds) {
        try {
            Query q = new Query(Criteria.where("migrationId").is(job.getId())
                    .and("status").is(STATUS_IN_PROGRESS)
                    .and("trackingId").nin(carriedTrackingIds));
            Update u = new Update()
                    .set("status", STATUS_FAILED)
                    .set("lastError", "resource produced no deployable decK content — "
                            + "see the migration report's warnings");
            long n = mongoTemplate.updateMulti(q, u, MigrationHistoryEntry.class).getModifiedCount();
            if (n > 0) {
                log.warn("migration_history: {} row(s) of job {} are carried by no pipeline leg — "
                        + "marked FAILED.", n, job.getId());
            }
        } catch (Exception e) {
            log.warn("migration_history uncarried reconcile failed for job {}: {}",
                    job.getId(), e.getMessage());
        }
    }

    /** True while any of the run's rows still awaits a pipeline report. */
    public boolean hasOpenRows(String migrationId) {
        return repository.countByMigrationIdAndStatus(migrationId, STATUS_IN_PROGRESS) > 0;
    }

    /** True when at least one of the run's rows ended FAILED. */
    public boolean anyFailed(String migrationId) {
        return repository.countByMigrationIdAndStatus(migrationId, STATUS_FAILED) > 0;
    }

    /**
     * Run-level fallback flip (job deleted / timed out / crashed before its per-resource
     * reports): every row this run still owns IN_PROGRESS goes to the final status. Atomic
     * {@code updateMulti} — rows a newer run has re-claimed (different migrationId) or
     * already-final rows are untouched.
     */
    public void finalizeRun(MigrationJob job, boolean failed, String error) {
        flipRows(job.getId(), failed, error);
    }

    /**
     * Safety net, run from the scheduled sweeper: any row stuck IN_PROGRESS past the grace
     * period whose owning job is already terminal (or deleted) gets flipped from the job's final
     * state. Recovers the flips missed when (a) a pipeline status call never arrived, (b) the
     * job was deleted while awaiting its callback, or (c) a one-shot finalize hit a transient
     * DB error. Rows whose job is still in flight are left alone.
     */
    public void repairStuckInProgress() {
        List<MigrationHistoryEntry> stuck;
        try {
            stuck = repository.findByStatusAndMigratedAtBefore(STATUS_IN_PROGRESS,
                    Instant.now().minus(REPAIR_GRACE_MINUTES, ChronoUnit.MINUTES));
        } catch (Exception e) {
            log.warn("migration_history repair sweep query failed: {}", e.getMessage());
            return;
        }
        if (stuck.isEmpty()) {
            return;
        }
        Map<String, List<MigrationHistoryEntry>> byRun = stuck.stream()
                .filter(r -> StringUtils.hasText(r.getMigrationId()))
                .collect(Collectors.groupingBy(MigrationHistoryEntry::getMigrationId));
        for (Map.Entry<String, List<MigrationHistoryEntry>> e : byRun.entrySet()) {
            MigrationJob job = jobRepository.findById(e.getKey()).orElse(null);
            if (job == null) {
                // Job deleted while its rows were IN_PROGRESS — no callback can ever arrive.
                flipRows(e.getKey(), true,
                        "migration job was deleted before the pipeline result arrived");
                continue;
            }
            MigrationState s = job.getState();
            if (s == MigrationState.COMPLETED) {
                flipRows(e.getKey(), false, null);
            } else if (s == MigrationState.FAILED || s == MigrationState.TIMED_OUT
                    || s == MigrationState.CANCELLED) {
                flipRows(e.getKey(), true, job.getLastError() != null
                        ? job.getLastError() : "migration ended as " + s);
            }
            // else: still in flight — leave for the status callback (or the next sweep).
        }
    }

    private void flipRows(String migrationId, boolean failed, String error) {
        try {
            Query q = new Query(Criteria.where("migrationId").is(migrationId)
                    .and("status").is(STATUS_IN_PROGRESS));
            Update u = new Update()
                    .set("status", failed ? STATUS_FAILED : STATUS_MIGRATED)
                    .set("lastError", failed ? error : null);
            long n = mongoTemplate.updateMulti(q, u, MigrationHistoryEntry.class).getModifiedCount();
            if (n > 0) {
                log.info("migration_history: {} IN_PROGRESS row(s) of job {} flipped to {}",
                        n, migrationId, failed ? STATUS_FAILED : STATUS_MIGRATED);
            }
        } catch (Exception e) {
            log.warn("migration_history flip failed for job {} (repair sweep retries): {}",
                    migrationId, e.getMessage());
        }
    }

    /** List the checklist for a tenant, newest first; resourceType optional. */
    public List<MigrationHistoryEntry> list(String companyName, String wso2Tenant, String resourceType) {
        if (StringUtils.hasText(resourceType)) {
            return repository.findByCompanyNameAndWso2TenantAndResourceTypeOrderByMigratedAtDesc(
                    companyName, wso2Tenant, resourceType);
        }
        return repository.findByCompanyNameAndWso2TenantOrderByMigratedAtDesc(companyName, wso2Tenant);
    }

    /**
     * For each requested sourceId: its history row, or a status-less placeholder when never
     * migrated. Null/blank ids are dropped; the rest keep their request order so the frontend
     * can zip results back onto its selection.
     */
    public List<Map.Entry<String, MigrationHistoryEntry>> check(String companyName, String wso2Tenant,
                                                                String resourceType,
                                                                Collection<String> sourceIds) {
        List<String> ids = sourceIds.stream().filter(StringUtils::hasText).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, MigrationHistoryEntry> byId = repository
                .findByCompanyNameAndWso2TenantAndResourceTypeAndSourceIdIn(
                        companyName, wso2Tenant, resourceType, ids)
                .stream()
                .collect(Collectors.toMap(MigrationHistoryEntry::getSourceId, e -> e, (a, b) -> a));
        List<Map.Entry<String, MigrationHistoryEntry>> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(Map.entry(id, byId.getOrDefault(id,
                    MigrationHistoryEntry.builder().sourceId(id).build())));
        }
        return out;
    }

    // ---------------- internals ----------------

    private static void putTracking(Map<String, String> tracking, String type, String sourceId,
                                    String trackingId) {
        if (StringUtils.hasText(sourceId) && StringUtils.hasText(trackingId)) {
            tracking.put(type + ":" + sourceId, trackingId);
        }
    }

    /**
     * One row's upsert, isolated: a single bad row must not abort the rest of the run's
     * recording. Returns the row's trackingId for this run (null when the write failed).
     */
    private String upsertQuietly(MigrationJob job, BundleResult bundle, String resourceType,
                                 String sourceId, String sourceName, String sourceVersion,
                                 String status, String error) {
        try {
            return upsert(job, bundle, resourceType, sourceId, sourceName, sourceVersion, status, error);
        } catch (Exception e) {
            log.warn("migration_history upsert failed for {} {} of job {} (migration unaffected): {}",
                    resourceType, sourceId, job.getId(), e.getMessage());
            return null;
        }
    }

    private String upsert(MigrationJob job, BundleResult bundle, String resourceType,
                          String sourceId, String sourceName, String sourceVersion,
                          String status, String error) {
        if (!StringUtils.hasText(sourceId)) {
            return null;
        }
        String id = MigrationHistoryEntry.compositeId(
                job.getCompanyName(), job.getWso2Tenant(), resourceType, sourceId);
        Instant now = Instant.now();
        MigrationHistoryEntry row = repository.findById(id).orElseGet(() ->
                MigrationHistoryEntry.builder()
                        .id(id)
                        .companyName(job.getCompanyName())
                        .wso2Tenant(job.getWso2Tenant())
                        .resourceType(resourceType)
                        .sourceId(sourceId)
                        .firstMigratedAt(now)
                        .timesMigrated(0)
                        .build());
        // Same run touching the row again (Phase A then Phase B) keeps its trackingId and
        // doesn't double-count; a NEW run takes the row over: fresh trackingId, counter +1.
        boolean sameRun = job.getId() != null && job.getId().equals(row.getMigrationId());
        if (!sameRun) {
            row.setTrackingId(UUID.randomUUID().toString());
            row.setTimesMigrated(row.getTimesMigrated() + 1);
            row.setKongResponse(null);
            row.setStatusReportedAt(null);
        } else if (row.getTrackingId() == null) {
            row.setTrackingId(UUID.randomUUID().toString());
        }
        if (StringUtils.hasText(sourceName)) {
            row.setSourceName(sourceName);
        }
        if (StringUtils.hasText(sourceVersion)) {
            row.setSourceVersion(sourceVersion);
        }
        row.setStatus(status);
        row.setMigrationId(job.getId());
        row.setRequestTransactionId(job.getRequestTransactionId());
        row.setControlPlaneId(job.getControlPlaneId());
        // Which discovery revision this run migrated FROM — drives the UI's re-assessment rule.
        // Null at trigger time (Phase A, before loadSnapshots resolves it); filled by Phase B.
        if (job.getSourceRevision() != null) {
            row.setSourceRevision(job.getSourceRevision());
        }
        if (StringUtils.hasText(job.getSourceDiscoveryId())) {
            row.setSourceDiscoveryId(job.getSourceDiscoveryId());
        }
        if (bundle != null) {
            row.setGitRepo(bundle.getGitRepo());
            row.setGitBranch(bundle.getGitBranch());
            row.setGitCommitSha(bundle.getGitCommitSha());
            row.setGitCommitUrl(bundle.getGitCommitUrl());
        }
        row.setMigratedBy(job.getCreatedBy());
        row.setMigratedAt(now);
        row.setLastError(error);
        repository.save(row);
        return row.getTrackingId();
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= KONG_RESPONSE_MAX_CHARS) {
            return s;
        }
        return s.substring(0, KONG_RESPONSE_MAX_CHARS) + "…(truncated)";
    }

    private static Set<String> safeSet(List<String> ids) {
        if (ids == null) {
            return Set.of();
        }
        return ids.stream().filter(StringUtils::hasText).collect(Collectors.toSet());
    }
}
