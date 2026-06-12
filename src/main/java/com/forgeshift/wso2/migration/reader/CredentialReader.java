package com.forgeshift.wso2.migration.reader;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the assessment service's captured application credentials
 * ({@code wso2_application_credential_relations}) and per-API security schemes
 * ({@code wso2_api_security_relations}) for one company/tenant. Coupling is by
 * collection name only — the migration service never imports the assessment's
 * classes (same convention as {@link AssessmentDependencyReader} and
 * {@link RelationGraphReader}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialReader {

    private final MongoTemplate mongoTemplate;
    private final MigrationProperties props;

    /** One captured OAuth2 key of an application. */
    @Data
    @Builder
    public static class AppCredential {
        private String applicationId;
        private String applicationName;
        private String keyType;       // PRODUCTION / SANDBOX
        private String keyManager;
        private String consumerKey;
        private String consumerSecret;
        private List<String> supportedGrantTypes;
    }

    /** applicationId → its captured OAuth2 credentials (may be empty). */
    public Map<String, List<AppCredential>> readCredentialsByApplication(String companyName, String wso2Tenant) {
        Map<String, List<AppCredential>> out = new LinkedHashMap<>();
        if (!props.getCredentials().isEnabled()) return out;
        String collection = props.getCredentials().getCredentialCollection();
        try {
            List<Document> docs = mongoTemplate.find(tenantQuery(companyName, wso2Tenant), Document.class, collection);
            for (Document d : docs) {
                String appId = d.getString("applicationId");
                if (!StringUtils.hasText(appId)) continue;
                out.computeIfAbsent(appId, k -> new ArrayList<>()).add(AppCredential.builder()
                        .applicationId(appId)
                        .applicationName(d.getString("applicationName"))
                        .keyType(d.getString("keyType"))
                        .keyManager(d.getString("keyManager"))
                        .consumerKey(d.getString("consumerKey"))
                        .consumerSecret(d.getString("consumerSecret"))
                        .supportedGrantTypes(stringList(d.get("supportedGrantTypes")))
                        .build());
            }
            log.info("[credentials] loaded keys for {} application(s) from {} for {}/{}",
                    out.size(), collection, companyName, wso2Tenant);
        } catch (Exception e) {
            log.warn("[credentials] could not read {} for {}/{}: {} — consumers will have no credentials.",
                    collection, companyName, wso2Tenant, e.getMessage());
        }
        return out;
    }

    /** apiId → lowercased securityScheme values (e.g. oauth2, api_key). */
    public Map<String, Set<String>> readSecuritySchemesByApi(String companyName, String wso2Tenant) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        String collection = props.getCredentials().getApiSecurityCollection();
        try {
            List<Document> docs = mongoTemplate.find(tenantQuery(companyName, wso2Tenant), Document.class, collection);
            for (Document d : docs) {
                String apiId = d.getString("apiId");
                if (!StringUtils.hasText(apiId)) continue;
                Set<String> schemes = out.computeIfAbsent(apiId, k -> new LinkedHashSet<>());
                for (String s : stringList(d.get("securityScheme"))) {
                    schemes.add(s.toLowerCase());
                }
            }
        } catch (Exception e) {
            log.warn("[credentials] could not read {} for {}/{}: {} — credential type will fall back to default.",
                    collection, companyName, wso2Tenant, e.getMessage());
        }
        return out;
    }

    private static Query tenantQuery(String companyName, String wso2Tenant) {
        return Query.query(Criteria.where("companyName").is(companyName).and("wso2Tenant").is(wso2Tenant));
    }

    private static List<String> stringList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object x : list) {
            if (x != null && StringUtils.hasText(x.toString())) out.add(x.toString());
        }
        return out;
    }
}
