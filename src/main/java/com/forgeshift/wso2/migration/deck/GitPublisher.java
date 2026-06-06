package com.forgeshift.wso2.migration.deck;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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

        WebClient gh = WebClient.builder()
                .baseUrl(cfg.getApiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "forgeshift-wso2-migration-service")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
