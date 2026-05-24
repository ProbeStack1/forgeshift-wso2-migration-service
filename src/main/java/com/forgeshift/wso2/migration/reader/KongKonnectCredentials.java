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
    private String region;
}
