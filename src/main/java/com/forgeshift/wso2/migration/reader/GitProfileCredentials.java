package com.forgeshift.wso2.migration.reader;

import lombok.Builder;
import lombok.Data;

/** Resolved git provider creds from the {@code git_profiles} collection (by companyName). */
@Data
@Builder
public class GitProfileCredentials {
    private String source;          // "profile" or "missing"
    private String organization;    // e.g. ProbeStack1
    private String pat;             // GitHub personal access token
    private String githubUrl;       // e.g. https://github.com/ProbeStack1
}
