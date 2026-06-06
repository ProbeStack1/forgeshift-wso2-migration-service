package com.forgeshift.wso2.migration.deck;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.reader.GitProfileCredentials;
import com.forgeshift.wso2.migration.reader.GitProfileReader;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
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
        String owner = firstNonBlank(git.getOrganization(), ownerFromRepo(cfg.getRepo()));
        String branch = firstNonBlank(creds == null ? null : creds.getGitBranch(), cfg.getBranch());
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

    public GitPushResult push(KongKonnectCredentials creds, Map<String, String> files,
                              Set<String> createOnlyPaths, String message) {
        MigrationProperties.Deck.Git cfg = props.getDeck().getGit();
        if (!cfg.isEnabled()) {
            return skip("auto-commit disabled (forgeshift.migration.deck.git.enabled=false)");
        }
        String repo = firstNonBlank(creds == null ? null : creds.getGitRepo(), cfg.getRepo());
        String branch = firstNonBlank(creds == null ? null : creds.getGitBranch(), cfg.getBranch());
        String token = firstNonBlank(creds == null ? null : creds.getGitToken(), cfg.getToken());
        if (!StringUtils.hasText(repo) || !StringUtils.hasText(token)) {
            return skip("no git repo/token configured (profile or deck.git.repo + deck.git.token)");
        }
        int slash = repo.indexOf('/');
        if (slash <= 0 || slash == repo.length() - 1) {
            return skip("git repo must be in 'owner/repo' form, got: " + repo);
        }
        String owner = repo.substring(0, slash);
        String name = repo.substring(slash + 1);

        WebClient gh = githubClient(cfg, token);

        int pushed = 0;
        String lastSha = null;
        String lastUrl = null;
        try {
            for (Map.Entry<String, String> e : files.entrySet()) {
                String path = e.getKey();
                String existingSha = getSha(gh, owner, name, path, branch);
                if (existingSha != null && createOnlyPaths != null && createOnlyPaths.contains(path)) {
                    log.debug("Skipping existing create-only file {}", path);
                    continue;   // workflow/README written once, not re-committed every run
                }
                Map<String, Object> commit = putFile(gh, owner, name, path, branch,
                        e.getValue(), existingSha, message, cfg);
                pushed++;
                if (commit != null) {
                    Object c = commit.get("commit");
                    if (c instanceof Map<?, ?> cm) {
                        lastSha = str(cm.get("sha"));
                        lastUrl = str(cm.get("html_url"));
                    }
                }
            }
        } catch (WebClientResponseException ex) {
            int code = ex.getStatusCode().value();
            log.error("Git auto-commit failed ({} {}): {}", code, repo, ex.getResponseBodyAsString());
            return GitPushResult.builder().pushed(false).repo(repo).branch(branch)
                    .filesPushed(pushed).error("GitHub " + code + ": " + ex.getStatusText()).build();
        } catch (Exception ex) {
            log.error("Git auto-commit failed for {}: {}", repo, ex.getMessage());
            return GitPushResult.builder().pushed(false).repo(repo).branch(branch)
                    .filesPushed(pushed).error(ex.getMessage()).build();
        }

        log.info("Auto-committed {} file(s) to {}@{} (last commit {})", pushed, repo, branch, lastSha);
        return GitPushResult.builder()
                .pushed(pushed > 0).repo(repo).branch(branch)
                .commitSha(lastSha).commitUrl(lastUrl).filesPushed(pushed).build();
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
