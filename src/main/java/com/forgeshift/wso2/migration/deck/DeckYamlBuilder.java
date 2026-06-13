package com.forgeshift.wso2.migration.deck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.domain.kong.KongRoute;
import com.forgeshift.wso2.migration.translator.TranslatedApi;
import com.forgeshift.wso2.migration.translator.TranslatedApiProduct;
import com.forgeshift.wso2.migration.translator.TranslatedCertificate;
import com.forgeshift.wso2.migration.translator.TranslatedConsumer;
import com.forgeshift.wso2.migration.translator.TranslatedMediationPolicy;
import com.forgeshift.wso2.migration.translator.Wso2SecuritySchemes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the already-translated Kong objects into decK declarative YAML.
 *
 * <p>Two outputs:
 * <ul>
 *   <li>{@link #build} — a single combined {@code kong.yaml} (used for dry-run / preview).</li>
 *   <li>{@link #buildFiles} — <b>one file per API</b> (plus grouped consumers / certs / products)
 *       under {@code kong/<env>/}. This is what makes <b>single / incremental</b> migration work:
 *       each API owns its own file, so migrating one API only rewrites that one file and the
 *       pipeline applies the whole directory (decK merges it). A single shared file would
 *       overwrite the others on every run.</li>
 * </ul>
 *
 * <p>Relationships are expressed the decK way: nesting (routes under services, plugins under
 * their scope, targets under upstreams) and, for API products + mediation policies, by
 * referencing the member service <b>by name</b> (decK merges across files, so the name resolves
 * even when the service is defined in a different file). Every entity keeps its
 * {@code wso2-source-id:<uuid>} tag for later mapping rebuild.
 *
 * <p>Entities are emitted <b>without an {@code id}</b> by default (see {@link #putId}) so
 * {@code deck gateway apply} matches an already-present Konnect entity by name and updates it
 * instead of colliding on the name; the real Kong ids are recovered afterwards from
 * {@code deck gateway dump} (see {@code DeckResultMapper}). Set {@code deck.emit-entity-ids} to
 * pin deterministic ids instead (greenfield control planes only).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeckYamlBuilder {

    private final MigrationProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------- single combined file (dry-run / preview) ----------------

    public String build(List<TranslatedApi> apis,
                        List<TranslatedConsumer> consumers,
                        List<TranslatedCertificate> certificates,
                        List<TranslatedApiProduct> products,
                        List<TranslatedMediationPolicy> mediations) {
        return build(apis, consumers, certificates, products, mediations, Map.of());
    }

    /**
     * @param kongIds real Kong ids of already-deployed entities, keyed
     *                {@code "<wso2SourceId>|<KONG_ENTITY_TYPE>"} (SERVICE / CONSUMER /
     *                UPSTREAM / CA_CERTIFICATE). When present, the id is emitted on the
     *                entity so {@code deck gateway apply} matches and UPDATES it instead of
     *                failing "entity already exists" — apply never deletes, so other services
     *                are untouched. New entities (no mapping) are left for apply to create.
     */
    public String build(List<TranslatedApi> apis,
                        List<TranslatedConsumer> consumers,
                        List<TranslatedCertificate> certificates,
                        List<TranslatedApiProduct> products,
                        List<TranslatedMediationPolicy> mediations,
                        Map<String, String> kongIds) {

        Map<String, String> svcNames = serviceNames(apis);
        Map<String, Object> root = newRoot();
        // Product-context routes and mediation plugins are folded into their member/target service
        // (nested), so the service stays a single self-contained definition — see serviceNode().
        Map<String, List<Object>> prodByMember = productRoutesByMember(products, svcNames);
        Map<String, List<Object>> medByApi = mediationPluginsByApi(mediations, svcNames);

        List<Object> services = new ArrayList<>();
        List<Object> upstreams = new ArrayList<>();
        if (apis != null) {
            for (TranslatedApi a : apis) {
                if (a.getService() == null) continue;
                Map<String, Object> svc = serviceNode(a, prodByMember.get(a.getWso2SourceId()),
                        medByApi.get(a.getWso2SourceId()));
                injectKongId(svc, kongIds, a.getWso2SourceId(), "SERVICE");
                services.add(svc);
                Map<String, Object> up = upstreamNode(a);
                if (up != null) {
                    injectKongId(up, kongIds, a.getWso2SourceId(), "UPSTREAM");
                    upstreams.add(up);
                }
            }
        }

        List<Object> consumerList = consumerNodes(consumers, kongIds);
        if (usesAnonymousAuth(apis, products)) consumerList.add(anonymousConsumerNode());
        List<Object> certList = certNodes(certificates, kongIds);

        if (!services.isEmpty()) root.put("services", services);
        if (!consumerList.isEmpty()) root.put("consumers", consumerList);
        if (!upstreams.isEmpty()) root.put("upstreams", upstreams);
        if (!certList.isEmpty()) root.put("ca_certificates", certList);

        return dump(root);
    }

    // ---------------- one file per API (incremental-friendly) ----------------

    /**
     * Build the set of decK files for a migration: {@code kong/<env>/api-<name>.yaml} per API
     * (service + its routes/plugins/upstream + its mediation plugins), plus grouped
     * {@code consumers.yaml}, {@code ca-certificates.yaml}, {@code api-products.yaml}.
     *
     * @return map of repo-relative path → YAML content
     */
    public Map<String, String> buildFiles(String env,
                                          List<TranslatedApi> apis,
                                          List<TranslatedConsumer> consumers,
                                          List<TranslatedCertificate> certificates,
                                          List<TranslatedApiProduct> products,
                                          List<TranslatedMediationPolicy> mediations) {
        return buildFiles(env, apis, consumers, certificates, products, mediations, Map.of());
    }

    /** See {@link #build(List, List, List, List, List, Map)} for the meaning of {@code kongIds}. */
    public Map<String, String> buildFiles(String env,
                                          List<TranslatedApi> apis,
                                          List<TranslatedConsumer> consumers,
                                          List<TranslatedCertificate> certificates,
                                          List<TranslatedApiProduct> products,
                                          List<TranslatedMediationPolicy> mediations,
                                          Map<String, String> kongIds) {
        String dir = props.getDeck().getKongConfigDirTemplate().replace("{env}", env);
        // Single-API migration → isolate every file for this API under kong/<env>/<api-slug>/ so the
        // pipeline applies ONLY this API. An unrelated API's leftover file (a different directory)
        // is never in the apply path, so it can't block this migration. Multi-API keeps flat.
        if (props.getDeck().isPerApiDir() && apis != null && apis.size() == 1
                && apis.get(0).getService() != null) {
            dir = dir + "/" + fileSlug(apis.get(0).getService().getName(), apis.get(0).getWso2SourceId());
        }
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, String> svcNames = serviceNames(apis);

        // Product-context routes and mediation plugins fold (nested) into their member/target service's
        // own file, so each API file holds ONE self-contained service. A separate api-products.yaml with
        // top-level routes referencing the service by {name:X} makes decK apply insert the service twice
        // ("entity already exists") even though it validates/renders fine — see serviceNode().
        Map<String, List<Object>> prodByMember = productRoutesByMember(products, svcNames);
        Map<String, List<Object>> medByApi = mediationPluginsByApi(mediations, svcNames);

        if (apis != null) {
            for (TranslatedApi a : apis) {
                if (a.getService() == null) continue;
                Map<String, Object> root = newRoot();
                Map<String, Object> svc = serviceNode(a, prodByMember.get(a.getWso2SourceId()),
                        medByApi.get(a.getWso2SourceId()));
                injectKongId(svc, kongIds, a.getWso2SourceId(), "SERVICE");
                root.put("services", List.of(svc));
                Map<String, Object> up = upstreamNode(a);
                if (up != null) {
                    injectKongId(up, kongIds, a.getWso2SourceId(), "UPSTREAM");
                    root.put("upstreams", List.of(up));
                }
                files.put(dir + "/api-" + fileSlug(a.getService().getName(), a.getWso2SourceId()) + ".yaml",
                        dump(root));
            }
        }

        List<Object> consumerList = consumerNodes(consumers, kongIds);
        if (usesAnonymousAuth(apis, products)) consumerList.add(anonymousConsumerNode());
        if (!consumerList.isEmpty()) {
            Map<String, Object> root = newRoot();
            root.put("consumers", consumerList);
            files.put(dir + "/consumers.yaml", dump(root));
        }

        List<Object> certList = certNodes(certificates, kongIds);
        if (!certList.isEmpty()) {
            Map<String, Object> root = newRoot();
            root.put("ca_certificates", certList);
            files.put(dir + "/ca-certificates.yaml", dump(root));
        }

        return files;
    }

    // ---------------- node builders ----------------

    /**
     * @param extraRoutes  product-context routes whose member API is this service — nested here so the
     *                     service owns ALL its routes. A top-level route in a SEPARATE file referencing
     *                     the service by {@code {name: X}} makes decK's apply state-builder insert the
     *                     service a second time ("entity already exists"), even though it validates and
     *                     renders fine. Nesting keeps one self-contained service definition.
     * @param extraPlugins mediation plugins targeting this service — nested for the same reason.
     */
    private Map<String, Object> serviceNode(TranslatedApi a, List<Object> extraRoutes, List<Object> extraPlugins) {
        Map<String, Object> svc = toMap(a.getService());
        String svcName = a.getService().getName();
        putId(svc, "service:" + svcName);
        List<Object> routeList = new ArrayList<>();
        if (a.getRoutes() != null) {
            for (KongRoute r : a.getRoutes()) {
                Map<String, Object> routeMap = toMap(r);
                routeMap.remove("service");   // nested under the service → no FK needed
                putId(routeMap, "route:" + svcName + ":" + r.getName());
                List<KongPlugin> rps = a.getRoutePlugins() == null ? null : a.getRoutePlugins().get(r.getName());
                List<Object> rpMaps = pluginMaps(rps, "route:" + svcName + ":" + r.getName());
                if (!rpMaps.isEmpty()) routeMap.put("plugins", rpMaps);
                routeList.add(routeMap);
            }
        }
        if (extraRoutes != null) routeList.addAll(extraRoutes);
        if (!routeList.isEmpty()) svc.put("routes", routeList);
        List<Object> spMaps = new ArrayList<>(pluginMaps(a.getServicePlugins(), "service:" + svcName));
        if (extraPlugins != null) spMaps.addAll(extraPlugins);
        if (!spMaps.isEmpty()) svc.put("plugins", spMaps);
        return svc;
    }

    private Map<String, Object> upstreamNode(TranslatedApi a) {
        if (a.getUpstream() == null) return null;
        Map<String, Object> up = toMap(a.getUpstream());
        String upName = a.getUpstream().getName();
        putId(up, "upstream:" + upName);
        List<Object> tgts = new ArrayList<>();
        if (a.getTargets() != null) {
            a.getTargets().forEach(t -> {
                Map<String, Object> tm = toMap(t);
                putId(tm, "target:" + upName + ":" + t.getTarget());
                tgts.add(tm);
            });
        }
        if (!tgts.isEmpty()) up.put("targets", tgts);
        return up;
    }

    private List<Object> consumerNodes(List<TranslatedConsumer> consumers, Map<String, String> kongIds) {
        List<Object> out = new ArrayList<>();
        if (consumers == null) return out;
        for (TranslatedConsumer c : consumers) {
            if (c.getConsumer() == null) continue;
            Map<String, Object> cm = toMap(c.getConsumer());
            Object cid = cm.getOrDefault("username", cm.get("custom_id"));
            putId(cm, "consumer:" + cid);
            injectKongId(cm, kongIds, c.getWso2SourceId(), "CONSUMER");
            List<Object> cps = pluginMaps(c.getConsumerPlugins(), "consumer:" + cid);
            if (!cps.isEmpty()) cm.put("plugins", cps);
            out.add(cm);
        }
        return out;
    }

    /** True when any auth plugin uses the anonymous fallback (an OR-auth route) — the shared
     *  anonymous consumer + its deny plugin must then be emitted so requests satisfying NEITHER
     *  scheme are rejected. */
    private boolean usesAnonymousAuth(List<TranslatedApi> apis, List<TranslatedApiProduct> products) {
        if (apis != null) {
            for (TranslatedApi a : apis) {
                if (pluginsUseAnonymous(a.getServicePlugins())) return true;
                if (a.getRoutePlugins() != null) {
                    for (List<KongPlugin> rp : a.getRoutePlugins().values()) {
                        if (pluginsUseAnonymous(rp)) return true;
                    }
                }
            }
        }
        if (products != null) {
            for (TranslatedApiProduct prod : products) {
                if (prod.getRoutes() == null) continue;
                for (TranslatedApiProduct.ProductRoute pr : prod.getRoutes()) {
                    if (pluginsUseAnonymous(pr.getPlugins())) return true;
                }
            }
        }
        return false;
    }

    private static boolean pluginsUseAnonymous(List<KongPlugin> plugins) {
        if (plugins == null) return false;
        for (KongPlugin pl : plugins) {
            if (pl != null && pl.getConfig() != null && pl.getConfig().get("anonymous") != null) return true;
        }
        return false;
    }

    /** The shared anonymous consumer (fixed id so {@code config.anonymous} resolves) plus a
     *  consumer-scoped request-termination that 401s any request which fell through to it — i.e.
     *  one that satisfied NEITHER auth method. A request authenticated by jwt OR key-auth becomes
     *  its real consumer and never reaches this, giving WSO2's optional multi-auth its OR meaning. */
    private Map<String, Object> anonymousConsumerNode() {
        List<String> tags = List.of(props.getTranslation().getMigratedByTag(), "wso2-anonymous");
        Map<String, Object> deny = new LinkedHashMap<>();
        deny.put("name", "request-termination");
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("status_code", 401);
        cfg.put("message", "No authentication credentials found");
        deny.put("config", cfg);
        deny.put("tags", tags);

        Map<String, Object> consumer = new LinkedHashMap<>();
        consumer.put("username", Wso2SecuritySchemes.ANONYMOUS_USERNAME);
        consumer.put("id", Wso2SecuritySchemes.ANONYMOUS_CONSUMER_ID);
        consumer.put("tags", tags);
        consumer.put("plugins", List.of(deny));
        return consumer;
    }

    /**
     * Override an entity's {@code id} with the real Kong id of the already-deployed entity
     * (recovered into {@code entity_mappings} by {@link DeckResultMapper} after a prior apply),
     * so {@code deck gateway apply} matches by id and UPDATES it. No mapping → leave whatever
     * {@link #putId} set (none by default), so apply creates the new entity. apply never deletes.
     */
    private void injectKongId(Map<String, Object> node, Map<String, String> kongIds,
                              String wso2SourceId, String kongEntityType) {
        if (node == null || kongIds == null) return;
        String realId = null;
        // Prefer Kong's UNIQUE business key (service/upstream name, consumer username). It is
        // unambiguous even when an orphan entity from an older run carries the same wso2-source-id
        // tag — matching on that tag alone could pin the orphan's id and make apply think the real
        // entity is new ("entity already exists"). Source-id is only a fallback (e.g. CA certs).
        Object name = node.get("name");
        Object username = node.get("username");
        if (name instanceof String n && StringUtils.hasText(n)) {
            realId = kongIds.get(kongEntityType + "|name:" + n);
        }
        if (realId == null && username instanceof String u && StringUtils.hasText(u)) {
            realId = kongIds.get(kongEntityType + "|username:" + u);
        }
        if (realId == null && StringUtils.hasText(wso2SourceId)) {
            realId = kongIds.get(wso2SourceId + "|" + kongEntityType);
        }
        if (StringUtils.hasText(realId)) {
            node.put("id", realId);
        }
    }

    private List<Object> certNodes(List<TranslatedCertificate> certificates, Map<String, String> kongIds) {
        List<Object> out = new ArrayList<>();
        if (certificates == null) return out;
        for (TranslatedCertificate tc : certificates) {
            if (tc.getCaCertificate() == null || !StringUtils.hasText(tc.getCaCertificate().getCert())) continue;
            Map<String, Object> cm = toMap(tc.getCaCertificate());
            putId(cm, "ca:" + tc.getCaCertificate().getCert());
            injectKongId(cm, kongIds, tc.getWso2SourceId(), "CA_CERTIFICATE");
            out.add(cm);
        }
        return out;
    }

    /**
     * Product-context routes grouped by their member API's wso2SourceId, in NESTED form (no {@code service}
     * FK) so each can be folded into its member service's {@code routes:} list. See {@link #serviceNode}
     * for why nesting (vs a top-level route with {@code service: {name: X}}) matters for decK apply.
     */
    private Map<String, List<Object>> productRoutesByMember(List<TranslatedApiProduct> products,
                                                            Map<String, String> svcNames) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        if (products == null) return out;
        for (TranslatedApiProduct p : products) {
            if (p.getRoutes() == null) continue;
            for (TranslatedApiProduct.ProductRoute pr : p.getRoutes()) {
                if (pr.getRoute() == null || !svcNames.containsKey(pr.getMemberApiId())) {
                    log.warn("Product '{}' route skipped — member API {} has no translated service in this bundle",
                            p.getWso2SourceName(), pr.getMemberApiId());
                    continue;
                }
                Map<String, Object> routeMap = toMap(pr.getRoute());
                routeMap.remove("service");   // nested under the member service → no cross-file FK
                putId(routeMap, "route:product:" + pr.getRoute().getName());
                List<Object> rpMaps = pluginMaps(pr.getPlugins(), "route:product:" + pr.getRoute().getName());
                if (!rpMaps.isEmpty()) routeMap.put("plugins", rpMaps);
                out.computeIfAbsent(pr.getMemberApiId(), k -> new ArrayList<>()).add(routeMap);
            }
        }
        return out;
    }

    /**
     * Mediation plugins grouped by their target API's wso2SourceId, in NESTED form (no {@code service}
     * FK) so each folds into its service's {@code plugins:} list (same apply-safety reason as routes).
     */
    private Map<String, List<Object>> mediationPluginsByApi(List<TranslatedMediationPolicy> mediations,
                                                            Map<String, String> svcNames) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        if (mediations == null) return out;
        for (TranslatedMediationPolicy m : mediations) {
            if (!m.isDeployable()) continue;
            if (!svcNames.containsKey(m.getTargetApiId())) {
                log.warn("Mediation '{}' skipped — target API {} has no translated service in this bundle",
                        m.getWso2SourceName(), m.getTargetApiId());
                continue;
            }
            Map<String, Object> pm = toMap(m.getPlugin());
            pm.remove("route");
            pm.remove("consumer");
            pm.remove("service");   // nested under the service → no FK
            putId(pm, "plugin:service:" + svcNames.get(m.getTargetApiId()) + ":mediation:" + m.getWso2SourceId());
            out.computeIfAbsent(m.getTargetApiId(), k -> new ArrayList<>()).add(pm);
        }
        return out;
    }

    // ---------------- helpers ----------------

    private Map<String, String> serviceNames(List<TranslatedApi> apis) {
        Map<String, String> m = new LinkedHashMap<>();
        if (apis != null) {
            for (TranslatedApi a : apis) {
                if (a.getService() != null && StringUtils.hasText(a.getService().getName())) {
                    m.put(a.getWso2SourceId(), a.getService().getName());
                }
            }
        }
        return m;
    }

    private List<Object> pluginMaps(List<KongPlugin> plugins, String parentKey) {
        List<Object> out = new ArrayList<>();
        if (plugins == null) return out;
        for (KongPlugin p : plugins) {
            if (p == null) continue;
            Map<String, Object> pm = toMap(p);
            pm.remove("service");
            pm.remove("route");
            pm.remove("consumer");
            putId(pm, "plugin:" + parentKey + ":" + p.getName());
            out.add(pm);
        }
        return out;
    }

    /**
     * Conditionally pin a deterministic {@code id} on an entity node, gated by
     * {@code deck.emit-entity-ids} (default off).
     *
     * <p><b>Off (default) — match by name.</b> Nodes carry NO id, so {@code deck gateway apply}
     * reconciles each entity against the control plane by its unique natural key (service /
     * route / upstream / consumer name, target host:port, plugin name+scope, ca-cert content),
     * ADOPTS the existing entity's real id, and UPDATES it. This is what makes incremental
     * migration onto a control plane that may already hold same-named entities safe: a service
     * already present as {@code existing-service} is updated, not re-created. Pinning a freshly
     * <i>generated</i> id does the opposite — decK sees an id Kong doesn't know, tries to CREATE,
     * and Kong rejects it because the name is taken ("entity already exists").
     *
     * <p><b>On — pin generated ids.</b> Re-applying then matches by id and updates. Only safe for
     * a greenfield control plane where these same deterministic ids were used from the first
     * apply; an escape hatch, not the default.
     */
    private void putId(Map<String, Object> node, String key) {
        if (props.getDeck().isEmitEntityIds()) {
            node.put("id", stableId(key));
        }
    }

    /** Deterministic name-based UUID from a stable identity key; used only when {@link #putId} is on. */
    private static String stableId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Map<String, Object> newRoot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_format_version", props.getDeck().getFormatVersion());
        if (props.getDeck().isTransform()) {
            root.put("_transform", true);
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object o) {
        return mapper.convertValue(o, LinkedHashMap.class);
    }

    /** Safe, stable filename fragment from the service name (already a slug) or the source id. */
    private static String fileSlug(String name, String fallback) {
        String base = StringUtils.hasText(name) ? name : fallback;
        if (!StringUtils.hasText(base)) base = "entity";
        String slug = base.toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("(^-+|-+$)", "");
        return StringUtils.hasText(slug) ? slug : "entity";
    }

    private String dump(Map<String, Object> root) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        opts.setIndicatorIndent(0);
        opts.setSplitLines(false);
        return new Yaml(opts).dump(root);
    }
}
