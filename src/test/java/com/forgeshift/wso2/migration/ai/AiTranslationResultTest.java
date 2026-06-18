package com.forgeshift.wso2.migration.ai;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiTranslationResultTest {

    @Test
    void defaultModeIsServerlessInline() {
        assertThat(AiTranslationResult.builder().build().getMode()).isEqualTo(TargetMode.SERVERLESS_INLINE);
    }

    @Test
    void isUsableCustomPlugin_trueOnlyWhenBothFilesPresentAndValid() {
        AiTranslationResult ok = AiTranslationResult.builder()
                .translatable(true).valid(true).mode(TargetMode.CUSTOM_PLUGIN)
                .handlerLua("local H={PRIORITY=1,VERSION='1'} function H:access(c) end return H")
                .schemaLua("return { name='p', fields={} }")
                .build();
        assertThat(ok.isUsableCustomPlugin()).isTrue();
        assertThat(ok.isUsableLua()).isFalse();   // no inline lua

        AiTranslationResult missingSchema = AiTranslationResult.builder()
                .translatable(true).valid(true).handlerLua("x").build();
        assertThat(missingSchema.isUsableCustomPlugin()).isFalse();

        AiTranslationResult notValid = AiTranslationResult.builder()
                .translatable(true).valid(false).handlerLua("x").schemaLua("y").build();
        assertThat(notValid.isUsableCustomPlugin()).isFalse();
    }

    @Test
    void customPluginArtifact_isCompleteOnlyWithAllThreeParts() {
        assertThat(CustomPluginArtifact.builder()
                .pluginName("p").handlerLua("h").schemaLua("s").build().isComplete()).isTrue();
        assertThat(CustomPluginArtifact.builder()
                .pluginName("p").handlerLua("h").build().isComplete()).isFalse();
        assertThat(CustomPluginArtifact.builder().build().isComplete()).isFalse();
    }
}
