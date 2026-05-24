package com.forgeshift.wso2.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** Payload for {@code POST /migrations} and the per-resource translate endpoints. */
@Data
public class StartMigrationRequest {

    @Schema(description = "Multi-tenancy partner id (defaults to the configured default if omitted).",
            example = "probestack")
    private String companyName;

    @NotBlank
    @Schema(description = "Source WSO2 tenant.", example = "carbon.super",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String wso2Tenant;

    @Schema(description = "Optional: source discovery id. When omitted, the migrator uses the latest revision " +
            "for each requested resource type.")
    private String discoveryId;

    @Schema(description = "Which Kong Konnect profile (in the kong_konnect_profiles collection) to deploy to. " +
            "Defaults to 'primary'. Falls back to the static .env config if no profile exists.",
            example = "primary")
    private String kongProfileName;

    @Schema(description = "Resource types to migrate. Defaults to [apis, applications, subscriptions]. " +
            "Valid: apis, applications, subscriptions, throttlingpolicies, keymanagers.")
    private List<String> resourceTypes;

    @Schema(description = "When true, translate only - no Konnect writes. Useful for inspecting the planned diff.",
            defaultValue = "false")
    private boolean dryRun;

    @Schema(description = "Optional caller-supplied correlation id. A server UUID is generated when absent.")
    private String requestTransactionId;

    @Schema(description = "Audit-only: who initiated this migration.")
    private String userEmail;
}
