package com.forgeshift.wso2.migration.deck;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Result of building one decK bundle (per-API files + workflow + README, zipped) and optionally auto-committing it. */
@Data
@Builder
public class BundleResult {
    private String jobId;
    private String env;
    private String controlPlaneName;
    private String konnectAddr;
    /** Directory the pipeline applies, e.g. kong/dev (decK merges every file in it). */
    private String kongConfigPath;
    /** Absolute path to bundle.zip on disk. */
    private String bundlePath;
    private String fileName;
    /** URL the caller GETs to download the zip. */
    private String downloadUrl;
    /** Entry paths inside the zip. */
    private List<String> files;

    /** Full repo file contents (paths → content) for auto-commit; not persisted. */
    @JsonIgnore
    private Map<String, String> repoFileContents;
    /** Paths that should be committed only once (README). */
    @JsonIgnore
    private List<String> createOnlyPaths;
    /** Basename of the caller workflow to dispatch, e.g. {@code deploy-dev.yml}. */
    @JsonIgnore
    private String workflowFile;
    /** Per-migration callback URL passed as the {@code workflow_dispatch} input
     *  ({@code …/migrations/{jobId}/deck-result}); null when no callback is configured. */
    @JsonIgnore
    private String callbackUrl;

    // ----- git auto-commit outcome (null when auto-commit is off/skipped) -----
    private String gitRepo;
    private String gitBranch;
    private String gitCommitSha;
    private String gitCommitUrl;
    private Integer gitFilesPushed;
    private String gitError;
    /** True when the GitHub Actions pipeline was successfully dispatched for this migration. */
    private boolean dispatched;
}
