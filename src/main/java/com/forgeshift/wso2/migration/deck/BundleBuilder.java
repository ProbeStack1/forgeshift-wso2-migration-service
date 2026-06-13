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
        return build(jobId, env, controlPlaneName, konnectAddr, null, kongFiles);
    }

    public BundleResult build(String jobId, String env, String controlPlaneName,
                              String konnectAddr, String konnectAccessToken,
                              Map<String, String> kongFiles) {
        MigrationProperties.Deck d = props.getDeck();
        // Apply path = the deepest directory that holds every generated kong file. A per-API
        // (single-API) run lands under kong/<env>/<api>/ → apply just that; a flat run → kong/<env>.
        String configDir = commonDir(kongFiles, d.getKongConfigDirTemplate().replace("{env}", env));
        String wfPath = workflowPath(env);

        // Full repo layout: the (static, dispatch-only) workflow + README FIRST, then the per-API
        // kong files. Workflow-first means the dispatch-only caller is in place before any kong
        // file is committed, so a lingering old push-triggered workflow can't fire a stray run.
        Map<String, String> repoFiles = new LinkedHashMap<>();
        repoFiles.put(wfPath, buildWorkflow(env, configDir, controlPlaneName, konnectAddr,
                konnectAccessToken));
        repoFiles.put("README.md", buildReadme(jobId, env, controlPlaneName, configDir));
        repoFiles.putAll(kongFiles);

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
        // Per-migration callback URL — passed to the pipeline as a workflow_dispatch INPUT (not
        // baked into the shared workflow file), so every job gets its OWN callback target.
        String callbackUrl = StringUtils.hasText(d.getCallbackBaseUrl())
                ? d.getCallbackBaseUrl() + "/migrations/" + jobId + "/deck-result"
                : null;

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
                .createOnlyPaths(List.of("README.md"))
                .workflowFile("deploy-" + env + ".yml")
                .callbackUrl(callbackUrl)
                .build();
    }

    /**
     * decK apply path for this run = the deepest directory containing every generated kong file.
     * Flat layout ({@code kong/<env>/api-*.yaml}) → {@code kong/<env>}; a per-API single migration
     * ({@code kong/<env>/<api>/*.yaml}) → {@code kong/<env>/<api>}. Falls back when no files exist.
     */
    static String commonDir(Map<String, String> kongFiles, String fallback) {
        if (kongFiles == null || kongFiles.isEmpty()) {
            return fallback;
        }
        String[] prefix = null;
        for (String path : kongFiles.keySet()) {
            String norm = path.replace('\\', '/');
            int slash = norm.lastIndexOf('/');
            String[] segs = (slash < 0 ? "" : norm.substring(0, slash)).split("/");
            if (prefix == null) {
                prefix = segs;
                continue;
            }
            int i = 0;
            int n = Math.min(prefix.length, segs.length);
            while (i < n && prefix[i].equals(segs[i])) {
                i++;
            }
            prefix = java.util.Arrays.copyOf(prefix, i);
        }
        String dir = String.join("/", prefix);
        return dir.isEmpty() ? fallback : dir;
    }

    private String buildWorkflow(String env, String configDir,
                                 String controlPlaneName, String konnectAddr,
                                 String konnectAccessToken) {
        MigrationProperties.Deck d = props.getDeck();
        // STATIC, dispatch-only caller. The per-migration callback URL is NOT baked in — it is
        // supplied at dispatch time as the `result_callback_url` input, so the same shared file
        // serves every job and each run reports back to the RIGHT migration. No `push` trigger,
        // which removes the stray-run and stale-shared-callback problems of the old create-only file.
        List<String> lines = new ArrayList<>(List.of(
                "name: Deploy Kong (" + env + ")",
                "# Dispatched by forgeshift-wso2-migration-service via the GitHub API. The migration",
                "# passes result_callback_url so the pipeline POSTs its apply result back to the",
                "# matching migration job. Do NOT add a push trigger — that reintroduces stray runs.",
                "on:",
                "  workflow_dispatch:",
                "    inputs:",
                "      result_callback_url:",
                "        description: 'URL the pipeline POSTs its apply result to (per migration).'",
                "        required: false",
                "        default: ''",
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
                "      deck_version: \"" + d.getDeckVersion() + "\"",
                "      konnect_addr: " + konnectAddr,
                "      validate_only: false",
                "      result_callback_url: ${{ inputs.result_callback_url }}"));
        // Prefer the resolved profile token for the current test flow. Secret/variable
        // references remain as fallback for environments that do not inline credentials.
        String tokenRef = StringUtils.hasText(konnectAccessToken)
                ? "'" + konnectAccessToken.replace("'", "''") + "'"
                : d.isKonnectTokenViaVariable()
                ? "${{ vars." + d.getKonnectSecretName() + " }}"
                : "${{ secrets." + d.getKonnectSecretName() + " }}";
        lines.add("    secrets:");
        lines.add("      konnect_token: " + tokenRef);
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
