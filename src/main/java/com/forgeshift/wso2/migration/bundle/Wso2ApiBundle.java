package com.forgeshift.wso2.migration.bundle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed contents of a WSO2 API export ZIP. All fields are optional —
 * older WSO2 versions / APIs without mediation or certificates will leave
 * the corresponding maps empty.
 *
 * <p>Field reference:
 * <ul>
 *   <li>{@link #apiJson} - {@code Meta-information/api.json}, the authoritative
 *       API definition (endpointConfig, policies, securityScheme, ...). Same
 *       shape the discovery service caches in {@code payload} but pulled
 *       fresh at migration time so we never operate on stale data.</li>
 *   <li>{@link #swaggerJson} - {@code Definitions/swagger.json}, the OpenAPI
 *       2.0 spec. Used to enrich routes when {@code apiJson.operations[]}
 *       is empty.</li>
 *   <li>{@link #sequences} - request/response/fault mediation XML keyed by
 *       {@code "<flow>:<filename>"} (e.g. {@code "in:default-in.xml"}).
 *       Surfaced as warnings only — translation to Kong plugins is out
 *       of scope for this phase.</li>
 *   <li>{@link #deployments} - {@code Deployments/deployments.json} parsed
 *       as a list of maps; informational.</li>
 *   <li>{@link #endpointCerts} / {@link #clientCerts} - per-file metadata
 *       (filename, alias if extractable, raw bytes length); also warning
 *       material since cert migration to Konnect ssl objects is follow-up
 *       work.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Wso2ApiBundle {

    @Builder.Default private Map<String, Object> apiJson = new LinkedHashMap<>();
    @Builder.Default private Map<String, Object> swaggerJson = new LinkedHashMap<>();
    @Builder.Default private Map<String, String> sequences = new LinkedHashMap<>();
    @Builder.Default private List<Map<String, Object>> deployments = new ArrayList<>();
    @Builder.Default private List<CertificateRef> endpointCerts = new ArrayList<>();
    @Builder.Default private List<CertificateRef> clientCerts = new ArrayList<>();

    /** Name of the root directory inside the ZIP, e.g. {@code "PetStoreAPI-1.0.0"}. */
    private String rootName;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CertificateRef {
        private String fileName;
        private int sizeBytes;
    }
}
