package com.forgeshift.wso2.migration.translator;

import java.util.List;

/**
 * Parses a WSO2 API / API-Product {@code securityScheme} array into the Kong auth plugins it implies.
 *
 * <p><b>Scheme names must be matched EXACTLY.</b> WSO2 stores a mandatory/optional indicator in the
 * SAME array — {@code oauth_basic_auth_api_key_mandatory} or {@code …_optional} — which literally
 * contains the substring {@code "api_key"} but is NOT the {@code api_key} scheme. A naive
 * {@code contains("api_key")} check therefore bolts a {@code key-auth} plugin onto essentially every
 * API (the flag is present on nearly all of them), so an OAuth2-only API ends up with both {@code jwt}
 * AND {@code key-auth}; Kong treats two auth plugins as AND, and real OAuth2 callers (bearer token, no
 * key) get 401. Exact-matching the scheme tokens avoids that.
 *
 * <p>When an API genuinely enables BOTH schemes, the {@code …_mandatory} / {@code …_optional} flag
 * decides the Kong wiring: <em>mandatory</em> → both plugins enforce (AND); <em>optional</em> → either
 * credential is accepted (OR), expressed with Kong's anonymous-consumer fallback.
 */
public final class Wso2SecuritySchemes {

    private Wso2SecuritySchemes() {}

    /** Shared anonymous consumer used as the fallback for OR-auth (optional multi-scheme) routes. */
    public static final String ANONYMOUS_USERNAME = "wso2-anonymous";
    /** Fixed id so the {@code config.anonymous} reference resolves without per-run id discovery. */
    public static final String ANONYMOUS_CONSUMER_ID = "a4000000-0000-4000-8000-000000000a11";

    public static boolean hasOauth2(List<String> securityScheme) {
        return contains(securityScheme, "oauth2");
    }

    public static boolean hasApiKey(List<String> securityScheme) {
        return contains(securityScheme, "api_key");
    }

    /**
     * True when the API enables BOTH oauth2 and api_key AND marks the combination optional — i.e. a
     * caller may present EITHER an OAuth2 token OR an API key. Maps to Kong OR-auth (anonymous
     * fallback). A {@code …_mandatory} flag (or no flag) means both are required → plain AND.
     */
    public static boolean isEitherAuthAccepted(List<String> securityScheme) {
        return hasOauth2(securityScheme) && hasApiKey(securityScheme)
                && securityScheme.stream().anyMatch(s -> s != null && s.trim().toLowerCase().endsWith("_optional"));
    }

    private static boolean contains(List<String> securityScheme, String scheme) {
        return securityScheme != null
                && securityScheme.stream().anyMatch(s -> s != null && scheme.equalsIgnoreCase(s.trim()));
    }
}
