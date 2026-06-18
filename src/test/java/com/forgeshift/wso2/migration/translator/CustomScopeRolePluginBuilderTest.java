package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.LuaSandboxValidator;
import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CustomScopeRolePluginBuilderTest {

    private static final int MAX = 51200;
    private final CustomScopeRolePluginBuilder builder = new CustomScopeRolePluginBuilder();
    private final LuaSandboxValidator validator = new LuaSandboxValidator();

    @Test
    void asset_isAReusablePluginThatPassesTheDedicatedSandbox() {
        CustomPluginArtifact a = builder.asset();
        assertThat(a.isComplete()).isTrue();
        assertThat(a.getPluginName()).isEqualTo("forgeshift-oauth-scope");

        LuaSandboxValidator.Result h = validator.validateHandler(a.getHandlerLua(), MAX);
        assertThat(h.violations()).isEmpty();
        assertThat(h.valid()).isTrue();

        LuaSandboxValidator.Result s = validator.validateSchema(a.getSchemaLua(), MAX);
        assertThat(s.violations()).isEmpty();
        assertThat(s.valid()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildConfig_groupsMethodsBySharedScopeSet() {
        List<Map<String, Object>> ops = List.of(
                op("GET", List.of("accounts:read")),
                op("HEAD", List.of("accounts:read")),
                op("POST", List.of("accounts:write")),
                op("DELETE", List.of()));   // no scope → ignored

        Optional<Map<String, Object>> cfg = builder.buildConfig("/bank/accounts", ops);
        assertThat(cfg).isPresent();
        assertThat(cfg.get()).containsEntry("scope_claim", "scope").containsEntry("unmatched", "allow");

        List<Map<String, Object>> rules = (List<Map<String, Object>>) cfg.get().get("rules");
        assertThat(rules).hasSize(2);

        Map<String, Object> readRule = rules.stream()
                .filter(r -> ((List<?>) r.get("scopes")).contains("accounts:read")).findFirst().orElseThrow();
        assertThat((List<String>) readRule.get("methods")).containsExactlyInAnyOrder("GET", "HEAD");

        Map<String, Object> writeRule = rules.stream()
                .filter(r -> ((List<?>) r.get("scopes")).contains("accounts:write")).findFirst().orElseThrow();
        assertThat((List<String>) writeRule.get("methods")).containsExactly("POST");
    }

    @Test
    void buildConfig_emptyWhenNoScopesDeclared() {
        assertThat(builder.buildConfig("/x", List.of(op("GET", List.of()), op("POST", null)))).isEmpty();
        assertThat(builder.buildConfig("/x", null)).isEmpty();
    }

    private static Map<String, Object> op(String verb, List<String> scopes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verb", verb);
        if (scopes != null) m.put("scopes", scopes);
        return m;
    }
}
