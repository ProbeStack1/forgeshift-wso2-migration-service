package com.forgeshift.wso2.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Payload for {@code POST /migrations} (bulk) and the per-resource
 * convenience endpoints. Carries the Apigee-aligned audit/target fields
 * as optional extras so the legacy minimal shape still works.
 *
 * <p>{@link #resourceFilters} is what the per-resource endpoints
 * ({@code POST /konnect/wso2/apis} etc.) populate to scope a migration
 * down to a specific set of {@code sourceId}s. Null/empty for a type
 * means "migrate every snapshot of that type", which is the bulk-default
 * behaviour.
 */
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

    @Schema(description = "Optional: explicit discovery revision number. Ignored when discoveryId is set.")
    private Integer revision;

    @Schema(description = "Which Kong Konnect profile (in the kong_konnect_profiles collection) to deploy to. " +
            "Defaults to 'primary'. Falls back to the static .env config if no profile exists.",
            example = "primary")
    private String kongProfileName;

    @Schema(description = "Override the Kong region resolved from the profile.", example = "us")
    private String kongRegion;

    @Schema(description = "Override the Kong control plane id resolved from the profile.",
            example = "78ac8c8b-74a8-4338-875f-2ccaee19a52c")
    private String kongCtrlPlanId;

    @Schema(description = "Source gateway label. Defaults to 'wso2-apim'.", example = "wso2-apim")
    private String sourceGateway;

    @Schema(description = "Target gateway label. Defaults to 'kong-konnect'.", example = "kong-konnect")
    private String targetGateway;

    @Schema(description = "Optional environment classification, mirrors Apigee's field.",
            example = "production")
    private String envClassification;

    @Schema(description = "Where the call came from.", example = "API",
            allowableValues = {"UI", "API", "SCHEDULER", "MIGRATION_TOOL"})
    private Wso2BaseMigrationRequest.RequestSourceEnum requestSource;

    @Schema(description = "Resource types to migrate. Defaults to [apis, applications, subscriptions]. " +
            "Valid: apis, applications, subscriptions, throttlingpolicies, keymanagers.")
    private List<String> resourceTypes;

    /**
     * Per-type sourceId allow-list. Populated by per-resource endpoints
     * (e.g. {@code POST /konnect/wso2/apis} sets {@code {"apis": [...]}}).
     * Bulk callers leave this null and every snapshot of every requested
     * type is migrated.
     */
    @Schema(description = "Optional per-type allow-list of WSO2 sourceIds. " +
            "null/empty entry = migrate all snapshots of that type.",
            example = "{\"apis\": [\"5d983d56-bae4-...\"]}")
    private Map<String, List<String>> resourceFilters;

    @Schema(description = "When true, translate only - no Konnect writes. Useful for inspecting the planned diff.",
            defaultValue = "false")
    private boolean dryRun;

    @Schema(description = "Optional caller-supplied correlation id. A server UUID is generated when absent.")
    private String requestTransactionId;

    @Schema(description = "Audit-only: who initiated this migration.")
    private String userEmail;
}
