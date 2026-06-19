package com.forgeshift.wso2.migration.deck;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.reader.GitProfileCredentials;
import com.forgeshift.wso2.migration.reader.GitProfileReader;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-commits the generated decK files straight to the Kong-config git repo via the
 * GitHub Contents API ({@code PUT /repos/{owner}/{repo}/contents/{path}}). Each PUT is its
 * own commit and only touches that one path, so existing files are preserved — which makes
 * <b>single / incremental</b> migration safe: re-migrating one API rewrites only that API's
 * file and leaves every other file (and Kong entity) alone.
 *
 * <p>Repo / branch / token are taken from the Kong Konnect profile when present, else from
 * {@code forgeshift.migration.deck.git.*} config. When auto-commit is disabled or no
 * repo/token is configured, the push is skipped (the downloadable bundle is still produced).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitPublisher {

    private final MigrationProperties props;
    private final GitProfileReader gitProfileReader;

    public GitPushResult pushBundle(KongKonnectCredentials creds, String companyName, Path bundlePath,
                                    String message) {
        MigrationProperties.Deck.Git cfg = props.getDeck().getGit();
        if (!cfg.isEnabled()) {
            return skip("auto-commit disabled (forgeshift.migration.deck.git.enabled=false)");
        }

        // Git creds come from the git_profiles collection (by companyName); fall back to config.
        GitProfileCredentials git = gitProfileReader.resolve(companyName);
        String token = firstNonBlank(git.getPat(),
                firstNonBlank(creds == null ? null : creds.getGitToken(), cfg.getToken()));
        String owner = firstNonBlank(git.getOrganization(), ownerFromRepo(firstNonBlank(git.getRepo(), cfg.getRepo())));
        String branch = firstNonBlank(creds == null ? null : creds.getGitBranch(),
                firstNonBlank(git.getBranch(), cfg.getBranch()));
        if (!StringUtils.hasText(token) || !StringUtils.hasText(owner)) {
            return skip("no git organization/token (git_profiles for company '" + companyName
                    + "' or deck.git fallback)");
        }

        String repoName = safeRepoName(companyName) + "-wso2-migration-bundles";
        String repo = owner + "/" + repoName;
        WebClient gh = githubClient(cfg, token);

        try {
            ensureRepo(gh, owner, repoName);
            ensureBranch(gh, owner, repoName, branch);
            String path = "bundles/" + safeRepoName(companyName) + "/" + bundlePath.getFileName();
            String existingSha = getSha(gh, owner, repoName, path, branch);
            Map<String, Object> commit = putBinaryFile(gh, owner, repoName, path, branch,
                    Files.readAllBytes(bundlePath), existingSha, message, cfg);
            String lastSha = null;
            String lastUrl = null;
            if (commit != null && commit.get("commit") instanceof Map<?, ?> cm) {
                lastSha = str(cm.get("sha"));
                lastUrl = str(cm.get("html_url"));
            }
            return GitPushResult.builder()
                    .pushed(true)
                    .repo(repo)
                    .branch(branch)
                    .commitSha(lastSha)
                    .commitUrl(lastUrl)
                    .filesPushed(1)
                    .build();
        } catch (WebClientResponseException ex) {
            int code = ex.getStatusCode().value();
            log.error("Git bundle upload failed ({} {}): {}", code, repo, ex.getResponseBodyAsString());
            return GitPushResult.builder().pushed(false).repo(repo).branch(branch)
                    .filesPushed(0).error("GitHub " + code + ": " + ex.getStatusText()).build();
        } catch (IOException ex) {
            return GitPushResult.builder().pushed(false).repo(repo).branch(branch)
                    .filesPushed(0).error(ex.getMessage()).build();
        } catch (RuntimeException ex) {
            log.error("Git bundle upload failed for {}: {}", repo, ex.getMessage());
            return GitPushResult.builder().pushed(false).repo(repo).branch(branch)
                    .filesPushed(0).error(ex.getMessage()).build();
        }
    }

    /**
     * Auto-commit the UNZIPPED bundle files (per-API {@code kong/<env>/*.yaml} + the
     * {@code .github/workflows/deploy-<env>.yml} workflow + README) to the company's
     * Kong-config repo. Because the committed workflow lands at its real path on the
     * repo's branch, the push <b>triggers the deck pipeline</b> (unlike {@link #pushBundle}
     * which only archives the zip).
     *
     * <p>Credentials/target are resolved in this order:
     * <ul>
     *   <li><b>org + PAT</b> from the {@code git_profiles} row for the company (fallback:
     *       the Kong Konnect profile's git fields, then {@code deck.git.*} config);</li>
     *   <li><b>repo</b> — an explicit {@code owner/repo} (Kong Konnect profile {@code gitRepo},
     *       {@code git_profiles} repo, or {@code deck.git.repo}) is used as-is; otherwise it's
     *       derived as {@code <organization>/<company>-kong-config}. The repo <b>must already
     *       exist</b> — this only pushes to it and never creates a repo or branch.</li>
     * </ul>
     * Workflow + README are create-only, so re-migrating one API rewrites just that API's
     * file and leaves the rest — and Kong — untouched.
     */
    public GitPushResult pushFiles(KongKonnectCredentials creds, String companyName,
                                   Map<String, String> files, Set<String> createOnlyPaths, String message,
                                   String dispatchWorkflowFile, Map<String, String> dispatchInputs) {
        MigrationProperties.Deck.Git cfg = props.getDeck().getGit();
        if (!cfg.isEnabled()) {
            return skip("auto-commit disabled (forgeshift.migration.deck.git.enabled=false)");
        }

        // Org + PAT come from git_profiles (by companyName); fall back to the Konnect
        // profile's git fields, then deck.git config.
        GitProfileCredentials git = gitProfileReader.resolve(companyName);
        String token = firstNonBlank(git.getPat(),
                firstNonBlank(creds == null ? null : creds.getGitToken(), cfg.getToken()));
        String branch = firstNonBlank(creds == null ? null : creds.getGitBranch(),
                firstNonBlank(git.getBranch(), cfg.getBranch()));

        // Explicit owner/repo (Konnect profile gitRepo, git_profiles repo, or deck.git.repo)
        // wins; else derive <organization>/<company>-kong-config. The repo MUST already exist —
        // we only push to it, never create it.
        String explicitRepo = firstNonBlank(creds == null ? null : creds.getGitRepo(),
                firstNonBlank(git.getRepo(), cfg.getRepo()));
        String owner;
        String repoName;
        if (StringUtils.hasText(explicitRepo) && explicitRepo.indexOf('/') > 0
                && explicitRepo.indexOf('/') < explicitRepo.length() - 1) {
            owner = explicitRepo.substring(0, explicitRepo.indexOf('/'));
            repoName = explicitRepo.substring(explicitRepo.indexOf('/') + 1);
        } else {
            owner = firstNonBlank(git.getOrganization(), creds == null ? null : creds.getGitOrganization());
            repoName = safeRepoName(companyName) + "-kong-config";
        }
        if (!StringUtils.hasText(token) || !StringUtils.hasText(owner)) {
            return skip("no git organization/token (git_profiles for company '" + companyName
                    + "' or deck.git fallback)");
        }
        String repo = owner + "/" + repoName;
        WebClient gh = githubClient(cfg, token);

        int pushed = 0;
        String lastSha = null;
        String lastUrl = null;
        boolean dispatched = false;
        String dispatchError = null;
        try {
            // Only PUSH to an existing repo — never create the repo/branch (creating in an org
            // needs elevated perms and was the source of the 403). A missing repo/branch now
            // surfaces as a clear error on the file PUT instead.
            // Set the Konnect token as a plaintext Actions variable BEFORE the commits so it
            // exists when the run dispatches (test mode only; no-op otherwise).
            maybeSetKonnectVariable(gh, owner, repoName, creds);
            for (Map.Entry<String, String> e : files.entrySet()) {
                String path = e.getKey();
                String newContent = e.getValue();
                Existing existing = getExisting(gh, owner, repoName, path, branch);
                if (existing != null && createOnlyPaths != null && createOnlyPaths.contains(path)) {
                    log.debug("Skipping existing create-only file {}", path);
                    continue;   // README written once, not re-committed every run
                }
                if (existing != null && newContent.equals(existing.content())) {
                    log.debug("Unchanged, skipping {}", path);
                    continue;   // avoid churn — identical content, no commit needed
                }
                Map<String, Object> commit = putFile(gh, owner, repoName, path, branch,
                        newContent, existing == null ? null : existing.sha(), message, cfg);
                pushed++;
                if (commit != null && commit.get("commit") instanceof Map<?, ?> cm) {
                    lastSha = str(cm.get("sha"));
                    lastUrl = str(cm.get("html_url"));
                }
            }

            // Reconcile files left by a previous bundle but no longer generated, so the pipeline applies
            // ONLY the current bundle. "Reconcile" = archive into deck.git.archive-dir (default) or
            // hard-delete (archive-stale=false). Either way the file leaves the apply path, so an entity
            // the current bundle dropped (or an API you removed from the control plane) is not re-created
            // by `deck gateway apply` (which reads the whole kong/<env> dir and never deletes).
            int removed = 0;
            // Per-API subdirs (single-API layout): reconcile any non-current file in the API's own dir.
            if (props.getDeck().isPerApiDir()) {
                removed += reconcileDirs(perApiDirs(files.keySet()), gh, owner, repoName, branch,
                        files.keySet(), createOnlyPaths, false, false, message, cfg);
            }
            // Flat env root (multi-API layout): reconcile stale migrator-generated YAML under kong/<env>.
            // With reconcile-recursive (default) this also descends into per-API subdirs, so leftovers
            // from an EARLIER single-API migration (kong/<env>/<old-slug>/*.yaml) — which `deck apply`
            // reads recursively and would otherwise re-create — are cleared too. YAML-only, non-current,
            // non-create-only; the workflow/README and other non-YAML files are never touched.
            if (props.getDeck().isReconcileEnvRoot()) {
                removed += reconcileDirs(envRootDirs(files.keySet()), gh, owner, repoName, branch,
                        files.keySet(), createOnlyPaths, true, props.getDeck().isReconcileRecursive(),
                        message, cfg);
            }
            if (removed > 0) {
                log.info("Reconciled {} stale bundle file(s) ({}) so the pipeline applies only the current bundle.",
                        removed, cfg.isArchiveStale() ? "archived" : "deleted");
            }

            // Trigger the pipeline EXPLICITLY (workflow_dispatch) so it picks up THIS migration's
            // result_callback_url — instead of relying on a push trigger baked into a shared,
            // possibly-stale workflow file. A dispatch failure is recorded but does not discard the
            // commit outcome above.
            if (StringUtils.hasText(dispatchWorkflowFile)) {
                try {
                    dispatchWorkflow(gh, owner, repoName, branch, dispatchWorkflowFile, dispatchInputs);
                    dispatched = true;
                } catch (Exception ex) {
                    dispatchError = "files committed but workflow dispatch failed: " + ex.getMessage();
                    log.error("Workflow dispatch failed for {} ({}): {}", repo, dispatchWorkflowFile, ex.getMessage());
                }
            }
        } catch (WebClientResponseException ex) {
            int code = ex.getStatusCode().value();
            String body = ex.getResponseBodyAsString();
            log.error("Git auto-commit failed ({} {}): {}", code, repo, body);
            return GitPushResult.builder().pushed(false).repo(repo).branch(branch)
                    .filesPushed(pushed)
                    .error("GitHub " + code + ": " + ex.getStatusText() + githubMessage(body)).build();
        } catch (Exception ex) {
            log.error("Git auto-commit failed for {}: {}", repo, ex.getMessage());
            return GitPushResult.builder().pushed(false).repo(repo).branch(branch)
                    .filesPushed(pushed).error(ex.getMessage()).build();
        }

        log.info("Auto-committed {} file(s) to {}@{} (last commit {}){}", pushed, repo, branch, lastSha,
                dispatched ? " + dispatched " + dispatchWorkflowFile : "");
        return GitPushResult.builder()
                // pushed/dispatched ⇒ there's a pipeline to await even if no file changed (re-run).
                .pushed(pushed > 0 || dispatched).repo(repo).branch(branch)
                .commitSha(lastSha).commitUrl(lastUrl).filesPushed(pushed)
                .dispatched(dispatched).error(dispatchError).build();
    }

    /**
     * TEST-ONLY: when {@code deck.konnect-token-via-variable=true}, upsert a plaintext GitHub
     * Actions <b>variable</b> (name = {@code deck.konnect-secret-name}) on the repo holding the
     * Konnect token, so the generated workflow can read it via {@code ${{ vars.NAME }}} without a
     * libsodium-encrypted secret. Best-effort — a failure here never fails the file push.
     * INSECURE: the value is visible in the repo's Actions settings; use a real secret in prod.
     */
    private void maybeSetKonnectVariable(WebClient gh, String owner, String repo,
                                         KongKonnectCredentials creds) {
        MigrationProperties.Deck d = props.getDeck();
        if (!d.isKonnectTokenViaVariable()) {
            return;
        }
        String token = creds == null ? null : creds.getKonnectAccessToken();
        if (!StringUtils.hasText(token)) {
            log.warn("konnect-token-via-variable=true but no Konnect token resolved — skipping variable on {}/{}",
                    owner, repo);
            return;
        }
        String name = d.getKonnectSecretName();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("value", token);
        try {
            gh.post().uri("/repos/{owner}/{repo}/actions/variables", owner, repo)
                    .bodyValue(body).retrieve().bodyToMono(Void.class).block();
            log.info("Set Actions variable {} on {}/{} (TEST mode — plaintext, NOT a secret)", name, owner, repo);
        } catch (WebClientResponseException.Conflict existing) {
            try {
                gh.patch().uri("/repos/{owner}/{repo}/actions/variables/{name}", owner, repo, name)
                        .bodyValue(body).retrieve().bodyToMono(Void.class).block();
                log.info("Updated Actions variable {} on {}/{} (TEST mode)", name, owner, repo);
            } catch (Exception ex) {
                log.warn("Could not update Actions variable {} on {}/{}: {}", name, owner, repo, ex.getMessage());
            }
        } catch (Exception ex) {
            log.warn("Could not set Actions variable {} on {}/{}: {}", name, owner, repo, ex.getMessage());
        }
    }

    private WebClient githubClient(MigrationProperties.Deck.Git cfg, String token) {
        return WebClient.builder()
                .baseUrl(cfg.getApiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "forgeshift-wso2-migration-service")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    private void ensureRepo(WebClient gh, String owner, String repoName) {
        try {
            gh.get()
                    .uri("/repos/{owner}/{repo}", owner, repoName)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return;   // already exists
        } catch (WebClientResponseException.NotFound nf) {
            // fall through to create
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", repoName);
        body.put("private", true);
        body.put("auto_init", true);
        body.put("description", "WSO2 migration bundles generated by ForgeShift");

        if ("Organization".equalsIgnoreCase(accountType(gh, owner))) {
            gh.post().uri("/orgs/{org}/repos", owner)
                    .bodyValue(body).retrieve().bodyToMono(Map.class).block();
            return;
        }
        // Personal (User) account: GitHub only lets you create under the AUTHENTICATED user.
        String me = authLogin(gh);
        if (owner.equalsIgnoreCase(me)) {
            gh.post().uri("/user/repos")
                    .bodyValue(body).retrieve().bodyToMono(Map.class).block();
            return;
        }
        throw new IllegalStateException("Repo " + owner + "/" + repoName
                + " does not exist and cannot be auto-created: '" + owner + "' is a user account and the token"
                + " belongs to '" + me + "'. Create the repo manually, or target an org / the token owner's account.");
    }

    /** GitHub account type for an owner: "User" or "Organization" (null if unknown). */
    private String accountType(WebClient gh, String owner) {
        try {
            Map<?, ?> r = gh.get().uri("/users/{owner}", owner).retrieve().bodyToMono(Map.class).block();
            return r == null ? null : str(r.get("type"));
        } catch (WebClientResponseException e) {
            return null;
        }
    }

    /** Login of the token's owner. */
    private String authLogin(WebClient gh) {
        try {
            Map<?, ?> r = gh.get().uri("/user").retrieve().bodyToMono(Map.class).block();
            return r == null ? null : str(r.get("login"));
        } catch (WebClientResponseException e) {
            return null;
        }
    }

    private void ensureBranch(WebClient gh, String owner, String repo, String branch) {
        try {
            gh.get()
                    .uri("/repos/{owner}/{repo}/branches/{branch}", owner, repo, branch)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException.NotFound nf) {
            if (!"main".equals(branch)) {
                throw nf;
            }
        }
    }

    private String getSha(WebClient gh, String owner, String repo, String path, String branch) {
        try {
            Map<?, ?> resp = gh.get()
                    .uri("/repos/" + owner + "/" + repo + "/contents/" + path + "?ref=" + branch)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return resp == null ? null : str(resp.get("sha"));
        } catch (WebClientResponseException.NotFound nf) {
            return null;   // new file
        }
    }

    /**
     * Reconciles files in {@code dirs} that are NOT part of the current bundle (and aren't create-only),
     * returning the count reconciled. Each stale file is either ARCHIVED into {@code cfg.archiveDir}
     * (default — moved out of the apply path but kept for audit/rollback) or hard-DELETED
     * ({@code archive-stale=false}). {@code yamlOnly} restricts action to {@code .yaml/.yml} (used for
     * the shared env root so README/workflow/non-YAML are never touched); per-API subdirs pass false
     * since the whole subdir belongs to that one API. When {@code recursive} is true the walk descends
     * into subdirectories (used by the env-root pass so an earlier single-API migration's
     * {@code kong/<env>/<slug>/} leftovers — which {@code deck apply} reads recursively — are cleared).
     */
    private int reconcileDirs(Set<String> dirs, WebClient gh, String owner, String repo, String branch,
                              Set<String> keep, Set<String> createOnlyPaths, boolean yamlOnly,
                              boolean recursive, String message, MigrationProperties.Deck.Git cfg) {
        int removed = 0;
        for (String dir : dirs) {
            removed += reconcileDir(dir, gh, owner, repo, branch, keep, createOnlyPaths, yamlOnly,
                    recursive, message, cfg);
        }
        return removed;
    }

    private int reconcileDir(String dir, WebClient gh, String owner, String repo, String branch,
                             Set<String> keep, Set<String> createOnlyPaths, boolean yamlOnly,
                             boolean recursive, String message, MigrationProperties.Deck.Git cfg) {
        int removed = 0;
        String archiveDir = cfg.getArchiveDir();
        boolean archive = cfg.isArchiveStale() && StringUtils.hasText(archiveDir);
        for (Map<String, Object> item : listDir(gh, owner, repo, dir, branch)) {
            String type = str(item.get("type"));
            String p = str(item.get("path"));
            if (p == null) continue;
            if ("dir".equals(type)) {
                // Descend into per-API subdirs for the env-root authoritative pass. NEVER walk into the
                // archive tree, or we'd re-archive already-archived files on every run.
                if (recursive && !isUnderArchive(p, archiveDir)) {
                    removed += reconcileDir(p, gh, owner, repo, branch, keep, createOnlyPaths, yamlOnly,
                            true, message, cfg);
                }
                continue;
            }
            if (!"file".equals(type)) continue;
            if (keep.contains(p)) continue;                                       // current bundle file
            if (createOnlyPaths != null && createOnlyPaths.contains(p)) continue;
            if (yamlOnly && !(p.endsWith(".yaml") || p.endsWith(".yml"))) continue; // protect README/workflow/non-YAML
            try {
                String sha = str(item.get("sha"));
                if (archive) {
                    if (archiveFile(gh, owner, repo, p, branch, sha, archiveDir, message, cfg)) {
                        removed++;
                        log.info("Archived stale bundle file {} -> {}/ (no longer generated)", p, archiveDir);
                    }
                } else {
                    deleteFile(gh, owner, repo, p, branch, sha, message, cfg);
                    removed++;
                    log.info("Deleted stale bundle file {} (no longer generated)", p);
                }
            } catch (Exception ex) {
                log.warn("Could not reconcile stale bundle file {}: {}", p, ex.getMessage());
            }
        }
        return removed;
    }

    /**
     * Moves a stale file out of the apply path by re-creating it under {@code archiveDir} (original
     * sub-path preserved) and deleting the original. GitHub has no native move, so this is a
     * copy-then-delete: read content → PUT {@code <archiveDir>/<path>} → DELETE {@code <path>}.
     * Returns {@code false} (leaving the file untouched) when its content can't be read, so we never
     * delete data we couldn't first preserve.
     */
    private boolean archiveFile(WebClient gh, String owner, String repo, String path, String branch,
                                String sha, String archiveDir, String message,
                                MigrationProperties.Deck.Git cfg) {
        Existing src = getExisting(gh, owner, repo, path, branch);
        if (src == null || src.content() == null) {
            log.warn("Skipping archive of {} — content unavailable; left in place (not deleted)", path);
            return false;
        }
        String dest = archivePathFor(archiveDir, path);
        String destSha = getSha(gh, owner, repo, dest, branch);   // overwrite any prior archived copy
        putFile(gh, owner, repo, dest, branch, src.content(), destSha,
                "chore(deck): archive stale " + path + " -> " + dest + " — " + message, cfg);
        deleteFile(gh, owner, repo, path, branch, sha, message, cfg);
        return true;
    }

    /** Repo path a stale file is archived to: {@code <archiveDir>/<original path>} (structure preserved). */
    static String archivePathFor(String archiveDir, String path) {
        return archiveDir + "/" + path;
    }

    /** True when {@code path} IS the archive dir or sits under it — keeps the recursive reconcile from
     *  walking into (and endlessly re-archiving) the archive tree. */
    static boolean isUnderArchive(String path, String archiveDir) {
        if (!StringUtils.hasText(archiveDir) || path == null) {
            return false;
        }
        return path.equals(archiveDir) || path.startsWith(archiveDir + "/");
    }

    /** Per-API kong directories ({@code kong/<env>/<slug>}) among the bundle paths — never {@code kong/<env>}. */
    static Set<String> perApiDirs(Set<String> bundlePaths) {
        Set<String> dirs = new LinkedHashSet<>();
        for (String path : bundlePaths) {
            int slash = path == null ? -1 : path.lastIndexOf('/');
            if (slash <= 0) continue;
            String dir = path.substring(0, slash);
            if (dir.startsWith("kong/") && dir.chars().filter(c -> c == '/').count() >= 2) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /** The flat env-root kong directory ({@code kong/<env>}, exactly one separator under {@code kong/})
     *  where MULTI-API bundles place their per-API files — distinct from the nested {@link #perApiDirs}. */
    static Set<String> envRootDirs(Set<String> bundlePaths) {
        Set<String> dirs = new LinkedHashSet<>();
        for (String path : bundlePaths) {
            int slash = path == null ? -1 : path.lastIndexOf('/');
            if (slash <= 0) continue;
            String dir = path.substring(0, slash);
            if (dir.startsWith("kong/") && dir.chars().filter(c -> c == '/').count() == 1) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listDir(WebClient gh, String owner, String repo, String dir, String branch) {
        try {
            List<Map<String, Object>> resp = gh.get()
                    .uri("/repos/" + owner + "/" + repo + "/contents/" + dir + "?ref=" + branch)
                    .retrieve()
                    .bodyToMono(List.class)
                    .block();
            return resp == null ? new ArrayList<>() : resp;
        } catch (WebClientResponseException.NotFound nf) {
            return new ArrayList<>();   // directory doesn't exist yet (first migration)
        }
    }

    private void deleteFile(WebClient gh, String owner, String repo, String path, String branch,
                            String sha, String message, MigrationProperties.Deck.Git cfg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "chore(deck): remove stale " + path + " — " + message);
        body.put("sha", sha);
        body.put("branch", branch);
        if (StringUtils.hasText(cfg.getAuthorName()) && StringUtils.hasText(cfg.getAuthorEmail())) {
            body.put("committer", Map.of("name", cfg.getAuthorName(), "email", cfg.getAuthorEmail()));
        }
        gh.method(HttpMethod.DELETE)
                .uri("/repos/" + owner + "/" + repo + "/contents/" + path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    /** Existing file sha + decoded text content (null when the file doesn't exist). */
    private record Existing(String sha, String content) {}

    /** Like {@link #getSha} but also returns the decoded content, so callers can skip identical PUTs. */
    private Existing getExisting(WebClient gh, String owner, String repo, String path, String branch) {
        try {
            Map<?, ?> resp = gh.get()
                    .uri("/repos/" + owner + "/" + repo + "/contents/" + path + "?ref=" + branch)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (resp == null) {
                return null;
            }
            String content = null;
            if ("base64".equals(str(resp.get("encoding"))) && resp.get("content") != null) {
                content = new String(Base64.getMimeDecoder().decode(String.valueOf(resp.get("content"))),
                        StandardCharsets.UTF_8);
            }
            return new Existing(str(resp.get("sha")), content);
        } catch (WebClientResponseException.NotFound nf) {
            return null;   // new file
        }
    }

    /**
     * Trigger a {@code workflow_dispatch} run of the caller workflow, passing per-migration inputs
     * (notably {@code result_callback_url}). A just-committed workflow file can take a moment to be
     * registered for dispatch, so a 404 is retried a few times.
     */
    private void dispatchWorkflow(WebClient gh, String owner, String repo, String branch,
                                  String workflowFile, Map<String, String> inputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", branch);
        if (inputs != null && !inputs.isEmpty()) {
            body.put("inputs", inputs);
        }
        WebClientResponseException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                gh.post()
                        .uri("/repos/{o}/{r}/actions/workflows/{w}/dispatches", owner, repo, workflowFile)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Void.class)
                        .block();
                log.info("Dispatched workflow {} on {}/{}@{}", workflowFile, owner, repo, branch);
                return;
            } catch (WebClientResponseException.NotFound nf) {
                last = nf;   // workflow file not registered yet — wait and retry
                sleepQuietly(1500L * attempt);
            }
        }
        throw last;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> putFile(WebClient gh, String owner, String repo, String path, String branch,
                                        String content, String sha, String message,
                                        MigrationProperties.Deck.Git cfg) {
        return putBinaryFile(gh, owner, repo, path, branch,
                content.getBytes(StandardCharsets.UTF_8), sha, message, cfg);
    }

    private Map<String, Object> putBinaryFile(WebClient gh, String owner, String repo, String path, String branch,
                                              byte[] content, String sha, String message,
                                              MigrationProperties.Deck.Git cfg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(content));
        body.put("branch", branch);
        if (sha != null) body.put("sha", sha);
        if (StringUtils.hasText(cfg.getAuthorName()) && StringUtils.hasText(cfg.getAuthorEmail())) {
            body.put("committer", Map.of("name", cfg.getAuthorName(), "email", cfg.getAuthorEmail()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = gh.put()
                .uri("/repos/" + owner + "/" + repo + "/contents/" + path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        return resp;
    }

    private static GitPushResult skip(String why) {
        log.info("Git auto-commit skipped: {}", why);
        return GitPushResult.builder().pushed(false).error(why).build();
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : (StringUtils.hasText(b) ? b : null);
    }

    private static String ownerFromRepo(String repo) {
        if (!StringUtils.hasText(repo) || !repo.contains("/")) {
            return null;
        }
        return repo.substring(0, repo.indexOf('/'));
    }

    private static String safeRepoName(String value) {
        String cleaned = StringUtils.hasText(value) ? value.trim().toLowerCase() : "company";
        cleaned = cleaned.replaceAll("[^a-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return StringUtils.hasText(cleaned) ? cleaned : "company";
    }

    /** Pulls GitHub's JSON {@code "message"} (e.g. the missing-scope reason) out of an error body. */
    private static String githubMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"message\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (m.find()) {
            return " — " + m.group(1);
        }
        String b = body.length() > 200 ? body.substring(0, 200) : body;
        return " — " + b.replaceAll("\\s+", " ").trim();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
