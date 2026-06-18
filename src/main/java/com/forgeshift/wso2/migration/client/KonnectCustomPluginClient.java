package com.forgeshift.wso2.migration.client;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Konnect client for Dedicated Cloud custom-plugin <b>assets</b> (handler.lua + schema.lua) and for
 * probing whether a control plane can run them.
 *
 * <p>The asset upload is the one piece decK can't carry: the handler+schema must be REGISTERED on the
 * control plane ({@code POST .../core-entities/custom-plugins}) <b>before</b> a decK bundle references
 * a plugin instance of the same name, or {@code deck gateway validate/apply} fails "schema not found".
 * Idempotent: a re-run adopts the existing plugin by name and PUT-updates it (no duplicates), mirroring
 * {@link KonnectAdminClient}'s adopt-on-unique-constraint behaviour.
 */
@Slf4j
@Component
public class KonnectCustomPluginClient {

    private final WebClient webClient;
    private final MigrationProperties props;

    public KonnectCustomPluginClient(@Qualifier("konnectWebClient") WebClient webClient,
                                     MigrationProperties props) {
        this.webClient = webClient;
        this.props = props;
    }

    public enum ControlPlaneType { CUSTOM_PLUGIN_CAPABLE, SERVERLESS, UNKNOWN }

    @Data
    @Builder
    public static class Result {
        private boolean ok;
        private String action;     // CREATED / UPDATED / FAILED
        private String pluginId;
        private String error;
    }

    /** Register (or, on conflict, update) the custom-plugin asset on the control plane. Idempotent by name. */
    public Result upsert(KongKonnectCredentials creds, CustomPluginArtifact a) {
        if (creds == null || !StringUtils.hasText(creds.getKonnectAccessToken())
                || !StringUtils.hasText(creds.getControlPlaneId())) {
            return Result.builder().ok(false).action("FAILED")
                    .error("Konnect credentials incomplete (need accessToken + controlPlaneId).").build();
        }
        if (a == null || !a.isComplete()) {
            return Result.builder().ok(false).action("FAILED").error("custom plugin artifact incomplete").build();
        }
        String endpoint = customPluginsEndpoint(creds);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", a.getPluginName());
        body.put("handler", a.getHandlerLua());
        body.put("schema", a.getSchemaLua());
        try {
            Map<String, Object> resp = post(creds, endpoint, body);
            return Result.builder().ok(true).action("CREATED")
                    .pluginId(resp == null ? null : str(resp.get("id"))).build();
        } catch (WebClientResponseException e) {
            if (isConflict(e)) {
                String id = findByName(creds, endpoint, a.getPluginName());
                if (id != null) {
                    try {
                        Map<String, Object> resp = put(creds, endpoint + "/" + id, body);
                        return Result.builder().ok(true).action("UPDATED")
                                .pluginId(resp == null ? id : str(resp.getOrDefault("id", id))).build();
                    } catch (Exception putErr) {
                        return Result.builder().ok(false).action("FAILED")
                                .error("update failed: " + putErr.getMessage()).build();
                    }
                }
            }
            return Result.builder().ok(false).action("FAILED")
                    .error("Konnect " + e.getStatusCode() + ": " + e.getResponseBodyAsString()).build();
        } catch (Exception e) {
            return Result.builder().ok(false).action("FAILED").error(e.getMessage()).build();
        }
    }

    /** Probe the control plane's type so we never push a custom plugin to a serverless CP. */
    public ControlPlaneType clusterType(KongKonnectCredentials creds) {
        if (creds == null || !StringUtils.hasText(creds.getKonnectAccessToken())
                || !StringUtils.hasText(creds.getControlPlaneId())) {
            return ControlPlaneType.UNKNOWN;
        }
        try {
            String url = trimTrailingSlash(creds.getKonnectBaseUrl())
                    + "/v2/control-planes/" + creds.getControlPlaneId();
            Map<String, Object> body = get(creds, url);
            String ct = body != null && body.get("config") instanceof Map<?, ?> cfg && cfg.get("cluster_type") != null
                    ? cfg.get("cluster_type").toString() : null;
            return classify(ct);
        } catch (Exception e) {
            log.warn("Control-plane type probe failed for {}: {}", creds.getControlPlaneId(), e.getMessage());
            return ControlPlaneType.UNKNOWN;
        }
    }

    public boolean supportsCustomPlugins(KongKonnectCredentials creds) {
        return clusterType(creds) == ControlPlaneType.CUSTOM_PLUGIN_CAPABLE;
    }

    /** Map a Konnect {@code config.cluster_type} string to our support classification. */
    static ControlPlaneType classify(String clusterType) {
        if (clusterType == null || clusterType.isBlank()) return ControlPlaneType.UNKNOWN;
        String c = clusterType.toUpperCase();
        if (c.contains("SERVERLESS")) return ControlPlaneType.SERVERLESS;
        // CLUSTER_TYPE_CONTROL_PLANE (hybrid / Dedicated Cloud) + k8s ingress run real data planes.
        if (c.contains("CONTROL_PLANE") || c.contains("HYBRID") || c.contains("K8S") || c.contains("INGRESS")) {
            return ControlPlaneType.CUSTOM_PLUGIN_CAPABLE;
        }
        return ControlPlaneType.UNKNOWN;
    }

    // ---------------- helpers ----------------

    private String customPluginsEndpoint(KongKonnectCredentials creds) {
        return trimTrailingSlash(creds.getKonnectBaseUrl())
                + "/v2/control-planes/" + creds.getControlPlaneId()
                + "/core-entities" + props.getCustomPlugins().getPath();
    }

    @SuppressWarnings("unchecked")
    private String findByName(KongKonnectCredentials creds, String endpoint, String name) {
        try {
            Map<String, Object> body = get(creds, endpoint);
            if (body == null || !(body.get("data") instanceof List<?> items)) return null;
            for (Object it : items) {
                if (it instanceof Map<?, ?> m && name.equals(str(m.get("name")))) {
                    return str(m.get("id"));
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("custom-plugin name lookup failed for {}: {}", name, e.getMessage());
            return null;
        }
    }

    private boolean isConflict(WebClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 409) return true;
        if (code == 400) {
            String b = e.getResponseBodyAsString();
            return b != null && b.contains("(type: unique) constraint failed");
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(KongKonnectCredentials creds, String url, Object body) {
        return (Map<String, Object>) webClient.post().uri(url)
                .header("Authorization", "Bearer " + creds.getKonnectAccessToken())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds())).block();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> put(KongKonnectCredentials creds, String url, Object body) {
        return (Map<String, Object>) webClient.put().uri(url)
                .header("Authorization", "Bearer " + creds.getKonnectAccessToken())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds())).block();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(KongKonnectCredentials creds, String url) {
        return (Map<String, Object>) webClient.get().uri(url)
                .header("Authorization", "Bearer " + creds.getKonnectAccessToken())
                .accept(MediaType.APPLICATION_JSON).retrieve().bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds())).block();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String trimTrailingSlash(String s) {
        if (!StringUtils.hasText(s)) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
