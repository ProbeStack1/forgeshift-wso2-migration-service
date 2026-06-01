package com.forgeshift.wso2.migration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GCS configuration for the migration service. Used to read the artifacts the
 * assessment service staged in GCS (the per-API JSON) as a third reconcile
 * source. Opt-in: GCS is OFF unless {@code gcs.enabled=true} and credentials are
 * available — when off, the migration simply skips the assessment source.
 *
 * <pre>
 *   gcs.enabled=false
 *   gcs.project-id=
 *   gcs.default-bucket-name=        # the bucket the assessment wrote to
 *   gcs.credentials.location=       # file:/... or classpath:... (else ADC)
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "gcs")
public class GcsProperties {

    private boolean enabled = false;
    private String projectId;
    private String defaultBucketName;
    private String migrationBucketName;
    private Credentials credentials = new Credentials();

    @Data
    public static class Credentials {
        private String location;
    }
}
