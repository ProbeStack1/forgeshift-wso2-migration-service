package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.LuaSandboxValidator;
import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionalHeaderPluginBuilderTest {

    private final LuaSandboxValidator validator = new LuaSandboxValidator();

    @Test
    void asset_isAReusablePluginThatPassesTheDedicatedSandbox() {
        CustomPluginArtifact a = ConditionalHeaderPluginBuilder.asset();
        assertThat(a.isComplete()).isTrue();
        assertThat(a.getPluginName()).isEqualTo("forgeshift-conditional-headers");

        LuaSandboxValidator.Result h = validator.validateHandler(a.getHandlerLua(), 51200);
        assertThat(h.violations()).isEmpty();
        assertThat(h.valid()).isTrue();

        LuaSandboxValidator.Result s = validator.validateSchema(a.getSchemaLua(), 51200);
        assertThat(s.violations()).isEmpty();
        assertThat(s.valid()).isTrue();
    }
}
