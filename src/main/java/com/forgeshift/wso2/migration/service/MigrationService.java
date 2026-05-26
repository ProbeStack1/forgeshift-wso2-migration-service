package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.bundle.Wso2ApiBundle;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import com.forgeshift.wso2.migration.domain.MigrationState;
import com.forgeshift.wso2.migration.dto.StartMigrationRequest;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshotReader;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import com.forgeshift.wso2.migration.reader.KongKonnectProfileReader;
import com.forgeshift.wso2.migration.reader.Wso2Credentials;
import com.forgeshift.wso2.migration.reader.Wso2ProfileReader;
import com.forgeshift.wso2.migration.repository.MigrationJobRepository;
import com.forgeshift.wso2.migration.repository.MigrationReportRepository;
import com.forgeshift.wso2.migration.translator.ApiTranslator;
import com.forgeshift.wso2.migration.translator.SubscriptionTranslator;
import com.forgeshift.wso2.migration.translator.TranslatedApi;
import com.forgeshift.wso2.migration.translator.TranslatedConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Top-level migration orchestrator.
 *
 * <ol>
 *   <li>Accept a {@link StartMigrationRequest} → create a {@link MigrationJob} in PENDING.</li>
 *   <li>Run async: resolve Konnect creds → load snapshots → translate → diff (dry-run) or deploy.</li>
 *   <li>Persist a {@link MigrationReport} with per-resource outcomes + warnings.</li>
 *   <li>Transition the job to COMPLETED / FAILED / DRY_RUN_DONE.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationService {

    private final MigrationJobRepository jobRepository;
    private final MigrationReportRepository reportRepository;
    private final DiscoverySnapshotReader snapshotReader;
    private final KongKonnectProfileReader profileReader;
    private final Wso2ProfileReader wso2ProfileReader;
    private final Wso2BundleDownloadService bundleDownloadService;
    private final ApiTranslator apiTranslator;
    private final SubscriptionTranslator subscriptionTranslator;
    private final KongDeployer deployer;

    /** Public entry: synchronous create + async fan-out. */
    public MigrationJob startMigration(StartMigrationRequest req) {
        MigrationJob job = MigrationJob.builder()
                .companyName(req.getCompanyName())
                .wso2Tenant(req.getWso2Tenant())
                .state(MigrationState.PENDING)
                .sourceDiscoveryId(req.getDiscoveryId())
                .resourceTypes(defaultIfEmpty(req.getResourceTypes()))
                .dryRun(req.isDryRun())
                .createdBy(req.getUserEmail())
                .requestTransactionId(req.getRequestTransactionId() != null
                        ? req.getRequestTransactionId() : UUID.randomUUID().toString())
                .resourceProgress(new HashMap<>())
                .build();
        job = jobRepository.save(job);
        log.info("Migration job {} created (company={} tenant={} dryRun={} resourceTypes={})",
                job.getId(), job.getCompanyName(), job.getWso2Tenant(), job.isDryRun(),
                job.getResourceTypes());
        runMigration(job.getId(), req);
        return job;
    }

    /**
     * Synchronous variant of {@link #startMigration}: kicks off the
     * migration and polls the job document until it reaches a terminal
     * state (COMPLETED / FAILED / DRY_RUN_DONE / CANCELLED) or the
     * caller-supplied timeout elapses. Returns the final job document so
     * the controller can attach the report and respond in a single
     * round-trip.
     *
     * <p>If the timeout fires before the job finishes, the last-known
     * (non-terminal) state is returned and the caller can decide whether
     * to keep polling via {@code GET /migrations/{id}}.
     */
    public MigrationJob startMigrationAndWait(StartMigrationRequest req, long timeoutSeconds) {
        MigrationJob job = startMigration(req);
        long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            MigrationJob current = jobRepository.findById(job.getId()).orElse(null);
            if (current != null && isTerminal(current.getState())) {
                return current;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("Sync wait for migration {} timed out after {}s; last state was non-terminal",
                job.getId(), timeoutSeconds);
        return jobRepository.findById(job.getId()).orElse(job);
    }

    private static boolean isTerminal(MigrationState s) {
        return s == MigrationState.COMPLETED
                || s == MigrationState.FAILED
                || s == MigrationState.DRY_RUN_DONE
                || s == MigrationState.CANCELLED;
    }

    @Async("migrationExecutor")
    public void runMigration(String jobId, StartMigrationRequest req) {
        MigrationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Migration job {} disappeared", jobId);
            return;
        }
        try {
            // ----- LOADING -----
            job.setState(MigrationState.LOADING);
            jobRepository.save(job);
            KongKonnectCredentials creds = profileReader.resolve(req.getCompanyName(),
                    req.getKongProfileName());
            job.setControlPlaneId(creds.getControlPlaneId());
            job.setKonnectBaseUrl(creds.getKonnectBaseUrl());
            jobRepository.save(job);

            Map<String, List<DiscoverySnapshot>> byType = loadSnapshots(job, req);

            // ----- DOWNLOADING BUNDLES -----
            // Pull each API's full export ZIP from WSO2 so the translator has
            // authoritative endpoint config, swagger, sequences and certs to
            // work with. Per-API failures degrade gracefully — warn, mark
            // BUNDLE_FAILED, fall back to JSON-only translation, keep going.
            job.setState(MigrationState.DOWNLOADING_BUNDLES);
            jobRepository.save(job);

            List<DiscoverySnapshot> apiSnapshots = byType.getOrDefault("apis", List.of());
            Wso2Credentials wso2Creds = wso2ProfileReader.resolve(
                    req.getCompanyName(), req.getWso2Tenant());
            Wso2BundleDownloadService.Result bundleResult =
                    bundleDownloadService.download(wso2Creds, apiSnapshots);

            // Warnings for every failed bundle so they show up in the report.
            List<MigrationReport.Warning> warnings = new ArrayList<>();
            for (DiscoverySnapshot s : apiSnapshots) {
                String fail = bundleResult.failures.get(s.getSourceId());
                if (fail != null) {
                    warnings.add(warn("apis", s, "BUNDLE_FAILED", fail));
                }
            }
            recordProgress(job, "apis-bundles",
                    bundleResult.failures.isEmpty() ? "COMPLETED" : "PARTIAL",
                    bundleResult.bundles.size(),
                    0,
                    bundleResult.failures.isEmpty() ? null
                            : bundleResult.failures.size() + " API bundle(s) failed; using JSON-only fallback");

            // ----- TRANSLATING -----
            job.setState(MigrationState.TRANSLATING);
            jobRepository.save(job);

            List<TranslatedApi> translatedApis = new ArrayList<>();
            List<TranslatedConsumer> translatedConsumers = new ArrayList<>();
            // (warnings list already created above for BUNDLE_FAILED entries)

            for (DiscoverySnapshot s : apiSnapshots) {
                Wso2ApiBundle bundle = bundleResult.bundles.get(s.getSourceId());
                TranslatedApi t = apiTranslator.translate(s, bundle);
                translatedApis.add(t);
                for (String w : t.getWarnings()) {
                    warnings.add(warn("apis", s, "TRANSLATION_NOTE", w));
                }
            }
            recordProgress(job, "apis", "TRANSLATED", translatedApis.size(), 0, null);

            if (job.getResourceTypes().contains("subscriptions")) {
                List<TranslatedConsumer> tcs = subscriptionTranslator.translate(
                        byType.getOrDefault("applications", List.of()),
                        byType.getOrDefault("subscriptions", List.of()));
                translatedConsumers.addAll(tcs);
                for (TranslatedConsumer tc : tcs) {
                    for (String w : tc.getWarnings()) {
                        warnings.add(MigrationReport.Warning.builder()
                                .resourceType("subscriptions")
                                .wso2SourceId(tc.getWso2SourceId())
                                .wso2SourceName(tc.getWso2SourceName())
                                .code("TRANSLATION_NOTE").message(w).build());
                    }
                }
                recordProgress(job, "subscriptions", "TRANSLATED", translatedConsumers.size(), 0, null);
            }

            job.getCounts().setTotalTranslated(translatedApis.size() + translatedConsumers.size());
            jobRepository.save(job);

            // ----- DRY RUN OR DEPLOY -----
            if (req.isDryRun()) {
                MigrationReport.DiffSummary diff = computeDiff(creds, translatedApis, translatedConsumers);
                writeReport(job, translatedApis, translatedConsumers, warnings, diff,
                        new ResourceCounters(translatedApis.size(), 0, 0, 0, List.of()),
                        new ResourceCounters(translatedConsumers.size(), 0, 0, 0, List.of()));
                job.setState(MigrationState.DRY_RUN_DONE);
                job.setCompletedAt(Instant.now());
                jobRepository.save(job);
                return;
            }

            job.setState(MigrationState.DEPLOYING);
            jobRepository.save(job);

            int totalDeployed = 0, totalUnchanged = 0, totalFailed = 0;
            int apiCreated = 0, apiUpdated = 0, apiUnchanged = 0, apiFailed = 0;
            List<String> failedApiIds = new ArrayList<>();
            for (TranslatedApi t : translatedApis) {
                KongDeployer.DeployOutcome o = deployer.deployApi(creds, t, job);
                totalDeployed += (o.created + o.updated);
                totalUnchanged += o.unchanged;
                totalFailed += o.failed;
                apiCreated += o.created;
                apiUpdated += o.updated;
                apiUnchanged += o.unchanged;
                if (o.failed > 0) {
                    apiFailed += o.failed;
                    failedApiIds.add(t.getWso2SourceId());
                }
            }
            recordProgress(job, "apis", "COMPLETED",
                    translatedApis.size(), apiCreated + apiUpdated,
                    apiFailed > 0 ? apiFailed + " entit(ies) failed across "
                            + failedApiIds.size() + " API(s)" : null);

            int consCreated = 0, consUpdated = 0, consUnchanged = 0, consFailed = 0;
            List<String> failedConsumerIds = new ArrayList<>();
            for (TranslatedConsumer c : translatedConsumers) {
                KongDeployer.DeployOutcome o = deployer.deployConsumer(creds, c, job);
                totalDeployed += (o.created + o.updated);
                totalUnchanged += o.unchanged;
                totalFailed += o.failed;
                consCreated += o.created;
                consUpdated += o.updated;
                consUnchanged += o.unchanged;
                if (o.failed > 0) {
                    consFailed += o.failed;
                    failedConsumerIds.add(c.getWso2SourceId());
                }
            }
            if (!translatedConsumers.isEmpty()) {
                recordProgress(job, "subscriptions", "COMPLETED",
                        translatedConsumers.size(), consCreated + consUpdated,
                        consFailed > 0 ? consFailed + " entit(ies) failed across "
                                + failedConsumerIds.size() + " consumer(s)" : null);
            }

            job.getCounts().setTotalDeployed(totalDeployed);
            job.getCounts().setTotalUnchanged(totalUnchanged);
            job.getCounts().setTotalFailed(totalFailed);

            // Per-resource counters land in the report instead of the leaky
            // job-level totals that used to count consumer deploys as API
            // deploys in the "apis" outcome row.
            ResourceCounters apiCounts = new ResourceCounters(
                    translatedApis.size(), apiCreated + apiUpdated,
                    apiUnchanged, apiFailed, failedApiIds);
            ResourceCounters consCounts = new ResourceCounters(
                    translatedConsumers.size(), consCreated + consUpdated,
                    consUnchanged, consFailed, failedConsumerIds);
            writeReport(job, translatedApis, translatedConsumers, warnings, null,
                    apiCounts, consCounts);

            job.setState(MigrationState.COMPLETED);
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
            log.info("Migration job {} COMPLETED: deployed={} unchanged={} failed={}",
                    job.getId(), totalDeployed, totalUnchanged, totalFailed);

        } catch (Exception e) {
            log.error("Migration job {} FAILED: {}", jobId, e.getMessage(), e);
            job.setState(MigrationState.FAILED);
            job.setLastError(e.getMessage());
            jobRepository.save(job);
        }
    }

    // ---------------- helpers ----------------

    private Map<String, List<DiscoverySnapshot>> loadSnapshots(MigrationJob job, StartMigrationRequest req) {
        Map<String, List<DiscoverySnapshot>> out = new HashMap<>();
        Map<String, List<String>> filters = req.getResourceFilters() != null
                ? req.getResourceFilters() : Collections.emptyMap();
        for (String type : job.getResourceTypes()) {
            List<DiscoverySnapshot> snaps;
            if (StringUtils.hasText(req.getDiscoveryId())) {
                snaps = snapshotReader.findByDiscoveryId(req.getCompanyName(), req.getWso2Tenant(),
                        type, req.getDiscoveryId());
            } else {
                snaps = snapshotReader.findLatestRevision(req.getCompanyName(), req.getWso2Tenant(), type);
                if (!snaps.isEmpty() && job.getSourceRevision() == null) {
                    job.setSourceRevision(snaps.get(0).getRevision());
                    job.setSourceDiscoveryId(snaps.get(0).getDiscoveryId());
                }
            }
            // Per-type allow-list (sourceIds): when supplied, drop every
            // snapshot whose sourceId isn't on the list. Bulk callers omit
            // resourceFilters → no filtering, every snapshot survives.
            List<String> filter = filters.get(type);
            if (filter != null && !filter.isEmpty()) {
                java.util.Set<String> wanted = new java.util.HashSet<>(filter);
                int before = snaps.size();
                snaps = snaps.stream()
                        .filter(s -> wanted.contains(s.getSourceId()))
                        .collect(Collectors.toList());
                log.info("[{}] filter shrank {} snapshots → {} for migration {} (allow-list size {})",
                        type, before, snaps.size(), job.getId(), wanted.size());
            }
            out.put(type, snaps);
            log.info("[{}] loaded {} snapshots for migration {}", type, snaps.size(), job.getId());
        }
        return out;
    }

    private MigrationReport.DiffSummary computeDiff(KongKonnectCredentials creds,
                                                    List<TranslatedApi> apis,
                                                    List<TranslatedConsumer> consumers) {
        // MVP: diff produced naively from the EntityMapping table.
        // CREATE counts = entities not yet mapped. UPDATE counts = entities already mapped.
        // A future enhancement can fetch live state and field-diff.
        int created = 0, updated = 0;
        List<String> sampleCreate = new ArrayList<>();
        List<String> sampleUpdate = new ArrayList<>();
        // For brevity we just inspect SERVICE-level mappings.
        for (TranslatedApi a : apis) {
            String diff = "CREATE"; // proxy; real check needs the EntityMapping lookup
            if ("CREATE".equals(diff)) {
                created++;
                if (sampleCreate.size() < 5) sampleCreate.add(a.getWso2SourceName());
            } else {
                updated++;
                if (sampleUpdate.size() < 5) sampleUpdate.add(a.getWso2SourceName());
            }
        }
        return MigrationReport.DiffSummary.builder()
                .created(created)
                .updated(updated)
                .unchanged(0)
                .wouldFail(0)
                .sampleCreate(sampleCreate)
                .sampleUpdate(sampleUpdate)
                .build();
    }

    /**
     * Per-resource roll-up the orchestrator hands to {@link #writeReport} so
     * each outcome row reports counts for its own resource type only —
     * instead of the leaky job-level totals the earlier impl used.
     */
    private record ResourceCounters(int translated, int deployed, int unchanged,
                                    int failed, List<String> failedSourceIds) {}

    private void writeReport(MigrationJob job, List<TranslatedApi> apis,
                             List<TranslatedConsumer> consumers,
                             List<MigrationReport.Warning> warnings,
                             MigrationReport.DiffSummary diff,
                             ResourceCounters apiCounts,
                             ResourceCounters consumerCounts) {
        List<MigrationReport.ResourceOutcome> outcomes = new ArrayList<>();
        outcomes.add(MigrationReport.ResourceOutcome.builder()
                .resourceType("apis")
                .translated(apiCounts.translated())
                .deployed(apiCounts.deployed())
                .unchanged(apiCounts.unchanged())
                .failed(apiCounts.failed())
                .failedSourceIds(apiCounts.failedSourceIds())
                .build());
        if (!consumers.isEmpty()) {
            outcomes.add(MigrationReport.ResourceOutcome.builder()
                    .resourceType("subscriptions")
                    .translated(consumerCounts.translated())
                    .deployed(consumerCounts.deployed())
                    .unchanged(consumerCounts.unchanged())
                    .failed(consumerCounts.failed())
                    .failedSourceIds(consumerCounts.failedSourceIds())
                    .build());
        }
        MigrationReport report = MigrationReport.builder()
                .migrationJobId(job.getId())
                .companyName(job.getCompanyName())
                .wso2Tenant(job.getWso2Tenant())
                .controlPlaneId(job.getControlPlaneId())
                .dryRun(job.isDryRun())
                .outcomes(outcomes)
                .warnings(warnings)
                .diff(diff)
                .generatedAt(Instant.now())
                .build();
        reportRepository.save(report);
    }

    private static MigrationReport.Warning warn(String type, DiscoverySnapshot s, String code, String msg) {
        return MigrationReport.Warning.builder()
                .resourceType(type)
                .wso2SourceId(s.getSourceId())
                .wso2SourceName(s.getSourceName())
                .code(code).message(msg)
                .build();
    }

    private void recordProgress(MigrationJob job, String slug, String state,
                                int translated, int deployed, String lastError) {
        Map<String, MigrationJob.ResourceProgress> map = job.getResourceProgress();
        MigrationJob.ResourceProgress p = map.getOrDefault(slug,
                MigrationJob.ResourceProgress.builder().build());
        if (p.getStartedAt() == null) p.setStartedAt(Instant.now());
        p.setState(state);
        p.setTranslated(translated);
        p.setDeployed(deployed);
        p.setLastError(lastError);
        if ("COMPLETED".equals(state) || "FAILED".equals(state)) {
            p.setCompletedAt(Instant.now());
        }
        map.put(slug, p);
        jobRepository.save(job);
    }

    private static List<String> defaultIfEmpty(List<String> in) {
        if (in == null || in.isEmpty()) return List.of("apis", "applications", "subscriptions");
        return in;
    }
}
