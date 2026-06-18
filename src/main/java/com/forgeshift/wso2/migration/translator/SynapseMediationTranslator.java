package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.TargetMode;
import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import lombok.Builder;
import lombok.Data;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DETERMINISTIC (no-AI) translator for WSO2 legacy mediation sequences (Synapse XML).
 *
 * <p>Two clean shapes are handled:
 * <ul>
 *   <li><b>Pure header manipulation</b> — {@code <property scope="transport" .../>} / {@code <header/>}
 *       set/remove with LITERAL values → a Kong {@code request}/{@code response-transformer} (works in
 *       any gateway mode; same config shape as {@link OperationPolicyTranslator}).</li>
 *   <li><b>Pure conditional headers</b> — {@code <filter source="$trp:H" regex="..">} whose
 *       {@code then}/{@code else} branches only set/remove headers → the reusable
 *       {@link ConditionalHeaderPluginBuilder} Lua plugin (CUSTOM_PLUGIN mode only — a custom plugin
 *       can't run on serverless).</li>
 * </ul>
 *
 * <p>Conservative by design: any unsupported mediator (switch/payloadFactory/enrich/class/script/send/
 * call/sequence/makefault), a dynamic value/expression, a non-transport property, a non-header filter
 * source, a branch with non-header mediators, a MIX of plain headers + filters, or a fault flow makes
 * the whole sequence non-deterministic → the caller warns (and may fall back to AI, when enabled). A
 * branching/coded sequence is never partially or wrongly migrated.
 */
public final class SynapseMediationTranslator {

    private SynapseMediationTranslator() {}

    @Data
    @Builder
    public static class Result {
        /** true → output is safe to deploy with no AI; false → caller must warn / fall back. */
        private boolean deterministic;
        /** request/response-transformer OR the forgeshift-conditional-headers instance; null when not deterministic. */
        private KongPlugin plugin;
        /** Non-null ONLY for the conditional Lua plugin — the asset (handler+schema) to upload before apply. */
        private CustomPluginArtifact customPlugin;
        @Builder.Default private List<String> unsupported = new ArrayList<>();
        @Builder.Default private List<String> notes = new ArrayList<>();
    }

    private static final Set<String> TRANSPORT_SCOPES = Set.of("transport", "axis2", "axis2-client");
    private static final List<String> DYNAMIC_MARKERS = List.of("{", "$", "get-property(");
    private static final Pattern GET_PROP_TRANSPORT =
            Pattern.compile("get-property\\(\\s*'transport'\\s*,\\s*'([^']+)'\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** Defaults to serverless (the conditional custom-plugin path is skipped). */
    public static Result translate(String synapseXml, String flow, List<String> tags) {
        return translate(synapseXml, flow, tags, TargetMode.SERVERLESS_INLINE);
    }

    public static Result translate(String synapseXml, String flow, List<String> tags, TargetMode mode) {
        if (synapseXml == null || synapseXml.isBlank()) return notDeterministic("empty-sequence");
        if ("fault".equalsIgnoreCase(flow)) return notDeterministic("fault-flow");

        Element seq;
        try {
            seq = parse(synapseXml);
        } catch (Exception e) {
            return notDeterministic("unparseable-xml");
        }

        List<String> unsupported = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Object> cfg = new LinkedHashMap<>();
        List<Map<String, Object>> rules = new ArrayList<>();
        List<Map<String, Object>> switches = new ArrayList<>();
        int headerOps = 0;

        NodeList children = seq.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String local = localName(el);
            switch (local) {
                case "property", "header" -> {
                    HeaderOp op = parseHeaderMediator(el, local);
                    switch (op.type()) {
                        case SET -> {
                            addToList(cfg, "add", "headers", op.value());
                            addToList(cfg, "replace", "headers", op.value());
                            headerOps++;
                        }
                        case REMOVE -> {
                            addToList(cfg, "remove", "headers", op.value());
                            headerOps++;
                        }
                        case UNSUPPORTED -> unsupported.add(local + "(" + op.reason() + ")");
                    }
                }
                case "filter" -> {
                    Map<String, Object> rule = parseFilter(el, unsupported);
                    if (rule != null) rules.add(rule);
                }
                case "switch" -> {
                    Map<String, Object> sw = parseSwitch(el, unsupported);
                    if (sw != null) switches.add(sw);
                }
                case "log", "comment" -> notes.add(local + " mediator ignored (Kong logs via its logging plugins)");
                default -> unsupported.add(local);
            }
        }

        if (!unsupported.isEmpty()) {
            return done(false, null, null, unsupported, notes);
        }

        // Conditional (filter/switch) sequences → the reusable Lua plugin, custom-plugin mode only.
        if (!rules.isEmpty() || !switches.isEmpty()) {
            if (headerOps > 0) {
                unsupported.add("conditional+header-mix");
                return done(false, null, null, unsupported, notes);
            }
            if (mode != TargetMode.CUSTOM_PLUGIN) {
                unsupported.add("conditional(requires a dedicated custom-plugin control plane)");
                return done(false, null, null, unsupported, notes);
            }
            Map<String, Object> conf = new LinkedHashMap<>();
            conf.put("flow", isResponseFlow(flow) ? "response" : "request");
            conf.put("rules", rules);
            conf.put("switches", switches);
            KongPlugin instance = KongPlugin.builder()
                    .name(ConditionalHeaderPluginBuilder.PLUGIN_NAME).config(conf).enabled(true)
                    .tags(tags == null ? null : new ArrayList<>(tags)).build();
            return done(true, instance, ConditionalHeaderPluginBuilder.asset(), unsupported, notes);
        }

        // Pure header manipulation → built-in transformer (any mode).
        if (headerOps > 0) {
            String pluginName = isResponseFlow(flow) ? "response-transformer" : "request-transformer";
            KongPlugin plugin = KongPlugin.builder()
                    .name(pluginName).config(cfg).enabled(true)
                    .tags(tags == null ? null : new ArrayList<>(tags)).build();
            return done(true, plugin, null, unsupported, notes);
        }

        return done(false, null, null, unsupported, notes);   // nothing to migrate
    }

    // ---------------- filter ----------------

    /** Parse a {@code <filter source=.. regex=..>} into a conditional rule; records into {@code unsupported} on any miss. */
    private static Map<String, Object> parseFilter(Element filter, List<String> unsupported) {
        String source = trimToNull(filter.getAttribute("source"));
        String regex = trimToNull(filter.getAttribute("regex"));
        String header = source == null ? null : transportHeaderFromSource(source);
        if (header == null) {
            unsupported.add("filter(source)");
            return null;
        }
        if (regex == null) {
            unsupported.add("filter(no-regex)");
            return null;
        }
        BranchOps thenOps = parseBranch(childByLocal(filter, "then"));
        BranchOps elseOps = parseBranch(childByLocal(filter, "else"));
        if (thenOps == null || elseOps == null) {
            unsupported.add("filter(branch)");
            return null;
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("source_header", header);
        rule.put("regex", regex);
        rule.put("then_set", thenOps.set);
        rule.put("then_remove", thenOps.remove);
        rule.put("else_set", elseOps.set);
        rule.put("else_remove", elseOps.remove);
        return rule;
    }

    /** Parse a {@code <switch source=..>} with {@code <case regex=..>} + {@code <default>} into a switch entry. */
    private static Map<String, Object> parseSwitch(Element sw, List<String> unsupported) {
        String source = trimToNull(sw.getAttribute("source"));
        String header = source == null ? null : transportHeaderFromSource(source);
        if (header == null) {
            unsupported.add("switch(source)");
            return null;
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        BranchOps defaultOps = new BranchOps();
        NodeList kids = sw.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String local = localName(el);
            switch (local) {
                case "case" -> {
                    String regex = trimToNull(el.getAttribute("regex"));
                    if (regex == null) {
                        unsupported.add("switch(case-no-regex)");
                        return null;
                    }
                    BranchOps ops = parseBranch(el);
                    if (ops == null) {
                        unsupported.add("switch(case-branch)");
                        return null;
                    }
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("regex", regex);
                    c.put("set", ops.set);
                    c.put("remove", ops.remove);
                    cases.add(c);
                }
                case "default" -> {
                    BranchOps ops = parseBranch(el);
                    if (ops == null) {
                        unsupported.add("switch(default-branch)");
                        return null;
                    }
                    defaultOps = ops;
                }
                case "log", "comment" -> { /* ignore */ }
                default -> {
                    unsupported.add("switch(" + local + ")");
                    return null;
                }
            }
        }
        if (cases.isEmpty()) {
            unsupported.add("switch(no-cases)");
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source_header", header);
        out.put("cases", cases);
        out.put("default_set", defaultOps.set);
        out.put("default_remove", defaultOps.remove);
        return out;
    }

    private static String transportHeaderFromSource(String source) {
        String s = source.trim();
        if (s.regionMatches(true, 0, "$trp:", 0, 5)) {
            String h = s.substring(5).trim();
            return h.isEmpty() ? null : h;
        }
        Matcher m = GET_PROP_TRANSPORT.matcher(s);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private static final class BranchOps {
        final List<String> set = new ArrayList<>();
        final List<String> remove = new ArrayList<>();
    }

    /** A null branch (no {@code <then>}/{@code <else>}) → empty ops. Returns null if any child is unsupported. */
    private static BranchOps parseBranch(Element branch) {
        BranchOps ops = new BranchOps();
        if (branch == null) return ops;
        NodeList kids = branch.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String local = localName(el);
            if (local.equals("log") || local.equals("comment")) continue;
            if (!local.equals("property") && !local.equals("header")) return null;
            HeaderOp op = parseHeaderMediator(el, local);
            switch (op.type()) {
                case SET -> ops.set.add(op.value());
                case REMOVE -> ops.remove.add(op.value());
                case UNSUPPORTED -> {
                    return null;
                }
            }
        }
        return ops;
    }

    private static Element childByLocal(Element parent, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName((Element) n).equals(name)) return (Element) n;
        }
        return null;
    }

    // ---------------- header mediator ----------------

    private enum OpType { SET, REMOVE, UNSUPPORTED }

    private record HeaderOp(OpType type, String value, String reason) {}

    private static HeaderOp parseHeaderMediator(Element el, String local) {
        String name = trimToNull(el.getAttribute("name"));
        String value = trimToNull(el.getAttribute("value"));
        String expression = trimToNull(el.getAttribute("expression"));
        String scope = lower(trimToNull(el.getAttribute("scope")));
        String action = lower(trimToNull(el.getAttribute("action")));

        boolean isHttpHeader = "header".equals(local)
                ? (scope == null || TRANSPORT_SCOPES.contains(scope))
                : (scope != null && TRANSPORT_SCOPES.contains(scope));
        if (!isHttpHeader) return new HeaderOp(OpType.UNSUPPORTED, null, "scope=" + scope);
        if ("remove".equals(action)) {
            return name != null ? new HeaderOp(OpType.REMOVE, name, null)
                    : new HeaderOp(OpType.UNSUPPORTED, null, "remove,no-name");
        }
        if (expression != null && value == null) return new HeaderOp(OpType.UNSUPPORTED, null, "expression");
        if (name == null || value == null) return new HeaderOp(OpType.UNSUPPORTED, null, "incomplete");
        if (isDynamic(value)) return new HeaderOp(OpType.UNSUPPORTED, null, "dynamic-value");
        // WSO2 "set" = overwrite-or-add → both add (if absent) and replace (if present).
        return new HeaderOp(OpType.SET, name + ":" + value, null);
    }

    // ---------------- helpers ----------------

    private static Result done(boolean det, KongPlugin plugin, CustomPluginArtifact cp,
                               List<String> unsupported, List<String> notes) {
        return Result.builder().deterministic(det).plugin(plugin).customPlugin(cp)
                .unsupported(unsupported).notes(notes).build();
    }

    private static Result notDeterministic(String reason) {
        List<String> u = new ArrayList<>();
        u.add(reason);
        return Result.builder().deterministic(false).unsupported(u).build();
    }

    private static boolean isResponseFlow(String flow) {
        return flow != null && (flow.equalsIgnoreCase("out") || flow.equalsIgnoreCase("response"));
    }

    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element root = doc.getDocumentElement();
        if ("sequence".equals(localName(root))) return root;
        NodeList seqs = root.getElementsByTagNameNS("*", "sequence");
        if (seqs.getLength() > 0 && seqs.item(0) instanceof Element e) return e;
        return root;
    }

    private static String localName(Element el) {
        String ln = el.getLocalName();
        return (ln != null ? ln : el.getNodeName()).toLowerCase();
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(); }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isDynamic(String value) {
        String v = value.toLowerCase();
        for (String m : DYNAMIC_MARKERS) {
            if (v.contains(m)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void addToList(Map<String, Object> cfg, String section, String field, String value) {
        Map<String, Object> sec = (Map<String, Object>) cfg.computeIfAbsent(section, k -> new LinkedHashMap<String, Object>());
        ((List<String>) sec.computeIfAbsent(field, k -> new ArrayList<String>())).add(value);
    }
}
