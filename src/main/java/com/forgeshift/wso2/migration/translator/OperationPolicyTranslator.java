package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.TargetMode;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import lombok.Builder;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Translates the WSO2 AM 4.x <b>operation policy</b> framework — {@code apiPolicies} and each
 * {@code operations[].operationPolicies}, with their {@code request} / {@code response} / {@code fault}
 * flows — into Kong {@code request-transformer} / {@code response-transformer} plugins.
 *
 * <p>The common built-in policies map directly:
 * <ul>
 *   <li>Add / Set / Remove / Rename header → transformer {@code add|replace|remove|rename.headers}</li>
 *   <li>Add / Remove query parameter (request only) → {@code add|remove.querystring}</li>
 *   <li>Rewrite resource path (request only) → {@code replace.uri}</li>
 *   <li>Rewrite HTTP method (request only) → {@code http_method}</li>
 * </ul>
 * Anything else — interceptor-service calls, custom Lua/Java policies, fault-flow policies — can't be
 * expressed declaratively, so it is returned as a manual-review warning rather than mistranslated.
 *
 * <p>Because the migration emits ONE Kong route per API (anchored at the WSO2 context), per-operation
 * policies are applied API-wide; that is flagged in a warning so it is never silent.
 */
public final class OperationPolicyTranslator {

    private OperationPolicyTranslator() {}

    @Data
    @Builder
    public static class Result {
        @Builder.Default private List<KongPlugin> plugins = new ArrayList<>();
        @Builder.Default private List<String> warnings = new ArrayList<>();
    }

    /** Back-compat: serverless mode, no custom-plugin catalog (custom script policies → manual review). */
    public static Result translate(Map<String, Object> apiPayload, String apiName, List<String> tags) {
        return translate(apiPayload, apiName, tags, TargetMode.SERVERLESS_INLINE, null);
    }

    /**
     * As {@link #translate(Map, String, List)} but, in {@code CUSTOM_PLUGIN} mode, a custom (non-built-in)
     * operation policy whose Synapse body is supplied in {@code opPolicyDefs} (keyed by policyId or
     * policyName — fetched live from WSO2's {@code /operation-policies/{id}/content}) is run through the
     * deterministic {@link KnownCustomMediatorTranslator} catalog. A recognised policy becomes its
     * pre-built Kong plugin instance (the handler/schema asset is uploaded by name in DeckBundleDeployer);
     * anything the catalog doesn't recognise stays a manual-review warning. No AI.
     */
    public static Result translate(Map<String, Object> apiPayload, String apiName, List<String> tags,
                                   TargetMode mode, Map<String, String> opPolicyDefs) {
        Result result = Result.builder().build();
        if (apiPayload == null) return result;

        List<Map<String, Object>> request = new ArrayList<>();
        List<Map<String, Object>> response = new ArrayList<>();
        boolean fault = collect(apiPayload.get("apiPolicies"), request, response);
        boolean perOperation = false;
        if (apiPayload.get("operations") instanceof List<?> ops) {
            for (Object o : ops) {
                if (o instanceof Map<?, ?> om && om.get("operationPolicies") != null) {
                    perOperation = true;
                    fault |= collect(om.get("operationPolicies"), request, response);
                }
            }
        }
        if (request.isEmpty() && response.isEmpty() && !fault) return result;

        List<String> unsupported = new ArrayList<>();
        List<String> customMapped = new ArrayList<>();
        List<Map<String, Object>> customCandidates = new ArrayList<>();
        Map<String, Object> reqCfg = new LinkedHashMap<>();
        applyTo(request, reqCfg, true, unsupported, customMapped, customCandidates);
        if (!reqCfg.isEmpty()) result.getPlugins().add(plugin("request-transformer", reqCfg, tags));

        Map<String, Object> respCfg = new LinkedHashMap<>();
        applyTo(response, respCfg, false, unsupported, customMapped, customCandidates);
        if (!respCfg.isEmpty()) result.getPlugins().add(plugin("response-transformer", respCfg, tags));

        // Custom (non-header) operation policies. In CUSTOM_PLUGIN mode, try the deterministic
        // custom-mediator catalog against each policy's fetched Synapse body (opPolicyDefs, keyed by
        // policyId/policyName). A recognised policy → its pre-built Kong plugin instance (the
        // handler/schema asset is uploaded by name in DeckBundleDeployer); the rest stay manual-review.
        String apiId = str(apiPayload.get("id"));
        if (apiId == null) apiId = apiName;
        List<String> migratedToPlugin = new ArrayList<>();
        for (Map<String, Object> pol : customCandidates) {
            String name = str(pol.get("policyName"));
            String synapse = mode == TargetMode.CUSTOM_PLUGIN ? lookupDef(opPolicyDefs, pol) : null;
            TranslatedMediationPolicy cat = (synapse == null || synapse.isBlank()) ? null
                    : KnownCustomMediatorTranslator.translate(apiId, apiName,
                            name == null ? "op-policy" : name, wrapSequence(synapse), "request", tags);
            if (cat != null && cat.getPlugin() != null) {
                result.getPlugins().add(cat.getPlugin());
                migratedToPlugin.add(name == null ? "custom-policy" : name);
            } else {
                unsupported.add(name == null ? "unnamed-policy" : name);
            }
        }
        if (!migratedToPlugin.isEmpty()) {
            result.getWarnings().add("API " + apiName + ": custom operation policies migrated to a pre-built "
                    + "Kong custom plugin from the catalog (no AI — verify the generated config): "
                    + String.join(", ", new LinkedHashSet<>(migratedToPlugin)) + ".");
        }

        if (!customMapped.isEmpty()) {
            result.getWarnings().add("API " + apiName + ": custom operation policies mapped to a header "
                    + "transform from their parameters (verify the intended behaviour): "
                    + String.join(", ", new LinkedHashSet<>(customMapped)) + ".");
        }
        if (!unsupported.isEmpty()) {
            result.getWarnings().add("API " + apiName + ": operation policies not auto-translated (manual review): "
                    + String.join(", ", new LinkedHashSet<>(unsupported)) + ".");
        }
        if (fault) {
            result.getWarnings().add("API " + apiName + ": fault-flow operation policies were not migrated "
                    + "(Kong has no fault flow) — handle error responses with plugins manually.");
        }
        if (perOperation && !result.getPlugins().isEmpty()) {
            result.getWarnings().add("API " + apiName + ": per-operation policies were applied API-wide "
                    + "(the migration emits one Kong route per API).");
        }
        return result;
    }

