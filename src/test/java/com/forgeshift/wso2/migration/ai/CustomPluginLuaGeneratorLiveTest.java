package com.forgeshift.wso2.migration.ai;

import com.forgeshift.wso2.migration.config.AnthropicProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE Target-2 test — runs ONLY when {@code ANTHROPIC_API_KEY} is set (otherwise skipped, so the
 * normal build is unaffected). It calls Claude for real, generates a Kong custom plugin from a
 * representative WSO2 Synapse sequence with CONDITIONAL logic (no built-in Kong equivalent — a
 * request-transformer can't branch), runs the output through the DEDICATED sandbox + confidence
 * gate, and writes the two files to {@code target/generated-plugin/} for a follow-up live deploy.
 *
 * <p>Run: {@code ANTHROPIC_API_KEY=sk-... mvn -Dtest=CustomPluginLuaGeneratorLiveTest test}
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class CustomPluginLuaGeneratorLiveTest {

    // Branch on an inbound header and stamp a priority header + log — exactly the kind of mediation
    // that a static request-transformer cannot express, so it needs a real plugin.
    private static final String SYNAPSE = """
            <sequence name="conditional-priority" xmlns="http://ws.apache.org/ns/synapse">
              <property name="channel" expression="$trp:X-Channel" scope="default"/>
              <filter source="get-property('channel')" regex="mobile">
                <then>
                  <property name="X-Priority" value="high" scope="transport"/>
                  <log level="custom"><property name="msg" value="mobile request prioritised"/></log>
                </then>
                <else>
                  <property name="X-Priority" value="normal" scope="transport"/>
                </else>
              </filter>
            </sequence>
            """;

    @Test
    void generatesAndValidatesACustomPluginFromSynapse() throws Exception {
        AnthropicProperties props = new AnthropicProperties();
        props.setEnabled(true);   // AI is OFF by default now; this live test opts in explicitly
        props.setApiKey(System.getenv("ANTHROPIC_API_KEY"));
        CustomPluginLuaGenerator gen = new CustomPluginLuaGenerator(props, new LuaSandboxValidator());

        String pluginName = "forgeshift-med-conditional-priority";
        AiTranslationResult r = gen.generate(pluginName, "conditional-priority", SYNAPSE,
                "Runs on the request (access) flow. Set the X-Priority request header based on the "
                        + "inbound X-Channel header: 'high' when X-Channel == mobile, else 'normal'; log the mobile case.");

        System.out.println("=== translatable=" + r.isTranslatable() + " valid=" + r.isValid()
                + " usableCustomPlugin=" + r.isUsableCustomPlugin()
                + " confidence=" + r.getConfidence() + " mode=" + r.getMode());
        System.out.println("=== violations: " + r.getViolations());
        System.out.println("=== reason: " + r.getReason());
        System.out.println("=== notes: " + r.getNotes());
        System.out.println("=== handler.lua ===\n" + r.getHandlerLua());
        System.out.println("=== schema.lua ===\n" + r.getSchemaLua());

        if (r.isUsableCustomPlugin()) {
            Path dir = Path.of("target", "generated-plugin");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("handler.lua"), r.getHandlerLua());
            Files.writeString(dir.resolve("schema.lua"), r.getSchemaLua());
            System.out.println("=== wrote generated files to " + dir.toAbsolutePath());
        }

        assertThat(r.isTranslatable()).as("Claude should translate this conditional sequence").isTrue();
        assertThat(r.isUsableCustomPlugin())
                .as("the generated plugin must pass the dedicated sandbox + confidence gate; violations=" + r.getViolations())
                .isTrue();
    }
}
