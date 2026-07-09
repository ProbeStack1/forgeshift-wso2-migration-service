package com.forgeshift.wso2.migration.controller;

import com.forgeshift.wso2.migration.domain.MigrationHistoryEntry;
import com.forgeshift.wso2.migration.dto.MigrationHistoryCheckRequest;
import com.forgeshift.wso2.migration.dto.MigrationHistoryCheckResult;
import com.forgeshift.wso2.migration.service.MigrationHistoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The per-resource migration checklist ({@code migration_history} collection) — one row per
 * migrated WSO2 resource, distinct from the per-run history at {@code /wso2/history/*}. Backs
 * the frontend's "already migrated" badge and the migrated-resources overview.
 */
@RestController
@RequiredArgsConstructor
public class MigrationHistoryController {

    private final MigrationHistoryService historyService;

    /**
     * Overview list: every resource ever migrated for the tenant (newest first), optionally
     * narrowed to one resourceType (apis | applications | certificates | apiproducts).
     */
    @GetMapping("/wso2/migration-history")
    public ResponseEntity<List<MigrationHistoryEntry>> list(
            @RequestParam("companyName") String companyName,
            @RequestParam("wso2Tenant") String wso2Tenant,
            @RequestParam(value = "resourceType", required = false) String resourceType) {
        return ResponseEntity.ok(historyService.list(companyName, wso2Tenant, resourceType));
    }

    /**
     * Pre-migration check: given the user's selected sourceIds, return each one's migrated
     * status (results in request order). The frontend shows the badge; the user proceeding
     * anyway is a conscious re-migration.
     */
    @PostMapping("/wso2/migration-history/check")
    public ResponseEntity<List<MigrationHistoryCheckResult>> check(
            @Valid @RequestBody MigrationHistoryCheckRequest req) {
        List<MigrationHistoryCheckResult> results = historyService
                .check(req.getCompanyName(), req.getWso2Tenant(), req.getResourceType(), req.getSourceIds())
                .stream()
                .map((Map.Entry<String, MigrationHistoryEntry> e) ->
                        MigrationHistoryCheckResult.of(e.getKey(), e.getValue()))
                .toList();
        return ResponseEntity.ok(results);
    }
}
