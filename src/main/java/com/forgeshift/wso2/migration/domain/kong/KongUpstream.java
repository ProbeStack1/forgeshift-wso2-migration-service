package com.forgeshift.wso2.migration.domain.kong;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KongUpstream {
    private String name;
    private String algorithm;         // round-robin / consistent-hashing / least-connections
    private Integer slots;
    private List<String> tags;
}
