package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.LuaSandboxValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnownCustomMediatorTranslatorTest {

    private static final List<String> TAGS = List.of("wso2-source-id:seq1");
    private final LuaSandboxValidator validator = new LuaSandboxValidator();

    private static final String HMAC_SEQ =
            "<sequence name=\"hmac-request-signing\" xmlns=\"http://ws.apache.org/ns/synapse\">"
                    + "<class name=\"com.bank.gateway.mediators.HmacRequestSigner\">"
                    + "<property name=\"secret\" value=\"s3cr3t-shared-key\"/>"
                    + "<property name=\"headerName\" value=\"X-Signature\"/>"
                    + "</class></sequence>";

    private static final String RISK_SEQ =
            "<sequence name=\"fraud-risk-scoring\" xmlns=\"http://ws.apache.org/ns/synapse\">"
                    + "<script language=\"js\"><![CDATA["
                    + "var amount = parseFloat(mc.getProperty('X-Txn-Amount')) || 0;"
                    + "var risk='low'; if (amount >= 10000) { risk='high'; } else if (amount >= 2000) { risk='medium'; }"
                    + "mc.setProperty('X-Risk-Level', risk, 'transport');"
                    + "if (risk==='high' && amount >= 50000) { mc.setProperty('HTTP_SC',403); }"
                    + "]]></script></sequence>";

    @Test
    void hmacClassMediator_mapsToHmacPlugin_withConfigFromProperties() {
        TranslatedMediationPolicy t = KnownCustomMediatorTranslator.translate(
                "api1", "PaymentsAPI", "hmac-request-signing", HMAC_SEQ, "in", TAGS);

        assertThat(t).isNotNull();
        assertThat(t.getPlugin().getName()).isEqualTo("forgeshift-hmac-signer");
        assertThat(t.getPlugin().getConfig())
                .containsEntry("secret", "s3cr3t-shared-key")
                .containsEntry("header_name", "X-Signature");
        assertThat(t.getCustomPlugin()).isNotNull();
        assertThat(validator.validateHandler(t.getCustomPlugin().getHandlerLua(), 51200).violations()).isEmpty();
        assertThat(validator.validateSchema(t.getCustomPlugin().getSchemaLua(), 51200).violations()).isEmpty();
        assertThat(t.getWarnings()).anyMatch(w -> w.contains("custom-mediator catalog") && w.contains("no AI"));
    }

    @Test
    void riskScript_mapsToRiskPlugin_withThresholdsParsedFromTheScript() {
        TranslatedMediationPolicy t = KnownCustomMediatorTranslator.translate(
                "api1", "PaymentsAPI", "fraud-risk-scoring", RISK_SEQ, "in", TAGS);

        assertThat(t).isNotNull();
        assertThat(t.getPlugin().getName()).isEqualTo("forgeshift-risk-scoring");
        assertThat(t.getPlugin().getConfig())
                .containsEntry("medium_amount", 2000)
                .containsEntry("high_amount", 10000)
                .containsEntry("block_amount", 50000);
        assertThat(t.getCustomPlugin()).isNotNull();
        assertThat(validator.validateHandler(t.getCustomPlugin().getHandlerLua(), 51200).violations()).isEmpty();
    }

    @Test
    void unknownClassMediator_returnsNull() {
        String seq = "<sequence xmlns=\"http://ws.apache.org/ns/synapse\"><class name=\"com.other.Foo\"/></sequence>";
        assertThat(KnownCustomMediatorTranslator.translate("a", "A", "s", seq, "in", TAGS)).isNull();
    }

    @Test
    void plainHeaderSequence_returnsNull_soItFallsThroughToTheStructuredTranslator() {
        String seq = "<sequence xmlns=\"http://ws.apache.org/ns/synapse\">"
                + "<property name=\"X-A\" value=\"1\" scope=\"transport\"/></sequence>";
        assertThat(KnownCustomMediatorTranslator.translate("a", "A", "s", seq, "in", TAGS)).isNull();
    }

    private static final String SECURE_GATEWAY_SEQ =
            "<sequence name=\"secure-gateway\" xmlns=\"http://ws.apache.org/ns/synapse\">"
                    + "<script language=\"js\"><![CDATA["
                    + "  var requiredScope = 'bank:access';"
                    + "  var amountLimit = 100000;"
                    + "  var hmacSecret = 's3cr3t-bank-key';"
                    + "  // decode JWT, validate scope, enforce limit, enrich headers, HMAC sign ..."
                    + "]]></script>"
                    + "<property name=\"X-Secure-Gateway\" value=\"forgeshift\" scope=\"transport\"/>"
                    + "</sequence>";

    @Test
    void secureGatewayScript_mapsToSecureGatewayPlugin_withConfigParsedFromTheScript() {
        TranslatedMediationPolicy t = KnownCustomMediatorTranslator.translate(
                "api1", "SecureApi", "secure-gateway", SECURE_GATEWAY_SEQ, "in", TAGS);

        assertThat(t).isNotNull();
        assertThat(t.getPlugin().getName()).isEqualTo("forgeshift-secure-gateway");
        assertThat(t.getPlugin().getConfig())
                .containsEntry("required_scope", "bank:access")
                .containsEntry("amount_limit", 100000)
                .containsEntry("hmac_secret", "s3cr3t-bank-key");
        assertThat(t.getCustomPlugin()).isNotNull();
        assertThat(validator.validateHandler(t.getCustomPlugin().getHandlerLua(), 51200).violations()).isEmpty();
        assertThat(validator.validateSchema(t.getCustomPlugin().getSchemaLua(), 51200).violations()).isEmpty();
    }
}
