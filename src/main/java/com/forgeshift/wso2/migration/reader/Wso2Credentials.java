package com.forgeshift.wso2.migration.reader;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Connection details for a WSO2 instance, resolved from the
 * {@code wso2_profiles} collection. Same shape the discovery service
 * uses internally — kept local here so this module doesn't have to
 * depend on the discovery service's domain classes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Wso2Credentials {
    /** "profile" when found in Mongo, "missing" when no row matched. */
    private String source;

    private String companyName;
    private String profileName;
    private String wso2BaseUrl;
    private String username;
    private String password;
    private String clientId;
    private String clientSecret;
    private boolean trustSelfSigned;
}
