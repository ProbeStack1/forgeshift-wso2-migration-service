package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.TargetMode;
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
    void customHeaderPolicy_mapsToTransformer_preservingValue() {
        // A custom (non-built-in) policy that carries headerName/headerValue is a Synapse header-set policy
        // (e.g. the bank's addBankCorrelationId / setBankAuditHeader). It maps to a header transform so the
        // policy's VALUE is preserved instead of dropped; "set" semantics => both add and replace.
        Map<String, Object> api = Map.of("apiPolicies", Map.of(
                "request", List.of(policy("addBankCorrelationId", "headerName", "X-Correlation-Id", "headerValue", "generated")),
                "response", List.of(policy("setBankAuditHeader", "headerName", "X-Audit", "headerValue", "served"))));

        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(api, "API", List.of("t"));

        KongPlugin rt = named(r, "request-transformer");
        assertThat(section(rt, "add", "headers")).containsExactly("X-Correlation-Id:generated");
        assertThat(section(rt, "replace", "headers")).containsExactly("X-Correlation-Id:generated");
        KongPlugin resp = named(r, "response-transformer");
        assertThat(section(resp, "add", "headers")).containsExactly("X-Audit:served");
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("addBankCorrelationId") && w.contains("header transform"));
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

    /** The real fraudRiskScoring op-policy .j2 (fragment): script computes fs_risk + a sibling property
     *  stamps X-Risk-Level. Fed to the translator via opPolicyDefs keyed by policyId. */
    private static final String FRAUD_RISK_J2 =
            "<property name=\"fs_amount\" expression=\"$trp:X-Txn-Amount\" scope=\"default\"/>"
          + "<script language=\"js\"><![CDATA["
          + "  var amount = parseFloat(mc.getProperty('fs_amount')) || 0;"
          + "  var risk='low';"
          + "  if (amount >= 10000) { risk='high'; } else if (amount >= 2000) { risk='medium'; }"
          + "  mc.setProperty('fs_risk', risk);"
          + "  if (risk=='high' && amount >= 50000) { mc.setProperty('fs_block','yes'); }"
          + "]]></script>"
          + "<property name=\"X-Risk-Level\" expression=\"$ctx:fs_risk\" scope=\"transport\"/>";

    private static Map<String, Object> customPolicy(String name, String policyId) {
        return Map.of("policyName", name, "policyId", policyId, "parameters", new LinkedHashMap<>());
    }

    @Test
    void customScriptOpPolicy_inCustomPluginMode_mapsToCatalogLuaPlugin_noManualReview() {
        Map<String, Object> api = Map.of("id", "44dd940b", "apiPolicies", Map.of("request", List.of(
                policy("addHeader", "headerName", "X-Env", "headerValue", "seed"),
                customPolicy("fraudRiskScoring", "fr-1"))));
        Map<String, String> defs = Map.of("fr-1", FRAUD_RISK_J2);

        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(
                api, "PolicyAPI", List.of("t"), TargetMode.CUSTOM_PLUGIN, defs);

        // built-in addHeader still becomes a request-transformer
        assertThat(named(r, "request-transformer")).isNotNull();
        // the JS risk policy becomes the catalog Lua plugin with thresholds parsed from the script
        KongPlugin risk = named(r, "forgeshift-risk-scoring");
        assertThat(risk).isNotNull();
        assertThat(risk.getConfig())
                .containsEntry("medium_amount", 2000)
                .containsEntry("high_amount", 10000)
                .containsEntry("block_amount", 50000);
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("custom plugin") && w.contains("no AI"));
        assertThat(r.getWarnings()).noneMatch(w -> w.contains("manual review") && w.contains("fraudRiskScoring"));
    }

    @Test
    void customScriptOpPolicy_inServerlessMode_staysManualReview() {
        Map<String, Object> api = Map.of("id", "44dd940b", "apiPolicies", Map.of("request", List.of(
                customPolicy("fraudRiskScoring", "fr-1"))));

        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(api, "PolicyAPI", List.of("t"));

        assertThat(named(r, "forgeshift-risk-scoring")).isNull();
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("manual review") && w.contains("fraudRiskScoring"));
    }

    @Test
    void customScriptOpPolicy_customPluginMode_butNoDefinition_staysManualReview() {
        // CUSTOM_PLUGIN mode but the .j2 couldn't be fetched (empty defs) → no guessing, manual review.
        Map<String, Object> api = Map.of("id", "x", "apiPolicies", Map.of("request", List.of(
                customPolicy("fraudRiskScoring", "fr-1"))));

        OperationPolicyTranslator.Result r = OperationPolicyTranslator.translate(
                api, "PolicyAPI", List.of("t"), TargetMode.CUSTOM_PLUGIN, Map.of());

        assertThat(named(r, "forgeshift-risk-scoring")).isNull();
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("manual review") && w.contains("fraudRiskScoring"));
    }
}
