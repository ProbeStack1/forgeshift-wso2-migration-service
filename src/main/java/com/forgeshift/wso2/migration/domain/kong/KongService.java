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
public class KongService {
    private String name;
    private String protocol;
    private String host;
    private Integer port;
    private String path;
    private Integer connect_timeout;
    private Integer read_timeout;
    private Integer write_timeout;
    private Integer retries;
    private Boolean enabled;
    private List<String> tags;
}
