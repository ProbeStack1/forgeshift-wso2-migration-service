package com.forgeshift.wso2.migration.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LuaSandboxValidatorTest {

    private static final int MAX = 51200;
    private final LuaSandboxValidator validator = new LuaSandboxValidator();

    // -------- dedicated handler.lua --------

    private static final String GOOD_HANDLER = String.join("\n",
            "local H = { PRIORITY = 1000, VERSION = \"1.0\" }",
            "function H:access(conf)",
            "  local s = kong.request.get_header(\"x-scope\")",
            "  if not s then return kong.response.exit(403) end",
            "end",
            "return H");

    @Test
    void validateHandler_acceptsWellFormedPlugin() {
        LuaSandboxValidator.Result r = validator.validateHandler(GOOD_HANDLER, MAX);
        assertThat(r.valid()).isTrue();
        assertThat(r.violations()).isEmpty();
    }

    @Test
    void validateHandler_rejectsMissingPriorityAndPhase() {
        LuaSandboxValidator.Result r = validator.validateHandler("local H = {}\nreturn H", MAX);
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).anyMatch(v -> v.contains("PRIORITY"))
                .anyMatch(v -> v.contains("phase handler"));
    }

    @Test
    void validateHandler_rejectsForbiddenHostEscape() {
        String lua = "local H = { PRIORITY = 1, VERSION = \"1\" }\n"
                + "function H:access(conf) os.execute(\"id\") end\nreturn H";
        LuaSandboxValidator.Result r = validator.validateHandler(lua, MAX);
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).anyMatch(v -> v.contains("os.execute"));
    }

    @Test
    void validateHandler_rejectsBackgroundTimer_dedicatedCloudForbidsTimers() {
        String lua = "local H = { PRIORITY = 1, VERSION = \"1\" }\n"
                + "function H:access(conf) ngx.timer.at(0, function() end) end\nreturn H";
        LuaSandboxValidator.Result r = validator.validateHandler(lua, MAX);
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).anyMatch(v -> v.contains("ngx.timer.at"));
    }

    @Test
    void dedicatedAllowsSharedDict_butServerlessForbidsIt() {
        String lua = "local H = { PRIORITY = 1, VERSION = \"1\" }\n"
                + "function H:access(conf) local d = ngx.shared.cache end\nreturn H";
        // Dedicated custom plugin: shared dicts are part of the real runtime → allowed.
        assertThat(validator.validateHandler(lua, MAX).valid()).isTrue();
        // Serverless pre-function: shared dicts blocked → the same code is rejected.
        LuaSandboxValidator.Result serverless = validator.validate(lua, MAX);
        assertThat(serverless.valid()).isFalse();
        assertThat(serverless.violations()).anyMatch(v -> v.contains("ngx.shared."));
    }

    // -------- dedicated schema.lua --------

    private static final String GOOD_SCHEMA = String.join("\n",
            "local typedefs = require \"kong.db.schema.typedefs\"",
            "return {",
            "  name = \"forgeshift-probe\",",
            "  fields = { { config = { type = \"record\", fields = {} } } }",
            "}");

    @Test
    void validateSchema_acceptsWellFormedSchema() {
        LuaSandboxValidator.Result r = validator.validateSchema(GOOD_SCHEMA, MAX);
        assertThat(r.valid()).isTrue();
        assertThat(r.violations()).isEmpty();
    }

    @Test
    void validateSchema_rejectsDisallowedRequire() {
        String lua = "local s = require \"socket\"\nreturn { name = \"p\", fields = {} }";
        LuaSandboxValidator.Result r = validator.validateSchema(lua, MAX);
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).anyMatch(v -> v.contains("socket"));
    }

    @Test
    void validateSchema_rejectsMissingFields() {
        LuaSandboxValidator.Result r = validator.validateSchema("return { name = \"p\" }", MAX);
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).anyMatch(v -> v.contains("fields"));
    }

    // -------- serverless regression (unchanged behaviour) --------

    @Test
    void validate_serverlessStillRejectsTimersAndAcceptsCleanSnippet() {
        assertThat(validator.validate("ngx.timer.at(0, f)", MAX).valid()).isFalse();
        assertThat(validator.validate("kong.response.set_header(\"X\", \"y\")", MAX).valid()).isTrue();
    }
}
