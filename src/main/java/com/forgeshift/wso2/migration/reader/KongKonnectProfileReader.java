package com.forgeshift.wso2.migration.reader;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
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

    public KongKonnectCredentials resolve(String companyName, String profileName,
                                          String requestedControlPlaneId, String requestedRegion) {
        if (StringUtils.hasText(companyName)) {
            String desiredName = StringUtils.hasText(profileName) ? profileName : "primary";
            Query q = Query.query(Criteria.where("companyName").is(companyName)
                    .and("profileName").is(desiredName));
            Document doc = mongoTemplate.find(q, Document.class,
                            props.getKongKonnectProfilesCollection())
                    .stream()
                    .filter(KongKonnectProfileReader::isActive)
                    .findFirst()
                    .orElse(null);
            if (doc == null && "primary".equalsIgnoreCase(desiredName)) {
                Query fallback = Query.query(Criteria.where("companyName").is(companyName))
                        .with(Sort.by(Sort.Direction.DESC, "lastUpdatedAt", "updatedAt", "createdAt"));
                List<Document> activeProfiles = mongoTemplate.find(fallback, Document.class,
                                props.getKongKonnectProfilesCollection())
                        .stream()
                        .filter(KongKonnectProfileReader::isActive)
                        .toList();
                if (activeProfiles.size() == 1) {
                    doc = activeProfiles.get(0);
                    log.info("No Kong Konnect profile named 'primary' for company={}; using sole ACTIVE profile '{}'",
                            companyName, doc.getString("profileName"));
                }
            }
            if (doc != null) {
                log.debug("Using Kong Konnect profile (company={}, profileName={})", companyName, desiredName);
                Document selectedControlPlane = selectedControlPlane(doc, requestedControlPlaneId);
                KongKonnectCredentials creds = KongKonnectCredentials.builder()
                        .source("profile")
                        .konnectBaseUrl(firstString(doc, "adminUrl", "konnectBaseUrl"))
                        .konnectAccessToken(firstString(doc, "konnectPat", "konnectAccessToken"))
                        .controlPlaneId(controlPlaneId(doc, selectedControlPlane))
                        .controlPlaneName(controlPlaneName(doc, selectedControlPlane))
                        .region(StringUtils.hasText(requestedRegion) ? requestedRegion : doc.getString("region"))
                        .gitRepo(firstString(doc, "gitRepo", "configRepo", "kongConfigRepo"))
                        .gitBranch(firstString(doc, "gitBranch", "configBranch"))
                        .gitToken(firstString(doc, "gitToken", "githubToken"))
                        .build();
                applyGitProfile(creds, companyName, desiredName);
                return creds;
            }
        }
        log.debug("No Kong Konnect profile found - using static fallback");
        KongKonnectCredentials creds = KongKonnectCredentials.builder()
                .source("static")
                .konnectBaseUrl(props.getKonnect().getBaseUrlFallback())
                .konnectAccessToken(props.getKonnect().getAccessTokenFallback())
                .controlPlaneId(StringUtils.hasText(requestedControlPlaneId)
                        ? requestedControlPlaneId : props.getKonnect().getControlPlaneIdFallback())
                .controlPlaneName(props.getDeck().getControlPlaneNameFallback())
                .region(StringUtils.hasText(requestedRegion) ? requestedRegion : "us")
                .build();
        applyGitProfile(creds, companyName, StringUtils.hasText(profileName) ? profileName : "primary");
        return creds;
    }

    private void applyGitProfile(KongKonnectCredentials creds, String companyName, String profileName) {
        if (!StringUtils.hasText(companyName)) {
            return;
        }
        Query q = Query.query(Criteria.where("companyName").is(companyName)
                .and("profileName").is(profileName));
        Document doc = mongoTemplate.find(q, Document.class, props.getGitProfilesCollection())
                .stream()
                .filter(KongKonnectProfileReader::isActive)
                .findFirst()
                .orElse(null);
        if (doc == null) {
            return;
        }
        log.debug("Using Git profile (company={}, profileName={})", companyName, profileName);
        creds.setGitRepo(firstString(doc, "repo", "gitRepo", "configRepo", "kongConfigRepo"));
        creds.setGitBranch(firstString(doc, "branch", "gitBranch", "configBranch"));
        creds.setGitToken(firstString(doc, "pat", "gitToken", "githubToken"));
        creds.setGitOrganization(firstString(doc, "organization"));
        creds.setGitUsername(firstString(doc, "username"));
        creds.setGitTeamName(firstString(doc, "teamName"));
        creds.setGitGithubUrl(firstString(doc, "githubUrl"));
    }

    @SuppressWarnings("unchecked")
    private static Document selectedControlPlane(Document doc, String requestedControlPlaneId) {
        Object value = doc.get("controlPlanes");
        if (!(value instanceof List<?> controlPlanes) || controlPlanes.isEmpty()) {
            return null;
        }

        if (StringUtils.hasText(requestedControlPlaneId)) {
            return controlPlanes.stream()
                    .filter(Document.class::isInstance)
                    .map(Document.class::cast)
                    .filter(cp -> requestedControlPlaneId.equals(cp.getString("controlPlaneId")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Control Plane not found in Kong Konnect profile: " + requestedControlPlaneId));
        }

        if (controlPlanes.size() == 1 && controlPlanes.get(0) instanceof Document controlPlane) {
            return controlPlane;
        }

        throw new IllegalArgumentException(
                "Multiple Kong control planes found. Pass kongCtrlPlanId to select the target control plane.");
    }

    private static String controlPlaneId(Document doc, Document controlPlane) {
        if (controlPlane != null) {
            return controlPlane.getString("controlPlaneId");
        }
        return doc.getString("controlPlaneId");
    }

    private static String controlPlaneName(Document doc, Document controlPlane) {
        if (controlPlane != null) {
            String name = controlPlane.getString("controlPlaneName");
            if (name == null) name = controlPlane.getString("name");
            if (name != null) return name;
        }
        return doc.getString("controlPlaneName");
    }

    private static String firstString(Document doc, String... keys) {
        for (String k : keys) {
            String v = doc.getString(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static boolean isActive(Document doc) {
        Object status = doc.get("status");
        return status == null || "ACTIVE".equalsIgnoreCase(status.toString());
    }
}
