package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.LuaSandboxValidator;
import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtClaimHeaderPluginBuilderTest {

    private final LuaSandboxValidator validator = new LuaSandboxValidator();

    @Test
    void asset_isAReusablePluginThatPassesTheDedicatedSandbox() {
        CustomPluginArtifact a = JwtClaimHeaderPluginBuilder.asset();
        assertThat(a.isComplete()).isTrue();
        assertThat(a.getPluginName()).isEqualTo("forgeshift-jwt-claim-headers");
        assertThat(validator.validateHandler(a.getHandlerLua(), 51200).violations()).isEmpty();
        assertThat(validator.validateSchema(a.getSchemaLua(), 51200).violations()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildConfig_mapsClaimsToHeaders_skippingBlanks() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("sub", "X-User");
        map.put("organization", "X-Org");
        map.put("", "X-Bad");   // skipped (blank claim)

        Optional<Map<String, Object>> cfg = JwtClaimHeaderPluginBuilder.buildConfig(map);
        assertThat(cfg).isPresent();
        List<Map<String, Object>> mappings = (List<Map<String, Object>>) cfg.get().get("mappings");
        assertThat(mappings).hasSize(2);
        assertThat(mappings.get(0)).containsEntry("claim", "sub").containsEntry("header", "X-User");
        assertThat(mappings.get(1)).containsEntry("claim", "organization").containsEntry("header", "X-Org");
    }

    @Test
    void buildConfig_emptyWhenNothingToMap() {
        assertThat(JwtClaimHeaderPluginBuilder.buildConfig(null)).isEmpty();
        assertThat(JwtClaimHeaderPluginBuilder.buildConfig(Map.of())).isEmpty();
    }
}
