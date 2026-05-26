package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** Selective key-manager migration. */
@Data
@EqualsAndHashCode(callSuper = true)
public class Wso2MigrateKeyManagersRequest extends Wso2BaseMigrationRequest {

    @NotEmpty(message = "keymanagers list is required and must not be empty")
    @JsonProperty("keymanagers")
    @Schema(description = "WSO2 key-manager sourceIds to migrate",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> keymanagers = new ArrayList<>();
}
