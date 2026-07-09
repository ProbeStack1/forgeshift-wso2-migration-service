package com.forgeshift.wso2.migration.controller;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import com.forgeshift.wso2.migration.domain.MigrationState;
import com.forgeshift.wso2.migration.dto.MigrationJobResponse;
import com.forgeshift.wso2.migration.dto.MigrationResultResponse;
import com.forgeshift.wso2.migration.deck.DeckResultMapper;
import com.forgeshift.wso2.migration.dto.DeckResultRequest;
import com.forgeshift.wso2.migration.dto.StartMigrationRequest;
import com.forgeshift.wso2.migration.dto.Wso2BaseMigrationRequest;
import com.forgeshift.wso2.migration.dto.Wso2MigrateApisRequest;
import com.forgeshift.wso2.migration.dto.Wso2MigrateApplicationsRequest;
import com.forgeshift.wso2.migration.dto.Wso2MigrateKeyManagersRequest;
import com.forgeshift.wso2.migration.dto.Wso2MigrateSubscriptionsRequest;
import com.forgeshift.wso2.migration.dto.Wso2MigrateThrottlingPoliciesRequest;
import com.forgeshift.wso2.migration.repository.MigrationJobRepository;
import com.forgeshift.wso2.migration.repository.MigrationReportRepository;
import com.forgeshift.wso2.migration.service.MigrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Top-level migration endpoints.
 *
 * <pre>
 *   POST   /migrations                     start a migration (sync by default; ?async=true for fire-and-forget)
 *   GET    /migrations/{id}                read job state
 *   GET    /migrations/{id}/report         read the final report
 *   GET    /migrations                     list jobs (filters: companyName, tenant, state)
 *   DELETE /migrations/{id}                delete the job document (entity_mappings NOT touched)
 * </pre>
 *
 * <p>By default every POST blocks until the migration reaches a terminal
 * state and the response carries the full {@link MigrationResultResponse}
 * (job + report). Pass {@code ?async=true} to keep the legacy fire-and-
 * forget behavior — useful for very long migrations that would exceed the
 * caller's HTTP timeout.
 *
 * <p>Convenience per-resource shortcuts mirror the Apigee migration service's API.
 */
