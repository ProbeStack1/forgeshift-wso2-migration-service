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
import java.util.Base64;
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
