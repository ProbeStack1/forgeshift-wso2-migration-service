package com.forgeshift.wso2.migration.client;

import lombok.Builder;
import lombok.Data;

/** Outcome of one upsert call. */
@Data
@Builder
public class KonnectUpsertResult {
    /** CREATED / UPDATED / UNCHANGED / FAILED. */
    private String action;
    private String kongUuid;
    private String errorMessage;
}
