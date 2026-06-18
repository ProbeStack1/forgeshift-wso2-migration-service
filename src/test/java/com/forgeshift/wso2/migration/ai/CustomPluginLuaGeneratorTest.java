package com.forgeshift.wso2.migration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeshift.wso2.migration.config.AnthropicProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CustomPluginLuaGeneratorTest {

    private final CustomPluginLuaGenerator gen =
            new CustomPluginLuaGenerator(new AnthropicProperties(), new LuaSandboxValidator());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parse_mapsTheTwoFileContractToCustomPluginResult() throws Exception {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("translatable", true);
        model.put("plugin_name", "forgeshift-med-x");
        model.put("handler_lua", "local H={PRIORITY=1,VERSION='1'} function H:access(c) end return H");
        model.put("schema_lua", "local t=require 'kong.db.schema.typedefs' return { name='forgeshift-med-x', fields={} }");
        model.put("confidence", 0.9);
        String anthropic = envelope("```json\n" + mapper.writeValueAsString(model) + "\n```");

        AiTranslationResult r = gen.parse(anthropic);

        assertThat(r.isTranslatable()).isTrue();
        assertThat(r.getMode()).isEqualTo(TargetMode.CUSTOM_PLUGIN);
        assertThat(r.getHandlerLua()).contains("PRIORITY").contains("H:access");
        assertThat(r.getSchemaLua()).contains("forgeshift-med-x");
        assertThat(r.getConfidence()).isEqualTo(0.9);
    }

    @Test
    void parse_handlesNoJsonBlockGracefully() throws Exception {
        AiTranslationResult r = gen.parse(envelope("I could not translate this sequence."));
        assertThat(r.isTranslatable()).isFalse();
        assertThat(r.getMode()).isEqualTo(TargetMode.CUSTOM_PLUGIN);
    }

    @Test
    void generate_disabledWhenNoApiKey() {
        AiTranslationResult r = gen.generate("forgeshift-med-x", "Seq", "<sequence/>", null);
        assertThat(r.isTranslatable()).isFalse();
        assertThat(r.getMode()).isEqualTo(TargetMode.CUSTOM_PLUGIN);
        assertThat(r.getReason()).contains("disabled");
    }

    /** Wrap model text in the Anthropic {@code /v1/messages} response envelope. */
    private String envelope(String modelText) throws Exception {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", modelText);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("content", List.of(block));
        return mapper.writeValueAsString(env);
    }
}
