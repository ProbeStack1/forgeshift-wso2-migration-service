package com.forgeshift.wso2.migration.reader;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CredentialReaderTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MigrationProperties props = new MigrationProperties();
    private final CredentialReader reader = new CredentialReader(mongo, props);

    @Test
    void productSchemeIncludesMemberApiSchemesSoCredentialTypeIsRight() {
        String securityColl = props.getCredentials().getApiSecurityCollection();
        String productColl = props.getDependency().getRelationApiProductCollection();

        // seed05 (member) = api_key; the product itself only carries the WSO2 "mandatory" flag.
        Document seed05 = new Document("apiId", "api-5")
                .append("securityScheme", List.of("api_key", "oauth_basic_auth_api_key_mandatory"));
        Document product = new Document("apiId", "prod-1")
                .append("securityScheme", List.of("oauth_basic_auth_api_key_mandatory"));
        Document productEdge = new Document("apiProductId", "prod-1").append("apiId", "api-5");

        when(mongo.find(any(Query.class), eq(Document.class), eq(securityColl)))
                .thenReturn(List.of(seed05, product));
        when(mongo.find(any(Query.class), eq(Document.class), eq(productColl)))
                .thenReturn(List.of(productEdge));

        Map<String, Set<String>> schemes = reader.readSecuritySchemesByApi("probestack", "carbon.super");

        // a subscription points at the product id → its schemes must include the member's api_key,
        // so the consumer gets a key-auth credential (not a defaulted jwt one).
        assertThat(schemes.get("prod-1")).contains("api_key");
        assertThat(schemes.get("api-5")).contains("api_key");
    }
}
