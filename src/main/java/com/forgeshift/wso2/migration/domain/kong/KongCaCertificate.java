package com.forgeshift.wso2.migration.domain.kong;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Kong {@code ca_certificate} entity — a trusted (public) certificate, no private
 * key. WSO2 endpoint certificates are public trust certs, so they map here rather
 * than to Kong {@code certificates} (which require a cert+key pair).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KongCaCertificate {
    /** PEM-encoded certificate. Required by Konnect. */
    private String cert;
    private String cert_digest;
    private List<String> tags;
}
