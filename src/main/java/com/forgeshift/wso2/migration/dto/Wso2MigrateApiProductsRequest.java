package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Selective API-Product migration. A product re-exposes operations from member
 * APIs under its own context, so migrating it (a) ensures each member API is
 * migrated first, then (b) creates Kong routes (the product's paths) pointing at
 * the member APIs' Kong services.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Wso2MigrateApiProductsRequest extends Wso2BaseMigrationRequest {

    @NotEmpty(message = "apiproducts list is required and must not be empty")
    @JsonProperty("apiproducts")
    @Schema(description = "WSO2 API Product sourceIds (ids) to migrate",
            example = "[\"a1b2c3d4-...\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> apiproducts = new ArrayList<>();
}
