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

import java.util.Optional;

/**
 * Reads WSO2 connection profiles written by the profile-config service from
 * the {@code wso2_profiles} collection. Matches the new
 * {@code (companyName, profileName)} schema where {@code tenants} is an
 * array — picks the profile whose tenants list contains the requested
 * {@code wso2Tenant}, filtered to {@code status == ACTIVE} (treating null
 * as ACTIVE), most recent {@code updatedAt} wins.
 *
 * <p>Uses raw {@code org.bson.Document} reads so this module doesn't need
 * to depend on the discovery service's {@code Wso2TenantProfile} class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Wso2ProfileReader {

    private final MongoTemplate mongoTemplate;
    private final MigrationProperties props;

    /**
     * Resolve credentials for a given (companyName, wso2Tenant). Returns a
     * non-null {@link Wso2Credentials}; check {@code source} to distinguish
     * a real profile ("profile") from a miss ("missing"). The caller
     * decides what to do on a miss — typically warn and skip the bundle
     * download phase for that tenant.
     */
    public Wso2Credentials resolve(String companyName, String wso2Tenant) {
        if (!StringUtils.hasText(companyName) || !StringUtils.hasText(wso2Tenant)) {
            log.warn("Wso2ProfileReader.resolve called with blank companyName/wso2Tenant");
            return missing(companyName);
        }

        Query q = Query.query(Criteria.where("companyName").is(companyName)
                        .and("tenants").is(wso2Tenant))
                .with(Sort.by(Sort.Direction.DESC, "updatedAt"));
        Document chosen = mongoTemplate.find(q, Document.class,
                        props.getWso2ProfilesCollection())
                .stream()
                .filter(d -> {
                    Object status = d.get("status");
                    return status == null || "ACTIVE".equalsIgnoreCase(status.toString());
                })
                .findFirst()
                .orElse(null);

        if (chosen == null) {
            log.warn("No ACTIVE wso2_profiles row matched company={} tenant={}",
                    companyName, wso2Tenant);
            return missing(companyName);
        }

        log.debug("Resolved WSO2 profile name={} for company={} tenant={}",
                chosen.getString("profileName"), companyName, wso2Tenant);
        return Wso2Credentials.builder()
                .source("profile")
                .companyName(companyName)
                .profileName(chosen.getString("profileName"))
                .wso2BaseUrl(chosen.getString("wso2BaseUrl"))
                .username(chosen.getString("username"))
                .password(chosen.getString("password"))
                .clientId(chosen.getString("clientId"))
                .clientSecret(chosen.getString("clientSecret"))
                .trustSelfSigned(Optional.ofNullable(chosen.getBoolean("trustSelfSigned"))
                        .orElse(false))
                .build();
    }

    private Wso2Credentials missing(String companyName) {
        return Wso2Credentials.builder()
                .source("missing")
                .companyName(companyName)
                .build();
    }
}
