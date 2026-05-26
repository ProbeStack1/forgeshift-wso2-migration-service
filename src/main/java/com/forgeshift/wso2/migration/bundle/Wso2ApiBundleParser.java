package com.forgeshift.wso2.migration.bundle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Walks a WSO2 API export ZIP (in memory) and produces a {@link Wso2ApiBundle}.
 *
 * <p>WSO2 APIM 4.x export layout:
 * <pre>
 * &lt;APIName-Version&gt;/
 *   Meta-information/api.json
 *   Definitions/swagger.json
 *   Sequences/{in,out,fault}-sequence/*.xml
 *   Deployments/deployments.json
 *   Endpoint-certificates/*
 *   Client-certificates/*
 *   Image/icon.* (ignored)
 *   README.md   (ignored)
 * </pre>
 *
 * <p>We deliberately avoid writing the ZIP to disk — keeps the migration
 * stateless and removes a whole class of cleanup bugs. Bundles top out at
 * a few MB even for chatty APIs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Wso2ApiBundleParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Wso2ApiBundle parse(byte[] zipBytes) {
        Wso2ApiBundle.Wso2ApiBundleBuilder out = Wso2ApiBundle.builder();
        Map<String, Object> apiJson = new LinkedHashMap<>();
        Map<String, Object> swaggerJson = new LinkedHashMap<>();
        Map<String, String> sequences = new LinkedHashMap<>();
        List<Map<String, Object>> deployments = new ArrayList<>();
        List<Wso2ApiBundle.CertificateRef> endpointCerts = new ArrayList<>();
        List<Wso2ApiBundle.CertificateRef> clientCerts = new ArrayList<>();
        String rootName = null;

        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    if (rootName == null) {
                        // First directory entry is the root, e.g. "PetStoreAPI-1.0.0/"
                        rootName = stripTrailingSlash(e.getName());
                    }
                    continue;
                }
                String name = e.getName();
                String relative = stripRoot(name);
                byte[] body = zin.readAllBytes();

                if (relative.equals("Meta-information/api.json")) {
                    apiJson = readJsonMap(body);
                } else if (relative.equals("Definitions/swagger.json")) {
                    swaggerJson = readJsonMap(body);
                } else if (relative.startsWith("Sequences/")) {
                    // Sequences/in-sequence/foo.xml → "in:foo.xml"
                    String[] parts = relative.split("/", 3);
                    if (parts.length == 3) {
                        String flow = parts[1].replace("-sequence", "");
                        sequences.put(flow + ":" + parts[2],
                                new String(body, StandardCharsets.UTF_8));
                    }
                } else if (relative.equals("Deployments/deployments.json")) {
                    deployments = readJsonList(body);
                } else if (relative.startsWith("Endpoint-certificates/")) {
                    endpointCerts.add(toCertRef(relative, body));
                } else if (relative.startsWith("Client-certificates/")) {
                    clientCerts.add(toCertRef(relative, body));
                }
                // everything else (Image/, README) is ignored
            }
        } catch (IOException ioe) {
            throw new IllegalStateException("Failed to parse WSO2 API export ZIP: " + ioe.getMessage(), ioe);
        }

        return out.apiJson(apiJson)
                .swaggerJson(swaggerJson)
                .sequences(sequences)
                .deployments(deployments)
                .endpointCerts(endpointCerts)
                .clientCerts(clientCerts)
                .rootName(rootName)
                .build();
    }

    private Map<String, Object> readJsonMap(byte[] body) {
        if (body == null || body.length == 0) return new LinkedHashMap<>();
        try {
            Object parsed = objectMapper.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                return typed;
            }
            log.warn("Expected JSON object but got {}", parsed == null ? "null" : parsed.getClass());
            return new LinkedHashMap<>();
        } catch (IOException ioe) {
            log.warn("Failed to parse JSON entry as map: {}", ioe.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private List<Map<String, Object>> readJsonList(byte[] body) {
        if (body == null || body.length == 0) return new ArrayList<>();
        try {
            return objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException ioe) {
            log.warn("Failed to parse JSON entry as list: {}", ioe.getMessage());
            return new ArrayList<>();
        }
    }

    private Wso2ApiBundle.CertificateRef toCertRef(String relative, byte[] body) {
        return Wso2ApiBundle.CertificateRef.builder()
                .fileName(relative)
                .sizeBytes(body == null ? 0 : body.length)
                .build();
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String stripRoot(String name) {
        int slash = name.indexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
