package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** Selective throttling-policy migration (translated into Kong rate-limiting plugins). */
@Data
@EqualsAndHashCode(callSuper = true)
public class Wso2MigrateThrottlingPoliciesRequest extends Wso2BaseMigrationRequest {

    @NotEmpty(message = "throttlingpolicies list is required and must not be empty")
    @JsonProperty("throttlingpolicies")
    @Schema(description = "WSO2 throttling-policy sourceIds to migrate",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> throttlingpolicies = new ArrayList<>();
}
