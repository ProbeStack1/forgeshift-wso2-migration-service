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
public class KongPlugin {
    private String name;                // rate-limiting, jwt, key-auth, cors, response-transformer, ...
    private Map<String, Object> config; // plugin-specific
    private Boolean enabled;
    private List<String> protocols;     // ["http","https"]
    /** Foreign key to the scope. Set EXACTLY ONE of service / route / consumer. */
    private Map<String, String> service;
    private Map<String, String> route;
    private Map<String, String> consumer;
    private List<String> tags;
}
