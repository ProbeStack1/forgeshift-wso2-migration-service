package com.forgeshift.wso2.migration.controller;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import com.forgeshift.wso2.migration.domain.MigrationState;
import com.forgeshift.wso2.migration.dto.MigrationJobResponse;
import com.forgeshift.wso2.migration.dto.MigrationResultResponse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
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
        if (!jobRepository.existsById(id)) return ResponseEntity.notFound().build();
        jobRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void applyDefaults(StartMigrationRequest req) {
        if (!StringUtils.hasText(req.getCompanyName())) {
            req.setCompanyName(props.getTenant().getDefaultTenant());
        }
    }
}
