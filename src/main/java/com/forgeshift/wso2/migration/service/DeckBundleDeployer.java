package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.client.KonnectCustomPluginClient;
import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.translator.CustomScopeRolePluginBuilder;
import com.forgeshift.wso2.migration.translator.HmacSignerPluginBuilder;
import com.forgeshift.wso2.migration.translator.JsonXmlPluginBuilder;
import com.forgeshift.wso2.migration.translator.JwtClaimHeaderPluginBuilder;
import com.forgeshift.wso2.migration.translator.RiskScoringPluginBuilder;
import com.forgeshift.wso2.migration.translator.SecureGatewayPluginBuilder;
import com.forgeshift.wso2.migration.deck.BundleBuilder;
import com.forgeshift.wso2.migration.deck.BundleResult;
import com.forgeshift.wso2.migration.deck.DeckYamlBuilder;
import com.forgeshift.wso2.migration.deck.GitPublisher;
import com.forgeshift.wso2.migration.deck.GitPushResult;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import com.forgeshift.wso2.migration.translator.TranslatedApi;
import com.forgeshift.wso2.migration.translator.TranslatedApiProduct;
import com.forgeshift.wso2.migration.translator.TranslatedCertificate;
import com.forgeshift.wso2.migration.translator.TranslatedConsumer;
import com.forgeshift.wso2.migration.translator.TranslatedMediationPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * decK-bundle replacement for {@link KongDeployer}. Instead of writing entities to Konnect
 * over REST, it serializes the translated plan to per-API {@code kong.yaml} files, packages a
 * downloadable bundle, and (when enabled) auto-commits the files to the Kong-config git repo
 * so the GitHub Actions pipeline applies them with {@code deck gateway apply}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeckBundleDeployer {

    private final DeckYamlBuilder yamlBuilder;
    private final BundleBuilder bundleBuilder;
    private final GitPublisher gitPublisher;
    private final MigrationProperties props;
    private final KonnectCustomPluginClient customPluginClient;
    /** Stateless + dependency-free → instantiated directly (not a constructor arg). */
    private final CustomScopeRolePluginBuilder scopeBuilder = new CustomScopeRolePluginBuilder();

    /** Single combined kong.yaml (used by dry-run / preview). */
    public String buildYaml(List<TranslatedApi> apis, List<TranslatedConsumer> consumers,
                            List<TranslatedCertificate> certs, List<TranslatedApiProduct> products,
                            List<TranslatedMediationPolicy> mediations) {
        return yamlBuilder.build(apis, consumers, certs, products, mediations);
    }

    /** Build the per-API files + bundle, and auto-commit to git when enabled. */
    public BundleResult buildBundle(MigrationJob job, KongKonnectCredentials creds,
                                    List<TranslatedApi> apis, List<TranslatedConsumer> consumers,
                                    List<TranslatedCertificate> certs, List<TranslatedApiProduct> products,
                                    List<TranslatedMediationPolicy> mediations) {
        String env = props.getDeck().getEnvName();
        // Emit NO entity ids (see forgeshift.migration.deck.emit-entity-ids). `deck gateway apply`
        // then matches an already-migrated entity by its unique NAME and UPDATES it (adopting the
        // real Kong id). Injecting the real id instead makes apply rebuild a state that already
        // holds the same-id current entity → "building state: ... entity already exists".
        var kongFiles = yamlBuilder.buildFiles(env, apis, consumers, certs, products, mediations);

        String cpName = creds != null && StringUtils.hasText(creds.getControlPlaneName())
                ? creds.getControlPlaneName()
                : props.getDeck().getControlPlaneNameFallback();
        String addr = creds != null && StringUtils.hasText(creds.getKonnectBaseUrl())
                ? creds.getKonnectBaseUrl()
                : props.getDeck().getKonnectAddr();

        String token = creds == null ? null : creds.getKonnectAccessToken();
        BundleResult bundle = bundleBuilder.build(job.getId(), env, cpName, addr, token, kongFiles);

        // Custom-plugin ASSETS (handler.lua + schema.lua) can't travel in the decK bundle — register
        // them on the control plane now, BEFORE the pipeline runs deck validate/apply (which would
        // otherwise fail "schema not found"). Inert unless customPlugins.enabled + a non-dry-run.
        registerCustomPlugins(job, creds, apis, mediations);

        if (props.getDeck().getGit().isEnabled()) {
            // Commit the UNZIPPED files (workflow + README + kong/<env>/*.yaml) to the Kong-config
            // repo, then EXPLICITLY dispatch the pipeline (workflow_dispatch) passing this job's
            // result_callback_url — so the run reports back to THIS migration. Never dispatch a
            // dry-run (that would apply to Kong). README stays create-only; the workflow + kong
            // files are committed only when their content actually changed (no churn).
            Set<String> createOnly = bundle.getCreateOnlyPaths() == null
                    ? Set.of() : new LinkedHashSet<>(bundle.getCreateOnlyPaths());
            String dispatchWf = job.isDryRun() ? null : bundle.getWorkflowFile();
            Map<String, String> dispatchInputs = job.isDryRun() ? null
                    : Map.of("result_callback_url",
                            bundle.getCallbackUrl() == null ? "" : bundle.getCallbackUrl());
            GitPushResult push = gitPublisher.pushFiles(creds, job.getCompanyName(),
                    bundle.getRepoFileContents(), createOnly, commitMessage(job),
                    dispatchWf, dispatchInputs);
            bundle.setGitRepo(push.getRepo());
            bundle.setGitBranch(push.getBranch());
            bundle.setGitCommitSha(push.getCommitSha());
            bundle.setGitCommitUrl(push.getCommitUrl());
            bundle.setGitFilesPushed(push.getFilesPushed());
            bundle.setGitError(push.getError());
            bundle.setDispatched(push.isDispatched());
            if (push.getError() != null) {
                log.warn("Auto-commit/dispatch issue for job {}: {}", job.getId(), push.getError());
            }
        }

        log.info("decK bundle ready for job {} ({} -> {}{})",
                job.getId(), bundle.getKongConfigPath(), bundle.getDownloadUrl(),
                bundle.getGitCommitSha() != null ? ", committed " + bundle.getGitCommitSha() : "");
        return bundle;
    }

    /**
     * Upload the distinct custom-plugin assets this bundle references, BEFORE the pipeline applies it.
     * Idempotent (adopt-on-conflict in the client) and never throws: an upload failure is logged and
     * the corresponding gap-warning simply remains in the report (the apply will fail validation for
     * that plugin, correctly signalling the gap is not yet closed). Inert when the feature is off or
     * on a dry run — so the default behaviour is unchanged.
     */
    private void registerCustomPlugins(MigrationJob job, KongKonnectCredentials creds,
                                       List<TranslatedApi> apis, List<TranslatedMediationPolicy> mediations) {
        if (!props.getCustomPlugins().isEnabled() || job.isDryRun()) {
            return;
        }
        Map<String, CustomPluginArtifact> assets = new LinkedHashMap<>();
        // Service-attached reusable custom plugins (scope enforcer, JWT claim→header): upload each one's
        // asset when any API in this run references it by name.
        Map<String, CustomPluginArtifact> serviceAssets = new LinkedHashMap<>();
        serviceAssets.put(CustomScopeRolePluginBuilder.PLUGIN_NAME, scopeBuilder.asset());
        serviceAssets.put(JwtClaimHeaderPluginBuilder.PLUGIN_NAME, JwtClaimHeaderPluginBuilder.asset());
        // Catalog plugins emitted from custom operation policies (OperationPolicyTranslator): the
        // handler/schema are fixed assets, the per-policy config travels on the plugin instance.
        serviceAssets.put(RiskScoringPluginBuilder.PLUGIN_NAME, RiskScoringPluginBuilder.asset());
        serviceAssets.put(HmacSignerPluginBuilder.PLUGIN_NAME, HmacSignerPluginBuilder.asset());
        serviceAssets.put(JsonXmlPluginBuilder.PLUGIN_NAME, JsonXmlPluginBuilder.asset());
        serviceAssets.put(SecureGatewayPluginBuilder.PLUGIN_NAME, SecureGatewayPluginBuilder.asset());
        serviceAssets.put(JsonXmlPluginBuilder.PLUGIN_NAME, JsonXmlPluginBuilder.asset());
        if (apis != null) {
            for (Map.Entry<String, CustomPluginArtifact> e : serviceAssets.entrySet()) {
                if (apis.stream().anyMatch(a -> referencesPlugin(a, e.getKey()))) {
                    assets.put(e.getKey(), e.getValue());
                }
            }
        }
        // Target 2 (later): AI-generated per-policy assets carried on the translated mediation.
        if (mediations != null) {
            for (TranslatedMediationPolicy m : mediations) {
                if (m.getCustomPlugin() != null && m.getCustomPlugin().isComplete()) {
                    assets.put(m.getCustomPlugin().getPluginName(), m.getCustomPlugin());
                }
            }
        }
        if (assets.isEmpty()) {
            return;
        }
        for (CustomPluginArtifact a : assets.values()) {
            KonnectCustomPluginClient.Result r = customPluginClient.upsert(creds, a);
            if (r.isOk()) {
                log.info("Custom plugin '{}' {} on CP for job {} (id={})",
                        a.getPluginName(), r.getAction(), job.getId(), r.getPluginId());
            } else {
                log.warn("Custom plugin '{}' upload FAILED for job {}: {} — deck apply will reject any "
                        + "instance referencing it until it is registered.", a.getPluginName(), job.getId(), r.getError());
            }
        }
    }

    /** True when this API attaches a plugin (service- or route-scoped) with the given name. */
    private static boolean referencesPlugin(TranslatedApi a, String name) {
        if (a == null) return false;
        if (a.getServicePlugins() != null) {
            for (KongPlugin p : a.getServicePlugins()) {
                if (p != null && name.equals(p.getName())) return true;
            }
        }
        if (a.getRoutePlugins() != null) {
            for (List<KongPlugin> rps : a.getRoutePlugins().values()) {
                if (rps == null) continue;
                for (KongPlugin p : rps) {
                    if (p != null && name.equals(p.getName())) return true;
                }
            }
        }
        return false;
    }

    private String commitMessage(MigrationJob job) {
        return props.getDeck().getGit().getCommitMessageTemplate()
                .replace("{jobId}", nz(job.getId()))
                .replace("{company}", nz(job.getCompanyName()))
                .replace("{tenant}", nz(job.getWso2Tenant()));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
