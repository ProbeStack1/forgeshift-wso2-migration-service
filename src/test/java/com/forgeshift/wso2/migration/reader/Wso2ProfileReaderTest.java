package com.forgeshift.wso2.migration.reader;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Wso2ProfileReaderTest {

    @Test
    void resolvesProfileByDefaultWso2Tenant() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MigrationProperties props = new MigrationProperties();
        Wso2ProfileReader reader = new Wso2ProfileReader(mongo, props);

        Document profile = new Document()
                .append("companyName", "probestack")
                .append("profileName", "probestack-wso2")
                .append("defaultWso2Tenant", "carbon.super")
                .append("status", "ACTIVE")
                .append("wso2BaseUrl", "https://wso2.example")
                .append("username", "admin")
                .append("password", "secret")
                .append("clientId", "client-id")
                .append("clientSecret", "client-secret")
                .append("trustSelfSigned", true);

        when(mongo.find(any(Query.class), eq(Document.class), eq("wso2_profiles")))
                .thenReturn(List.of(profile));

        Wso2Credentials creds = reader.resolve("probestack", "carbon.super");

        assertEquals("profile", creds.getSource());
        assertEquals("probestack-wso2", creds.getProfileName());
        assertEquals("https://wso2.example", creds.getWso2BaseUrl());
    }
}
