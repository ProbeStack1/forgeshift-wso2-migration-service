package com.forgeshift.wso2.migration.domain.kong;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KongConsumer {
    private String username;
    private String custom_id;
    private List<String> tags;

    // Nested credentials (decK resolves these under the consumer when _transform: true).
    // Each entry is a raw Kong credential object; secret fields hold references
    // (${ENV} / {vault://...}) rather than plaintext unless INLINE mode is set.
    private List<Map<String, Object>> jwt_secrets;
    private List<Map<String, Object>> keyauth_credentials;
    private List<Map<String, Object>> oauth2_credentials;
}