    /** Pulls request[]/response[] entries out of a policies object; true when a non-empty fault[] exists. */
    private static boolean collect(Object policies, List<Map<String, Object>> request, List<Map<String, Object>> response) {
        if (!(policies instanceof Map<?, ?> m)) return false;
        addAll(m.get("request"), request);
        addAll(m.get("response"), response);
        return m.get("fault") instanceof List<?> f && !f.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static void addAll(Object arr, List<Map<String, Object>> out) {
        if (arr instanceof List<?> l) {
            for (Object e : l) if (e instanceof Map<?, ?> em) out.add((Map<String, Object>) em);
        }
    }

    /**
     * Maps each policy into the transformer config. Built-ins map by name; unrecognized policy names
     * that carry header parameters are treated as custom header-set policies (recorded in
     * {@code customMapped}); everything else is recorded in {@code unsupported} for a manual-review warning.
     */
    @SuppressWarnings("unchecked")
    private static void applyTo(List<Map<String, Object>> policies, Map<String, Object> cfg, boolean requestFlow,
                                List<String> unsupported, List<String> customMapped,
                                List<Map<String, Object>> customCandidates) {
        for (Map<String, Object> pol : policies) {
            String name = str(pol.get("policyName"));
            Map<String, Object> params = pol.get("parameters") instanceof Map<?, ?> pm
                    ? (Map<String, Object>) pm : Map.of();
            switch (name == null ? "" : name.trim().toLowerCase()) {
                case "addheader", "setheader" -> {
                    String h = param(params, "headerName", "headerKey", "name");
                    String v = param(params, "headerValue", "value");
                    if (has(h)) {
                        addToList(cfg, "add", "headers", h + ":" + nz(v));
                        if (name.trim().equalsIgnoreCase("setHeader")) addToList(cfg, "replace", "headers", h + ":" + nz(v));
                    } else unsupported.add(name);
                }
                case "removeheader" -> {
                    String h = param(params, "headerName", "headerKey", "name");
                    if (has(h)) addToList(cfg, "remove", "headers", h); else unsupported.add(name);
                }
                case "renameheader" -> {
                    String from = param(params, "headerName", "currentHeaderName", "fromHeaderName");
                    String to = param(params, "updatedHeaderName", "newHeaderName", "toHeaderName");
                    if (has(from) && has(to)) addToList(cfg, "rename", "headers", from + ":" + to); else unsupported.add(name);
                }
                case "addqueryparam" -> {
                    // WSO2's built-in addQueryParam uses paramKey/paramValue; older guesses kept as fallback.
                    String q = param(params, "paramKey", "queryParamName", "paramName", "name");
                    String v = param(params, "paramValue", "queryParamValue", "value");
                    if (requestFlow && has(q)) addToList(cfg, "add", "querystring", q + ":" + nz(v)); else unsupported.add(name);
                }
                case "removequeryparam" -> {
                    String q = param(params, "paramKey", "queryParamName", "paramName", "name");
                    if (requestFlow && has(q)) addToList(cfg, "remove", "querystring", q); else unsupported.add(name);
                }
                case "rewriteresourcepath" -> {
                    // WSO2's built-in rewriteResourcePath (v3) uses newResourcePath.
                    String path = param(params, "newResourcePath", "resourcePath", "uriTemplate", "value");
                    if (requestFlow && has(path)) setScalar(cfg, "replace", "uri", path); else unsupported.add(name);
                }
                case "changehttpmethod", "rewritehttpmethod" -> {
                    // WSO2's built-in policy is changeHTTPMethod (httpMethod param); accept the older alias too.
                    String method = param(params, "httpMethod", "updatedHttpMethod", "updatedMethod", "value");
                    if (requestFlow && has(method)) cfg.put("http_method", method.trim().toUpperCase()); else unsupported.add(name);
                }
                default -> {
                    // Custom (non-built-in) operation policy. The overwhelmingly common enterprise/bank
                    // custom policy is a header setter — a WSO2 Synapse
                    // <property scope="transport" action="set" name=.. value=..> declared with
                    // headerName/headerValue parameters. Map that shape to a header transform so the policy's
                    // VALUES survive the migration instead of being dropped; anything without header params
                    // stays a manual-review warning (complex Synapse logic still needs the AI/Lua path).
                    String hName = param(params, "headerName", "headerKey", "name");
                    String hValue = param(params, "headerValue", "value");
                    if (has(hName) && has(hValue)) {
                        addToList(cfg, "add", "headers", hName + ":" + hValue);
                        addToList(cfg, "replace", "headers", hName + ":" + hValue); // WSO2 "set" = overwrite-or-add
                        customMapped.add(has(name) ? name : "custom-policy");
                    } else {
                        // Not a header policy. In CUSTOM_PLUGIN mode the caller tries the custom-mediator
                        // catalog against its Synapse body; otherwise it becomes a manual-review warning.
                        customCandidates.add(pol);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void addToList(Map<String, Object> cfg, String section, String field, String value) {
        Map<String, Object> sec = (Map<String, Object>) cfg.computeIfAbsent(section, k -> new LinkedHashMap<String, Object>());
        ((List<String>) sec.computeIfAbsent(field, k -> new ArrayList<String>())).add(value);
    }

    @SuppressWarnings("unchecked")
    private static void setScalar(Map<String, Object> cfg, String section, String field, String value) {
        Map<String, Object> sec = (Map<String, Object>) cfg.computeIfAbsent(section, k -> new LinkedHashMap<String, Object>());
        sec.put(field, value);
    }

    private static KongPlugin plugin(String name, Map<String, Object> config, List<String> tags) {
        return KongPlugin.builder().name(name).config(config).enabled(true)
                .tags(tags == null ? null : new ArrayList<>(tags)).build();
    }

    private static String param(Map<String, Object> params, String... keys) {
        for (String k : keys) {
            Object v = params.get(k);
            if (v != null && StringUtils.hasText(v.toString())) return v.toString().trim();
        }
        return null;
    }

    private static boolean has(String s) {
        return StringUtils.hasText(s);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** The fetched Synapse body for a policy entry, looked up by policyId first, then policyName. */
    private static String lookupDef(Map<String, String> defs, Map<String, Object> pol) {
        if (defs == null || defs.isEmpty()) return null;
        String id = str(pol.get("policyId"));
        String byId = id == null ? null : defs.get(id);
        if (byId != null) return byId;
        String name = str(pol.get("policyName"));
        return name == null ? null : defs.get(name);
    }

    /**
     * An operation-policy {@code .j2} is a Synapse <i>fragment</i> (several top-level mediators), not a
     * single-rooted document. Wrap it in a {@code <sequence>} so the catalog's XML parser accepts it.
     */
    private static String wrapSequence(String fragment) {
        String f = fragment == null ? "" : fragment.trim();
        if (f.regionMatches(true, 0, "<sequence", 0, "<sequence".length())) return f;
        return "<sequence xmlns=\"http://ws.apache.org/ns/synapse\" name=\"op\">" + f + "</sequence>";
    }
}
