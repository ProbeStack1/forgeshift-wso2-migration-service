package com.forgeshift.wso2.migration.translator;

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

    public static Result translate(Map<String, Object> apiPayload, String apiName, List<String> tags) {
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
        Map<String, Object> reqCfg = new LinkedHashMap<>();
        unsupported.addAll(applyTo(request, reqCfg, true));
        if (!reqCfg.isEmpty()) result.getPlugins().add(plugin("request-transformer", reqCfg, tags));

        Map<String, Object> respCfg = new LinkedHashMap<>();
        unsupported.addAll(applyTo(response, respCfg, false));
        if (!respCfg.isEmpty()) result.getPlugins().add(plugin("response-transformer", respCfg, tags));

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

    /** Maps each policy into the transformer config; returns the policy names it could not map. */
    @SuppressWarnings("unchecked")
    private static List<String> applyTo(List<Map<String, Object>> policies, Map<String, Object> cfg, boolean requestFlow) {
        List<String> unsupported = new ArrayList<>();
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
                    String q = param(params, "queryParamName", "paramName", "name");
                    String v = param(params, "queryParamValue", "value");
                    if (requestFlow && has(q)) addToList(cfg, "add", "querystring", q + ":" + nz(v)); else unsupported.add(name);
                }
                case "removequeryparam" -> {
                    String q = param(params, "queryParamName", "paramName", "name");
                    if (requestFlow && has(q)) addToList(cfg, "remove", "querystring", q); else unsupported.add(name);
                }
                case "rewriteresourcepath" -> {
                    String path = param(params, "resourcePath", "newResourcePath", "uriTemplate", "value");
                    if (requestFlow && has(path)) setScalar(cfg, "replace", "uri", path); else unsupported.add(name);
                }
                case "rewritehttpmethod" -> {
                    String method = param(params, "updatedHttpMethod", "updatedMethod", "httpMethod", "value");
                    if (requestFlow && has(method)) cfg.put("http_method", method.trim().toUpperCase()); else unsupported.add(name);
                }
                default -> unsupported.add(has(name) ? name : "unnamed-policy");
            }
        }
        return unsupported;
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
}