@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class MigrationController {

    /**
     * Default upper bound on how long the synchronous POST endpoints block
     * waiting for a terminal state. Field migrations take ~30s in steady
     * state, so 10 minutes is comfortably generous while still bounding
     * the HTTP request so an unresponsive run can't hang the connection
     * forever.
     */
    private static final long SYNC_WAIT_SECONDS = 600L;

    private final MigrationService service;
    private final MigrationJobRepository jobRepository;
    private final MigrationReportRepository reportRepository;
    private final MigrationProperties props;
    private final DeckResultMapper deckResultMapper;
    private final com.forgeshift.wso2.migration.service.MigrationHistoryService migrationHistoryService;

    @PostMapping("/migrations")
    public ResponseEntity<?> start(@Valid @RequestBody StartMigrationRequest req,
                                   @RequestParam(defaultValue = "false") boolean async) {
        applyDefaults(req);
        return runMigration(req, async);
    }

    /** Shortcut: migrate ONLY apis from the given (or latest) discovery. */
    @PostMapping("/wso2/migrate/apis")
    public ResponseEntity<?> migrateApis(@Valid @RequestBody StartMigrationRequest req,
                                         @RequestParam(defaultValue = "false") boolean async) {
        applyDefaults(req);
        req.setResourceTypes(List.of("apis"));
        return runMigration(req, async);
    }

    /** Shortcut: migrate ONLY consumer mapping (applications + subscriptions). */
    @PostMapping("/wso2/migrate/subscriptions")
    public ResponseEntity<?> migrateSubscriptions(@Valid @RequestBody StartMigrationRequest req,
                                                  @RequestParam(defaultValue = "false") boolean async) {
        applyDefaults(req);
        req.setResourceTypes(List.of("applications", "subscriptions"));
        return runMigration(req, async);
    }

    /** Shortcut: dry-run (no Konnect writes) over the requested resource types. */
    @PostMapping("/wso2/migrate/dry-run")
    public ResponseEntity<?> dryRun(@Valid @RequestBody StartMigrationRequest req,
                                    @RequestParam(defaultValue = "false") boolean async) {
        applyDefaults(req);
        req.setDryRun(true);
        return runMigration(req, async);
    }

    // ───────── Apigee-style per-resource endpoints ─────────
    // Each accepts a strict DTO (companyName/wso2Tenant/userEmail/
    // requestSource required) with
    // an explicit NotEmpty list of WSO2 sourceIds. Converted internally
    // to a single-type StartMigrationRequest plus a resourceFilters map
    // so loadSnapshots only pulls the requested rows.

    @PostMapping("/konnect/wso2/apis")
    public ResponseEntity<?> migrateSelectedApis(
            @Valid @RequestBody Wso2MigrateApisRequest req,
            @RequestParam(defaultValue = "false") boolean async) {
        StartMigrationRequest internal = toInternal(req);
        internal.setResourceTypes(List.of("apis"));
        internal.setResourceFilters(Map.of("apis", req.getApis()));
        return runMigration(internal, async);
    }

    @PostMapping("/konnect/wso2/applications")
    public ResponseEntity<?> migrateSelectedApplications(
            @Valid @RequestBody Wso2MigrateApplicationsRequest req,
            @RequestParam(defaultValue = "false") boolean async) {
        StartMigrationRequest internal = toInternal(req);
        internal.setResourceTypes(List.of("applications", "subscriptions"));
        internal.setResourceFilters(Map.of("applications", req.getApplications()));
        return runMigration(internal, async);
    }

    @PostMapping("/konnect/wso2/subscriptions")
    public ResponseEntity<?> migrateSelectedSubscriptions(
            @Valid @RequestBody Wso2MigrateSubscriptionsRequest req,
            @RequestParam(defaultValue = "false") boolean async) {
        StartMigrationRequest internal = toInternal(req);
        // Subscriptions need applications too so the consumer translator
        // can match each subscription to its parent application. Load
        // every application snapshot in scope but ONLY the requested
        // subscriptions.
        internal.setResourceTypes(List.of("applications", "subscriptions"));
        internal.setResourceFilters(Map.of("subscriptions", req.getSubscriptions()));
        return runMigration(internal, async);
    }

    @PostMapping("/konnect/wso2/throttling-policies")
    public ResponseEntity<?> migrateSelectedThrottlingPolicies(
            @Valid @RequestBody Wso2MigrateThrottlingPoliciesRequest req,
            @RequestParam(defaultValue = "false") boolean async) {
        StartMigrationRequest internal = toInternal(req);
        internal.setResourceTypes(List.of("throttlingpolicies"));
        internal.setResourceFilters(Map.of("throttlingpolicies", req.getThrottlingpolicies()));
        return runMigration(internal, async);
    }

    @PostMapping("/konnect/wso2/key-managers")
    public ResponseEntity<?> migrateSelectedKeyManagers(
            @Valid @RequestBody Wso2MigrateKeyManagersRequest req,
            @RequestParam(defaultValue = "false") boolean async) {
        StartMigrationRequest internal = toInternal(req);
        internal.setResourceTypes(List.of("keymanagers"));
        internal.setResourceFilters(Map.of("keymanagers", req.getKeymanagers()));
        return runMigration(internal, async);
    }

    @PostMapping("/konnect/wso2/certificates")
    public ResponseEntity<?> migrateSelectedCertificates(
            @Valid @RequestBody com.forgeshift.wso2.migration.dto.Wso2MigrateCertificatesRequest req,
            @RequestParam(defaultValue = "false") boolean async) {
        StartMigrationRequest internal = toInternal(req);
        internal.setResourceTypes(List.of("certificates"));
        internal.setResourceFilters(Map.of("certificates", req.getCertificates()));
        return runMigration(internal, async);
    }

    @PostMapping("/konnect/wso2/api-products")
    public ResponseEntity<?> migrateSelectedApiProducts(
            @Valid @RequestBody com.forgeshift.wso2.migration.dto.Wso2MigrateApiProductsRequest req,
            @RequestParam(defaultValue = "false") boolean async) {
        StartMigrationRequest internal = toInternal(req);
        internal.setResourceTypes(List.of("apiproducts"));
        internal.setResourceFilters(Map.of("apiproducts", req.getApiproducts()));
        return runMigration(internal, async);
    }

    @PostMapping("/konnect/wso2/mediation-policies")
    public ResponseEntity<?> migrateSelectedMediationPolicies(
            @Valid @RequestBody com.forgeshift.wso2.migration.dto.Wso2MigrateMediationPoliciesRequest req,
            @RequestParam(defaultValue = "false") boolean async) {
        StartMigrationRequest internal = toInternal(req);
        internal.setResourceTypes(List.of("mediationpolicies"));
        internal.setResourceFilters(Map.of("mediationpolicies", req.getMediationpolicies()));
        return runMigration(internal, async);
    }

    /**
     * Copy every common audit/target field from the Apigee-style request
     * into the internal {@link StartMigrationRequest}. The per-resource
     * caller has already passed {@code @Valid} so all required fields
     * are present and well-formed by the time we get here.
     */
    private StartMigrationRequest toInternal(Wso2BaseMigrationRequest src) {
        StartMigrationRequest internal = new StartMigrationRequest();
        internal.setCompanyName(src.getCompanyName());
        internal.setWso2Tenant(src.getWso2Tenant());
        internal.setSourceGateway(src.getSourceGateway());
        internal.setTargetGateway(src.getTargetGateway());
        internal.setEnvClassification(src.getEnvClassification());
        internal.setKongRegion(src.getKongRegion());
        internal.setKongCtrlPlanId(src.getKongCtrlPlanId());
        internal.setKongProfileName(src.getKongProfileName());
        internal.setUserEmail(src.getUserEmail());
        internal.setRequestTransactionId(src.getRequestTransactionId());
        internal.setRequestSource(src.getRequestSource());
        internal.setDiscoveryId(src.getDiscoveryId());
        internal.setRevision(src.getRevision());
        internal.setDryRun(src.isDryRun());
        internal.setIncludeDependencies(src.isIncludeDependencies());
        internal.setAssessmentTransactionId(src.getAssessmentTransactionId());
        return internal;
    }

    /**
     * Single dispatcher for every POST entry point. {@code async=true}
     * keeps the legacy "fire and forget" semantics — returns a 201 with
     * the freshly-created PENDING job. {@code async=false} (the default)
     * blocks until the job reaches a terminal state and returns a 200
     * with the {@link MigrationResultResponse} so the caller doesn't have
     * to follow up with GET /migrations/{id} + GET /migrations/{id}/report.
     */
    private ResponseEntity<?> runMigration(StartMigrationRequest req, boolean async) {
        if (async) {
            MigrationJob job = service.startMigration(req);
            return ResponseEntity
                    .created(URI.create("/migrations/" + job.getId()))
                    .body(MigrationJobResponse.from(job));
        }
        MigrationJob job = service.startMigrationAndWait(req, SYNC_WAIT_SECONDS);
        MigrationReport report = reportRepository.findByMigrationJobId(job.getId()).orElse(null);
        return ResponseEntity.ok(MigrationResultResponse.from(job, report));
    }

    @GetMapping("/migrations/{id}")
    public ResponseEntity<MigrationJobResponse> get(@PathVariable String id) {
        return jobRepository.findById(id)
                .map(MigrationJobResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/migrations/{id}/report")
    public ResponseEntity<MigrationReport> getReport(@PathVariable String id) {
        return reportRepository.findByMigrationJobId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Download the generated decK bundle (kong.yaml + pipeline workflow + README, zipped)
     * for a migration. The same URL is returned in the migration report as bundleDownloadUrl.
     */
    @GetMapping("/migrations/{id}/bundle")
    public ResponseEntity<?> downloadBundle(@PathVariable String id) {
        MigrationReport report = reportRepository.findByMigrationJobId(id).orElse(null);
        if (report == null || !StringUtils.hasText(report.getBundlePath())) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(report.getBundlePath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] zip = Files.readAllBytes(path);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"kong-bundle-" + id + ".zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(zip);
        } catch (IOException e) {
            log.error("Failed to read bundle for migration {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body("Failed to read bundle: " + e.getMessage());
        }
    }

    /**
     * Ingest the decK apply result (Option B): the pipeline POSTs the two JSON outputs —
     * {@code deck gateway dump --format json} (kongState) and
     * {@code deck gateway apply --json-output} (applyReport) — and the service rebuilds
     * entity_mappings (with Kong ids + parents) and records failures.
     */
    @PostMapping("/migrations/{id}/deck-result")
    public ResponseEntity<?> ingestDeckResult(@PathVariable String id,
                                              @RequestBody DeckResultRequest body) {
        MigrationJob job = jobRepository.findById(id).orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        DeckResultMapper.Summary summary =
                deckResultMapper.ingest(job, body.getKongState(), body.getApplyReport());
        // Terminal guard: a late/duplicate POST (or a stale workflow still wired to this
        // endpoint while the matrix flow owns the run) must not overwrite a settled outcome.
        // Mappings above are still ingested — they're additive.
        if (job.getState() != MigrationState.DEPLOYING_TO_KONG) {
            log.info("Migration job {} deck-result ignored for state {} (already settled).",
                    job.getId(), job.getState());
            return ResponseEntity.ok(summary);
        }
        // Surface the apply failures on the migration report so they're visible via
        // GET /migrations/{id}/report (instead of only in the pipeline's apply-report.json).
        reportRepository.findByMigrationJobId(id).ifPresent(report -> {
            report.setDeckApplyErrorCount(summary.getErrors());
            report.setDeckApplyErrors(summary.getFailedDetails());
            report.setDeckApplyStderr(body.getApplyStderr());   // full reason for validate/other failures
            reportRepository.save(report);
        });
        // The pipeline's apply result is the source of truth — flip the job to its FINAL state.
        // (The migration parked it in DEPLOYING_TO_KONG after pushing the bundle; this is where
        // it becomes COMPLETED or FAILED. The pipeline must POST this even on failure — i.e. the
        // callback step runs with `if: always()`.)
        // Failed if decK reported errors[] OR the apply step exited non-zero (a hard failure such
        // as bad auth can leave applyReport empty, so the exit code is the reliable signal).
        boolean nonZeroExit = body.getApplyExitCode() != null && body.getApplyExitCode() != 0;
        boolean failed = summary.getErrors() > 0 || nonZeroExit;
        job.setState(failed ? MigrationState.FAILED : MigrationState.COMPLETED);
        job.setCompletedAt(java.time.Instant.now());
        if (failed) {
            java.util.List<String> d = summary.getFailedDetails();
            String detail = !d.isEmpty()
                    ? String.join("; ", d.subList(0, Math.min(3, d.size())))
                    : (StringUtils.hasText(body.getApplyStderr())
                        ? body.getApplyStderr().substring(0, Math.min(300, body.getApplyStderr().length()))
                        : "deck gateway apply exited " + body.getApplyExitCode());
            job.setLastError("decK apply failed: " + detail);
        } else {
            job.setLastError(null);
        }
        jobRepository.save(job);
        // Flip this run's migration_history rows from IN_PROGRESS to their final status.
        migrationHistoryService.finalizeRun(job, failed, job.getLastError());
        log.info("Migration job {} → {} via deck-result callback ({} apply error(s))",
                job.getId(), job.getState(), summary.getErrors());
        return ResponseEntity.ok(summary);
    }

    /**
     * Per-resource status callback (org spec). Each pipeline matrix leg POSTs its decK apply
     * result here — the URL carries the run's {@code migrationId} and the leg's
     * {@code trackingIds} — and the corresponding {@code migration_history} rows flip to
     * MIGRATED / FAILED with Kong's response stored. When the LAST leg reports (no IN_PROGRESS
     * rows remain), the job itself is flipped: FAILED if any resource failed, else COMPLETED.
     * The pipeline must POST even on failure (callback step runs with {@code if: always()}).
     */
    @PostMapping("/wso2/migration-status")
    public ResponseEntity<?> migrationStatus(@RequestParam("migrationId") String migrationId,
                                             @RequestParam(value = "trackingIds", required = false) String trackingIds,
                                             @RequestBody DeckResultRequest body) {
        MigrationJob job = jobRepository.findById(migrationId).orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        // Rebuild entity_mappings from this leg's dump (each successive leg's dump is a superset,
        // so per-leg ingest converges to the full mapping) and derive the leg's outcome.
        DeckResultMapper.Summary summary =
                deckResultMapper.ingest(job, body.getKongState(), body.getApplyReport());
        boolean nonZeroExit = body.getApplyExitCode() != null && body.getApplyExitCode() != 0;
        boolean failed = summary.getErrors() > 0 || nonZeroExit;
        String error = null;
        if (failed) {
            java.util.List<String> d = summary.getFailedDetails();
            error = !d.isEmpty()
                    ? String.join("; ", d.subList(0, Math.min(3, d.size())))
                    : (StringUtils.hasText(body.getApplyStderr())
                        ? body.getApplyStderr().substring(0, Math.min(300, body.getApplyStderr().length()))
                        : "deck gateway apply exited " + body.getApplyExitCode());
        }

        List<String> ids = trackingIds == null ? List.of()
                : java.util.Arrays.stream(trackingIds.split(",")).filter(StringUtils::hasText).toList();
        migrationHistoryService.reportStatus(job, ids, failed, kongResponseOf(body), error);

        if (job.getState() == MigrationState.DEPLOYING_TO_KONG) {
            // A failed UNTRACKED leg (e.g. a consumers.yaml holding only the anonymous consumer)
            // has no row to carry its failure — pin it on the job so the run can't close clean.
            if (failed && ids.isEmpty()) {
                job.setLastError("decK apply failed for an untracked bundle file: "
                        + (error != null ? error : "see the pipeline run"));
            }
            if (!migrationHistoryService.hasOpenRows(job.getId())) {
                // Run-level aggregation: the last leg to report closes the run.
                boolean anyFailed = migrationHistoryService.anyFailed(job.getId())
                        || StringUtils.hasText(job.getLastError());
                job.setState(anyFailed ? MigrationState.FAILED : MigrationState.COMPLETED);
                job.setCompletedAt(java.time.Instant.now());
                if (anyFailed && !StringUtils.hasText(job.getLastError())) {
                    job.setLastError(error != null ? "decK apply failed: " + error
                            : "one or more resources failed to apply — see migration_history");
                }
                log.info("Migration job {} → {} via migration-status callback (last leg reported)",
                        job.getId(), job.getState());
            }
            // Save EVERY callback (even mid-run) — it refreshes updatedAt, which is the timeout
            // sweeper's heartbeat. Without this a multi-leg sequential run outlives the apply
            // timeout measured from the bundle push and gets falsely TIMED_OUT mid-flight.
            jobRepository.save(job);
        }
        return ResponseEntity.ok(java.util.Map.of(
                "migrationId", migrationId,
                "trackingIds", ids,
                "status", failed ? "FAILED" : "SUCCESS",
                "jobState", job.getState().name()));
    }

    /** Compact Kong response stored per resource: apply outcome + report (service-side truncated). */
    private static String kongResponseOf(DeckResultRequest body) {
        StringBuilder sb = new StringBuilder();
        sb.append("applyExitCode=").append(body.getApplyExitCode());
        if (body.getApplyReport() != null) {
            sb.append("; applyReport=").append(body.getApplyReport());
        }
        if (StringUtils.hasText(body.getApplyStderr())) {
            sb.append("; stderr=").append(body.getApplyStderr());
        }
        return sb.toString();
    }

    @GetMapping("/migrations")
    public Page<MigrationJobResponse> list(
            @RequestParam(required = false) MigrationState state,
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) String tenant,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pr = PageRequest.of(page, size);
        Page<MigrationJob> jobs;
        if (state != null) {
            jobs = jobRepository.findByState(state, pr);
        } else if (StringUtils.hasText(companyName) && StringUtils.hasText(tenant)) {
            jobs = jobRepository.findByCompanyNameAndWso2Tenant(companyName, tenant, pr);
        } else if (StringUtils.hasText(companyName)) {
            jobs = jobRepository.findByCompanyName(companyName, pr);
        } else {
            jobs = jobRepository.findAll(pr);
        }
        return jobs.map(MigrationJobResponse::from);
    }

    @DeleteMapping("/migrations/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        MigrationJob job = jobRepository.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        // Deleting a job that's still awaiting its pipeline callback removes both things that
        // could ever finalize its migration_history rows (the callback 404s, the timeout sweeper
        // only sees existing jobs) — so fail those rows now instead of stranding them DEPLOYING.
        if (job.getState() == MigrationState.DEPLOYING_TO_KONG) {
            migrationHistoryService.finalizeRun(job, true,
                    "migration job was deleted before the pipeline result arrived");
        }
        jobRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void applyDefaults(StartMigrationRequest req) {
        if (!StringUtils.hasText(req.getCompanyName())) {
            req.setCompanyName(props.getTenant().getDefaultTenant());
        }
    }
}
