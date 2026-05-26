package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Selective API migration. The {@code apis} list contains discovery
 * {@code sourceId}s (WSO2 API UUIDs) — the same identifier used as
 * {@code wso2-source-id:<uuid>} tag on Kong entities.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Wso2MigrateApisRequest extends Wso2BaseMigrationRequest {

    @NotEmpty(message = "apis list is required and must not be empty")
    @JsonProperty("apis")
    @Schema(description = "WSO2 API sourceIds (UUIDs) to migrate",
            example = "[\"5d983d56-bae4-4317-a58f-38d73e94def3\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> apis = new ArrayList<>();
}
