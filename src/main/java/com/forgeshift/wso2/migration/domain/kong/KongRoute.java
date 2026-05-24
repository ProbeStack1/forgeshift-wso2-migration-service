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
public class KongRoute {
    private String name;
    private List<String> protocols;
    private List<String> methods;
    private List<String> paths;
    private List<String> hosts;
    private Boolean strip_path;
    private Boolean preserve_host;
    /** Foreign key. Konnect expects {@code {"id": "..."}} for the parent service. */
    private Map<String, String> service;
    private List<String> tags;
}
