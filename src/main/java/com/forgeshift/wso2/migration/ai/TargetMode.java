package com.forgeshift.wso2.migration.ai;

/**
 * Which Kong target a translation is shaped for. Decided ONCE per migration from the
 * target control plane's type (serverless vs dedicated / self-managed) and threaded
 * down into the translators — it is a migration-target property, not per-sequence.
 */
public enum TargetMode {

    /** Konnect serverless: custom logic only via inline {@code pre-function}/{@code post-function} snippets. */
    SERVERLESS_INLINE,

    /** Konnect Dedicated Cloud / self-managed: a real custom plugin (handler.lua + schema.lua). */
    CUSTOM_PLUGIN
}
