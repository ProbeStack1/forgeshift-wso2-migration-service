package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.config.MigrationProperties.Credentials.SecretHandling;
import com.forgeshift.wso2.migration.domain.kong.KongConsumer;
import com.forgeshift.wso2.migration.reader.CredentialReader.AppCredential;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the captured WSO2 application OAuth2 keys into Kong consumer credentials
 * so a migrated service is actually callable, matching the auth plugin
 * {@link ApiTranslator} emits per security scheme:
 * <ul>
 *   <li>{@code oauth2} API → {@code jwt} plugin → consumer {@code jwt_secrets}
 *       keyed by the WSO2 consumer key (the {@code azp}/client-id claim), verified
 *       with the Key Manager's RSA public key. The existing WSO2-issued tokens keep
 *       working; the consumer secret is not needed by the jwt plugin.</li>
 *   <li>{@code api_key} API → {@code key-auth} plugin → consumer
 *       {@code keyauth_credentials}. WSO2 never returns an API key's value after
 *       generation, so the consumer key is used as the new Kong key.</li>
 * </ul>
 *
 * <p><b>Secrets never land in the bundle as plaintext.</b> Sensitive values
 * (the key-auth key) are emitted as {@code ${ENV}} or {@code {vault://...}}
 * references per {@link MigrationProperties.Credentials#getSecretHandling()}; the
 * real values come back in {@link Result#manifest()} for the apply pipeline to
 * inject. The Key Manager public key isn't captured from WSO2 (one per Key
 * Manager, shared), so it is always emitted as a reference for the operator to
 * supply once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialTranslator {

    private final MigrationProperties props;

    /** A secret value the apply pipeline must inject for a reference left in the bundle. */
    @Data
    @Builder
    public static class SecretRef {
        private String reference;       // env var name, or {vault://...} ref
        private String value;           // SENSITIVE — never log; null when the operator must supply it
        private String applicationId;
        private String field;           // what it is (keyauth-key / km-public-key)
    }

    @Data
    @Builder
    public static class Result {
        @Builder.Default private List<SecretRef> manifest = new ArrayList<>();
        @Builder.Default private List<String> warnings = new ArrayList<>();
    }

    /**
     * Attaches credentials to {@code consumer} for one application. Mutates the
     * KongConsumer's credential lists and returns the secret manifest + warnings.
     *
     * @param schemes lowercased securityScheme values across the APIs this app is
     *                subscribed to; empty falls back to {@code oauth2} (the WSO2 default).
     */
    public Result attach(KongConsumer consumer, String appId, String appName,
                         List<AppCredential> creds, Set<String> schemes, List<String> tags) {
        Result result = Result.builder().build();
        if (!props.getCredentials().isEnabled()) return result;

        if (creds == null || creds.isEmpty()) {
            result.getWarnings().add("Application " + display(appName, appId)
                    + ": no OAuth2 credentials were captured (not visible to the assessment sync "
                    + "account, or none generated) — the Kong consumer has no credential and the "
                    + "service will reject its calls until one is added.");
            return result;
        }

        boolean wantsJwt = schemes.isEmpty() || schemes.contains("oauth2");
        boolean wantsKeyAuth = schemes.contains("api_key");
        if (schemes.isEmpty()) {
            result.getWarnings().add("Application " + display(appName, appId)
                    + ": subscribed-API security scheme unknown — defaulting to a jwt credential "
                    + "(WSO2's default). Set the API's securityScheme in the assessment to refine.");
        }

        String slug = envSlug(appName, appId);
        // A jwt credential needs the Key Manager's RSA public key. When the assessment captured it
        // (from /oauth2/jwks) it's inlined directly and the credential is self-contained. When it
        // wasn't captured: ENV/VAULT emit a reference the operator fills in, but INLINE has no
        // reference mechanism so the jwt cred is skipped (a dangling ${...} would break decK validate).
        boolean inlineMode = props.getCredentials().getSecretHandling() == SecretHandling.INLINE;
        boolean jwtSkipped = false;
        boolean jwtNeedsCert = false;
        for (AppCredential c : creds) {
            if (!StringUtils.hasText(c.getConsumerKey())) continue;
            if (wantsJwt) {
                if (StringUtils.hasText(c.getKeyManagerPublicKeyPem())) {
                    addJwtSecret(consumer, c, slug, tags, result, c.getKeyManagerPublicKeyPem());
                } else if (inlineMode) {
                    jwtSkipped = true;
                } else {
                    addJwtSecret(consumer, c, slug, tags, result, null);
                    jwtNeedsCert = true;
                }
            }
            if (wantsKeyAuth) addKeyAuth(consumer, c, appId, slug, tags, result);
        }
        for (String scheme : schemes) {
            if (!scheme.equals("oauth2") && !scheme.equals("api_key")) {
                result.getWarnings().add("Application " + display(appName, appId)
                        + ": security scheme '" + scheme + "' has no automatic Kong credential mapping "
                        + "— configure it manually.");
            }
        }
        if (jwtNeedsCert) {
            result.getWarnings().add("Application " + display(appName, appId)
                    + ": jwt credential created keyed on the consumer key — supply the WSO2 Key Manager's "
                    + "RSA public signing cert for the rsa_public_key reference so Kong can verify its tokens.");
        }
        if (jwtSkipped) {
            result.getWarnings().add("Application " + display(appName, appId)
                    + ": a jwt credential (for its OAuth2 APIs) was NOT emitted because secret-handling is INLINE "
                    + "and the Key Manager's RSA public key wasn't captured — re-run the assessment so it fetches "
                    + "the cert from /oauth2/jwks, or switch to ENV/VAULT. The key-auth credential is unaffected.");
        }
        dedupeManifest(result);
        return result;
    }

    /** Several keys can share one Key Manager ref — collapse duplicate manifest entries. */
    private static void dedupeManifest(Result result) {
        Map<String, SecretRef> byRef = new LinkedHashMap<>();
        for (SecretRef ref : result.getManifest()) {
            byRef.putIfAbsent(ref.getReference(), ref);
        }
        result.setManifest(new ArrayList<>(byRef.values()));
    }

    /**
     * @param inlinePem the Key Manager's RSA public key (PEM) to inline; when null, a reference
     *                  is emitted instead and a manifest entry records the cert the operator supplies.
     */
    private void addJwtSecret(KongConsumer consumer, AppCredential c, String slug,
                              List<String> tags, Result result, String inlinePem) {
        // jwt_secrets.key = the client-id claim value (NOT secret — it travels in every token).
        Map<String, Object> jwt = new LinkedHashMap<>();
        jwt.put("key", c.getConsumerKey());
        jwt.put("algorithm", "RS256");
        if (StringUtils.hasText(inlinePem)) {
            jwt.put("rsa_public_key", inlinePem);   // captured from /oauth2/jwks — self-contained
        } else {
            // Not captured — emit a reference the operator supplies once per Key Manager.
            String kmSlug = envSlug(c.getKeyManager() == null ? "default" : c.getKeyManager(), "km");
            String kmRef = props.getCredentials().getKeyManagerPublicKeyRef().replace("{km}", kmSlug);
            jwt.put("rsa_public_key", renderRef(kmRef));
            result.getManifest().add(SecretRef.builder()
                    .reference(kmRef).value(null)
                    .applicationId(c.getApplicationId()).field("km-public-key:" + kmSlug).build());
        }
        if (tags != null) jwt.put("tags", tags);
        addTo(consumer, "jwt", jwt);
    }

    private void addKeyAuth(KongConsumer consumer, AppCredential c, String appId, String slug,
                            List<String> tags, Result result) {
        // The key value authenticates the caller → treat as a secret reference.
        String refName = props.getCredentials().getEnvVarPrefix() + slug
                + "_" + keyTypeSlug(c.getKeyType()) + "_KEY";
        String vaultRef = "wso2-cred-" + slug.toLowerCase().replace('_', '-')
                + "-" + keyTypeSlug(c.getKeyType()).toLowerCase() + "-key";

        Map<String, Object> ka = new LinkedHashMap<>();
        Reference ref = secretReference(refName, vaultRef, c.getConsumerKey());
        ka.put("key", ref.inBundle());
        if (tags != null) ka.put("tags", tags);
        addTo(consumer, "keyauth", ka);

        if (ref.manifestRef() != null) {
            result.getManifest().add(SecretRef.builder()
                    .reference(ref.manifestRef()).value(c.getConsumerKey())
                    .applicationId(appId).field("keyauth-key").build());
        }
        result.getWarnings().add("Application " + display(c.getApplicationName(), appId)
                + ": WSO2 does not expose an API key's value after generation, so the consumer key is "
                + "used as the Kong key-auth key — clients must send 'apikey: <that value>'.");
    }

    @SuppressWarnings("unchecked")
    private void addTo(KongConsumer consumer, String type, Map<String, Object> cred) {
        switch (type) {
            case "jwt" -> {
                if (consumer.getJwt_secrets() == null) consumer.setJwt_secrets(new ArrayList<>());
                consumer.getJwt_secrets().add(cred);
            }
            case "keyauth" -> {
                if (consumer.getKeyauth_credentials() == null) consumer.setKeyauth_credentials(new ArrayList<>());
                consumer.getKeyauth_credentials().add(cred);
            }
            default -> { }
        }
    }

    /** The string written into the bundle for a non-secret reference (KM public key ref). */
    private String renderRef(String envOrVaultName) {
        return switch (props.getCredentials().getSecretHandling()) {
            case VAULT -> "{vault://" + props.getCredentials().getVaultBackend() + "/"
                    + envOrVaultName.toLowerCase().replace('_', '-') + "}";
            default -> "${" + envOrVaultName + "}";   // ENV and INLINE both leave a named ref (no value to inline)
        };
    }

    /** Resolves a secret value to what goes in the bundle + what (if anything) the pipeline must inject. */
    private Reference secretReference(String envVar, String vaultRef, String value) {
        return switch (props.getCredentials().getSecretHandling()) {
            case INLINE -> new Reference(value, null);
            case VAULT -> new Reference(
                    "{vault://" + props.getCredentials().getVaultBackend() + "/" + vaultRef + "}", vaultRef);
            case ENV -> new Reference("${" + envVar + "}", envVar);
        };
    }

    private record Reference(String inBundle, String manifestRef) {}

    private static String display(String name, String id) {
        return StringUtils.hasText(name) ? name : id;
    }

    private static String keyTypeSlug(String keyType) {
        return StringUtils.hasText(keyType) ? keyType.toUpperCase().replaceAll("[^A-Z0-9]", "") : "PRODUCTION";
    }

    /** Env-var-safe slug: uppercase alnum + underscore, from the app name (or id fallback). */
    private static String envSlug(String name, String fallback) {
        String base = StringUtils.hasText(name) ? name : fallback;
        String slug = base.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("(^_|_$)", "");
        return StringUtils.hasText(slug) ? slug : "APP";
    }
}
