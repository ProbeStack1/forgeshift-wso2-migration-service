package com.forgeshift.wso2.migration.controller;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import com.forgeshift.wso2.migration.domain.MigrationState;
import com.forgeshift.wso2.migration.dto.MigrationJobResponse;
import com.forgeshift.wso2.migration.dto.StartMigrationRequest;
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

/**
 * Top-level migration endpoints.
 *
 * <pre>
 *   POST   /migrations                     start a migration (or dry-run)
 *   GET    /migrations/{id}                read job state
 *   GET    /migrations/{id}/report         read the final report
 *   GET    /migrations                     list jobs (filters: companyName, tenant, state)
 *   DELETE /migrations/{id}                delete the job document (entity_mappings NOT touched)
 * </pre>
 *
 * Convenience per-resource shortcuts mirror the Apigee migration service's API.
 */
@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class MigrationController {

    private final MigrationService service;
    private final MigrationJobRepository jobRepository;
    private final MigrationReportRepository reportRepository;
    private final MigrationProperties props;

    @PostMapping("/migrations")
    public ResponseEntity<MigrationJobResponse> start(@Valid @RequestBody StartMigrationRequest req) {
        applyDefaults(req);
        MigrationJob job = service.startMigration(req);
        return ResponseEntity
                .created(URI.create("/migrations/" + job.getId()))
                .body(MigrationJobResponse.from(job));
    }

    /** Shortcut: migrate ONLY apis from the given (or latest) discovery. */
    @PostMapping("/wso2/migrate/apis")
    public ResponseEntity<MigrationJobResponse> migrateApis(@Valid @RequestBody StartMigrationRequest req) {
        applyDefaults(req);
        req.setResourceTypes(List.of("apis"));
        MigrationJob job = service.startMigration(req);
        return ResponseEntity
                .created(URI.create("/migrations/" + job.getId()))
                .body(MigrationJobResponse.from(job));
    }

    /** Shortcut: migrate ONLY consumer mapping (applications + subscriptions). */
    @PostMapping("/wso2/migrate/subscriptions")
    public ResponseEntity<MigrationJobResponse> migrateSubscriptions(@Valid @RequestBody StartMigrationRequest req) {
        applyDefaults(req);
        req.setResourceTypes(List.of("applications", "subscriptions"));
        MigrationJob job = service.startMigration(req);
        return ResponseEntity
                .created(URI.create("/migrations/" + job.getId()))
                .body(MigrationJobResponse.from(job));
    }

    /** Shortcut: dry-run (no Konnect writes) over the requested resource types. */
    @PostMapping("/wso2/migrate/dry-run")
    public ResponseEntity<MigrationJobResponse> dryRun(@Valid @RequestBody StartMigrationRequest req) {
        applyDefaults(req);
        req.setDryRun(true);
        MigrationJob job = service.startMigration(req);
        return ResponseEntity
                .created(URI.create("/migrations/" + job.getId()))
                .body(MigrationJobResponse.from(job));
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
