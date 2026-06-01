package com.forgeshift.wso2.migration.ai;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of an AI translation of one WSO2 mediation sequence (Synapse XML) →
 * Konnect serverless Lua snippet. Always returned (never thrown) so call sites
 * have a clear fall-back path.
 *
 * <ul>
 *   <li>translatable=true, valid=true  → safe to embed in pre/post-function</li>
 *   <li>translatable=true, valid=false → AI returned Lua but the sandbox rejected it
 *       (fall back to manual review, carrying the rejected Lua + violations)</li>
 *   <li>translatable=false             → the sequence depends on things that can't run
 *       in the serverless sandbox; {@code reason} explains why and
 *       {@code externalServiceStub} carries a backend skeleton to deploy instead.</li>
 * </ul>
 */
@Data
@Builder
public class AiTranslationResult {

    /** True when the model judged the sequence portable to pre/post-function. */
    private boolean translatable;

    /** True after LuaSandboxValidator passes. Always false when translatable=false. */
    private boolean valid;

    /** Phase to attach the snippet to: "access", "header_filter", "body_filter", "log". */
    private String phase;

    /** True when the snippet belongs in post-function rather than pre-function. */
    private boolean postFunction;

    /** Translator self-rated confidence, 0..1. */
    private double confidence;

    /** Generated Lua source (without the pcall wrapper — call sites add that). */
    private String lua;

    /** APIs / mediators the model could not fully translate. */
    @Builder.Default
    private List<String> unsupportedApis = new ArrayList<>();

    /** Validator violations (only populated when valid=false). */
    @Builder.Default
    private List<String> violations = new ArrayList<>();

    /** When translatable=false: why, and a stub for the alternative path. */
    private String reason;
    private String externalServiceStub;

    /** Free-form notes from the model. */
    private String notes;

    /** Convenience: usable Lua → snippet that callers can splice directly. */
    public boolean isUsableLua() {
        return translatable && valid && lua != null && !lua.isBlank();
    }
}
