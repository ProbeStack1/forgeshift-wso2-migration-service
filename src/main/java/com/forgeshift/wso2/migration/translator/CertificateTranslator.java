package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.KongCaCertificate;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * WSO2 endpoint certificate → Kong {@code ca_certificate}.
 *
 * <p>WSO2 endpoint certificates are public trust certs (no private key), so they
 * map to Kong {@code ca_certificates} (which need only the {@code cert} PEM). The
 * PEM content is NOT in the discovery snapshot — the WSO2 list API returns
 * metadata only — so it is fetched live from WSO2 and passed in as
 * {@code pemContent}. When the content can't be retrieved we still emit a
 * translated record carrying a warning so the report explains the skip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateTranslator {

    private final MigrationProperties props;

    public TranslatedCertificate translate(DiscoverySnapshot snap, String pemContent) {
        String alias = snap.getSourceId();

        List<String> tags = new ArrayList<>();
        tags.add(props.getTranslation().getTagPrefix() + ":" + alias);
        tags.add(props.getTranslation().getMigratedByTag());

        List<String> warnings = new ArrayList<>();
        if (!StringUtils.hasText(pemContent)) {
            warnings.add("Certificate '" + alias + "' has no retrievable content from WSO2 "
                    + "(endpoint-certificates/" + alias + "/content was empty) — "
                    + "Kong ca_certificate not created.");
        }

        KongCaCertificate caCert = KongCaCertificate.builder()
                .cert(pemContent)
                .tags(tags)
                .build();

        return TranslatedCertificate.builder()
                .wso2SourceId(alias)
                .wso2SourceName(snap.getSourceName())
                .caCertificate(caCert)
                .warnings(warnings)
                .build();
    }
}
