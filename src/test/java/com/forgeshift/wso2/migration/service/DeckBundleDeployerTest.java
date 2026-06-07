package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.deck.BundleBuilder;
import com.forgeshift.wso2.migration.deck.BundleResult;
import com.forgeshift.wso2.migration.deck.DeckYamlBuilder;
import com.forgeshift.wso2.migration.deck.GitPublisher;
import com.forgeshift.wso2.migration.deck.GitPushResult;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeckBundleDeployerTest {

    /**
     * Auto-commit must push the UNZIPPED files (kong/<env>/*.yaml + workflow + README)
     * to the Kong-config repo — that commit to the repo's default branch is what fires
     * the deck pipeline. It must NOT use pushBundle() (which only archives the zip and
     * never triggers a pipeline). The git outcome must flow back onto the BundleResult
     * so the MigrationReport carries non-null gitRepo/gitCommitSha/gitCommitUrl.
     */
    @Test
    void autoCommitPushesUnzippedFilesToKongConfigRepoNotTheZip() {
        DeckYamlBuilder yaml = mock(DeckYamlBuilder.class);
        BundleBuilder bundleBuilder = mock(BundleBuilder.class);
        GitPublisher git = mock(GitPublisher.class);
        MigrationProperties props = new MigrationProperties();
        props.getDeck().getGit().setEnabled(true);

        DeckBundleDeployer deployer = new DeckBundleDeployer(yaml, bundleBuilder, git, props);

        when(yaml.buildFiles(any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("kong/dev/api-foo.yaml", "services: []"));

        Map<String, String> repoFiles = new LinkedHashMap<>();
        repoFiles.put("kong/dev/api-foo.yaml", "services: []");
        repoFiles.put(".github/workflows/deploy-dev.yml", "name: Deploy Kong (dev)");
        repoFiles.put("README.md", "# bundle");
        BundleResult built = BundleResult.builder()
                .jobId("job1").env("dev").controlPlaneName("probestack-kong")
                .kongConfigPath("kong/dev").downloadUrl("http://x/migrations/job1/bundle")
                .bundlePath("/tmp/job1/bundle.zip")
                .repoFileContents(repoFiles)
                .createOnlyPaths(List.of(".github/workflows/deploy-dev.yml", "README.md"))
                .build();
        when(bundleBuilder.build(any(), any(), any(), any(), any(), any())).thenReturn(built);

        when(git.pushFiles(any(), any(), any(), any(), any())).thenReturn(GitPushResult.builder()
                .pushed(true).repo("ProbeStack1/probestack1-kong-config").branch("main")
                .commitSha("abc123")
                .commitUrl("https://github.com/ProbeStack1/probestack1-kong-config/commit/abc123")
                .filesPushed(3).build());

        MigrationJob job = MigrationJob.builder()
                .id("job1").companyName("probestack1").wso2Tenant("carbon.super").build();
        KongKonnectCredentials creds = KongKonnectCredentials.builder()
                .controlPlaneName("probestack-kong")
                .konnectBaseUrl("https://us.api.konghq.com")
                .konnectAccessToken("kpat_test")
                .build();

        BundleResult out = deployer.buildBundle(job, creds,
                List.of(), List.of(), List.of(), List.of(), List.of());

        // Commits the real files for company 'probestack1' (so git_profiles is resolved and
        // the pipeline fires); never the zip archive.
        verify(git).pushFiles(eq(creds), eq("probestack1"), eq(repoFiles),
                argThat(s -> s.contains(".github/workflows/deploy-dev.yml") && s.contains("README.md")),
                anyString());
        verify(git, never()).pushBundle(any(), any(), any(), any());

        // Git outcome flows back onto the bundle → report shows non-null git fields.
        assertEquals("ProbeStack1/probestack1-kong-config", out.getGitRepo());
        assertEquals("main", out.getGitBranch());
        assertEquals("abc123", out.getGitCommitSha());
        assertEquals("https://github.com/ProbeStack1/probestack1-kong-config/commit/abc123",
                out.getGitCommitUrl());
    }
}
