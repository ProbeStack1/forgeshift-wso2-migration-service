package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.MigrationReport;
import com.forgeshift.wso2.migration.dto.StartMigrationRequest;
import com.forgeshift.wso2.migration.reader.AssessmentDependencyReader;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshotReader;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import com.forgeshift.wso2.migration.reader.RelationGraphReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-aware migration. Given the SELECTED discovery snapshots, it:
 * <ol>
 *   <li>loads the assessment dependency graph for the run (by {@code assessmentTransactionId}),</li>
 *   <li>resolves each selected resource's dependencies (API → subscriptions + consuming apps + products),</li>
 *   <li>loads those dependency snapshots and merges them in,</li>
 *   <li>drops anything already present in Kong (Kong-authoritative, see {@link KongPresenceChecker}), and</li>
 *   <li>builds a structured tree (each selected resource + its dependencies, BY NAME, each flagged
 *       if it was already in Kong) for the migration report.</li>
 * </ol>
 * Mutates and returns {@code byType} so the normal translate flow picks up the extra resources.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyExpander {

    private static final List<String> DEP_TYPES = List.of("subscriptions", "applications", "apiproducts", "apis");

    private final AssessmentDependencyReader dependencyReader;
    private final RelationGraphReader relationGraphReader;
    private final DependencyResolver dependencyResolver;
    private final KongPresenceChecker presenceChecker;
    private final DiscoverySnapshotReader snapshotReader;
    private final MigrationProperties props;

    private record Root(String type, DiscoverySnapshot snap) {}

    /**
     * The assessment run's id used to read the dependency graph. Prefers the explicit
     * {@code assessmentTransactionId}; falls back to the migration's own {@code requestTransactionId}
     * for the common "one id across the whole flow" convention (assessment + migration share an id).
     * The graph is stored under {@code requestTransactionId} in {@code wso2_assessment_resource_info},
     * so either value is matched against the SAME DB field.
     */
    public static String effectiveAssessmentTxn(StartMigrationRequest req) {
        return StringUtils.hasText(req.getAssessmentTransactionId())
                ? req.getAssessmentTransactionId()
                : req.getRequestTransactionId();
    }

    /** Result of expansion: snapshots to migrate, "skipped" report warnings, and the structured tree. */
    public record ExpansionResult(Map<String, List<DiscoverySnapshot>> byType,
                                  List<MigrationReport.Warning> skipped,
                                  List<MigrationReport.DependencyMigration> tree) {}

    public ExpansionResult expand(MigrationJob job, StartMigrationRequest req,
                                  KongKonnectCredentials creds,
                                  Map<String, List<DiscoverySnapshot>> byType) {
        if (!props.getDependency().isEnabled()) {
            log.info("[dependency] disabled by config — skipping expansion for job {}", job.getId());
            return new ExpansionResult(byType, List.of(), List.of());
        }
        job.setResourceTypes(new ArrayList<>(job.getResourceTypes()));

        // Capture the original selection (the roots) before we mutate byType.
        List<Root> roots = new ArrayList<>();
        byType.forEach((type, list) -> list.forEach(s -> roots.add(new Root(type, s))));

        Map<String, Map<String, List<String>>> graph = dependencyReader.readGraph(effectiveAssessmentTxn(req));
        // Fold in the relationship-sync edges (the access-graph's store). It is refreshed
        // independently of assessment runs, so it catches subscriptions/apps created after
        // the assessment snapshot — without it, those would be silently left out of the run.
        relationGraphReader.mergeInto(graph, req.getCompanyName(), req.getWso2Tenant());

        // 1) resolve each root's DIRECT deps separately so we can attribute them per root.
        Map<String, Map<String, Set<String>>> rootDirect = new LinkedHashMap<>();   // rootId -> type -> dep ids
        Map<String, Set<String>> aggregate = new LinkedHashMap<>();                 // type -> all dep ids
        for (Root root : roots) {
            Map<String, Set<String>> d = dependencyResolver.resolveDirect(
                    Map.of(root.type(), List.of(root.snap())), graph);
            rootDirect.put(root.snap().getSourceId(), d);
            d.forEach((t, ids) -> aggregate.computeIfAbsent(t, k -> new LinkedHashSet<>()).addAll(ids));
        }

        // 2) load subscription snapshots; derive the consuming apps and attribute them back to each root.
        Set<String> subIds = aggregate.getOrDefault("subscriptions", new LinkedHashSet<>());
        if (!subIds.isEmpty()) {
            List<DiscoverySnapshot> subs = loadFiltered(req, "subscriptions", subIds);
            merge(byType, "subscriptions", subs);
            Map<String, String> subToApp = new HashMap<>();
            for (DiscoverySnapshot s : subs) {
                String app = payload(s, "applicationId");
                if (StringUtils.hasText(app)) {
                    subToApp.put(s.getSourceId(), app);
                    aggregate.computeIfAbsent("applications", k -> new LinkedHashSet<>()).add(app);
                }
            }
            for (Map<String, Set<String>> d : rootDirect.values()) {
                Set<String> rootApps = d.computeIfAbsent("applications", k -> new LinkedHashSet<>());
                for (String sub : d.getOrDefault("subscriptions", Set.of())) {
                    String app = subToApp.get(sub);
                    if (app != null) rootApps.add(app);
                }
            }
        }

        // 3) load the remaining dependency types
        loadAndMerge(req, byType, "applications", aggregate.get("applications"));
        loadAndMerge(req, byType, "apiproducts", aggregate.get("apiproducts"));
        loadAndMerge(req, byType, "apis", aggregate.get("apis"));

        // newly-added types become part of the job (iteration + reporting)
        for (String t : DEP_TYPES) {
            List<DiscoverySnapshot> snaps = byType.get(t);
            if (snaps != null && !snaps.isEmpty() && !job.getResourceTypes().contains(t)) {
                job.getResourceTypes().add(t);
            }
        }

        // 4) skip anything already in Kong (selection + dependencies), recording each skip.
        Set<String> skippedIds = new HashSet<>();
        List<MigrationReport.Warning> skippedWarnings = new ArrayList<>();
        if (props.getDependency().isExcludeAlreadyMigrated()) {
            Set<String> all = new LinkedHashSet<>();
            byType.values().forEach(list -> list.forEach(s -> all.add(s.getSourceId())));
            Set<String> skip = presenceChecker.alreadyInKong(creds, req.getCompanyName(), req.getWso2Tenant(), all);
            if (!skip.isEmpty()) {
                // In DECK mode we KEEP already-migrated resources in the bundle (still flagged below):
                // decK apply is idempotent and git skips unchanged files, and — crucially — the
                // per-API directory layout needs the selected API present to anchor its folder.
                // Dropping it would leave a dependencies-only bundle that falls back to the flat
                // kong/<env>/ layout and makes the pipeline apply the WHOLE directory. The REST
                // deployer (deck disabled) still drops them to avoid pointless re-POSTs.
                boolean keepInBundle = props.getDeck().isEnabled();
                byType.replaceAll((type, list) -> {
                    List<DiscoverySnapshot> kept = new ArrayList<>();
                    for (DiscoverySnapshot s : list) {
                        if (skip.contains(s.getSourceId())) {
                            skippedIds.add(s.getSourceId());
                            skippedWarnings.add(MigrationReport.Warning.builder()
                                    .resourceType(type).wso2SourceId(s.getSourceId()).wso2SourceName(s.getSourceName())
                                    .code("SKIPPED_ALREADY_IN_KONG")
                                    .message(keepInBundle
                                            ? "Already present in Kong (matched by wso2-source-id tag) — kept in the deck bundle so decK updates it idempotently."
                                            : "Already present in Kong (matched by wso2-source-id tag) — not re-migrated.")
                                    .build());
                            if (keepInBundle) {
                                kept.add(s);   // keep so the per-API folder anchors; decK apply is idempotent
                            }
                        } else {
                            kept.add(s);
                        }
                    }
                    return kept;
                });
                log.info("[dependency] {} resource(s) already in Kong for job {} ({})",
                        skippedIds.size(), job.getId(), keepInBundle ? "kept in deck bundle" : "skipped");
            }
        }

        // 5) build the structured tree (root + its deps, by name)
        Map<String, String> names = new HashMap<>();
        byType.forEach((t, list) -> list.forEach(s -> names.put(s.getSourceId(), s.getSourceName())));
        skippedWarnings.forEach(w -> names.put(w.getWso2SourceId(), w.getWso2SourceName()));
        roots.forEach(r -> names.putIfAbsent(r.snap().getSourceId(), r.snap().getSourceName()));

        List<MigrationReport.DependencyMigration> tree = new ArrayList<>();
        for (Root root : roots) {
            List<MigrationReport.DependencyMigration.Dep> deps = new ArrayList<>();
            rootDirect.getOrDefault(root.snap().getSourceId(), Map.of()).forEach((depType, ids) -> {
                for (String id : ids) {
                    deps.add(MigrationReport.DependencyMigration.Dep.builder()
                            .resourceType(depType).wso2SourceId(id)
                            .name(names.getOrDefault(id, id))
                            .alreadyInKong(skippedIds.contains(id))
                            .build());
                }
            });
            tree.add(MigrationReport.DependencyMigration.builder()
                    .resourceType(root.type()).wso2SourceId(root.snap().getSourceId())
                    .name(root.snap().getSourceName())
                    .alreadyInKong(skippedIds.contains(root.snap().getSourceId()))
                    .dependencies(deps)
                    .build());
        }

        log.info("[dependency] expansion done for job {} — final counts {}, skipped {}, roots {}",
                job.getId(), countMap(byType), skippedIds.size(), tree.size());
        return new ExpansionResult(byType, skippedWarnings, tree);
    }

    private void loadAndMerge(StartMigrationRequest req, Map<String, List<DiscoverySnapshot>> byType,
                              String type, Set<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        merge(byType, type, loadFiltered(req, type, ids));
    }

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
