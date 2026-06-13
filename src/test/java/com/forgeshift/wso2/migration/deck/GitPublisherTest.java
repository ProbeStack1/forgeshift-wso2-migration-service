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
}
