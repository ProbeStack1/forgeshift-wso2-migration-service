package com.forgeshift.wso2.migration.deck;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packages the generated decK files into a bundle: the per-API {@code kong/<env>/*.yaml}
 * files + a ready-to-use GitHub Actions workflow (calling the reusable decK pipeline with
 * {@code deck_mode: apply} and {@code kong_config_path} pointed at the directory) + a README,
 * all zipped. The same file set is what {@link GitPublisher} auto-commits.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BundleBuilder {

    private final MigrationProperties props;

    public static String workflowPath(String env) {
        return ".github/workflows/deploy-" + env + ".yml";
    }

    public BundleResult build(String jobId, String env, String controlPlaneName,
                              String konnectAddr, Map<String, String> kongFiles) {
        MigrationProperties.Deck d = props.getDeck();
        String configDir = d.getKongConfigDirTemplate().replace("{env}", env);
        String wfPath = workflowPath(env);

        // Full repo layout: per-API kong files + the pipeline workflow + README.
        Map<String, String> repoFiles = new LinkedHashMap<>(kongFiles);
        repoFiles.put(wfPath, buildWorkflow(jobId, env, configDir, controlPlaneName, konnectAddr));
        repoFiles.put("README.md", buildReadme(jobId, env, controlPlaneName, configDir));

        Path bundlePath;
        try {
            Path dir = Path.of(d.getBundleDir(), jobId);
            Files.createDirectories(dir);
            bundlePath = dir.resolve("bundle.zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(bundlePath))) {
                for (Map.Entry<String, String> e : repoFiles.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write Kong bundle for job " + jobId, ex);
        }

        String base = d.getDownloadBaseUrl() == null ? "" : d.getDownloadBaseUrl();
        String downloadUrl = base + "/migrations/" + jobId + "/bundle";

        log.info("Built decK bundle for job {} at {} ({} files)", jobId, bundlePath, repoFiles.size());
        return BundleResult.builder()
                .jobId(jobId)
                .env(env)
                .controlPlaneName(controlPlaneName)
                .konnectAddr(konnectAddr)
                .kongConfigPath(configDir)
                .bundlePath(bundlePath.toString())
                .fileName("bundle.zip")
                .downloadUrl(downloadUrl)
                .files(List.copyOf(repoFiles.keySet()))
                .repoFileContents(repoFiles)
                .createOnlyPaths(List.of(wfPath, "README.md"))
                .build();
    }

    private String buildWorkflow(String jobId, String env, String configDir,
                                 String controlPlaneName, String konnectAddr) {
        MigrationProperties.Deck d = props.getDeck();
        List<String> lines = new ArrayList<>(List.of(
                "name: Deploy Kong (" + env + ")",
                "on:",
                "  workflow_dispatch:",
                "  push:",
                "    branches: [ main ]",
                "jobs:",
                "  deploy:",
                "    uses: " + d.getPipelineTemplateRef(),
                "    permissions:",
                "      contents: read",
                "      id-token: write",
                "    with:",
                "      environment: " + env,
                "      kong_config_path: " + configDir,
                "      control_plane_name: " + controlPlaneName,
                "      deck_mode: " + d.getDeckMode(),
                "      konnect_addr: " + konnectAddr,
                "      validate_only: false"));
        if (StringUtils.hasText(d.getCallbackBaseUrl())) {
            lines.add("      result_callback_url: " + d.getCallbackBaseUrl()
                    + "/migrations/" + jobId + "/deck-result");
        }
        lines.add("    secrets:");
        lines.add("      konnect_token: ${{ secrets." + d.getKonnectSecretName() + " }}");
        lines.add("");
        return String.join("\n", lines);
    }

    private String buildReadme(String jobId, String env, String controlPlaneName, String configDir) {
        MigrationProperties.Deck d = props.getDeck();
        return String.join("\n",
                "# Kong decK bundle — migration job " + jobId,
                "",
                "Generated by forgeshift-wso2-migration-service.",
                "",
                "## Layout",
                "- `" + configDir + "/api-<name>.yaml` — one file per migrated API (incremental-safe)",
                "- `" + configDir + "/consumers.yaml`, `ca-certificates.yaml`, `api-products.yaml` — grouped resources",
                "- `.github/workflows/deploy-" + env + ".yml` — runs `deck gateway " + d.getDeckMode() + "` over the whole `" + configDir + "` directory",
                "",
                "## How to deploy",
                "1. Commit these files to your Kong-config repository and push to `main`.",
                "2. The workflow applies the directory to control plane **" + controlPlaneName + "**",
                "   with `deck gateway " + d.getDeckMode() + "` (decK merges every file; never deletes).",
                "3. Add a repository secret named `" + d.getKonnectSecretName() + "` holding your Konnect PAT.",
                "");
    }
}
