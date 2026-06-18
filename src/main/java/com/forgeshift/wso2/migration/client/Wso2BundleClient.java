package com.forgeshift.wso2.migration.client;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.reader.Wso2Credentials;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Calls WSO2 APIM to (1) acquire an OAuth2 access token via password grant
 * using the DCR clientId/secret stored on the profile, and (2) download the
 * full API export ZIP for a given API id.
 *
 * <p>The TLS-insecure WebClient is built per-credentials so we can honour
 * the per-profile {@code trustSelfSigned} flag — production WSO2 boxes
 * with proper certs use the strict client; lab boxes with self-signed
 * certs use the insecure one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Wso2BundleClient {

    private final MigrationProperties props;

    /**
     * Password grant against WSO2 {@code /oauth2/token}. Returns the
     * {@code access_token} string. Throws {@link IllegalStateException}
     * on transport or auth failure so the caller can surface a clear error.
     */
    public String acquireToken(Wso2Credentials creds) {
        String base = trimTrailingSlash(creds.getWso2BaseUrl());
        String basic = Base64.getEncoder().encodeToString(
                (creds.getClientId() + ":" + creds.getClientSecret()).getBytes());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", creds.getUsername());
        form.add("password", creds.getPassword());
        form.add("scope", props.getWso2().getPublisherScope());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = webClientFor(creds).post()
                    .uri(base + props.getWso2().getTokenPath())
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(props.getWso2().getTimeoutSeconds()))
                    .block();
            Object t = body != null ? body.get("access_token") : null;
            if (t == null) {
                throw new IllegalStateException(
                        "WSO2 /oauth2/token returned no access_token (body=" + body + ")");
            }
            // Log granted scopes so operators can spot when WSO2 silently
            // dropped a scope the request asked for (typical cause of the
            // 903220 "Failed to get API" on the export endpoint when
            // apim:api_import_export isn't allowlisted on the DCR app).
            if (body.get("scope") != null) {
                log.info("WSO2 token granted scopes: [{}] (requested: [{}])",
                        body.get("scope"), props.getWso2().getPublisherScope());
            }
            return t.toString();
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("WSO2 /oauth2/token returned "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * {@code GET /api/am/publisher/v4/apis/export?apiId=...&format=...&preserveStatus=...}
     * — returns the raw ZIP bytes for the API. The export endpoint streams a
     * multipart ZIP containing {@code Meta-information/api.json},
     * {@code Definitions/swagger.json}, {@code Sequences/*},
     * {@code Deployments/deployments.json}, {@code Endpoint-certificates/*},
     * {@code Client-certificates/*}.
     *
     * <p>The path is intentionally NOT keyed by {apiId} — APIM 4.x uses a
     * query-string form. Earlier {@code /apis/{apiId}/export} guesses 404 on
     * 4.x installs.
     */
    public byte[] exportApi(String accessToken, Wso2Credentials creds, String apiId) {
        String base = trimTrailingSlash(creds.getWso2BaseUrl());
        String path = props.getWso2().getExportPath();
        try {
            byte[] zip = webClientFor(creds).get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme(java.net.URI.create(base).getScheme())
                            .host(java.net.URI.create(base).getHost())
                            .port(java.net.URI.create(base).getPort())
                            .path(path)
                            .queryParam("apiId", apiId)
                            .queryParam("format", props.getWso2().getExportFormat())
                            .queryParam("preserveStatus", props.getWso2().isPreserveStatus())
                            .build())
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.parseMediaType("application/zip"))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(props.getWso2().getTimeoutSeconds()))
                    .block();
            if (zip == null || zip.length == 0) {
                throw new IllegalStateException("WSO2 export returned empty body for apiId=" + apiId);
            }
            log.debug("Downloaded WSO2 API export for apiId={} ({} bytes)", apiId, zip.length);
            return zip;
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("WSO2 " + path + "?apiId=" + apiId + " returned "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * Downloads the PEM content of one endpoint certificate from WSO2
     * ({@code GET /api/am/publisher/v4/endpoint-certificates/{alias}/content}).
     * The discovery/assessment snapshots carry cert metadata only, so the actual
     * certificate must be fetched live before it can be pushed to Kong as a
     * ca_certificate. Returns null on failure so the caller can skip-and-warn.
     */
    public String fetchCertificateContent(String accessToken, Wso2Credentials creds, String alias) {
        String base = trimTrailingSlash(creds.getWso2BaseUrl());
        String url = base + "/api/am/publisher/v4/endpoint-certificates/" + alias + "/content";
        try {
            byte[] body = webClientFor(creds).get()
                    .uri(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN,
                            MediaType.parseMediaType("application/x-x509-ca-cert"))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(props.getWso2().getTimeoutSeconds()))
                    .block();
            if (body == null || body.length == 0) {
                log.warn("WSO2 endpoint-certificate content empty for alias={}", alias);
                return null;
            }
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        } catch (WebClientResponseException e) {
            log.warn("fetchCertificateContent({}) failed: status={} body={}",
                    alias, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("fetchCertificateContent({}) failed: {}", alias, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the Synapse XML of one API mediation policy from WSO2
     * ({@code GET /api/am/publisher/v4/apis/{apiId}/mediation-policies/{policyId}}).
     * The discovery snapshot carries only metadata, so the actual sequence must be
     * fetched before it can be AI-translated. APIM 4.x returns the XML in the
     * {@code config} (or {@code content}) field. Returns null on failure.
     */
    public String fetchMediationPolicyContent(String accessToken, Wso2Credentials creds,
                                              String apiId, String policyId) {
        String base = trimTrailingSlash(creds.getWso2BaseUrl());
        String url = base + "/api/am/publisher/v4/apis/" + apiId + "/mediation-policies/" + policyId;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> bodyMap = webClientFor(creds).get()
                    .uri(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(props.getWso2().getTimeoutSeconds()))
                    .block();
            if (bodyMap == null) return null;
            Object cfg = bodyMap.get("config");
            if (cfg == null) cfg = bodyMap.get("content");
            return cfg == null ? null : cfg.toString();
        } catch (WebClientResponseException e) {
            log.warn("fetchMediationPolicyContent({}/{}) failed: status={} body={}",
                    apiId, policyId, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("fetchMediationPolicyContent({}/{}) failed: {}", apiId, policyId, e.getMessage());
            return null;
        }
    }

    /**
     * Downloads an operation policy's Synapse definition from WSO2 and returns the {@code .j2} fragment
     * (the snapshot/export carry only the policy name + parameters, never the body). The content ZIP
     * contains {@code <name>/<name>.j2} + {@code <name>/<name>.yaml}.
     *
     * <p>A common policy attached to an API is CLONED into an API-specific policy with a NEW id, served
     * only from {@code /apis/{apiId}/operation-policies/{id}/content} (the common
     * {@code /operation-policies/{id}/content} 404s for it). So we try the API-scoped endpoint first,
     * then fall back to the common one. Needs {@code apim:api_view} (API-scoped) /
     * {@code apim:common_operation_policy_view} (common) on the token. Returns null on failure.
     */
    public String fetchOperationPolicyContent(String accessToken, Wso2Credentials creds,
                                              String apiId, String policyId) {
        String base = trimTrailingSlash(creds.getWso2BaseUrl());
        String j2 = null;
        if (StringUtils.hasText(apiId)) {
            j2 = downloadPolicyJ2(accessToken, creds, base + "/api/am/publisher/v4/apis/" + apiId
                    + "/operation-policies/" + policyId + "/content");
        }
        if (j2 == null) {
            j2 = downloadPolicyJ2(accessToken, creds, base
                    + "/api/am/publisher/v4/operation-policies/" + policyId + "/content");
        }
        if (j2 == null) {
            log.warn("operation-policy {} (api {}) content unavailable from both API-scoped and common endpoints",
                    policyId, apiId);
        }
        return j2;
    }

    /** GET a content ZIP and return its {@code .j2} entry, or null on any failure / no .j2. */
    private String downloadPolicyJ2(String accessToken, Wso2Credentials creds, String url) {
        try {
            byte[] zip = webClientFor(creds).get()
                    .uri(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.parseMediaType("application/zip"))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(props.getWso2().getTimeoutSeconds()))
                    .block();
            if (zip == null || zip.length == 0) return null;
            try (java.util.zip.ZipInputStream zis =
                         new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.getName().toLowerCase().endsWith(".j2")) {
                        return new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
            return null;
        } catch (WebClientResponseException e) {
            log.debug("op-policy content {} -> {} {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.debug("op-policy content {} failed: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Password-grant token for the WSO2 DevPortal API (different scopes than the publisher token), used
     * by the live consumer-credential capture to read applications + their OAuth2 keys. Uses the same
     * profile DCR client. Returns the access token; throws on failure so the caller can skip-and-warn.
     */
    public String acquireDevPortalToken(Wso2Credentials creds) {
        String base = trimTrailingSlash(creds.getWso2BaseUrl());
        String basic = Base64.getEncoder().encodeToString(
                (creds.getClientId() + ":" + creds.getClientSecret()).getBytes());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", creds.getUsername());
        form.add("password", creds.getPassword());
        form.add("scope", props.getCredentials().getDevPortalScope());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = webClientFor(creds).post()
                    .uri(base + props.getWso2().getTokenPath())
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve().bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(props.getWso2().getTimeoutSeconds()))
                    .block();
            Object t = body != null ? body.get("access_token") : null;
            if (t == null) throw new IllegalStateException("DevPortal /oauth2/token returned no access_token");
            return t.toString();
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("DevPortal /oauth2/token returned "
                    + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * Reads one application's OAuth2 keys from the DevPortal
     * ({@code GET /api/am/devportal/v3/applications/{id}/oauth-keys}). Returns one map per key with
     * {@code consumerKey} (the PUBLIC client id — the {@code azp} claim Kong's jwt plugin keys on),
     * {@code keyType} (PRODUCTION/SANDBOX), {@code keyManager}. The client SECRET is intentionally not
     * read. Empty list on failure or when the app has no keys generated.
     */
    public List<Map<String, String>> fetchApplicationOauthKeys(String devToken, Wso2Credentials creds, String appId) {
        String base = trimTrailingSlash(creds.getWso2BaseUrl());
        String url = base + "/api/am/devportal/v3/applications/" + appId + "/oauth-keys";
        List<Map<String, String>> out = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = webClientFor(creds).get()
                    .uri(url).header("Authorization", "Bearer " + devToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(props.getWso2().getTimeoutSeconds()))
                    .block();
            Object list = body == null ? null : body.get("list");
            if (list instanceof List<?> l) {
                for (Object e : l) {
                    if (!(e instanceof Map<?, ?> km)) continue;
                    Object ck = km.get("consumerKey");
                    if (ck == null || !StringUtils.hasText(ck.toString())) continue;
                    Map<String, String> k = new java.util.LinkedHashMap<>();
                    k.put("consumerKey", ck.toString());
                    if (km.get("keyType") != null) k.put("keyType", km.get("keyType").toString());
                    if (km.get("keyManager") != null) k.put("keyManager", km.get("keyManager").toString());
                    out.add(k);
                }
            }
        } catch (WebClientResponseException e) {
            log.warn("fetchApplicationOauthKeys({}) failed: status={} body={}",
                    appId, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("fetchApplicationOauthKeys({}) failed: {}", appId, e.getMessage());
        }
        return out;
    }

    /**
     * Fetches the WSO2 Key Manager's RSA public signing key from {@code GET /oauth2/jwks} (public, no
     * auth) and returns it as an X.509 {@code SubjectPublicKeyInfo} PEM ({@code -----BEGIN PUBLIC KEY-----}),
     * which is what Kong's jwt plugin needs as {@code rsa_public_key} to verify the WSO2-issued RS256
     * tokens. Returns null on failure. One Key Manager per tenant → fetch once and reuse for all consumers.
     */
    public String fetchKeyManagerPublicKeyPem(Wso2Credentials creds) {
        String base = trimTrailingSlash(creds.getWso2BaseUrl());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> jwks = webClientFor(creds).get()
                    .uri(base + "/oauth2/jwks")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(props.getWso2().getTimeoutSeconds()))
                    .block();
            Object keysObj = jwks == null ? null : jwks.get("keys");
            if (!(keysObj instanceof List<?> keys) || keys.isEmpty()) return null;
            Map<?, ?> sig = null;
            for (Object o : keys) {
                if (o instanceof Map<?, ?> k && "RSA".equals(k.get("kty"))
                        && (k.get("use") == null || "sig".equals(k.get("use")))) { sig = k; break; }
            }
            if (sig == null && keys.get(0) instanceof Map<?, ?> k0) sig = k0;
            if (sig == null) return null;
            Object n = sig.get("n"), e = sig.get("e");
            if (n == null || e == null) return null;
            java.math.BigInteger modulus = new java.math.BigInteger(1, Base64.getUrlDecoder().decode(n.toString()));
            java.math.BigInteger exponent = new java.math.BigInteger(1, Base64.getUrlDecoder().decode(e.toString()));
            java.security.PublicKey pub = java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.RSAPublicKeySpec(modulus, exponent));
            String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pub.getEncoded());
            return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
        } catch (Exception ex) {
            log.warn("fetchKeyManagerPublicKeyPem failed: {}", ex.getMessage());
            return null;
        }
    }

    private WebClient webClientFor(Wso2Credentials creds) {
        HttpClient http = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(props.getWso2().getTimeoutSeconds()));
        if (creds.isTrustSelfSigned()) {
            http = http.secure(spec -> {
                try {
                    spec.sslContext(SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .build());
                } catch (SSLException e) {
                    throw new IllegalStateException("Failed to build insecure SSL context", e);
                }
            });
        }
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                .build();
    }

    private static String trimTrailingSlash(String s) {
        if (!StringUtils.hasText(s)) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
