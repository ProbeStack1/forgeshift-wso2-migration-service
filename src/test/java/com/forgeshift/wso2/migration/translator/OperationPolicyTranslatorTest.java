package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationPolicyTranslatorTest {

    private static Map<String, Object> policy(String name, String... params) {
        Map<String, Object> ps = new LinkedHashMap<>();
        for (int i = 0; i + 1 < params.length; i += 2) ps.put(params[i], params[i + 1]);
        return Map.of("policyName", name, "parameters", ps);
    }

    private static KongPlugin named(OperationPolicyTranslator.Result r, String name) {
        return r.getPlugins().stream().filter(pl -> pl.getName().equals(name)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> section(KongPlugin pl, String section, String field) {
        Map<String, Object> sec = (Map<String, Object>) pl.getConfig().get(section);
        return sec == null ? null : (List<String>) sec.get(field);
    }

    @Test
    void requestFlow_mapsHeaderQueryPathMethodBuiltins() {
        // Uses the REAL WSO2 built-in policy names + param keys (addQueryParam→paramKey/paramValue,
        // rewriteResourcePath→newResourcePath, changeHTTPMethod→httpMethod).
        Map<String, Object> api = Map.of("apiPolicies", Map.of("request", List.of(
                policy("addHeader", "headerName", "X-Tenant", "headerValue", "acme"),
                policy("removeHeader", "headerName", "X-Internal"),
                policy("addQueryParam", "paramKey", "debug", "paramValue", "true"),
                policy("rewriteResourcePath", "newResourcePath", "/v2/items"),
                policy("changeHTTPMethod", "httpMethod", "post"))));

        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(api, "API", List.of("t"));
        KongPlugin rt = named(r, "request-transformer");
        assertThat(rt).isNotNull();
        assertThat(section(rt, "add", "headers")).containsExactly("X-Tenant:acme");
        assertThat(section(rt, "remove", "headers")).containsExactly("X-Internal");
        assertThat(section(rt, "add", "querystring")).containsExactly("debug:true");
        assertThat(((Map<?, ?>) rt.getConfig().get("replace")).get("uri")).isEqualTo("/v2/items");
        assertThat(rt.getConfig().get("http_method")).isEqualTo("POST");
        assertThat(rt.getTags()).contains("t");
    }

    @Test
    void responseFlow_mapsToResponseTransformer() {
        Map<String, Object> api = Map.of("apiPolicies", Map.of("response", List.of(
                policy("removeHeader", "headerName", "Server"),
                policy("addHeader", "headerName", "X-Cache", "headerValue", "HIT"))));

        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(api, "API", List.of("t"));
        assertThat(named(r, "request-transformer")).isNull();
        KongPlugin resp = named(r, "response-transformer");
        assertThat(section(resp, "remove", "headers")).containsExactly("Server");
        assertThat(section(resp, "add", "headers")).containsExactly("X-Cache:HIT");
    }

    @Test
    void unsupportedPolicy_becomesWarning_notPlugin() {
        Map<String, Object> api = Map.of("apiPolicies", Map.of("request", List.of(
                policy("callInterceptorService", "serviceUrl", "https://x"))));

        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(api, "API", List.of("t"));
        assertThat(r.getPlugins()).isEmpty();
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("callInterceptorService") && w.contains("manual review"));
    }

    @Test
    void perOperationPolicies_appliedApiWide_withWarning() {
        Map<String, Object> api = Map.of("operations", List.of(Map.of(
                "verb", "GET", "target", "/x",
                "operationPolicies", Map.of("request", List.of(
                        policy("addHeader", "headerName", "X-Op", "headerValue", "1"))))));

        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(api, "API", List.of("t"));
        assertThat(named(r, "request-transformer")).isNotNull();
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("API-wide"));
    }

    @Test
    void faultFlowPolicies_warnButDoNotBlock() {
        Map<String, Object> api = Map.of("apiPolicies", Map.of("fault", List.of(
                policy("addHeader", "headerName", "X-Err", "headerValue", "1"))));

        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(api, "API", List.of("t"));
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("fault-flow"));
    }

    @Test
    void noPolicies_noPluginsNoWarnings() {
        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(Map.of("name", "x"), "API", List.of("t"));
        assertThat(r.getPlugins()).isEmpty();
        assertThat(r.getWarnings()).isEmpty();
    }
}
