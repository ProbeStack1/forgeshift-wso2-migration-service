package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.dto.StartMigrationRequest;
import com.forgeshift.wso2.migration.reader.AssessmentDependencyReader;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshotReader;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dependency-aware migration. Given the SELECTED discovery snapshots, it:
 * <ol>
 *   <li>loads the assessment dependency graph for the run (by {@code assessmentTransactionId}),</li>
 *   <li>resolves each selected resource's dependencies (API → subscriptions + consuming apps + products),</li>
 *   <li>loads those dependency snapshots and merges them in, and</li>
 *   <li>drops anything already present in Kong (Kong‑authoritative, see {@link KongPresenceChecker}).</li>
 * </ol>
 * It mutates and returns {@code byType} so the normal translate flow picks up the extra resources.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyExpander {

    private static final List<String> DEP_TYPES = List.of("subscriptions", "applications", "apiproducts", "apis");

    private final AssessmentDependencyReader dependencyReader;
    private final DependencyResolver dependencyResolver;
    private final KongPresenceChecker presenceChecker;
    private final DiscoverySnapshotReader snapshotReader;
    private final MigrationProperties props;

    public Map<String, List<DiscoverySnapshot>> expand(MigrationJob job, StartMigrationRequest req,
                                                       KongKonnectCredentials creds,
                                                       Map<String, List<DiscoverySnapshot>> byType) {
        if (!props.getDependency().isEnabled()) {
            log.info("[dependency] disabled by config — skipping expansion for job {}", job.getId());
            return byType;
        }
        // job.resourceTypes may be immutable — make it mutable before we add dependency types.
        job.setResourceTypes(new ArrayList<>(job.getResourceTypes()));

        // 1) the saved dependency graph for THIS assessment run
        Map<String, Map<String, List<String>>> graph = dependencyReader.readGraph(req.getAssessmentTransactionId());

        // 2) resolve direct dependency ids from the graph (+ subscription payloads)
        Map<String, Set<String>> deps = dependencyResolver.resolveDirect(byType, graph);

        // 3) load subscription snapshots first; derive the consuming apps from their applicationId
        Set<String> subIds = deps.getOrDefault("subscriptions", new LinkedHashSet<>());
        if (!subIds.isEmpty()) {
            List<DiscoverySnapshot> subs = loadFiltered(req, "subscriptions", subIds);
            merge(byType, "subscriptions", subs);
            Set<String> appIds = deps.computeIfAbsent("applications", k -> new LinkedHashSet<>());
            for (DiscoverySnapshot s : subs) {
                String appId = payload(s, "applicationId");
                if (StringUtils.hasText(appId)) appIds.add(appId);
            }
        }

        // 4) load the remaining dependency types
        loadAndMerge(req, byType, "applications", deps.get("applications"));
        loadAndMerge(req, byType, "apiproducts", deps.get("apiproducts"));
        loadAndMerge(req, byType, "apis", deps.get("apis"));

        // make sure newly-added types are part of the job (iteration + reporting)
        for (String t : DEP_TYPES) {
            List<DiscoverySnapshot> snaps = byType.get(t);
            if (snaps != null && !snaps.isEmpty() && !job.getResourceTypes().contains(t)) {
                job.getResourceTypes().add(t);
            }
        }

        // 5) skip anything already in Kong (across ALL types: original selection + dependencies)
        if (props.getDependency().isExcludeAlreadyMigrated()) {
            Set<String> all = new LinkedHashSet<>();
            byType.values().forEach(list -> list.forEach(s -> all.add(s.getSourceId())));
            Set<String> skip = presenceChecker.alreadyInKong(creds, req.getCompanyName(), req.getWso2Tenant(), all);
            if (!skip.isEmpty()) {
                int[] removed = {0};
                byType.replaceAll((type, list) -> {
                    int before = list.size();
                    List<DiscoverySnapshot> kept = list.stream()
                            .filter(s -> !skip.contains(s.getSourceId()))
                            .collect(Collectors.toList());
                    removed[0] += before - kept.size();
                    return kept;
                });
                log.info("[dependency] skipped {} resource(s) already present in Kong for job {}", removed[0], job.getId());
            }
        }

        log.info("[dependency] expansion done for job {} — final counts {}", job.getId(), countMap(byType));
        return byType;
    }

    private void loadAndMerge(StartMigrationRequest req, Map<String, List<DiscoverySnapshot>> byType,
                              String type, Set<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        merge(byType, type, loadFiltered(req, type, ids));
    }

    /** Load every snapshot of {@code type} (by discoveryId or latest revision) and keep only the wanted ids. */
    private List<DiscoverySnapshot> loadFiltered(StartMigrationRequest req, String type, Set<String> wantedIds) {
        List<DiscoverySnapshot> all = StringUtils.hasText(req.getDiscoveryId())
                ? snapshotReader.findByDiscoveryId(req.getCompanyName(), req.getWso2Tenant(), type, req.getDiscoveryId())
                : snapshotReader.findLatestRevision(req.getCompanyName(), req.getWso2Tenant(), type);
        List<DiscoverySnapshot> out = new ArrayList<>();
        for (DiscoverySnapshot s : all) {
            if (wantedIds.contains(s.getSourceId())) out.add(s);
        }
        log.info("[dependency] loaded {} {} dependency snapshot(s) (of {} wanted)", out.size(), type, wantedIds.size());
        return out;
    }

    /** Merge new snapshots into byType, de-duplicating by sourceId; tolerant of immutable values. */
    private void merge(Map<String, List<DiscoverySnapshot>> byType, String type, List<DiscoverySnapshot> add) {
        if (add.isEmpty()) return;
        List<DiscoverySnapshot> existing = new ArrayList<>(byType.getOrDefault(type, List.of()));
        Set<String> have = new HashSet<>();
        existing.forEach(s -> have.add(s.getSourceId()));
        for (DiscoverySnapshot s : add) {
            if (have.add(s.getSourceId())) existing.add(s);
        }
        byType.put(type, existing);
    }

    private String payload(DiscoverySnapshot s, String key) {
        if (s.getPayload() == null) return null;
        Object v = s.getPayload().get(key);
        return v == null ? null : v.toString();
    }

    private Map<String, Integer> countMap(Map<String, List<DiscoverySnapshot>> byType) {
        Map<String, Integer> m = new LinkedHashMap<>();
        byType.forEach((k, v) -> m.put(k, v.size()));
        return m;
    }
}
