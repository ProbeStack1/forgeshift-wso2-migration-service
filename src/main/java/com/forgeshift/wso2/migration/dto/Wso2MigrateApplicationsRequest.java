package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** Selective application → Kong consumer migration. */
@Data
@EqualsAndHashCode(callSuper = true)
public class Wso2MigrateApplicationsRequest extends Wso2BaseMigrationRequest {

    @NotEmpty(message = "applications list is required and must not be empty")
    @JsonProperty("applications")
    @Schema(description = "WSO2 application sourceIds (UUIDs) to migrate",
            example = "[\"b565e6b9-4e6f-4445-a70f-d1eb9addf829\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> applications = new ArrayList<>();
}
