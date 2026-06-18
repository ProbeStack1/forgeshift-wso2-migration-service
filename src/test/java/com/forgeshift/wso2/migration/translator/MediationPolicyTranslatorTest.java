package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.CustomPluginLuaGenerator;
import com.forgeshift.wso2.migration.ai.LuaSandboxValidator;
import com.forgeshift.wso2.migration.ai.MediationPolicyAiTranslator;
import com.forgeshift.wso2.migration.ai.TargetMode;
import com.forgeshift.wso2.migration.config.AnthropicProperties;
import com.forgeshift.wso2.migration.config.MigrationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the deterministic-first, AI-OFF behaviour of the mediation path: a header-manipulation Synapse
 * sequence migrates with no AI; a coded/scripted sequence (with AI disabled) degrades to a manual-review
 * warning rather than being mis-migrated.
 */
class MediationPolicyTranslatorTest {

    private MediationPolicyTranslator translator() {
        AnthropicProperties ai = new AnthropicProperties();   // enabled=false by default → AI OFF
        LuaSandboxValidator validator = new LuaSandboxValidator();
        return new MediationPolicyTranslator(
                new MediationPolicyAiTranslator(ai, validator),
                new CustomPluginLuaGenerator(ai, validator),
                new MigrationProperties());
    }

    private static String seq(String body) {
        return "<sequence xmlns=\"http://ws.apache.org/ns/synapse\" name=\"s\">" + body + "</sequence>";
    }

    @Test
    void headerSequence_migratesDeterministically_noAi() {
        TranslatedMediationPolicy t = translator().translate("api1", "AccountsAPI", "out-headers",
                seq("<property name=\"X-Bank-Audit\" value=\"account-open\" scope=\"transport\"/>"), "in");

        assertThat(t.isDeployable()).isTrue();
        assertThat(t.getPlugin().getName()).isEqualTo("request-transformer");
        assertThat(t.getWarnings()).anyMatch(w -> w.contains("no AI used"));
    }

    @Test
    void headerSequence_isDeterministicInCustomPluginModeToo() {
        TranslatedMediationPolicy t = translator().translate("api1", "AccountsAPI", "h",
                seq("<property name=\"X-A\" value=\"1\" scope=\"transport\"/>"), "in", TargetMode.CUSTOM_PLUGIN);
        // the deterministic transformer wins before any AI custom-plugin attempt
        assertThat(t.getPlugin().getName()).isEqualTo("request-transformer");
        assertThat(t.getCustomPlugin()).isNull();
    }

    @Test
    void conditionalSequence_inCustomPluginMode_usesTheConditionalLuaPlugin_noAi() {
        String body = "<filter source=\"$trp:X-Channel\" regex=\"mobile\">"
                + "<then><property name=\"X-Priority\" value=\"high\" scope=\"transport\"/></then>"
                + "<else><property name=\"X-Priority\" value=\"normal\" scope=\"transport\"/></else>"
                + "</filter>";
        TranslatedMediationPolicy t = translator().translate("api1", "AccountsAPI", "cond",
                seq(body), "in", TargetMode.CUSTOM_PLUGIN);

        assertThat(t.isDeployable()).isTrue();
        assertThat(t.getPlugin().getName()).isEqualTo("forgeshift-conditional-headers");
        assertThat(t.getCustomPlugin()).isNotNull();   // the asset gets uploaded by DeckBundleDeployer
        assertThat(t.getWarnings()).anyMatch(w -> w.contains("no AI used"));
    }

    @Test
    void scriptSequence_withAiOff_isManualReviewNotMigrated() {
        TranslatedMediationPolicy t = translator().translate("api1", "AccountsAPI", "js-seq",
                seq("<script language=\"js\">mc.setProperty('a','1');</script>"), "in");

        assertThat(t.isDeployable()).isFalse();
        assertThat(t.getPlugin()).isNull();
        assertThat(t.getCustomPlugin()).isNull();
        assertThat(t.getWarnings()).anyMatch(w -> w.contains("NOT auto-migrated"));
    }

    @Test
    void knownCustomJavaMediator_inCustomPluginMode_usesCatalogPlugin_noAi() {
        String seq = "<sequence name=\"hmac\" xmlns=\"http://ws.apache.org/ns/synapse\">"
                + "<class name=\"com.bank.gateway.mediators.HmacRequestSigner\">"
                + "<property name=\"secret\" value=\"k\"/></class></sequence>";
        TranslatedMediationPolicy t = translator().translate("api1", "PaymentsAPI", "hmac",
                seq, "in", TargetMode.CUSTOM_PLUGIN);

        assertThat(t.isDeployable()).isTrue();
        assertThat(t.getPlugin().getName()).isEqualTo("forgeshift-hmac-signer");
        assertThat(t.getCustomPlugin()).isNotNull();   // asset uploaded by DeckBundleDeployer
    }
}
