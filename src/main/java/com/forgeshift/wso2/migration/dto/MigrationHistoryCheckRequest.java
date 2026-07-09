package com.forgeshift.wso2.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * "Which of these are already migrated?" — the frontend sends the user's selection before a
 * migration and paints the already-migrated badge from the response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationHistoryCheckRequest {

    @NotBlank
    @Schema(example = "probestack")
    private String companyName;

    @NotBlank
    @Schema(example = "carbon.super")
    private String wso2Tenant;

    @NotBlank
    @Schema(description = "apis | applications | certificates | apiproducts", example = "apis")
    private String resourceType;

    @NotEmpty(message = "sourceIds is required and must not be empty")
    @Schema(description = "WSO2 source ids to check", example = "[\"a31236fc-e470-412f-a7a8-fc4391b5678d\"]")
    @Builder.Default
    private List<@NotBlank(message = "sourceIds must not contain blank entries") String> sourceIds =
            new ArrayList<>();
}
