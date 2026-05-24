package com.forgeshift.wso2.migration.domain;

/** Lifecycle states for a migration job. */
public enum MigrationState {
    PENDING,        // accepted, not yet started
    LOADING,        // reading snapshots + profile from Mongo
    TRANSLATING,    // producing Kong entity plan in memory
    DIFFING,        // computing diff vs Konnect current state (dry-run target)
    DEPLOYING,      // writing entities to Konnect
    COMPLETED,      // all entities applied
    FAILED,         // terminal error; see lastError
    CANCELLED,      // operator cancelled mid-flight
    DRY_RUN_DONE    // dry-run completed; no Konnect writes occurred
}
