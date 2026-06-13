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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the assessment service's relationship-sync edge collections — the same
 * store that backs the access-graph endpoint
 * ({@code GET /wso2/companies/{c}/tenants/{t}/access-graph}) — and folds them
 * into the {@link AssessmentDependencyReader} graph shape
 * ({@code resourceName -> relationType -> [ids]}).
 *
 * <p>The assessment's {@code resourceDependencies} and the relationship sync
 * capture the same WSO2 facts at different moments: the former is frozen per
 * assessment run, the latter is re-triggered freely (UI API-Insight screen /
 * {@code POST /wso2/relationships/sync}). Merging the two means a subscription
 * or application created AFTER the last assessment run still pulls its
 * dependencies into the migration instead of silently migrating an API with no
 * consumers. Existing assessment entries are never overwritten — relation edges
 * only fill gaps. Coupling is by collection name only — the migration service
 * never imports the assessment's classes (same convention as
 * {@link AssessmentDependencyReader}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationGraphReader {

    private final MongoTemplate mongoTemplate;
    private final MigrationProperties props;

    // Relation-type keys must match what DependencyResolver reads from the graph.
    private static final String REL_SUBSCRIPTIONS = "subscriptions";
    private static final String REL_APIS = "apis";
    private static final String REL_API_PRODUCTS = "apiProducts";

    /**
     * Merges relation-sync edges for the company/tenant into {@code graph},
     * adding only ids the assessment graph doesn't already have.
     *
     * @return number of edge ids added
     */
    public int mergeInto(Map<String, Map<String, List<String>>> graph,
                         String companyName, String wso2Tenant) {
        if (!props.getDependency().isUseRelationGraph()) {
            return 0;
        }
        int added = 0;
        try {
            Query byTenant = Query.query(Criteria.where("companyName").is(companyName)
                    .and("wso2Tenant").is(wso2Tenant));

            // Read product edges FIRST: add each member API's apiProducts link, and remember which
            // member APIs each product carries so a subscription ON the product can be attributed
            // back to those APIs below (the via-product join).
            List<Document> productEdges = mongoTemplate.find(byTenant, Document.class,
                    props.getDependency().getRelationApiProductCollection());
            Map<String, Set<String>> productMemberApiNames = new HashMap<>();   // apiProductId -> [member apiName]
            for (Document edge : productEdges) {
                String productId = edge.getString("apiProductId");
                String memberApiName = edge.getString("apiName");
                added += add(graph, memberApiName, REL_API_PRODUCTS, productId);
                if (StringUtils.hasText(productId) && StringUtils.hasText(memberApiName)) {
                    productMemberApiNames.computeIfAbsent(productId, k -> new LinkedHashSet<>()).add(memberApiName);
                }
            }

            List<Document> appSubEdges = mongoTemplate.find(byTenant, Document.class,
                    props.getDependency().getRelationAppSubscriptionCollection());
            for (Document edge : appSubEdges) {
                String appName = edge.getString("applicationName");
                String apiName = edge.getString("apiName");
                String subId = edge.getString("subscriptionId");
                String apiId = edge.getString("apiId");
                // application → its subscriptions + the APIs it subscribes to
                added += add(graph, appName, REL_SUBSCRIPTIONS, subId);
                added += add(graph, appName, REL_APIS, apiId);
                // api → the subscriptions on it (DependencyExpander derives the apps from these)
                added += add(graph, apiName, REL_SUBSCRIPTIONS, subId);
                // via-product join: when this subscription is on an API PRODUCT, attribute it to each
                // member API too, so an API reached only through a product still pulls in the
                // subscribing app as a consumer (otherwise the consumer + its credential are missed).
                Set<String> members = productMemberApiNames.get(apiId);
                if (members != null) {
                    for (String memberApiName : members) {
                        added += add(graph, memberApiName, REL_SUBSCRIPTIONS, subId);
                    }
                }
            }

            if (added > 0) {
                log.info("[dependency] relation-sync graph contributed {} edge id(s) the assessment "
                                + "graph lacked for {}/{} (scanned {} app-subscription edges, {} product edges)",
                        added, companyName, wso2Tenant, appSubEdges.size(), productEdges.size());
            }
        } catch (Exception e) {
            log.warn("[dependency] could not read relation-sync collections for {}/{}: {} — "
                    + "continuing with the assessment graph alone.", companyName, wso2Tenant, e.getMessage());
        }
        return added;
    }

    private int add(Map<String, Map<String, List<String>>> graph,
                    String resourceName, String relType, String id) {
        if (!StringUtils.hasText(resourceName) || !StringUtils.hasText(id)) {
            return 0;
        }
        List<String> ids = graph.computeIfAbsent(resourceName, k -> new HashMap<>())
                .computeIfAbsent(relType, k -> new ArrayList<>());
        if (ids.contains(id)) {
            return 0;
        }
        ids.add(id);
        return 1;
    }
}
