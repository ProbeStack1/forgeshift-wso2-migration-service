package com.forgeshift.wso2.migration.deck;

import lombok.Builder;
import lombok.Data;

/** Outcome of auto-committing the decK files to the Kong-config git repo. */
@Data
@Builder
public class GitPushResult {
    private boolean pushed;
    private String repo;          // owner/repo
    private String branch;
    private String commitSha;     // sha of the last commit made
    private String commitUrl;     // html_url of the last commit
    private int filesPushed;
    private String error;         // non-null when the push was skipped/failed
}
