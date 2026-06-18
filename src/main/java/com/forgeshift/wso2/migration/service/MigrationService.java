package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.bundle.Wso2ApiBundle;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import com.forgeshift.wso2.migration.domain.MigrationState;
import com.forgeshift.wso2.migration.dto.StartMigrationRequest;
import com.forgeshift.wso2.migration.reader.AssessmentSourceReader;
import com.forgeshift.wso2.migration.reader.CredentialReader;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshotReader;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import com.forgeshift.wso2.migration.reader.KongKonnectProfileReader;
import com.forgeshift.wso2.migration.reader.Wso2Credentials;
import com.forgeshift.wso2.migration.reader.Wso2ProfileReader;
import com.forgeshift.wso2.migration.repository.MigrationJobRepository;
import com.forgeshift.wso2.migration.repository.MigrationReportRepository;
import com.forgeshift.wso2.migration.client.Wso2BundleClient;
import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.deck.BundleResult;
import com.forgeshift.wso2.migration.translator.ApiProductTranslator;
import com.forgeshift.wso2.migration.ai.TargetMode;
import com.forgeshift.wso2.migration.client.KonnectCustomPluginClient;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.translator.ApiTranslator;
import com.forgeshift.wso2.migration.translator.JwtClaimHeaderPluginBuilder;
import com.forgeshift.wso2.migration.translator.CertificateTranslator;
import com.forgeshift.wso2.migration.translator.MediationPolicyTranslator;
import com.forgeshift.wso2.migration.translator.SubscriptionTranslator;
import com.forgeshift.wso2.migration.translator.TranslatedApi;
import com.forgeshift.wso2.migration.translator.TranslatedApiProduct;
import com.forgeshift.wso2.migration.translator.TranslatedCertificate;
import com.forgeshift.wso2.migration.translator.TranslatedConsumer;
import com.forgeshift.wso2.migration.translator.TranslatedMediationPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
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

    private static final String SCOPE_MANUAL_ACTION_MESSAGE =
            "WSO2 OAuth scopes are not auto-migrated because Kong does not have a WSO2-style "
                    + "scope registry or role-binding store. Manual action required: recreate this scope "
                    + "and its role/client permissions in the target IdP (Kong Identity, Keycloak, Okta, "
                    + "Azure AD, Auth0, Cognito, etc.), then configure Kong Konnect openid-connect on "
                    + "protected routes with scopes_claim=[scope] and scopes_required=[this scope].";

    private final MigrationJobRepository jobRepository;
    private final MigrationReportRepository reportRepository;
    private final DiscoverySnapshotReader snapshotReader;
    private final KongKonnectProfileReader profileReader;
    private final Wso2ProfileReader wso2ProfileReader;
    private final Wso2BundleDownloadService bundleDownloadService;
    private final ApiTranslator apiTranslator;
    private final KonnectCustomPluginClient customPluginClient;
    private final ApiProductTranslator apiProductTranslator;
    private final SubscriptionTranslator subscriptionTranslator;
    private final CertificateTranslator certificateTranslator;
    private final MediationPolicyTranslator mediationTranslator;
    private final AssessmentSourceReader assessmentSourceReader;
    private final Wso2BundleClient wso2BundleClient;
    private final KongDeployer deployer;
    private final DeckBundleDeployer deckBundleDeployer;
    private final DependencyExpander dependencyExpander;
    private final MigrationProperties props;
    @Qualifier("migrationExecutor")
    private final TaskExecutor migrationExecutor;

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
        String jobId = job.getId();
        migrationExecutor.execute(() -> runMigration(jobId, req));
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
                || s == MigrationState.CANCELLED
                || s == MigrationState.TIMED_OUT
                // The service's own work is done once the bundle is pushed; the deck-apply
                // result arrives later via the callback. Let a sync call return here (as
                // DEPLOYING_TO_KONG) instead of blocking for the whole pipeline.
                || s == MigrationState.DEPLOYING_TO_KONG;
    }

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
                    req.getKongProfileName(), req.getKongCtrlPlanId(), req.getKongRegion());
            job.setControlPlaneId(creds.getControlPlaneId());
            job.setKonnectBaseUrl(creds.getKonnectBaseUrl());
            jobRepository.save(job);

            // Decide the gateway target mode ONCE (serverless inline vs custom plugin), gated by the
            // customPlugins switch + a live control-plane-type probe — it's a target property, not
            // per-sequence. Defaults to SERVERLESS_INLINE, so the existing behaviour is unchanged.
            TargetMode targetMode = resolveTargetMode(creds);
            // Self-diagnostic surfaced in the report (warnings) so an operator can confirm WHY the mode
            // resolved as it did — without shell access to the pod. Distinguishes "flag off" from
            // "control plane not custom-plugin-capable" (e.g. stale Konnect PAT / serverless CP).
            boolean cpCustomPluginCapable = props.getCustomPlugins().isEnabled()
                    && customPluginClient.supportsCustomPlugins(creds);
            String targetModeDiag = "Resolved target mode = " + targetMode
                    + " [customPlugins.enabled=" + props.getCustomPlugins().isEnabled()
                    + ", controlPlane=" + (creds == null ? null : creds.getControlPlaneId())
                    + ", customPluginCapableCp=" + cpCustomPluginCapable + "].";
            log.info("[job {}] {}", job.getId(), targetModeDiag);
            // Operator-supplied JWT claim→header projection: WSO2 keeps this in GLOBAL server config (apim.jwt),
            // not the API payload, so it can't be discovered — when supplied it's attached as the
            // forgeshift-jwt-claim-headers custom plugin (custom-plugin-capable control planes only).
            Map<String, Object> claimHeaderCfg = JwtClaimHeaderPluginBuilder.buildConfig(req.getClaimHeaders()).orElse(null);

            Map<String, List<DiscoverySnapshot>> byType = loadSnapshots(job, req);

            // ----- DEPENDENCY EXPANSION (opt-in) -----
            // When includeDependencies=true, auto-pull each selected resource's dependencies
            // from the assessment graph and drop anything already present in Kong. Mutates byType
            // in place, then the normal flow translates the rest. Resources skipped because they're
            // already in Kong come back as report warnings (code SKIPPED_ALREADY_IN_KONG) so the
            // response shows exactly what was NOT re-migrated.
            // The graph is read by assessmentTransactionId, OR — when that's blank — the migration's
            // own requestTransactionId (both match the assessment doc's requestTransactionId field).
            List<MigrationReport.Warning> dependencyWarnings = new ArrayList<>();
            if (req.isIncludeDependencies() && StringUtils.hasText(DependencyExpander.effectiveAssessmentTxn(req))) {
                DependencyExpander.ExpansionResult ex = dependencyExpander.expand(job, req, creds, byType);
                byType = ex.byType();
                dependencyWarnings = ex.skipped();
                job.setDependencyMigrations(ex.tree());
                jobRepository.save(job);
            }

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
            // Seeded with any "skipped because already in Kong" warnings from dependency expansion.
            List<MigrationReport.Warning> warnings = new ArrayList<>(dependencyWarnings);
            warnings.add(MigrationReport.Warning.builder()
                    .resourceType("_migration").code("TARGET_MODE").message(targetModeDiag).build());
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

            scopeApplicationsForSelectedSubscriptions(byType, req);
            List<DiscoverySnapshot> scopeSnapshots = byType.getOrDefault("scopes", List.of());

            // Custom AM 4.x operation policies carry their Synapse body only in WSO2's
            // /operation-policies/{id}/content (not the discovery snapshot or the export ZIP), so when
            // targeting a custom-plugin-capable CP we fetch it live and feed it to the translator's
            // catalog. One token for the whole run; per-API/per-policy failures degrade to manual-review.
            String opPolicyToken = null;
            if (targetMode == TargetMode.CUSTOM_PLUGIN
                    && wso2Creds != null && !"missing".equals(wso2Creds.getSource())) {
                try {
                    opPolicyToken = wso2BundleClient.acquireToken(wso2Creds);
                } catch (Exception e) {
                    log.warn("Custom op-policy fetch skipped — WSO2 token failed: {}", e.getMessage());
                }
            }
            for (DiscoverySnapshot s : apiSnapshots) {
                Wso2ApiBundle bundle = bundleResult.bundles.get(s.getSourceId());
                // Third reconcile source: the assessment's stored API config from GCS
                // (lowest precedence; null when GCS is off / object absent — then skipped).
                Map<String, Object> assessmentJson = assessmentSourceReader.readApiConfig(
                        req.getCompanyName(), req.getWso2Tenant(), req.getEnvClassification(),
                        s.getSourceName(), s.getSourceVersion(), s.getSourceId());
                Map<String, String> opPolicyDefs = fetchOpPolicyDefs(opPolicyToken, wso2Creds, s);
                TranslatedApi t = apiTranslator.translate(s, bundle, assessmentJson, targetMode, opPolicyDefs);
                attachClaimHeaderPlugin(t, claimHeaderCfg, targetMode);
                translatedApis.add(t);
                for (String w : t.getWarnings()) {
                    warnings.add(warn("apis", s, "TRANSLATION_NOTE", w));
                }
            }
            recordProgress(job, "apis", "TRANSLATED", translatedApis.size(), 0, null);

            if (!scopeSnapshots.isEmpty()) {
                for (DiscoverySnapshot s : scopeSnapshots) {
                    warnings.add(scopeManualWarning(s));
                }
                recordProgress(job, "scopes", "MANUAL_ACTION_REQUIRED",
                        0, 0,
                        scopeSnapshots.size()
                                + " scope(s) require target IdP setup and Kong OIDC route configuration");
            }

            // Mediation policies: each API's Synapse sequences (bundled in its export
            // ZIP) → AI-translated Lua → Kong serverless plugins on that API's service.
            // Sequences the AI can't safely translate degrade to manual-review warnings;
            // nothing unsafe is ever deployed.
            List<TranslatedMediationPolicy> translatedMediations = new ArrayList<>();
            for (DiscoverySnapshot s : apiSnapshots) {
                Wso2ApiBundle apiBundle = bundleResult.bundles.get(s.getSourceId());
                if (apiBundle == null || apiBundle.getSequences() == null || apiBundle.getSequences().isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, String> seq : apiBundle.getSequences().entrySet()) {
                    TranslatedMediationPolicy med = mediationTranslator.translate(
                            s.getSourceId(), s.getSourceName(), seq.getKey(), seq.getValue(), flowOf(seq.getKey()), targetMode);
                    translatedMediations.add(med);
                    for (String w : med.getWarnings()) {
                        warnings.add(warn("mediationpolicies", s, "TRANSLATION_NOTE", w));
                    }
                }
            }
            // Standalone mediation-policy migration (UI path: POST /konnect/wso2/mediation-policies).
            // Selected policies load as snapshots (metadata only), so fetch each one's Synapse XML
            // live from WSO2, then AI-translate + attach to its (already-migrated) API service.
            List<DiscoverySnapshot> medSnapshots = byType.getOrDefault("mediationpolicies", List.of());
            if (!medSnapshots.isEmpty()) {
                String medToken = null;
                if (wso2Creds != null && !"missing".equals(wso2Creds.getSource())) {
                    try {
                        medToken = wso2BundleClient.acquireToken(wso2Creds);
                    } catch (Exception e) {
                        log.warn("Mediation content fetch skipped — WSO2 token failed: {}", e.getMessage());
                    }
                }
                for (DiscoverySnapshot s : medSnapshots) {
                    Map<String, String> meta = s.getMetadata() != null ? s.getMetadata() : Map.of();
                    String apiId = meta.get("apiId");
                    String apiName = meta.get("apiName");
                    String flow = meta.getOrDefault("type", "in");
                    String policyId = s.getPayload() != null && s.getPayload().get("id") != null
                            ? s.getPayload().get("id").toString() : null;
                    String xml = null;
                    if (medToken != null && apiId != null && policyId != null) {
                        try {
                            xml = wso2BundleClient.fetchMediationPolicyContent(medToken, wso2Creds, apiId, policyId);
                        } catch (Exception e) {
                            log.warn("Mediation content fetch failed for {}: {}", s.getSourceId(), e.getMessage());
                        }
                    }
                    TranslatedMediationPolicy med = mediationTranslator.translate(
                            apiId != null ? apiId : s.getSourceId(), apiName, s.getSourceName(), xml, flow, targetMode);
                    translatedMediations.add(med);
                    for (String w : med.getWarnings()) {
                        warnings.add(warn("mediationpolicies", s, "TRANSLATION_NOTE", w));
                    }
                }
            }
            if (!translatedMediations.isEmpty()) {
                recordProgress(job, "mediationpolicies", "TRANSLATED", translatedMediations.size(), 0, null);
            }

            if (hasConsumerResources(job.getResourceTypes())) {
                // Live-fetch each migrated app's PUBLIC consumer key + the Key Manager public key so the
                // consumer gets a working jwt/key-auth credential even when the assessment never captured
                // the OAuth2 keys. Inert unless credentials.live-key-fetch is on. Client secret never read.
                Map<String, List<CredentialReader.AppCredential>> liveCreds =
                        fetchLiveAppCredentials(wso2Creds, byType.getOrDefault("applications", List.of()));
                List<TranslatedConsumer> tcs = subscriptionTranslator.translate(
                        byType.getOrDefault("applications", List.of()),
                        byType.getOrDefault("subscriptions", List.of()), liveCreds);
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

            // Certificates: the snapshots carry metadata only, so fetch each cert's
            // PEM content live from WSO2, then translate to Kong ca_certificates.
            // Missing content degrades to a skip-and-warn (no Kong write).
            List<TranslatedCertificate> translatedCertificates = new ArrayList<>();
            List<DiscoverySnapshot> certSnapshots = byType.getOrDefault("certificates", List.of());
            if (!certSnapshots.isEmpty()) {
                String certToken = null;
                if (wso2Creds != null && !"missing".equals(wso2Creds.getSource())) {
                    try {
                        certToken = wso2BundleClient.acquireToken(wso2Creds);
                    } catch (Exception e) {
                        log.warn("Certificate content fetch skipped — WSO2 token failed: {}", e.getMessage());
                    }
                }
                for (DiscoverySnapshot s : certSnapshots) {
                    String pem = null;
                    if (certToken != null) {
                        try {
                            pem = wso2BundleClient.fetchCertificateContent(certToken, wso2Creds, s.getSourceId());
                        } catch (Exception e) {
                            log.warn("Certificate content fetch failed for {}: {}", s.getSourceId(), e.getMessage());
                        }
                    }
                    TranslatedCertificate tc = certificateTranslator.translate(s, pem);
                    translatedCertificates.add(tc);
                    for (String w : tc.getWarnings()) {
                        warnings.add(warn("certificates", s, "TRANSLATION_NOTE", w));
                    }
                }
                recordProgress(job, "certificates", "TRANSLATED", translatedCertificates.size(), 0, null);
            }

            // API Products: a product re-exposes operations from member APIs under
            // its own context. (a) Ensure each member API is migrated first — we
            // translate it and add it to translatedApis so it deploys in the API
            // loop (registering its Kong service). Then the product's routes are
            // attached to those member services (resolved from entity_mappings at
            // deploy time).
            List<TranslatedApiProduct> translatedApiProducts = new ArrayList<>();
            List<DiscoverySnapshot> productSnapshots = byType.getOrDefault("apiproducts", List.of());
            if (!productSnapshots.isEmpty()) {
                Map<String, DiscoverySnapshot> apiById = new HashMap<>();
                for (DiscoverySnapshot a : snapshotReader.findLatestRevision(
                        req.getCompanyName(), req.getWso2Tenant(), "apis")) {
                    if (a.getSourceId() != null) apiById.put(a.getSourceId(), a);
                }
                java.util.Set<String> alreadyTranslated = translatedApis.stream()
                        .map(TranslatedApi::getWso2SourceId)
                        .collect(java.util.stream.Collectors.toSet());
                List<DiscoverySnapshot> memberApiSnapshots = new ArrayList<>();

                for (DiscoverySnapshot prod : productSnapshots) {
                    TranslatedApiProduct tap = apiProductTranslator.translate(prod);
                    translatedApiProducts.add(tap);
                    for (String w : tap.getWarnings()) {
                        warnings.add(warn("apiproducts", prod, "TRANSLATION_NOTE", w));
                    }
                    for (String memberId : tap.memberApiIds()) {
                        if (alreadyTranslated.contains(memberId)) continue;
                        DiscoverySnapshot apiSnap = apiById.get(memberId);
                        if (apiSnap == null) {
                            warnings.add(warn("apiproducts", prod, "MEMBER_API_MISSING",
                                    "Member API " + memberId + " not found in discovery — its product routes will be skipped."));
                            continue;
                        }
                        memberApiSnapshots.add(apiSnap);
                        alreadyTranslated.add(memberId);
                    }
                }
                // Member APIs take the SAME full-fidelity path as directly-migrated APIs:
                // download each one's export ZIP and translate with the ZIP + snapshot
                // reconcile (never snapshot-only), and translate their mediation sequences too.
                if (!memberApiSnapshots.isEmpty()) {
                    Wso2BundleDownloadService.Result memberBundles =
                            bundleDownloadService.download(wso2Creds, memberApiSnapshots);
                    for (DiscoverySnapshot apiSnap : memberApiSnapshots) {
                        Wso2ApiBundle b = memberBundles.bundles.get(apiSnap.getSourceId());
                        Map<String, Object> mAssess = assessmentSourceReader.readApiConfig(
                                req.getCompanyName(), req.getWso2Tenant(), req.getEnvClassification(),
                                apiSnap.getSourceName(), apiSnap.getSourceVersion(), apiSnap.getSourceId());
                        TranslatedApi memberApi = apiTranslator.translate(apiSnap, b, mAssess, targetMode);
                        attachClaimHeaderPlugin(memberApi, claimHeaderCfg, targetMode);
                        translatedApis.add(memberApi);
                        if (b != null && b.getSequences() != null) {
                            for (Map.Entry<String, String> seq : b.getSequences().entrySet()) {
                                TranslatedMediationPolicy med = mediationTranslator.translate(
                                        apiSnap.getSourceId(), apiSnap.getSourceName(),
                                        seq.getKey(), seq.getValue(), flowOf(seq.getKey()), targetMode);
                                translatedMediations.add(med);
                                for (String w : med.getWarnings()) {
                                    warnings.add(warn("mediationpolicies", apiSnap, "TRANSLATION_NOTE", w));
                                }
                            }
                        }
                    }
                }
                recordProgress(job, "apiproducts", "TRANSLATED", translatedApiProducts.size(), 0, null);
            }

            job.getCounts().setTotalTranslated(
                    translatedApis.size() + translatedConsumers.size()
                            + translatedCertificates.size() + translatedApiProducts.size()
                            + translatedMediations.size());
            jobRepository.save(job);

            // ----- DRY RUN OR DEPLOY -----
            if (req.isDryRun()) {
                MigrationReport.DiffSummary diff = computeDiff(creds, translatedApis, translatedConsumers);
                writeReport(job, translatedApis, translatedConsumers, warnings, diff,
                        new ResourceCounters(translatedApis.size(), 0, 0, 0, 0, List.of()),
                        new ResourceCounters(translatedConsumers.size(), 0, 0, 0, 0, List.of()),
                        new ResourceCounters(translatedCertificates.size(), 0, 0, 0, 0, List.of()),
                        new ResourceCounters(translatedApiProducts.size(), 0, 0, 0, 0, List.of()),
                        new ResourceCounters(translatedMediations.size(), 0, 0, 0, 0, List.of()),
                        new ResourceCounters(0, 0, 0, 0, scopeSnapshots.size(), List.of()), null);
                job.setState(MigrationState.DRY_RUN_DONE);
                job.setCompletedAt(Instant.now());
                jobRepository.save(job);
                return;
            }

            // ----- DECK BUNDLE DELIVERY -----
            // When deck delivery is enabled we don't write to Konnect over REST.
            // We serialize the translated plan to kong.yaml, package a downloadable
            // bundle (yaml + pipeline workflow + README), and let the GitHub Actions
            // pipeline apply it with `deck gateway apply`. entity_mappings are rebuilt
            // afterwards from the decK dump via POST /migrations/{id}/deck-result.
            if (props.getDeck().isEnabled()) {
                job.setState(MigrationState.GENERATING_BUNDLE);
                jobRepository.save(job);
                BundleResult bundle = deckBundleDeployer.buildBundle(job, creds,
                        translatedApis, translatedConsumers, translatedCertificates,
                        translatedApiProducts, translatedMediations);
                recordProgress(job, "bundle", "COMPLETED",
                        job.getCounts().getTotalTranslated(),
                        job.getCounts().getTotalTranslated(), null);
                // Counts reflect what was written INTO the bundle; the real apply
                // result lives in the GitHub Actions run (two-stage — see deck-result).
                writeReport(job, translatedApis, translatedConsumers, warnings, null,
                        new ResourceCounters(translatedApis.size(), translatedApis.size(), 0, 0, 0, List.of()),
                        new ResourceCounters(translatedConsumers.size(), translatedConsumers.size(), 0, 0, 0, List.of()),
                        new ResourceCounters(translatedCertificates.size(), translatedCertificates.size(), 0, 0, 0, List.of()),
                        new ResourceCounters(translatedApiProducts.size(), translatedApiProducts.size(), 0, 0, 0, List.of()),
                        new ResourceCounters(translatedMediations.size(), translatedMediations.size(), 0, 0, 0, List.of()),
                        new ResourceCounters(0, 0, 0, 0, scopeSnapshots.size(), List.of()),
                        bundle);
                job.getCounts().setTotalDeployed(job.getCounts().getTotalTranslated());
                // If the pipeline was DISPATCHED AND we have a callback URL, the GitHub Actions
                // run will `deck gateway apply` and POST the result back to
                // /migrations/{id}/deck-result. Don't claim COMPLETED yet — wait for that result.
                // (Gate on dispatch, not on a commit sha: a re-run with no file change still has a
                // pipeline to await even though nothing was committed.)
                boolean awaitPipeline = bundle.isDispatched()
                        && StringUtils.hasText(props.getDeck().getCallbackBaseUrl());
                if (awaitPipeline) {
                    job.setState(MigrationState.DEPLOYING_TO_KONG);
                    jobRepository.save(job);
                    log.info("Migration job {} DEPLOYING_TO_KONG — bundle pushed (sha {}), awaiting deck-apply callback",
                            job.getId(), bundle.getGitCommitSha());
                } else {
                    // No pipeline to wait for (git push didn't happen / no callback configured) —
                    // the bundle itself is the deliverable.
                    job.setState(MigrationState.COMPLETED);
                    job.setCompletedAt(Instant.now());
                    jobRepository.save(job);
                    log.info("Migration job {} COMPLETED (decK bundle, no pipeline callback): {} -> {}",
                            job.getId(), bundle.getKongConfigPath(), bundle.getDownloadUrl());
                }
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

            int certCreated = 0, certUpdated = 0, certUnchanged = 0, certFailed = 0;
            List<String> failedCertIds = new ArrayList<>();
            for (TranslatedCertificate tc : translatedCertificates) {
                KongDeployer.DeployOutcome o = deployer.deployCertificate(creds, tc, job);
                totalDeployed += (o.created + o.updated);
                totalUnchanged += o.unchanged;
                totalFailed += o.failed;
                certCreated += o.created;
                certUpdated += o.updated;
                certUnchanged += o.unchanged;
                if (o.failed > 0) {
                    certFailed += o.failed;
                    failedCertIds.add(tc.getWso2SourceId());
                }
            }
            if (!translatedCertificates.isEmpty()) {
                recordProgress(job, "certificates", "COMPLETED",
                        translatedCertificates.size(), certCreated + certUpdated,
                        certFailed > 0 ? certFailed + " certificate(s) failed across "
                                + failedCertIds.size() + " cert(s)" : null);
            }

            // API Products deploy AFTER the API loop above, so each member API's
            // Kong service already exists for the product routes to attach to.
            int prodCreated = 0, prodUpdated = 0, prodUnchanged = 0, prodFailed = 0;
            List<String> failedProductIds = new ArrayList<>();
            for (TranslatedApiProduct tap : translatedApiProducts) {
                KongDeployer.DeployOutcome o = deployer.deployApiProduct(creds, tap, job);
                totalDeployed += (o.created + o.updated);
                totalUnchanged += o.unchanged;
                totalFailed += o.failed;
                prodCreated += o.created;
                prodUpdated += o.updated;
                prodUnchanged += o.unchanged;
                if (o.failed > 0) {
                    prodFailed += o.failed;
                    failedProductIds.add(tap.getWso2SourceId());
                }
            }
            if (!translatedApiProducts.isEmpty()) {
                recordProgress(job, "apiproducts", "COMPLETED",
                        translatedApiProducts.size(), prodCreated + prodUpdated,
                        prodFailed > 0 ? prodFailed + " product route(s) failed across "
                                + failedProductIds.size() + " product(s)" : null);
            }

            // Mediation policies → Kong serverless plugins on each API's service.
            // Non-translatable sequences are counted as "manual" (not deploy failures).
            int medCreated = 0, medUpdated = 0, medUnchanged = 0, medFailed = 0, medManual = 0;
            List<String> failedMediationIds = new ArrayList<>();
            for (TranslatedMediationPolicy med : translatedMediations) {
                if (!med.isDeployable()) {
                    medManual++;
                    failedMediationIds.add(med.getWso2SourceId());
                    continue;
                }
                KongDeployer.DeployOutcome o = deployer.deployMediationPolicy(creds, med, job);
                totalDeployed += (o.created + o.updated);
                totalUnchanged += o.unchanged;
                totalFailed += o.failed;
                medCreated += o.created;
                medUpdated += o.updated;
                medUnchanged += o.unchanged;
                if (o.failed > 0) {
                    medFailed += o.failed;
                    failedMediationIds.add(med.getWso2SourceId());
                }
            }
            if (!translatedMediations.isEmpty()) {
                recordProgress(job, "mediationpolicies", "COMPLETED",
                        translatedMediations.size(), medCreated + medUpdated,
                        (medManual + medFailed) > 0
                                ? medManual + " need manual review, " + medFailed + " deploy failure(s)" : null);
            }

            job.getCounts().setTotalDeployed(totalDeployed);
            job.getCounts().setTotalUnchanged(totalUnchanged);
            job.getCounts().setTotalFailed(totalFailed);

            // Per-resource counters land in the report instead of the leaky
            // job-level totals that used to count consumer deploys as API
            // deploys in the "apis" outcome row.
            ResourceCounters apiCounts = new ResourceCounters(
                    translatedApis.size(), apiCreated + apiUpdated,
                    apiUnchanged, apiFailed, 0, failedApiIds);
            ResourceCounters consCounts = new ResourceCounters(
                    translatedConsumers.size(), consCreated + consUpdated,
                    consUnchanged, consFailed, 0, failedConsumerIds);
            ResourceCounters certCounts = new ResourceCounters(
                    translatedCertificates.size(), certCreated + certUpdated,
                    certUnchanged, certFailed, 0, failedCertIds);
            ResourceCounters productCounts = new ResourceCounters(
                    translatedApiProducts.size(), prodCreated + prodUpdated,
                    prodUnchanged, prodFailed, 0, failedProductIds);
            ResourceCounters mediationCounts = new ResourceCounters(
                    translatedMediations.size(), medCreated + medUpdated,
                    medUnchanged, medManual + medFailed, 0, failedMediationIds);
            ResourceCounters scopeCounts = new ResourceCounters(
                    0, 0, 0, 0, scopeSnapshots.size(), List.of());
            writeReport(job, translatedApis, translatedConsumers, warnings, null,
                    apiCounts, consCounts, certCounts, productCounts, mediationCounts, scopeCounts, null);

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

    /**
     * A selective subscription request initially needs to load applications
     * broadly so the parent consumer can be built. Before translating, narrow
     * that application list to only the parents referenced by the selected
     * subscriptions; otherwise a single-subscription request deploys every
     * application in the latest discovery revision.
     */
    private void scopeApplicationsForSelectedSubscriptions(Map<String, List<DiscoverySnapshot>> byType,
                                                           StartMigrationRequest req) {
        Map<String, List<String>> filters = req.getResourceFilters();
        if (filters == null || filters.containsKey("applications")
                || !filters.containsKey("subscriptions")) {
            return;
        }

        Set<String> parentApplicationIds = byType.getOrDefault("subscriptions", List.of()).stream()
                .map(s -> mapField(s.getPayload(), "applicationId"))
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (parentApplicationIds.isEmpty()) {
            byType.put("applications", List.of());
            return;
        }

        List<DiscoverySnapshot> scopedApplications = byType.getOrDefault("applications", List.of()).stream()
                .filter(a -> parentApplicationIds.contains(a.getSourceId()))
                .collect(Collectors.toList());
        byType.put("applications", scopedApplications);
    }

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

    /**
     * Decide the gateway target mode for this migration. Defaults to {@code SERVERLESS_INLINE} (no
     * change); flips to {@code CUSTOM_PLUGIN} only when the customPlugins switch is on AND (unless
     * overridden) a live probe confirms the target control plane is custom-plugin-capable — so a
     * stale profile can never push a custom plugin to a serverless control plane.
     */
    private TargetMode resolveTargetMode(KongKonnectCredentials creds) {
        if (!props.getCustomPlugins().isEnabled()) {
            return TargetMode.SERVERLESS_INLINE;
        }
        if (props.getCustomPlugins().isRequireDedicatedControlPlane()
                && !customPluginClient.supportsCustomPlugins(creds)) {
            log.warn("customPlugins.enabled=true but control plane {} is not custom-plugin-capable "
                    + "(serverless/unknown) — using serverless inline translation.",
                    creds == null ? null : creds.getControlPlaneId());
            return TargetMode.SERVERLESS_INLINE;
        }
        return TargetMode.CUSTOM_PLUGIN;
    }

    /** Attach the operator-supplied JWT claim→header custom plugin to a translated API (custom-plugin mode only). */
    private void attachClaimHeaderPlugin(TranslatedApi t, Map<String, Object> claimHeaderCfg, TargetMode mode) {
        if (claimHeaderCfg == null || mode != TargetMode.CUSTOM_PLUGIN || t == null || t.getService() == null) {
            return;
        }
        if (t.getServicePlugins() == null) {
            t.setServicePlugins(new ArrayList<>());
        }
        List<String> tags = t.getService().getTags();
        t.getServicePlugins().add(KongPlugin.builder()
                .name(JwtClaimHeaderPluginBuilder.PLUGIN_NAME)
                .config(claimHeaderCfg).enabled(true)
                .tags(tags == null ? null : new ArrayList<>(tags))
                .build());
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
                                    int failed, int skipped, List<String> failedSourceIds) {}

    private void writeReport(MigrationJob job, List<TranslatedApi> apis,
                             List<TranslatedConsumer> consumers,
                             List<MigrationReport.Warning> warnings,
                             MigrationReport.DiffSummary diff,
                             ResourceCounters apiCounts,
                             ResourceCounters consumerCounts,
                             ResourceCounters certCounts,
                             ResourceCounters productCounts,
                             ResourceCounters mediationCounts,
                             ResourceCounters scopeCounts,
                             BundleResult bundle) {
        List<MigrationReport.ResourceOutcome> outcomes = new ArrayList<>();
        if (job.getResourceTypes().contains("apis") || apiCounts.translated() > 0) {
            outcomes.add(MigrationReport.ResourceOutcome.builder()
                    .resourceType("apis")
                    .translated(apiCounts.translated())
                    .deployed(apiCounts.deployed())
                    .unchanged(apiCounts.unchanged())
                    .failed(apiCounts.failed())
                    .skipped(apiCounts.skipped())
                    .failedSourceIds(apiCounts.failedSourceIds())
                    .build());
        }
        if (!consumers.isEmpty()) {
            outcomes.add(MigrationReport.ResourceOutcome.builder()
                    .resourceType("subscriptions")
                    .translated(consumerCounts.translated())
                    .deployed(consumerCounts.deployed())
                    .unchanged(consumerCounts.unchanged())
                    .failed(consumerCounts.failed())
                    .skipped(consumerCounts.skipped())
                    .failedSourceIds(consumerCounts.failedSourceIds())
                    .build());
        }
        if (certCounts.translated() > 0) {
            outcomes.add(MigrationReport.ResourceOutcome.builder()
                    .resourceType("certificates")
                    .translated(certCounts.translated())
                    .deployed(certCounts.deployed())
                    .unchanged(certCounts.unchanged())
                    .failed(certCounts.failed())
                    .skipped(certCounts.skipped())
                    .failedSourceIds(certCounts.failedSourceIds())
                    .build());
        }
        if (productCounts.translated() > 0) {
            outcomes.add(MigrationReport.ResourceOutcome.builder()
                    .resourceType("apiproducts")
                    .translated(productCounts.translated())
                    .deployed(productCounts.deployed())
                    .unchanged(productCounts.unchanged())
                    .failed(productCounts.failed())
                    .skipped(productCounts.skipped())
                    .failedSourceIds(productCounts.failedSourceIds())
                    .build());
        }
        if (mediationCounts.translated() > 0) {
            outcomes.add(MigrationReport.ResourceOutcome.builder()
                    .resourceType("mediationpolicies")
                    .translated(mediationCounts.translated())
                    .deployed(mediationCounts.deployed())
                    .unchanged(mediationCounts.unchanged())
                    .failed(mediationCounts.failed())
                    .skipped(mediationCounts.skipped())
                    .failedSourceIds(mediationCounts.failedSourceIds())
                    .build());
        }
        if (job.getResourceTypes().contains("scopes") || scopeCounts.skipped() > 0) {
            outcomes.add(MigrationReport.ResourceOutcome.builder()
                    .resourceType("scopes")
                    .translated(scopeCounts.translated())
                    .deployed(scopeCounts.deployed())
                    .unchanged(scopeCounts.unchanged())
                    .failed(scopeCounts.failed())
                    .skipped(scopeCounts.skipped())
                    .failedSourceIds(scopeCounts.failedSourceIds())
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
                .apiKongDetails(buildApiKongDetails(apis))
                .bundleDownloadUrl(bundle != null ? bundle.getDownloadUrl() : null)
                .bundlePath(bundle != null ? bundle.getBundlePath() : null)
                .controlPlaneName(bundle != null ? bundle.getControlPlaneName() : null)
                .kongConfigPath(bundle != null ? bundle.getKongConfigPath() : null)
                .deckMode(bundle != null ? props.getDeck().getDeckMode() : null)
                .gitRepo(bundle != null ? bundle.getGitRepo() : null)
                .gitBranch(bundle != null ? bundle.getGitBranch() : null)
                .gitCommitSha(bundle != null ? bundle.getGitCommitSha() : null)
                .gitCommitUrl(bundle != null ? bundle.getGitCommitUrl() : null)
                .gitFilesPushed(bundle != null ? bundle.getGitFilesPushed() : null)
                .gitError(bundle != null ? bundle.getGitError() : null)
                .dependencyMigrations(job.getDependencyMigrations())
                .generatedAt(Instant.now())
                .build();
        reportRepository.save(report);
    }

    /**
     * Captures the translated Kong objects (service name, route paths, plugin names) for each API so
     * the cutover service can show Kong detail without re-reading Konnect.
     */
    private List<MigrationReport.ApiKongDetail> buildApiKongDetails(List<TranslatedApi> apis) {
        List<MigrationReport.ApiKongDetail> details = new ArrayList<>();
        for (TranslatedApi t : apis) {
            List<String> routePaths = t.getRoutes() == null ? new ArrayList<>() : t.getRoutes().stream()
                    .filter(r -> r.getPaths() != null)
                    .flatMap(r -> r.getPaths().stream())
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(java.util.stream.Collectors.toList());
            List<String> plugins = new ArrayList<>();
            if (t.getServicePlugins() != null) {
                t.getServicePlugins().stream()
                        .filter(p -> StringUtils.hasText(p.getName()))
                        .forEach(p -> plugins.add(p.getName()));
            }
            if (t.getRoutePlugins() != null) {
                t.getRoutePlugins().values().stream()
                        .filter(java.util.Objects::nonNull)
                        .flatMap(List::stream)
                        .filter(p -> StringUtils.hasText(p.getName()))
                        .forEach(p -> plugins.add(p.getName()));
            }
            details.add(MigrationReport.ApiKongDetail.builder()
                    .wso2SourceId(t.getWso2SourceId())
                    .wso2SourceName(t.getWso2SourceName())
                    .kongServiceName(t.getService() != null ? t.getService().getName() : null)
                    .routePaths(routePaths)
                    .plugins(plugins.stream().distinct().collect(java.util.stream.Collectors.toList()))
                    .build());
        }
        return details;
    }

    /** Built-in AM 4.x operation policies handled declaratively by OperationPolicyTranslator — no need
     *  to fetch their Synapse body for the custom-plugin catalog. */
    private static final java.util.Set<String> BUILTIN_OP_POLICIES = java.util.Set.of(
            "addheader", "setheader", "removeheader", "renameheader", "addqueryparam",
            "removequeryparam", "rewriteresourcepath", "changehttpmethod", "rewritehttpmethod");

    /**
     * Fetch the Synapse bodies of an API's CUSTOM operation policies from WSO2 so the translator's
     * catalog can recognise them (the snapshot/export carry only the policy name + params). Keyed by
     * both policyId and policyName. Returns an empty map when the token is null (non-custom-plugin mode
     * or WSO2 unreachable) or the API references no custom op-policies. Per-policy failures are skipped.
     */
    private Map<String, String> fetchOpPolicyDefs(String token, Wso2Credentials creds, DiscoverySnapshot snap) {
        if (token == null || snap == null || snap.getPayload() == null) return Map.of();
        Map<String, String> idToName = new LinkedHashMap<>();
        Map<String, Object> p = snap.getPayload();
        collectPolicyRefs(p.get("apiPolicies"), idToName);
        if (p.get("operations") instanceof List<?> ops) {
            for (Object o : ops) {
                if (o instanceof Map<?, ?> om) collectPolicyRefs(om.get("operationPolicies"), idToName);
            }
        }
        if (idToName.isEmpty()) return Map.of();
        Map<String, String> defs = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : idToName.entrySet()) {
            String j2 = null;
            try {
                // Pass the API id: a common policy attached to an API is cloned to an API-specific id
                // served only from the API-scoped content endpoint.
                j2 = wso2BundleClient.fetchOperationPolicyContent(token, creds, snap.getSourceId(), e.getKey());
            } catch (Exception ex) {
                log.warn("op-policy {} content fetch failed: {}", e.getKey(), ex.getMessage());
            }
            if (j2 != null && !j2.isBlank()) {
                defs.put(e.getKey(), j2);                                  // by policyId
                if (e.getValue() != null) defs.put(e.getValue(), j2);      // by policyName
            }
        }
        return defs;
    }

    /** Collect (policyId → policyName) for non-built-in policies in a request/response/fault flow object. */
    @SuppressWarnings("unchecked")
    private static void collectPolicyRefs(Object policiesObj, Map<String, String> idToName) {
        if (!(policiesObj instanceof Map<?, ?> m)) return;
        for (String flow : List.of("request", "response", "fault")) {
            Object arr = ((Map<String, Object>) m).get(flow);
            if (!(arr instanceof List<?> l)) continue;
            for (Object e : l) {
                if (!(e instanceof Map<?, ?> pm)) continue;
                Object id = ((Map<?, ?>) pm).get("policyId");
                Object name = ((Map<?, ?>) pm).get("policyName");
                if (id == null) continue;
                String n = name == null ? null : name.toString();
                if (n != null && BUILTIN_OP_POLICIES.contains(n.trim().toLowerCase())) continue;
                idToName.putIfAbsent(id.toString(), n);
            }
        }
    }

    /**
     * Live-fetch consumer credentials when enabled: per migrated application, read its PUBLIC OAuth2
     * consumer key(s) from the WSO2 DevPortal + the Key Manager's RSA public key (once) from
     * /oauth2/jwks, so {@code CredentialTranslator} can emit a working jwt/key-auth credential. The
     * client SECRET is never read. Returns empty (skip) when disabled / no apps / WSO2 unreachable.
     */
    private Map<String, List<CredentialReader.AppCredential>> fetchLiveAppCredentials(
            Wso2Credentials creds, List<DiscoverySnapshot> apps) {
        Map<String, List<CredentialReader.AppCredential>> out = new LinkedHashMap<>();
        if (!props.getCredentials().isEnabled() || !props.getCredentials().isLiveKeyFetch()
                || apps == null || apps.isEmpty()
                || creds == null || "missing".equals(creds.getSource())) {
            return out;
        }
        String devToken;
        try {
            devToken = wso2BundleClient.acquireDevPortalToken(creds);
        } catch (Exception e) {
            log.warn("[credentials] live key fetch skipped — DevPortal token failed: {}", e.getMessage());
            return out;
        }
        String kmPem = wso2BundleClient.fetchKeyManagerPublicKeyPem(creds);
        if (kmPem == null) {
            log.warn("[credentials] Key Manager public key unavailable from /oauth2/jwks — jwt creds may be skipped.");
        }
        for (DiscoverySnapshot app : apps) {
            String appId = app.getSourceId();
            if (appId == null) continue;
            List<Map<String, String>> keys = wso2BundleClient.fetchApplicationOauthKeys(devToken, creds, appId);
            if (keys.isEmpty()) continue;
            List<CredentialReader.AppCredential> appCreds = new ArrayList<>();
            for (Map<String, String> k : keys) {
                appCreds.add(CredentialReader.AppCredential.builder()
                        .applicationId(appId).applicationName(app.getSourceName())
                        .keyType(k.get("keyType")).keyManager(k.get("keyManager"))
                        .consumerKey(k.get("consumerKey")).keyManagerPublicKeyPem(kmPem)
                        .build());
            }
            out.put(appId, appCreds);
        }
        log.info("[credentials] live-fetched OAuth2 keys for {}/{} application(s)", out.size(), apps.size());
        return out;
    }

    private static MigrationReport.Warning warn(String type, DiscoverySnapshot s, String code, String msg) {
        return MigrationReport.Warning.builder()
                .resourceType(type)
                .wso2SourceId(s.getSourceId())
                .wso2SourceName(s.getSourceName())
                .code(code).message(msg)
                .build();
    }

    private static MigrationReport.Warning scopeManualWarning(DiscoverySnapshot s) {
        String name = StringUtils.hasText(s.getSourceName()) ? s.getSourceName() : mapField(s.getPayload(), "name");
        String displayName = mapField(s.getPayload(), "displayName");
        List<String> bindings = stringListField(s.getPayload(), "bindings");
        StringBuilder msg = new StringBuilder();
        msg.append("Scope '").append(StringUtils.hasText(name) ? name : s.getSourceId()).append("'");
        if (StringUtils.hasText(displayName)) {
            msg.append(" (").append(displayName).append(")");
        }
        if (!bindings.isEmpty()) {
            msg.append(" has WSO2 role bindings ").append(bindings).append(".");
        } else {
            msg.append(" has no discovered WSO2 role bindings.");
        }
        msg.append(" ").append(SCOPE_MANUAL_ACTION_MESSAGE);
        return warn("scopes", s, "SCOPE_MANUAL_ACTION_REQUIRED", msg.toString());
    }

    /** Infer the Synapse flow a sequence runs in from its name (in / out / fault). */
    private static String flowOf(String sequenceName) {
        if (sequenceName == null) return "in";
        String n = sequenceName.toLowerCase();
        if (n.contains("fault")) return "fault";
        if (n.contains("out")) return "out";
        return "in";
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

    private static boolean hasConsumerResources(List<String> resourceTypes) {
        return resourceTypes != null
                && (resourceTypes.contains("applications") || resourceTypes.contains("subscriptions"));
    }

    private static String mapField(Map<String, Object> root, String key) {
        if (root == null) return null;
        Object v = root.get(key);
        return v == null ? null : v.toString();
    }

    private static List<String> stringListField(Map<String, Object> root, String key) {
        if (root == null) return List.of();
        Object v = root.get(key);
        if (v instanceof Collection<?> c) {
            List<String> out = new ArrayList<>(c.size());
            for (Object item : c) {
                if (item != null && StringUtils.hasText(item.toString())) {
                    out.add(item.toString());
                }
            }
            return out;
        }
        return List.of();
    }
}
