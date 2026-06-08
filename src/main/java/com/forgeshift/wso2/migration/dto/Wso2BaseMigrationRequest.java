package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Common base for every WSO2 → Kong migration request. Mirrors the field
 * surface of Apigee's {@code BaseRequest} so callers can use the same
 * payload shape they already know: {@code companyName} + source ID +
 * target overrides + audit fields. WSO2 has no separate org/env split
 * like Apigee Edge, so {@code wso2Tenant} stands in for the "source
 * environment" coordinate.
 *
 * <p>Validation:
 * <ul>
 *   <li>{@code companyName}, {@code wso2Tenant}, {@code userEmail},
 *       {@code requestSource} are required.</li>
 *   <li>{@code userEmail} must be a valid email address.</li>
 *   <li>{@code requestTransactionId} is optional. A server UUID is generated
 *       when absent.</li>
 *   <li>Everything else is optional. {@code kongRegion} /
 *       {@code kongCtrlPlanId} override the values resolved from
 *       {@code kong_konnect_profiles}.</li>
 * </ul>
 */
@Data
public abstract class Wso2BaseMigrationRequest {

    @NotBlank
    @JsonProperty("companyName")
    @Schema(description = "Multi-tenancy partner id", example = "probestack",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String companyName;

    @NotBlank
    @JsonProperty("wso2Tenant")
    @Schema(description = "WSO2 tenant — analogous to apigeeEdgeOrg", example = "carbon.super",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String wso2Tenant;

    @JsonProperty("sourceGateway")
    @Schema(description = "Source gateway label", example = "wso2-apim")
    private String sourceGateway;

    @JsonProperty("targetGateway")
    @Schema(description = "Target gateway label", example = "kong-konnect")
    private String targetGateway;

    @JsonProperty("envClassification")
    @Schema(description = "Optional environment classification", example = "production")
    private String envClassification;

    @JsonProperty("kongRegion")
    @Schema(description = "Override Konnect region from the profile", example = "us")
    private String kongRegion;

    @JsonProperty("kongCtrlPlanId")
    @Schema(description = "Override Konnect control plane id from the profile",
            example = "78ac8c8b-74a8-4338-875f-2ccaee19a52c")
    private String kongCtrlPlanId;

    @JsonProperty("kongProfileName")
    @Schema(description = "Which kong_konnect_profiles row to use", example = "primary")
    private String kongProfileName;

    @NotBlank
    @Email(message = "userEmail must be a valid email address")
    @JsonProperty("userEmail")
    @Schema(description = "Caller email — audit only", example = "ops@probestack.io",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String userEmail;

    @JsonProperty("requestTransactionId")
    @Schema(description = "Optional caller-supplied correlation id. A server UUID is generated when absent.",
            example = "WSO2_probestack_carbon.super_prod_1716700000000",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String requestTransactionId;

    @NotNull(message = "requestSource is required")
    @JsonProperty("requestSource")
    @Schema(description = "Origin of the call", example = "API",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private RequestSourceEnum requestSource;

    @JsonProperty("discoveryId")
    @Schema(description = "Optional discoveryId to migrate from; defaults to latest revision for the tenant")
    private String discoveryId;

    @JsonProperty("revision")
    @Schema(description = "Optional explicit discovery revision number")
    private Integer revision;

    @JsonProperty("dryRun")
    @Schema(description = "Translate + diff only, no Konnect writes")
    private boolean dryRun;

    @Schema(description = "When true, auto-include the selected resources' dependencies and skip " +
            "anything already present in Kong. Requires assessmentTransactionId.")
    private boolean includeDependencies;

    @Schema(description = "Assessment requestTransactionId whose saved dependency graph is used to " +
            "resolve dependencies. Required when includeDependencies=true.")
    private String assessmentTransactionId;

    /** Mirrors {@code com.probestack.apigee.migration.model.BaseRequest.RequestSourceEnum}. */
    public enum RequestSourceEnum {
        UI, API, SCHEDULER, MIGRATION_TOOL
    }
}
