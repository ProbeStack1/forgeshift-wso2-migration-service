package com.forgeshift.wso2.migration.domain;

/** Lifecycle states for a migration job. */
public enum MigrationState {
    PENDING,              // accepted, not yet started
    LOADING,              // reading snapshots + profile from Mongo
    DOWNLOADING_BUNDLES,  // pulling per-API export ZIPs from WSO2
    TRANSLATING,          // producing Kong entity plan in memory
    DIFFING,              // computing diff vs Konnect current state (dry-run target)
    DEPLOYING,            // writing entities to Konnect (REST mode)
    GENERATING_BUNDLE,    // building the decK kong.yaml bundle (deck delivery mode)
    DEPLOYING_TO_KONG,    // bundle pushed to git — waiting for the pipeline's deck-apply result (callback)
    COMPLETED,            // all entities applied (deck: pipeline reported success)
    FAILED,               // terminal error; see lastError
    CANCELLED,            // operator cancelled mid-flight
    DRY_RUN_DONE,         // dry-run completed; no Konnect writes occurred
    TIMED_OUT             // no deck-apply callback arrived within the timeout — check the pipeline run
}
