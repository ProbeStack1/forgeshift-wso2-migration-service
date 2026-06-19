package com.forgeshift.wso2.migration.deck;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GitPublisherTest {

    /**
     * Stale-file reconciliation must only ever touch a bundle's per-API subdirectory
     * ({@code kong/<env>/<slug>/...}) — never the shared {@code kong/<env>} root (which would
     * delete other APIs' files in flat mode) and never repo-root files like the workflow/README.
     */
    @Test
    void perApiDirs_returnsOnlyTheApiSubdir_neverTheSharedRootOrRepoRoot() {
        Set<String> bundle = new LinkedHashSet<>(Set.of(
                "kong/dev/seed05apikeybronze-1-0-0/api-seed05apikeybronze-1-0-0.yaml",
                "kong/dev/seed05apikeybronze-1-0-0/consumers.yaml",
                ".github/workflows/deploy-dev.yml",
                "README.md"));

        assertThat(GitPublisher.perApiDirs(bundle))
                .containsExactly("kong/dev/seed05apikeybronze-1-0-0");
    }

    @Test
    void perApiDirs_flatModeFilesUnderKongEnv_areNotReconciled() {
        // Flat (non-per-API) layout: files sit directly in kong/<env>, shared with other APIs.
        // Reconciling that directory could delete a sibling API's file, so it must be excluded.
        Set<String> bundle = Set.of(
                "kong/dev/api-orders-1-0.yaml",
                "kong/dev/consumers.yaml");

        assertThat(GitPublisher.perApiDirs(bundle)).isEmpty();
    }

    @Test
    void perApiDirs_collapsesMultipleFilesOfOneApiToASingleDir() {
        Set<String> bundle = Set.of(
                "kong/prod/petstore-2-0/api-petstore-2-0.yaml",
                "kong/prod/petstore-2-0/consumers.yaml",
                "kong/prod/petstore-2-0/ca-certificates.yaml");

        assertThat(GitPublisher.perApiDirs(bundle))
                .containsExactly("kong/prod/petstore-2-0");
    }

    @Test
    void envRootDirs_returnsTheFlatEnvRoot_forMultiApiBundles() {
        // Flat (multi-API) layout: per-API files sit directly in kong/<env>. The env-root reconcile
        // targets exactly that shared dir to clear stale leftovers (old combined kong.yaml etc.).
        Set<String> bundle = Set.of(
                "kong/dev/api-accountsapi-1-0-0.yaml",
                "kong/dev/api-policyapi-1-0-0.yaml",
                "kong/dev/consumers.yaml",
                ".github/workflows/deploy-dev.yml",
                "README.md");

        assertThat(GitPublisher.envRootDirs(bundle)).containsExactly("kong/dev");
        // and it must NOT classify the flat root as a per-API subdir
        assertThat(GitPublisher.perApiDirs(bundle)).isEmpty();
    }

    @Test
    void envRootDirs_isEmpty_forSingleApiSubdirBundles() {
        // Single-API (per-api-dir) layout: files are nested under kong/<env>/<slug>/, so the env root
        // is NOT reconciled (perApiDirs handles the subdir instead) — no risk to sibling APIs.
        Set<String> bundle = Set.of(
                "kong/dev/petstore-2-0/api-petstore-2-0.yaml",
                "kong/dev/petstore-2-0/consumers.yaml");

        assertThat(GitPublisher.envRootDirs(bundle)).isEmpty();
        assertThat(GitPublisher.perApiDirs(bundle)).containsExactly("kong/dev/petstore-2-0");
    }

    /**
     * A stale file is archived under archive-dir with its ORIGINAL repo path preserved beneath it, so
     * the archived copy lands OUTSIDE the kong/<env> apply path (decK never reads it) yet remains
     * traceable to where it came from.
     */
    @Test
    void archivePathFor_preservesOriginalPathUnderArchiveDir() {
        assertThat(GitPublisher.archivePathFor("archive", "kong/dev/api-orders-1-0.yaml"))
                .isEqualTo("archive/kong/dev/api-orders-1-0.yaml");
        // The archived path must not start with the kong config dir, or the pipeline would re-apply it.
        assertThat(GitPublisher.archivePathFor("archive", "kong/dev/old-api/api-old.yaml"))
                .doesNotStartWith("kong/");
    }

    /**
     * The recursive env-root reconcile must skip the archive tree itself — otherwise it would re-archive
     * already-archived files on every run. Matching is on a path boundary, so "archived/..." is NOT
     * treated as being under "archive".
     */
    @Test
    void isUnderArchive_detectsArchiveTree_onPathBoundary() {
        assertThat(GitPublisher.isUnderArchive("archive", "archive")).isTrue();
        assertThat(GitPublisher.isUnderArchive("archive/kong/dev/api-foo.yaml", "archive")).isTrue();
        assertThat(GitPublisher.isUnderArchive("kong/dev/api-foo.yaml", "archive")).isFalse();
        assertThat(GitPublisher.isUnderArchive("archived/x.yaml", "archive")).isFalse();
        assertThat(GitPublisher.isUnderArchive("kong/dev/api.yaml", "")).isFalse();
    }
}
