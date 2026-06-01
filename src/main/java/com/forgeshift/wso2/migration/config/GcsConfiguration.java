package com.forgeshift.wso2.migration.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Builds the GCS {@link Storage} client — only when {@code gcs.enabled=true}.
 * Mirrors the assessment service's GcsConfiguration so the same bucket/credentials
 * setup applies. When disabled, no Storage bean exists and the GCS reader / the
 * assessment reconcile source are simply absent (migration still works).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gcs.enabled", havingValue = "true")
public class GcsConfiguration {

    private final GcsProperties props;

    @Bean
    public Storage storage() throws IOException {
        log.info("[GCS] Initializing GCS client for migration. projectId={}", props.getProjectId());
        StorageOptions.Builder builder = StorageOptions.newBuilder();

        if (props.getProjectId() != null && !props.getProjectId().isBlank()
                && !"your-project-id".equals(props.getProjectId())) {
            builder.setProjectId(props.getProjectId());
        }

        String credLoc = props.getCredentials() == null ? null : props.getCredentials().getLocation();
        if (credLoc != null && !credLoc.isBlank()) {
            try {
                // Read-only scope: the migration service only ever READS staged
                // artifacts from GCS, never writes. Scoping the token to read-only
                // means this process cannot modify/delete objects even if the
                // underlying service account happens to have write IAM.
                GoogleCredentials credentials = loadCredentials(credLoc)
                        .createScoped("https://www.googleapis.com/auth/devstorage.read_only");
                credentials.refreshIfExpired();
                builder.setCredentials(credentials);
                log.info("[GCS] Loaded credentials from {}", credLoc);
            } catch (IOException ex) {
                log.warn("[GCS] Could not load credentials from {} ({}). Falling back to ADC.",
                        credLoc, ex.getMessage());
            }
        } else {
            log.info("[GCS] No credentials location configured. Using Application Default Credentials.");
        }

        return builder.build().getService();
    }

    private GoogleCredentials loadCredentials(String location) throws IOException {
        if (location.startsWith("file:")) {
            return loadFromFile(location.substring("file:".length()));
        }
        if (location.startsWith("classpath:")) {
            String resource = location.substring("classpath:".length());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (in == null) throw new IOException("Credentials resource not found on classpath: " + resource);
                return GoogleCredentials.fromStream(in);
            }
        }
        return loadFromFile(location);
    }

    private GoogleCredentials loadFromFile(String path) throws IOException {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) {
            throw new IOException("Credentials file missing or unreadable: " + path);
        }
        try (FileInputStream in = new FileInputStream(f)) {
            return GoogleCredentials.fromStream(in);
        }
    }
}
