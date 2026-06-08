package com.forgeshift.wso2.migration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * Body for {@code POST /migrations/{id}/deck-result}: the two JSON outputs the
 * pipeline collects after {@code deck gateway apply}. The service uses them to
 * rebuild {@code entity_mappings} (from {@code kongState}) and record failures
 * (from {@code applyReport.errors}).
 */
@Data
public class DeckResultRequest {

    @Schema(description = "Output of `deck gateway dump --select-tag ... --format json` "
            + "(deployed entities with ids + parents).")
    private Map<String, Object> kongState;

    @Schema(description = "Output of `deck gateway apply --json-output` (changes + errors[]).")
    private Map<String, Object> applyReport;

    @Schema(description = "Exit code of the `deck gateway apply` step. Non-zero = the apply failed "
            + "(even if applyReport is empty, e.g. an auth failure). 0/absent = success.")
    private Integer applyExitCode;

    @Schema(description = "Captured stderr of the apply step (optional) — surfaced on the job's lastError when it failed.")
    private String applyStderr;
}
