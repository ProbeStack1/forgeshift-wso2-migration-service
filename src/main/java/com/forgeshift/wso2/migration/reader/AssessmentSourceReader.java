package com.forgeshift.wso2.migration.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeshift.wso2.migration.client.MigrationGcsClient;
import com.forgeshift.wso2.migration.config.GcsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Reads the per-API JSON the assessment service staged in GCS, for use as a third
 * reconcile source (after the export ZIP and the discovery snapshot).
 *
 * <p>The object path mirrors the assessment's {@code GcsPathBuilder} +
 * {@code Wso2DownloadService} filename rule exactly:
 * {@code {company}/{tenant}/{env}/apis/{safeFileName(name-version)}.json}.
 *
 * <p>Optional everywhere: when GCS is disabled (no {@link MigrationGcsClient} bean),
 * or the bucket/env is missing, or the object isn't present, it returns null and
 * the reconcile simply proceeds with ZIP + snapshot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentSourceReader {

    private final GcsProperties props;

    /** Null when {@code gcs.enabled=false} — the assessment source is then skipped. */
    @Autowired(required = false)
    private MigrationGcsClient gcs;

    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isEnabled() {
        return gcs != null && props.getDefaultBucketName() != null && !props.getDefaultBucketName().isBlank();
    }

    public Map<String, Object> readApiConfig(String company, String tenant, String env,
                                             String apiName, String apiVersion, String apiId) {
        if (gcs == null) {
            return null;
        }
        String bucket = props.getDefaultBucketName();
        if (bucket == null || bucket.isBlank()) {
            return null;
        }
        // env is part of the assessment's path; without it we can't locate the object.
        if (company == null || tenant == null || env == null || env.isBlank()) {
            return null;
        }
        String fileName = safeFileName(versionedName(apiName, apiVersion), apiId) + ".json";
        String object = company + "/" + tenant + "/" + env + "/apis/" + fileName;
        byte[] bytes = gcs.fetchBytes(bucket, object);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(bytes, Map.class);
            log.debug("[assessment-source] loaded {}/{} for API {}", bucket, object, apiName);
            return parsed;
        } catch (Exception e) {
            log.warn("[assessment-source] failed to parse {}/{}: {}", bucket, object, e.getMessage());
            return null;
        }
    }

    // Filename rule — kept identical to the assessment's GcsPathBuilder/Wso2DownloadService.
    private static String versionedName(String name, String version) {
        if (version == null || version.isBlank()) return name;
        return name + "-" + version;
    }

    private static String safeFileName(String name, String id) {
        String base = name != null && !name.isBlank() ? name : (id != null ? id : "resource");
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
