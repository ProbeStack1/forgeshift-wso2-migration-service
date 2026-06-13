package com.forgeshift.wso2.migration.reader;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationGraphReaderTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MigrationProperties props = new MigrationProperties();
    private final RelationGraphReader reader = new RelationGraphReader(mongo, props);

    @Test
    void attributesProductSubscriptionToMemberApiSoTheConsumerIsPulled() {
        String productColl = props.getDependency().getRelationApiProductCollection();
        String appSubColl = props.getDependency().getRelationAppSubscriptionCollection();

        // tailor-product-1 contains Seed05apikeyBronze; DefaultApplication subscribes to the PRODUCT.
        Document productEdge = new Document("apiProductId", "prod-1").append("apiName", "Seed05apikeyBronze");
        Document appSubEdge = new Document("applicationName", "DefaultApplication")
                .append("apiName", "tailor-product-1")     // the subscription's target is the product
                .append("subscriptionId", "sub-1")
                .append("apiId", "prod-1");                 // ...whose id is the product id

        when(mongo.find(any(Query.class), eq(Document.class), eq(productColl))).thenReturn(List.of(productEdge));
        when(mongo.find(any(Query.class), eq(Document.class), eq(appSubColl))).thenReturn(List.of(appSubEdge));

        Map<String, Map<String, List<String>>> graph = new HashMap<>();
        int added = reader.mergeInto(graph, "probestack", "carbon.super");

        assertThat(added).isPositive();
        // the via-product join: the member API now lists the subscription as a consumer link
        assertThat(graph.get("Seed05apikeyBronze").get("subscriptions")).contains("sub-1");
        assertThat(graph.get("Seed05apikeyBronze").get("apiProducts")).contains("prod-1");
    }

    @Test
    void directApiSubscriptionStaysUnderTheApi() {
        String productColl = props.getDependency().getRelationApiProductCollection();
        String appSubColl = props.getDependency().getRelationAppSubscriptionCollection();
        Document appSubEdge = new Document("applicationName", "MobileApp")
                .append("apiName", "Seed04oauth2").append("subscriptionId", "sub-2").append("apiId", "api-4");

        when(mongo.find(any(Query.class), eq(Document.class), eq(productColl))).thenReturn(List.of());
        when(mongo.find(any(Query.class), eq(Document.class), eq(appSubColl))).thenReturn(List.of(appSubEdge));

        Map<String, Map<String, List<String>>> graph = new HashMap<>();
        reader.mergeInto(graph, "probestack", "carbon.super");

        assertThat(graph.get("Seed04oauth2").get("subscriptions")).contains("sub-2");
    }
}
