package com.forgeshift.wso2.migration.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.deck.DeckYamlBuilder;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policy-migration "test it and check it's going or not" harness. Feeds the REAL seeded banking
 * policy values (Archive/seed_wso2.ps1) through the actual translators and reports, per API, exactly
 * which WSO2 policy became which Kong plugin with which values — and where a value is LOST.
 *
 * <p>Covers every policy kind the bank dataset exercises: security schemes (oauth2 mandatory, oauth2
 * +api_key OPTIONAL/OR, api_key-only, oauth2+mutual-TLS), API-level + per-operation built-in operation
 * policies (addHeader, removeHeader, addQueryParam, rewriteResourcePath, changeHTTPMethod), CUSTOM
 * reusable operation policies (addBankCorrelationId, setBankAuditHeader), subscription/advanced
 * throttling tiers, CORS, response caching.
 *
 * <p>Built-in policies are asserted to migrate with correct values. The custom-policy section
 * documents the CURRENT gap (custom policies -> warning, their header values dropped). Run with:
 * {@code mvn -q -Dtest=PolicyMigrationReportTest test}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolicyMigrationReportTest {

    private final MigrationProperties props = new MigrationProperties();
    private final ApiTranslator translator = new ApiTranslator(props, new ThrottlingTierResolver(null, props));
    private final DeckYamlBuilder deck = new DeckYamlBuilder(props);
    private final ObjectMapper json = new ObjectMapper();

    // ---- tiny builders so the payloads read like the seeder hashtables ----
    private static Map<String, Object> m(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }
    private static List<Object> l(Object... items) { return new ArrayList<>(Arrays.asList(items)); }
    private static Map<String, Object> pol(String name, Object... params) {
        return m("policyName", name, "parameters", m(params));
    }
    private static Map<String, Object> flows(List<Object> request, List<Object> response) {
        return m("request", request, "response", response, "fault", l());
    }

    private TranslatedApi translate(String name, String version, Map<String, Object> payload) {
        return translator.translate(DiscoverySnapshot.builder()
                .sourceId("src-" + name).sourceName(name).sourceVersion(version)
                .companyName("acmebank").wso2Tenant("carbon.super").payload(payload).build());
    }

    private KongPlugin plugin(TranslatedApi api, String name) {
        return api.getServicePlugins().stream().filter(p -> name.equals(p.getName())).findFirst().orElse(null);
    }
    @SuppressWarnings("unchecked")
    private List<String> section(KongPlugin pl, String section, String field) {
        if (pl == null) return null;
        Map<String, Object> sec = (Map<String, Object>) pl.getConfig().get(section);
        return sec == null ? null : (List<String>) sec.get(field);
    }
    /** True if any service plugin's config text contains the needle (used to detect dropped values). */
    private boolean anyPluginConfigContains(TranslatedApi api, String needle) {
        try {
            for (KongPlugin p : api.getServicePlugins()) {
                if (json.writeValueAsString(p.getConfig() == null ? Map.of() : p.getConfig()).contains(needle)) return true;
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return false;
    }

    private void report(TranslatedApi api) {
        System.out.println("\n================ " + api.getWso2SourceName() + " " + api.getWso2SourceVersion() + " ================");
        System.out.println("  service: " + (api.getService() == null ? "<none>" : api.getService().getName()));
        System.out.println("  SERVICE PLUGINS (WSO2 policy -> Kong plugin):");
        for (KongPlugin p : api.getServicePlugins()) {
            try { System.out.println("    - " + p.getName() + "  config=" + json.writeValueAsString(p.getConfig())); }
            catch (Exception e) { System.out.println("    - " + p.getName()); }
        }
        if (api.getRoutePlugins() != null) {
            api.getRoutePlugins().forEach((route, plugins) -> {
                for (KongPlugin p : plugins) {
                    try { System.out.println("    - [route " + route + "] " + p.getName() + "  config=" + json.writeValueAsString(p.getConfig())); }
                    catch (Exception e) { /* ignore */ }
                }
            });
        }
        if (!api.getWarnings().isEmpty()) {
            System.out.println("  WARNINGS (NOT auto-migrated):");
            api.getWarnings().forEach(w -> System.out.println("    ! " + w));
        }
    }

    // ============================================================================================
    @Test
    void accountsApi_allPolicyKinds_migrate_includingCustomHeaders() {
        // AccountsAPI: oauth2 MANDATORY, response caching, CORS, throttling, API-level addHeader (built-in),
        // GET /accounts: custom addBankCorrelationId (request) + removeHeader (response),
        // POST /accounts: custom setBankAuditHeader (request).
        Map<String, Object> payload = m(
                "name", "AccountsAPI", "version", "1.0.0", "context", "/bank/accounts",
                "transport", l("https"), "tags", l("banking", "accounts", "core"),
                "securityScheme", l("oauth2", "oauth_basic_auth_api_key_mandatory"),
                "policies", l("Gold", "Unlimited"),
                "responseCachingEnabled", true, "cacheTimeout", 300,
                "corsConfiguration", m("corsConfigurationEnabled", true),
                "endpointConfig", m("production_endpoints", m("url", "https://postman-echo.com")),
                "apiPolicies", flows(l(pol("addHeader", "headerName", "X-Bank-Channel", "headerValue", "API")), l()),
                "operations", l(
                        m("verb", "GET", "target", "/accounts", "throttlingPolicy", "Unlimited",
                                "operationPolicies", flows(
                                        l(pol("addBankCorrelationId", "headerName", "X-Bank-Correlation-Id", "headerValue", "generated")),
                                        l(pol("removeHeader", "headerName", "X-Powered-By")))),
                        m("verb", "POST", "target", "/accounts", "throttlingPolicy", "Unlimited",
                                "operationPolicies", flows(
                                        l(pol("setBankAuditHeader", "headerName", "X-Bank-Audit", "headerValue", "account-open")),
                                        l()))));

        TranslatedApi api = translate("AccountsAPI", "1.0.0", payload);
        report(api);

        // --- security: oauth2 MANDATORY -> jwt only, NO anonymous (not optional) ---
        assertThat(plugin(api, "jwt")).as("oauth2 -> jwt").isNotNull();
        assertThat(plugin(api, "jwt").getConfig().get("anonymous")).as("mandatory => no OR-fallback").isNull();
        assertThat(plugin(api, "key-auth")).as("api_key flag is NOT the scheme").isNull();
        // --- caching, cors, throttling ---
        assertThat(plugin(api, "proxy-cache")).as("responseCaching -> proxy-cache").isNotNull();
        assertThat(plugin(api, "proxy-cache").getConfig().get("cache_ttl")).isEqualTo(300);
        assertThat(plugin(api, "cors")).as("CORS -> cors").isNotNull();
        assertThat(plugin(api, "rate-limiting")).as("throttling -> rate-limiting").isNotNull();
        // --- BUILT-IN operation policies carry their values ---
        assertThat(section(plugin(api, "request-transformer"), "add", "headers"))
                .as("api-level addHeader value").contains("X-Bank-Channel:API");
        assertThat(section(plugin(api, "response-transformer"), "remove", "headers"))
                .as("removeHeader value").contains("X-Powered-By");

        // --- CUSTOM operation policies now migrate WITH their values (Synapse header-set shape) ---
        assertThat(section(plugin(api, "request-transformer"), "add", "headers"))
                .as("custom addBankCorrelationId + setBankAuditHeader values")
                .contains("X-Bank-Correlation-Id:generated", "X-Bank-Audit:account-open");
        assertThat(section(plugin(api, "request-transformer"), "replace", "headers"))
                .as("WSO2 'set' = overwrite-or-add")
                .contains("X-Bank-Correlation-Id:generated", "X-Bank-Audit:account-open");
        assertThat(api.getWarnings().toString())
                .as("custom mapping surfaced for verification").contains("mapped to a header transform")
                .contains("addBankCorrelationId").contains("setBankAuditHeader");
    }

    @Test
    void paymentsApi_builtinsAndCustomAudit_migrate() {
        Map<String, Object> payload = m(
                "name", "PaymentsAPI", "version", "1.0.0", "context", "/bank/payments",
                "transport", l("https"), "securityScheme", l("oauth2"),
                "policies", l("Silver", "Unlimited"),
                "endpointConfig", m("production_endpoints", m("url", "https://postman-echo.com")),
                "operations", l(
                        m("verb", "POST", "target", "/payments", "throttlingPolicy", "Unlimited",
                                "operationPolicies", flows(l(
                                        pol("setBankAuditHeader", "headerName", "X-Bank-Audit", "headerValue", "payment-initiate"),
                                        pol("addQueryParam", "paramKey", "channel", "paramValue", "api")), l())),
                        m("verb", "PUT", "target", "/standing-orders", "throttlingPolicy", "Unlimited",
                                "operationPolicies", flows(l(
                                        pol("changeHTTPMethod", "httpMethod", "POST")), l()))));

        TranslatedApi api = translate("PaymentsAPI", "1.0.0", payload);
        report(api);

        assertThat(plugin(api, "jwt")).isNotNull();
        KongPlugin rt = plugin(api, "request-transformer");
        assertThat(section(rt, "add", "querystring")).as("addQueryParam value").contains("channel:api");
        assertThat(rt.getConfig().get("http_method")).as("changeHTTPMethod value").isEqualTo("POST");
        // custom audit policy now migrates with its value
        assertThat(section(rt, "add", "headers")).as("custom setBankAuditHeader value").contains("X-Bank-Audit:payment-initiate");
        assertThat(api.getWarnings().toString()).contains("mapped to a header transform").contains("setBankAuditHeader");
    }

    @Test
    void cardsApi_optionalOrAuth_and_rewriteResourcePath_migrate() {
        Map<String, Object> payload = m(
                "name", "CardsAPI", "version", "1.0.0", "context", "/bank/cards",
                "transport", l("https", "http"),
                "securityScheme", l("oauth2", "api_key", "oauth_basic_auth_api_key_optional"),
                "policies", l("Bronze", "Unlimited"),
                "endpointConfig", m("production_endpoints", m("url", "https://postman-echo.com")),
                "operations", l(
                        m("verb", "GET", "target", "/cards", "throttlingPolicy", "Unlimited"),
                        m("verb", "POST", "target", "/cards/{cardId}/block", "throttlingPolicy", "Unlimited",
                                "operationPolicies", flows(l(
                                        pol("rewriteResourcePath", "newResourcePath", "/cards/{cardId}/status")), l()))));

        TranslatedApi api = translate("CardsAPI", "1.0.0", payload);
        report(api);

        // OPTIONAL oauth2+api_key -> BOTH jwt and key-auth, each with the anonymous OR-fallback
        assertThat(plugin(api, "jwt")).isNotNull();
        assertThat(plugin(api, "jwt").getConfig().get("anonymous")).as("OR-auth fallback").isNotNull();
        assertThat(plugin(api, "key-auth")).isNotNull();
        assertThat(plugin(api, "key-auth").getConfig().get("anonymous")).as("OR-auth fallback").isNotNull();
        // rewriteResourcePath -> replace.uri
        @SuppressWarnings("unchecked")
        Map<String, Object> replace = (Map<String, Object>) plugin(api, "request-transformer").getConfig().get("replace");
        assertThat(replace.get("uri")).as("rewriteResourcePath value").isEqualTo("/cards/{cardId}/status");
    }

    @Test
    void forexApi_apiKeyOnly_caching_addQueryParam_migrate() {
        Map<String, Object> payload = m(
                "name", "ForexRatesAPI", "version", "1.0.0", "context", "/bank/forex",
                "transport", l("https"), "securityScheme", l("api_key"),
                "policies", l("Unlimited"),
                "responseCachingEnabled", true, "cacheTimeout", 600,
                "endpointConfig", m("production_endpoints", m("url", "https://postman-echo.com")),
                "operations", l(
                        m("verb", "GET", "target", "/rates", "throttlingPolicy", "Unlimited",
                                "operationPolicies", flows(l(
                                        pol("addQueryParam", "paramKey", "provider", "paramValue", "ecb")), l()))));

        TranslatedApi api = translate("ForexRatesAPI", "1.0.0", payload);
        report(api);

        assertThat(plugin(api, "key-auth")).as("api_key-only -> key-auth").isNotNull();
        assertThat(plugin(api, "jwt")).as("no oauth2 => no jwt").isNull();
        assertThat(plugin(api, "proxy-cache").getConfig().get("cache_ttl")).isEqualTo(600);
        assertThat(section(plugin(api, "request-transformer"), "add", "querystring")).contains("provider:ecb");
    }

    @Test
    void openBankingApi_customCorrelationId_migrates_and_yamlRenders() {
        Map<String, Object> payload = m(
                "name", "OpenBankingAPI", "version", "3.1.0", "context", "/open-banking/v3.1",
                "transport", l("https"), "securityScheme", l("oauth2", "mutualssl", "mutualssl_mandatory"),
                "policies", l("Gold", "Unlimited"),
                "endpointConfig", m("production_endpoints", m("url", "https://postman-echo.com")),
                "operations", l(
                        m("verb", "GET", "target", "/aisp/accounts", "throttlingPolicy", "Unlimited",
                                "operationPolicies", flows(l(
                                        pol("addBankCorrelationId", "headerName", "x-fapi-interaction-id", "headerValue", "generated")), l())),
                        m("verb", "POST", "target", "/pisp/domestic-payments", "throttlingPolicy", "Unlimited")));

        TranslatedApi api = translate("OpenBankingAPI", "3.1.0", payload);
        report(api);

        assertThat(plugin(api, "jwt")).isNotNull();
        // custom correlation-id policy now migrates with its value
        assertThat(section(plugin(api, "request-transformer"), "add", "headers")).contains("x-fapi-interaction-id:generated");
        assertThat(api.getWarnings().toString()).contains("mapped to a header transform").contains("addBankCorrelationId");

        // Render the actual decK YAML so the full migrated config is visible.
        String yaml = deck.build(List.of(api), List.of(), List.of(), List.of(), List.of());
        System.out.println("\n---------------- decK YAML (OpenBankingAPI) ----------------\n" + yaml);
        assertThat(yaml).contains("name: openbankingapi");
    }
}
