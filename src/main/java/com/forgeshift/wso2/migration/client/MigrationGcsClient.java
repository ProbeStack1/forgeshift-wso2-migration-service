package com.forgeshift.wso2.migration.client;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Read-only GCS client for the migration service. Created only when a
 * {@link Storage} bean exists (i.e. {@code gcs.enabled=true}). Used to read the
 * artifacts the assessment service staged in GCS. Never throws on a miss — returns
 * null / false so callers degrade gracefully.
 */
@Slf4j
@Component
@ConditionalOnBean(Storage.class)
@RequiredArgsConstructor
public class MigrationGcsClient {

    private final Storage storage;

    /** Fetch an object's bytes, or null if it doesn't exist / on error. */
    public byte[] fetchBytes(String bucketName, String objectName) {
        try {
            Blob blob = storage.get(BlobId.of(bucketName, objectName));
            if (blob == null || !blob.exists()) {
                return null;
            }
            return blob.getContent();
        } catch (Exception e) {
            log.warn("[GCS] fetch failed for {}/{}: {}", bucketName, objectName, e.getMessage());
            return null;
        }
    }

    public boolean objectExists(String bucketName, String objectName) {
        try {
            Blob blob = storage.get(BlobId.of(bucketName, objectName));
            return blob != null && blob.exists();
        } catch (Exception e) {
            log.warn("[GCS] existence check failed for {}/{}: {}", bucketName, objectName, e.getMessage());
            return false;
        }
    }
}
