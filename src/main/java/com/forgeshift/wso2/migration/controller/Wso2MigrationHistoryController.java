package com.forgeshift.wso2.migration.controller;

import com.forgeshift.wso2.migration.dto.Wso2MigrationHistorySummaryItem;
import com.forgeshift.wso2.migration.service.Wso2MigrationHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Migration history endpoints — the WSO2 mirror of the Apigee migration
 * service's {@code MigrationHistoryController}.
 */
@RestController
public class Wso2MigrationHistoryController {

    private final Wso2MigrationHistoryService historyService;

    public Wso2MigrationHistoryController(Wso2MigrationHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * Summary API.
     * Returns one row per unique (requestTransactionId + revision) with the
     * earliest createdDateTime, newest first. Mirrors the Apigee
     * GET /apigee/history/summary endpoint (same request params companyName/org/env,
     * same response shape) in WSO2 vocabulary.
     */
    @GetMapping("/wso2/history/summary")
    public ResponseEntity<List<Wso2MigrationHistorySummaryItem>> getMigrationHistorySummary(
            @RequestParam("companyName") String companyName,
            @RequestParam("org") String org,
            @RequestParam("env") String env
    ) {
        List<Wso2MigrationHistorySummaryItem> response =
                historyService.getMigrationHistorySummary(companyName, org, env);
        return ResponseEntity.ok(response);
    }
}
