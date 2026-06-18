package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.LuaSandboxValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the client-demo custom Lua plugins (the Java/JS → Lua conversions in
 * {@code wso2-custom-policy-demo/kong-lua}) against the real DEDICATED sandbox, so the demo
 * artifacts are guaranteed deployable on Konnect Dedicated Cloud. Path is relative to the
 * migration-service module dir (surefire's working directory).
 */
class DemoCustomPluginValidationTest {

    private static final Path DEMO = Path.of("..", "wso2-custom-policy-demo", "kong-lua");
    private final LuaSandboxValidator validator = new LuaSandboxValidator();

    private void validatePlugin(String dir) throws Exception {
        Path handler = DEMO.resolve(dir).resolve("handler.lua");
        Path schema = DEMO.resolve(dir).resolve("schema.lua");
        assertThat(Files.exists(handler)).as(handler + " exists").isTrue();
        assertThat(Files.exists(schema)).as(schema + " exists").isTrue();

        LuaSandboxValidator.Result h = validator.validateHandler(Files.readString(handler), 51200);
        assertThat(h.violations()).as(dir + " handler.lua violations").isEmpty();
        LuaSandboxValidator.Result s = validator.validateSchema(Files.readString(schema), 51200);
        assertThat(s.violations()).as(dir + " schema.lua violations").isEmpty();
    }

    @Test
    void hmacSignerPlugin_isSandboxClean_andDeployable() throws Exception {
        validatePlugin("forgeshift-hmac-signer");
    }

    @Test
    void riskScoringPlugin_isSandboxClean_andDeployable() throws Exception {
        validatePlugin("forgeshift-risk-scoring");
    }
}
