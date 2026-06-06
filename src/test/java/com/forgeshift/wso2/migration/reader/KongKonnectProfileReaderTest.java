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

class KongKonnectProfileReaderTest {

    @Test
    void primaryFallsBackToSoleActiveProfileAndSelectsControlPlaneById() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MigrationProperties props = new MigrationProperties();
        KongKonnectProfileReader reader = new KongKonnectProfileReader(mongo, props);

        Document profile = new Document()
                .append("companyName", "probestack")
                .append("profileName", "probestack-kong-konnect-demofis")
                .append("status", "ACTIVE")
                .append("adminUrl", "https://us.api.konghq.com")
                .append("konnectPat", "kpat_test")
                .append("region", "us")
                .append("controlPlanes", List.of(
                        new Document()
                                .append("controlPlaneId", "43dbc26e-23c1-4443-982a-96321e6d0dfb")
                                .append("controlPlaneName", "serverless-api-gateway-demo"),
                        new Document()
                                .append("controlPlaneId", "78ac8c8b-74a8-4338-875f-2ccaee19a52c")
                                .append("controlPlaneName", "probestack-kong")));

        when(mongo.findOne(any(Query.class), eq(Document.class), eq("kong_konnect_profiles")))
                .thenReturn(null);
        when(mongo.find(any(Query.class), eq(Document.class), eq("kong_konnect_profiles")))
                .thenReturn(List.of(profile));

        KongKonnectCredentials creds = reader.resolve(
                "probestack",
                "primary",
                "43dbc26e-23c1-4443-982a-96321e6d0dfb",
                null);

        assertEquals("profile", creds.getSource());
        assertEquals("43dbc26e-23c1-4443-982a-96321e6d0dfb", creds.getControlPlaneId());
        assertEquals("serverless-api-gateway-demo", creds.getControlPlaneName());
    }
}
