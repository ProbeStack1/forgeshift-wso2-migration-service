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

/**
 * Reads git provider profiles (written by the profile-config service) from the
 * {@code git_profiles} collection, keyed by companyName. Supplies the org + PAT used
 * to auto-commit the generated decK bundle. Raw {@code org.bson.Document} reads so we
 * don't share the profile-config domain classes across services.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitProfileReader {

    private final MongoTemplate mongoTemplate;
    private final MigrationProperties props;

    public GitProfileCredentials resolve(String companyName) {
        if (StringUtils.hasText(companyName)) {
            Query q = Query.query(Criteria.where("companyName").is(companyName).and("status").is("ACTIVE"));
            Document doc = mongoTemplate.findOne(q, Document.class,
                    props.getDeck().getGit().getProfilesCollection());
            if (doc != null) {
                log.debug("Using git profile for company {}", companyName);
                return GitProfileCredentials.builder()
                        .source("profile")
                        .organization(firstString(doc, "organization"))
                        .pat(firstString(doc, "pat", "token", "githubToken"))
                        .githubUrl(doc.getString("githubUrl"))
                        .build();
            }
        }
        log.debug("No git profile for company {} — using deck.git config fallback", companyName);
        return GitProfileCredentials.builder().source("missing").build();
    }

    private static String firstString(Document doc, String... keys) {
        for (String k : keys) {
            String v = doc.getString(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
