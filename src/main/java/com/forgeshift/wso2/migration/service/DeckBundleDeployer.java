package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.config.MigrationProperties;
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

import java.util.LinkedHashSet;
import java.util.List;
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
        var kongFiles = yamlBuilder.buildFiles(env, apis, consumers, certs, products, mediations);

        String cpName = creds != null && StringUtils.hasText(creds.getControlPlaneName())
                ? creds.getControlPlaneName()
                : props.getDeck().getControlPlaneNameFallback();
        String addr = creds != null && StringUtils.hasText(creds.getKonnectBaseUrl())
                ? creds.getKonnectBaseUrl()
                : props.getDeck().getKonnectAddr();

        BundleResult bundle = bundleBuilder.build(job.getId(), env, cpName, addr, kongFiles);

        if (props.getDeck().getGit().isEnabled()) {
            // Commit the UNZIPPED files (kong/<env>/*.yaml + workflow + README) to the
            // Kong-config repo so the GitHub Actions pipeline fires on push to its
            // default branch. (pushBundle() only archives the zip and never triggers a
            // pipeline.) Workflow + README are create-only, so re-migrating an API
            // rewrites just that API's file and leaves the rest — and Kong — untouched.
            Set<String> createOnly = bundle.getCreateOnlyPaths() == null
                    ? Set.of() : new LinkedHashSet<>(bundle.getCreateOnlyPaths());
            GitPushResult push = gitPublisher.pushFiles(creds, job.getCompanyName(),
                    bundle.getRepoFileContents(), createOnly, commitMessage(job));
            bundle.setGitRepo(push.getRepo());
            bundle.setGitBranch(push.getBranch());
            bundle.setGitCommitSha(push.getCommitSha());
            bundle.setGitCommitUrl(push.getCommitUrl());
            bundle.setGitFilesPushed(push.getFilesPushed());
            bundle.setGitError(push.getError());
            if (!push.isPushed() && push.getError() != null) {
                log.warn("Auto-commit not completed for job {}: {}", job.getId(), push.getError());
            }
        }

        log.info("decK bundle ready for job {} ({} -> {}{})",
                job.getId(), bundle.getKongConfigPath(), bundle.getDownloadUrl(),
                bundle.getGitCommitSha() != null ? ", committed " + bundle.getGitCommitSha() : "");
        return bundle;
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
