package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Selective mediation-policy migration. The {@code mediationpolicies} list holds
 * discovery {@code sourceId}s (the composite {@code <apiId>::<policyId>}). Each
 * policy's Synapse XML is fetched live from WSO2, AI-translated to Lua, and
 * deployed as a Kong serverless plugin on the policy's API service (which must
 * already be migrated).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Wso2MigrateMediationPoliciesRequest extends Wso2BaseMigrationRequest {

    @NotEmpty(message = "mediationpolicies list is required and must not be empty")
    @JsonProperty("mediationpolicies")
    @Schema(description = "WSO2 mediation-policy sourceIds (<apiId>::<policyId>) to migrate",
            example = "[\"5d983d56-...::log-in\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> mediationpolicies = new ArrayList<>();
}
