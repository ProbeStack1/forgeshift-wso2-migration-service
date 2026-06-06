package com.forgeshift.wso2.migration.reader;

import lombok.Builder;
import lombok.Data;

/**
 * Resolved Kong Konnect connection. Either built from a {@code kong_konnect_profiles}
 * row (multi-tenant path) or from the static {@code forgeshift.migration.konnect.*}
 * fallback (single-tenant path).
 */
@Data
@Builder
public class KongKonnectCredentials {
    private String source;            // "profile" or "static"
    private String konnectBaseUrl;
    private String konnectAccessToken;
    private String controlPlaneId;
    private String controlPlaneName;
    private String region;
    /** Kong-config git repo for auto-commit (owner/repo); resolved from the profile when present. */
    private String gitRepo;
    private String gitBranch;
    private String gitToken;
}
