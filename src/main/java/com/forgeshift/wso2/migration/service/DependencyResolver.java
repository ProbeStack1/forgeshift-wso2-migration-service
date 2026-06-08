package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure graph walk: given the SELECTED discovery snapshots (by type) and the assessment
 * dependency graph ({@code resourceName -> relationType -> [ids]}), work out the DIRECT
 * dependency ids to also migrate, keyed by migration resource type.
 *
 * <p>Directions:
 * <ul>
 *   <li>selected <b>API</b>          → its subscriptions + the API products it's in</li>
 *   <li>selected <b>APPLICATION</b>  → its subscriptions + the APIs it subscribes to</li>
 *   <li>selected <b>SUBSCRIPTION</b> → its API + its application (read from the snapshot payload,
 *       because the graph is keyed by api/app name, not by subscription)</li>
 * </ul>
 *
 * <p>The app behind an API's subscriptions is resolved later (once the subscription snapshots are
 * loaded) by {@link DependencyExpander} — the graph doesn't store the app id under the API.
 */
@Service
public class DependencyResolver {

    public Map<String, Set<String>> resolveDirect(Map<String, List<DiscoverySnapshot>> selectedByType,
                                                  Map<String, Map<String, List<String>>> graph) {
        Map<String, Set<String>> out = new HashMap<>();

        for (DiscoverySnapshot api : selectedByType.getOrDefault("apis", List.of())) {
            Map<String, List<String>> entry = lookup(graph, api.getSourceName());
            if (entry == null) continue;
            add(out, "subscriptions", entry.get("subscriptions"));
            add(out, "apiproducts", entry.get("apiProducts"));
        }

        for (DiscoverySnapshot app : selectedByType.getOrDefault("applications", List.of())) {
            Map<String, List<String>> entry = lookup(graph, app.getSourceName());
            if (entry == null) continue;
            add(out, "subscriptions", entry.get("subscriptions"));
            add(out, "apis", entry.get("apis"));
        }

        for (DiscoverySnapshot sub : selectedByType.getOrDefault("subscriptions", List.of())) {
            String apiId = payloadString(sub, "apiId");
            String appId = payloadString(sub, "applicationId");
            if (StringUtils.hasText(apiId)) add(out, "apis", List.of(apiId));
            if (StringUtils.hasText(appId)) add(out, "applications", List.of(appId));
        }

        // API Products → their member APIs (the product payload lists them; the graph doesn't
        // key products, so read the snapshot directly — mirrors ApiProductTranslator).
        for (DiscoverySnapshot product : selectedByType.getOrDefault("apiproducts", List.of())) {
            if (product.getPayload() == null) continue;
            if (product.getPayload().get("apis") instanceof List<?> members) {
                for (Object m : members) {
                    if (m instanceof Map<?, ?> map) {
                        Object apiId = map.get("apiId") != null ? map.get("apiId") : map.get("id");
                        if (apiId != null) add(out, "apis", List.of(apiId.toString()));
                    }
                }
            }
        }

        // Mediation policies → the API they attach to (snapshot metadata carries the apiId).
        for (DiscoverySnapshot med : selectedByType.getOrDefault("mediationpolicies", List.of())) {
            if (med.getMetadata() != null && StringUtils.hasText(med.getMetadata().get("apiId"))) {
                add(out, "apis", List.of(med.getMetadata().get("apiId")));
            }
        }

        // Leaf types (throttlingpolicies, keymanagers, certificates) have no sub-dependencies —
        // they ARE the leaves other resources depend on — so there's nothing to expand for them.
        return out;
    }

    private Map<String, List<String>> lookup(Map<String, Map<String, List<String>>> graph, String name) {
        if (name == null) return null;
        Map<String, List<String>> exact = graph.get(name);
        if (exact != null) return exact;
        for (Map.Entry<String, Map<String, List<String>>> g : graph.entrySet()) {
            if (name.equalsIgnoreCase(g.getKey())) return g.getValue();
        }
        return null;
    }

    private void add(Map<String, Set<String>> out, String type, List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        out.computeIfAbsent(type, k -> new LinkedHashSet<>()).addAll(ids);
    }

    private String payloadString(DiscoverySnapshot s, String key) {
        if (s.getPayload() == null) return null;
        Object v = s.getPayload().get(key);
        return v == null ? null : v.toString();
    }
}
