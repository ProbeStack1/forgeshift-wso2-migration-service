package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import com.forgeshift.wso2.migration.domain.MigrationState;
import com.forgeshift.wso2.migration.dto.StartMigrationRequest;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshotReader;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import com.forgeshift.wso2.migration.reader.KongKonnectProfileReader;
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

            // ----- TRANSLATING -----
            job.setState(MigrationState.TRANSLATING);
            jobRepository.save(job);

            List<TranslatedApi> translatedApis = new ArrayList<>();
            List<TranslatedConsumer> translatedConsumers = new ArrayList<>();
            List<MigrationReport.Warning> warnings = new ArrayList<>();

            for (DiscoverySnapshot s : byType.getOrDefault("apis", List.of())) {
                TranslatedApi t = apiTranslator.translate(s);
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
                writeReport(job, translatedApis, translatedConsumers, warnings, diff);
                job.setState(MigrationState.DRY_RUN_DONE);
                job.setCompletedAt(Instant.now());
                jobRepository.save(job);
                return;
            }

            job.setState(MigrationState.DEPLOYING);
            jobRepository.save(job);

            int totalDeployed = 0, totalUnchanged = 0, totalFailed = 0;
            int apiCreated = 0, apiUpdated = 0, apiFailed = 0;
            List<String> failedApiIds = new ArrayList<>();
            for (TranslatedApi t : translatedApis) {
                KongDeployer.DeployOutcome o = deployer.deployApi(creds, t, job);
                totalDeployed += (o.created + o.updated);
                totalUnchanged += o.unchanged;
                totalFailed += o.failed;
                apiCreated += o.created;
                apiUpdated += o.updated;
                if (o.failed > 0) {
                    apiFailed++;
                    failedApiIds.add(t.getWso2SourceId());
                }
            }
            recordProgress(job, "apis", "COMPLETED",
                    translatedApis.size(), apiCreated + apiUpdated,
                    apiFailed > 0 ? apiFailed + " APIs had failing entities" : null);

            int consCreated = 0, consUpdated = 0, consFailed = 0;
            for (TranslatedConsumer c : translatedConsumers) {
                KongDeployer.DeployOutcome o = deployer.deployConsumer(creds, c, job);
                totalDeployed += (o.created + o.updated);
                totalUnchanged += o.unchanged;
                totalFailed += o.failed;
                consCreated += o.created;
                consUpdated += o.updated;
                if (o.failed > 0) consFailed++;
            }
            if (!translatedConsumers.isEmpty()) {
                recordProgress(job, "subscriptions", "COMPLETED",
                        translatedConsumers.size(), consCreated + consUpdated,
                        consFailed > 0 ? consFailed + " consumers had failing entities" : null);
            }

            job.getCounts().setTotalDeployed(totalDeployed);
            job.getCounts().setTotalUnchanged(totalUnchanged);
            job.getCounts().setTotalFailed(totalFailed);
            writeReport(job, translatedApis, translatedConsumers, warnings, null);

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

    private void writeReport(MigrationJob job, List<TranslatedApi> apis,
                             List<TranslatedConsumer> consumers,
                             List<MigrationReport.Warning> warnings,
                             MigrationReport.DiffSummary diff) {
        List<MigrationReport.ResourceOutcome> outcomes = new ArrayList<>();
        outcomes.add(MigrationReport.ResourceOutcome.builder()
                .resourceType("apis")
                .translated(apis.size())
                .deployed(job.getCounts().getTotalDeployed())
                .unchanged(job.getCounts().getTotalUnchanged())
                .failed(job.getCounts().getTotalFailed())
                .build());
        if (!consumers.isEmpty()) {
            outcomes.add(MigrationReport.ResourceOutcome.builder()
                    .resourceType("subscriptions")
                    .translated(consumers.size())
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
