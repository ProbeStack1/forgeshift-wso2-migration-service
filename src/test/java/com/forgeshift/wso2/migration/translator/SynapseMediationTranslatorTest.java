package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.TargetMode;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseMediationTranslatorTest {

    private static final List<String> TAGS = List.of("wso2-source-id:seq1");

    private static String seq(String body) {
        return "<sequence xmlns=\"http://ws.apache.org/ns/synapse\" name=\"s\">" + body + "</sequence>";
    }

    @SuppressWarnings("unchecked")
    private static List<String> headers(KongPlugin p, String section) {
        Map<String, Object> cfg = p.getConfig();
        Map<String, Object> sec = (Map<String, Object>) cfg.get(section);
        return sec == null ? List.of() : (List<String>) sec.get("headers");
    }

    @Test
    void literalTransportHeaders_inFlow_mapToRequestTransformer() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<property name=\"X-Tenant\" value=\"acme\" scope=\"transport\" action=\"set\"/>"
                        + "<property name=\"X-Source\" value=\"gateway\" scope=\"transport\"/>"), "in", TAGS);

        assertThat(r.isDeterministic()).isTrue();
        assertThat(r.getPlugin().getName()).isEqualTo("request-transformer");
        assertThat(headers(r.getPlugin(), "add")).containsExactly("X-Tenant:acme", "X-Source:gateway");
        assertThat(headers(r.getPlugin(), "replace")).containsExactly("X-Tenant:acme", "X-Source:gateway");
        assertThat(r.getPlugin().getTags()).contains("wso2-source-id:seq1");
    }

    @Test
    void removeAction_mapsToRemoveHeaders() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<property name=\"X-Internal\" scope=\"transport\" action=\"remove\"/>"), "in", TAGS);
        assertThat(r.isDeterministic()).isTrue();
        assertThat(headers(r.getPlugin(), "remove")).containsExactly("X-Internal");
    }

    @Test
    void outFlow_mapsToResponseTransformer() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<property name=\"X-Served\" value=\"kong\" scope=\"transport\"/>"), "out", TAGS);
        assertThat(r.isDeterministic()).isTrue();
        assertThat(r.getPlugin().getName()).isEqualTo("response-transformer");
    }

    @Test
    void headerMediatorWithoutScope_treatedAsHttpHeader() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<header name=\"X-Trace\" value=\"on\"/>"), "in", TAGS);
        assertThat(r.isDeterministic()).isTrue();
        assertThat(headers(r.getPlugin(), "add")).containsExactly("X-Trace:on");
    }

    @Test
    void expressionValued_isNotDeterministic() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<property name=\"X-Id\" expression=\"get-property('uuid')\" scope=\"transport\"/>"), "in", TAGS);
        assertThat(r.isDeterministic()).isFalse();
        assertThat(r.getPlugin()).isNull();
        assertThat(r.getUnsupported()).anyMatch(u -> u.contains("expression"));
    }

    @Test
    void dynamicTemplateValue_isNotDeterministic() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<property name=\"X-Api\" value=\"{apiName}\" scope=\"transport\"/>"), "in", TAGS);
        assertThat(r.isDeterministic()).isFalse();
        assertThat(r.getUnsupported()).anyMatch(u -> u.contains("dynamic-value"));
    }

    @Test
    void nonTransportScope_isNotDeterministic() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<property name=\"localVar\" value=\"y\" scope=\"default\"/>"), "in", TAGS);
        assertThat(r.isDeterministic()).isFalse();
        assertThat(r.getUnsupported()).anyMatch(u -> u.contains("scope=default"));
    }

    @Test
    void branchingMediator_filter_isNotDeterministic() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<property name=\"X-A\" value=\"1\" scope=\"transport\"/>"
                        + "<filter source=\"$ctx:x\" regex=\"y\"><then/></filter>"), "in", TAGS);
        // one header op is fine in isolation, but the branching filter taints the whole sequence
        assertThat(r.isDeterministic()).isFalse();
        assertThat(r.getUnsupported()).anyMatch(u -> u.startsWith("filter"));
    }

    private static final String FILTER = "<filter source=\"$trp:X-Channel\" regex=\"mobile\">"
            + "<then><property name=\"X-Priority\" value=\"high\" scope=\"transport\"/></then>"
            + "<else><property name=\"X-Priority\" value=\"normal\" scope=\"transport\"/></else>"
            + "</filter>";

    @Test
    @SuppressWarnings("unchecked")
    void conditionalFilter_customPluginMode_emitsTheReusableConditionalPlugin() {
        SynapseMediationTranslator.Result r =
                SynapseMediationTranslator.translate(seq(FILTER), "in", TAGS, TargetMode.CUSTOM_PLUGIN);

        assertThat(r.isDeterministic()).isTrue();
        assertThat(r.getPlugin().getName()).isEqualTo("forgeshift-conditional-headers");
        assertThat(r.getCustomPlugin()).isNotNull();
        assertThat(r.getCustomPlugin().getPluginName()).isEqualTo("forgeshift-conditional-headers");

        Map<String, Object> cfg = r.getPlugin().getConfig();
        assertThat(cfg).containsEntry("flow", "request");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) cfg.get("rules");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0)).containsEntry("source_header", "X-Channel").containsEntry("regex", "mobile");
        assertThat((List<String>) rules.get(0).get("then_set")).containsExactly("X-Priority:high");
        assertThat((List<String>) rules.get(0).get("else_set")).containsExactly("X-Priority:normal");
    }

    @Test
    void conditionalFilter_serverlessMode_notDeterministic_noCustomPluginThere() {
        SynapseMediationTranslator.Result r =
                SynapseMediationTranslator.translate(seq(FILTER), "in", TAGS, TargetMode.SERVERLESS_INLINE);
        assertThat(r.isDeterministic()).isFalse();
        assertThat(r.getUnsupported()).anyMatch(u -> u.startsWith("conditional"));
    }

    private static final String SWITCH = "<switch source=\"$trp:X-Region\">"
            + "<case regex=\"us\"><property name=\"X-DC\" value=\"us-east\" scope=\"transport\"/></case>"
            + "<case regex=\"eu\"><property name=\"X-DC\" value=\"eu-west\" scope=\"transport\"/></case>"
            + "<default><property name=\"X-DC\" value=\"global\" scope=\"transport\"/></default>"
            + "</switch>";

    @Test
    @SuppressWarnings("unchecked")
    void switchMediator_customPluginMode_emitsSwitchConfig() {
        SynapseMediationTranslator.Result r =
                SynapseMediationTranslator.translate(seq(SWITCH), "in", TAGS, TargetMode.CUSTOM_PLUGIN);

        assertThat(r.isDeterministic()).isTrue();
        assertThat(r.getPlugin().getName()).isEqualTo("forgeshift-conditional-headers");
        assertThat(r.getCustomPlugin()).isNotNull();

        Map<String, Object> cfg = r.getPlugin().getConfig();
        List<Map<String, Object>> switches = (List<Map<String, Object>>) cfg.get("switches");
        assertThat(switches).hasSize(1);
        assertThat(switches.get(0)).containsEntry("source_header", "X-Region");
        List<Map<String, Object>> cases = (List<Map<String, Object>>) switches.get(0).get("cases");
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0)).containsEntry("regex", "us");
        assertThat((List<String>) cases.get(0).get("set")).containsExactly("X-DC:us-east");
        assertThat((List<String>) switches.get(0).get("default_set")).containsExactly("X-DC:global");
    }

    @Test
    void scriptMediator_isNotDeterministic() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<script language=\"js\">mc.setProperty('a','1');</script>"), "in", TAGS);
        assertThat(r.isDeterministic()).isFalse();
        assertThat(r.getUnsupported()).contains("script");
    }

    @Test
    void faultFlow_isNeverDeterministic() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<property name=\"X-A\" value=\"1\" scope=\"transport\"/>"), "fault", TAGS);
        assertThat(r.isDeterministic()).isFalse();
        assertThat(r.getUnsupported()).contains("fault-flow");
    }

    @Test
    void logOnlySequence_hasNothingToMigrate() {
        SynapseMediationTranslator.Result r = SynapseMediationTranslator.translate(seq(
                "<log level=\"custom\"><property name=\"m\" value=\"hi\"/></log>"), "in", TAGS);
        assertThat(r.isDeterministic()).isFalse();   // no header op produced
        assertThat(r.getNotes()).anyMatch(nz -> nz.contains("log"));
    }
}
