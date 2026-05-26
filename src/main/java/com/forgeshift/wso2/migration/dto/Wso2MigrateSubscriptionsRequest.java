package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Selective subscription migration. The translator also pulls the
 * matching application snapshots so the Kong consumer can be built.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Wso2MigrateSubscriptionsRequest extends Wso2BaseMigrationRequest {

    @NotEmpty(message = "subscriptions list is required and must not be empty")
    @JsonProperty("subscriptions")
    @Schema(description = "WSO2 subscription sourceIds (UUIDs) to migrate",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> subscriptions = new ArrayList<>();
}
