package com.forgeshift.wso2.migration.reader;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Reads Kong Konnect profiles written by the profile-config service from the
 * {@code kong_konnect_profiles} collection. Falls back to the static
 * {@code forgeshift.migration.konnect.*-fallback} config when no profile
 * matches the requested (companyName, profileName).
 *
 * Uses raw {@code org.bson.Document} reads so we don't need to share the
 * profile-config domain classes across services.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KongKonnectProfileReader {

    private final MongoTemplate mongoTemplate;
    private final MigrationProperties props;

    public KongKonnectCredentials resolve(String companyName, String profileName) {
        if (StringUtils.hasText(companyName)) {
            String desiredName = StringUtils.hasText(profileName) ? profileName : "primary";
            Query q = Query.query(Criteria.where("companyName").is(companyName)
                    .and("profileName").is(desiredName)
                    .and("status").is("ACTIVE"));
            Document doc = mongoTemplate.findOne(q, Document.class,
                    props.getKongKonnectProfilesCollection());
            if (doc != null) {
                log.debug("Using Kong Konnect profile (company={}, profileName={})", companyName, desiredName);
                return KongKonnectCredentials.builder()
                        .source("profile")
                        .konnectBaseUrl(doc.getString("adminUrl"))
                        .konnectAccessToken(doc.getString("konnectPat"))
                        .controlPlaneId(firstControlPlaneId(doc))
                        .region(doc.getString("region"))
                        .build();
            }
        }
        log.debug("No Kong Konnect profile found - using static fallback");
        return KongKonnectCredentials.builder()
                .source("static")
                .konnectBaseUrl(props.getKonnect().getBaseUrlFallback())
                .konnectAccessToken(props.getKonnect().getAccessTokenFallback())
                .controlPlaneId(props.getKonnect().getControlPlaneIdFallback())
                .region("us")
                .build();
    }

    @SuppressWarnings("unchecked")
    private static String firstControlPlaneId(Document doc) {
        Object value = doc.get("controlPlanes");
        if (!(value instanceof List<?> controlPlanes) || controlPlanes.isEmpty()) {
            return null;
        }
        Object first = controlPlanes.get(0);
        if (!(first instanceof Document controlPlane)) {
            return null;
        }
        return controlPlane.getString("controlPlaneId");
    }
}
