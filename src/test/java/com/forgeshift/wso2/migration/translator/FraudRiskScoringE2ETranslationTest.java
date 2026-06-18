package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.LuaSandboxValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E proof: feed the migrator's deterministic custom-mediator catalog the EXACT Synapse body of the
 * live WSO2 op-policy {@code fraudRiskScoring} (the .j2 deployed on PolicyAPI) and assert it converts to
 * the pre-built {@code forgeshift-risk-scoring} Lua plugin — no AI — with the thresholds parsed straight
 * from the JavaScript. This is the conversion the deployed pipeline cannot yet reach because
 * {@link OperationPolicyTranslator} (the AM 4.x op-policy path) is not wired to this catalog.
 */
class FraudRiskScoringE2ETranslationTest {

    /** The real synapse fragment of the fraudRiskScoring op-policy, wrapped as a sequence for the parser. */
    private static final String FRAUD_RISK_J2 =
            "<sequence xmlns=\"http://ws.apache.org/ns/synapse\" name=\"fraudRiskScoring\">"
          + "  <property name=\"fs_amount\" expression=\"$trp:X-Txn-Amount\" scope=\"default\"/>"
          + "  <property name=\"fs_channel\" expression=\"$trp:X-Channel\" scope=\"default\"/>"
          + "  <script language=\"js\"><![CDATA["
          + "    var amount = parseFloat(mc.getProperty('fs_amount')) || 0;"
          + "    var channel = mc.getProperty('fs_channel') || 'unknown';"
          + "    var risk = 'low';"
          + "    if (amount >= 10000 || channel == 'web') { risk = 'high'; }"
          + "    else if (amount >= 2000) { risk = 'medium'; }"
          + "    mc.setProperty('fs_risk', risk);"
          + "    if (risk == 'high' && amount >= 50000) { mc.setProperty('fs_block', 'yes'); }"
          + "  ]]></script>"
          + "  <property name=\"X-Risk-Level\" expression=\"$ctx:fs_risk\" scope=\"transport\"/>"
          + "  <filter source=\"$ctx:fs_block\" regex=\"yes\">"
          + "    <then>"
          + "      <payloadFactory media-type=\"json\">"
          + "        <format>{\"error\":\"Transaction blocked: risk threshold exceeded\"}</format><args/>"
          + "      </payloadFactory>"
          + "      <property name=\"HTTP_SC\" value=\"403\" scope=\"axis2\"/>"
          + "      <property name=\"RESPONSE\" value=\"true\" scope=\"default\"/>"
          + "      <respond/>"
          + "    </then>"
          + "  </filter>"
          + "</sequence>";

    @Test
    void fraudRiskScoringOpPolicy_convertsTo_forgeshiftRiskScoringLuaPlugin_noAi() {
        TranslatedMediationPolicy t = KnownCustomMediatorTranslator.translate(
                "44dd940b-e481-4717-b0cd-a79e091b274e", "PolicyAPI", "fraudRiskScoring",
                FRAUD_RISK_J2, "request", List.of("wso2-source-id:44dd940b-e481-4717-b0cd-a79e091b274e"));

        assertThat(t).as("the catalog must recognise the risk script").isNotNull();
        assertThat(t.getPlugin().getName()).isEqualTo("forgeshift-risk-scoring");
        assertThat(t.getPlugin().getConfig())
                .containsEntry("medium_amount", 2000)
                .containsEntry("high_amount", 10000)
                .containsEntry("block_amount", 50000);
        assertThat(t.getCustomPlugin()).isNotNull();

        LuaSandboxValidator v = new LuaSandboxValidator();
        assertThat(v.validateHandler(t.getCustomPlugin().getHandlerLua(), 51200).violations()).isEmpty();
        assertThat(v.validateSchema(t.getCustomPlugin().getSchemaLua(), 51200).violations()).isEmpty();

        System.out.println("\n================ MIGRATOR OUTPUT: fraudRiskScoring -> Kong ================");
        System.out.println("Kong plugin instance : " + t.getPlugin().getName());
        System.out.println("Plugin config        : " + t.getPlugin().getConfig());
        System.out.println("Custom plugin asset  : " + t.getCustomPlugin().getPluginName());
        System.out.println("\n---------- handler.lua ----------\n" + t.getCustomPlugin().getHandlerLua());
        System.out.println("\n---------- schema.lua ----------\n" + t.getCustomPlugin().getSchemaLua());
        System.out.println("==========================================================================\n");
    }
}
