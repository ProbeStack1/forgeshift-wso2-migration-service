package com.forgeshift.wso2.migration.reader;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the assessment service's saved dependency graph for ONE assessment run, by
 * {@code requestTransactionId}, from {@code wso2_assessment_resource_info.resourceDependencies}.
 *
 * <p>The assessment stores it as
 * {@code { "<resourceName>": { "<relationType>": [ {id,name}, ... ] } } } across the
 * {@code apis} and {@code applications} resource-type docs. We flatten it to
 * {@code resourceName -> relationType -> [dependency ids]} (relationType is one of
 * {@code subscriptions}, {@code apiProducts}, {@code apis}). Coupling is by collection name
 * only — the migration service never imports the assessment's classes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentDependencyReader {

    private final MongoTemplate mongoTemplate;
    private final MigrationProperties props;

    public Map<String, Map<String, List<String>>> readGraph(String requestTransactionId) {
        Map<String, Map<String, List<String>>> graph = new HashMap<>();
        if (requestTransactionId == null || requestTransactionId.isBlank()) {
            return graph;
        }
        String collection = props.getDependency().getAssessmentResourceInfoCollection();
        List<Document> docs = mongoTemplate.find(
                Query.query(Criteria.where("requestTransactionId").is(requestTransactionId)),
                Document.class, collection);

        int legacyStringRefs = 0;   // refs stored as bare names (old format, no id) — unmigratable
        for (Document doc : docs) {
            if (!(doc.get("resourceDependencies") instanceof Document depDoc)) continue;
            for (String resourceName : depDoc.keySet()) {
                if (!(depDoc.get(resourceName) instanceof Document relDoc)) continue;
                Map<String, List<String>> byRel = graph.computeIfAbsent(resourceName, k -> new HashMap<>());
                for (String relType : relDoc.keySet()) {
                    if (!(relDoc.get(relType) instanceof List<?> refs)) continue;
                    List<String> ids = byRel.computeIfAbsent(relType, k -> new ArrayList<>());
                    for (Object ref : refs) {
                        if (ref instanceof Document r && r.get("id") != null) {
                            // current format: { id, name } — id is the WSO2 source id we migrate by.
                            String id = r.get("id").toString();
                            if (!ids.contains(id)) ids.add(id);
                        } else if (ref instanceof String) {
                            // legacy format: bare name string (no id) — can't be auto-migrated.
                            legacyStringRefs++;
                        }
                    }
                }
            }
        }
        if (docs.isEmpty()) {
            log.warn("[dependency] no assessment doc found for txn {} in {} — no dependencies will be "
                    + "pulled in. Pass the requestTransactionId of an actual assessment run.",
                    requestTransactionId, collection);
        } else if (legacyStringRefs > 0) {
            log.warn("[dependency] assessment doc for txn {} stores {} dependency ref(s) in the LEGACY "
                    + "name-only format (no ids) — these cannot be auto-migrated. Re-run the assessment "
                    + "so the graph is written as {} pairs.", requestTransactionId, legacyStringRefs, "{id,name}");
        }
        log.info("[dependency] loaded graph for txn {}: {} resource entries from {}",
                requestTransactionId, graph.size(), collection);
        return graph;
    }
}
