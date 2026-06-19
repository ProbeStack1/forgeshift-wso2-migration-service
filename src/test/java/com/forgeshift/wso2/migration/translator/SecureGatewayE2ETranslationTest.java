package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.LuaSandboxValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E proof for the COMPLEX custom policy: feed the migrator the real {@code secureGatewayPolicy} Synapse
 * body (the ~100-line bank gateway policy: JWT-scope validation + amount limit + header enrichment + HMAC
 * signing) and assert it is recognised and converted to the pre-built {@code forgeshift-secure-gateway}
 * Lua plugin — no AI — with the config parsed straight out of the policy script.
 */
class SecureGatewayE2ETranslationTest {

    /** The real secureGatewayPolicy.j2 (trimmed to the parts the catalog keys on), wrapped as a sequence. */
    private static final String SECURE_GATEWAY_J2 =
            "<sequence xmlns=\"http://ws.apache.org/ns/synapse\" name=\"secureGatewayPolicy\">"
          + "  <property name=\"sg_authz\" expression=\"$trp:Authorization\" scope=\"default\"/>"
          + "  <script language=\"js\"><![CDATA["
          + "    var requiredScope = 'default';"
          + "    var amountLimit = 100000;"
          + "    var hmacSecret = 's3cr3t-bank-key';"
          + "    function hasScope(c, r) { return true; }"
          + "    var claims = null;"
          + "    if (claims && !hasScope(claims, requiredScope)) { mc.setProperty('sg_block', 'scope'); }"
          + "    var amount = parseFloat(mc.getProperty('sg_amount')) || 0;"
          + "    if (amount > amountLimit) { mc.setProperty('sg_block', 'limit'); }"
          + "    mc.setProperty('sg_signature', 'hmac...');"
          + "  ]]></script>"
          + "  <property name=\"X-Secure-Gateway\" value=\"forgeshift\" scope=\"transport\"/>"
          + "  <property name=\"X-Signature\" expression=\"$ctx:sg_signature\" scope=\"transport\"/>"
          + "  <filter source=\"$ctx:sg_block\" regex=\"(scope|limit)\"><then><respond/></then></filter>"
          + "</sequence>";

    @Test
    void secureGatewayPolicy_convertsTo_forgeshiftSecureGatewayLuaPlugin_noAi() {
        TranslatedMediationPolicy t = KnownCustomMediatorTranslator.translate(
                "9af1e6fa", "SecureGatewayApi", "secureGatewayPolicy",
                SECURE_GATEWAY_J2, "request", List.of("wso2-source-id:9af1e6fa"));

        assertThat(t).as("the catalog must recognise the secure-gateway policy").isNotNull();
        assertThat(t.getPlugin().getName()).isEqualTo("forgeshift-secure-gateway");
        assertThat(t.getPlugin().getConfig())
                .containsEntry("required_scope", "default")
                .containsEntry("amount_limit", 100000)
                .containsEntry("hmac_secret", "s3cr3t-bank-key");
        assertThat(t.getCustomPlugin()).isNotNull();

        LuaSandboxValidator v = new LuaSandboxValidator();
        assertThat(v.validateHandler(t.getCustomPlugin().getHandlerLua(), 51200).violations()).isEmpty();
        assertThat(v.validateSchema(t.getCustomPlugin().getSchemaLua(), 51200).violations()).isEmpty();

        System.out.println("\n========= MIGRATOR OUTPUT: secureGatewayPolicy -> Kong =========");
        System.out.println("Kong plugin : " + t.getPlugin().getName());
        System.out.println("config      : " + t.getPlugin().getConfig());
        System.out.println("\n---- handler.lua (" + t.getCustomPlugin().getHandlerLua().lines().count() + " lines) ----");
        System.out.println(t.getCustomPlugin().getHandlerLua());
        System.out.println("================================================================\n");
    }
}
