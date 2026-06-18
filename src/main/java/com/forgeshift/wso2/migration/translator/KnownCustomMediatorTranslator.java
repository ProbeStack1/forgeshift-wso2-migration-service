package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The <b>known-custom-mediator catalog</b>: deterministically (NO AI) maps a RECOGNISED WSO2 custom
 * mediator to a pre-built, reviewed Kong Lua plugin, filling its config from the policy.
 *
 * <p>This is how the migrator handles common enterprise custom Java/JS policies without AI: an engineer
 * writes the Lua plugin once (e.g. {@link HmacSignerPluginBuilder}, {@link RiskScoringPluginBuilder}),
 * registers it here against the mediator's identity, and every API that uses that mediator converts
 * automatically. Recognition is by stable identity, not by interpreting arbitrary code:
 * <ul>
 *   <li>a {@code <class name="…HmacRequestSigner">} Java class mediator → {@code forgeshift-hmac-signer}
 *       (secret + header pulled from the mediator's {@code <property>} values), and</li>
 *   <li>a {@code <script>} mediator that stamps {@code X-Risk-Level} → {@code forgeshift-risk-scoring}
 *       (the risk thresholds parsed out of the script).</li>
 * </ul>
 *
 * <p>Returns {@code null} when the sequence is not a cataloged mediator — the caller then falls through
 * to the structured-mediator translator / manual review. Only used in CUSTOM_PLUGIN mode.
 */
public final class KnownCustomMediatorTranslator {

    private KnownCustomMediatorTranslator() {}

    private static final Pattern GE_NUMBER = Pattern.compile(">=\\s*(\\d+)");

    public static TranslatedMediationPolicy translate(String apiId, String apiName, String sequenceName,
                                                      String synapseXml, String flow, List<String> tags) {
        if (synapseXml == null || synapseXml.isBlank()) return null;
        Element seq;
        try {
            seq = parse(synapseXml);
        } catch (Exception e) {
            return null;
        }

        NodeList children = seq.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String local = localName(el);

            if (local.equals("class")) {
                String fqn = attr(el, "name");
                if (fqn != null && fqn.toLowerCase().contains("hmacrequestsigner")) {
                    Map<String, String> props = classProps(el);
                    Map<String, Object> cfg = HmacSignerPluginBuilder.buildConfig(
                            props.get("secret"), props.get("headername"));
                    return known(apiId, apiName, sequenceName, flow, tags,
                            HmacSignerPluginBuilder.PLUGIN_NAME, cfg, HmacSignerPluginBuilder.asset(),
                            "custom Java class mediator '" + fqn + "'");
                }
            } else if (local.equals("script")) {
                String body = el.getTextContent() == null ? "" : el.getTextContent();
                if (body.toLowerCase().contains("x-risk-level")) {
                    int[] th = sortedThresholds(body);
                    Map<String, Object> cfg = th == null
                            ? RiskScoringPluginBuilder.buildConfig(null, null, null)
                            : RiskScoringPluginBuilder.buildConfig(th[0], th[1], th[2]);
                    return known(apiId, apiName, sequenceName, flow, tags,
                            RiskScoringPluginBuilder.PLUGIN_NAME, cfg, RiskScoringPluginBuilder.asset(),
                            "custom JavaScript risk-scoring policy");
                }
            }
        }
        return null;
    }

    private static TranslatedMediationPolicy known(String apiId, String apiName, String sequenceName, String flow,
                                                   List<String> tags, String pluginName, Map<String, Object> cfg,
                                                   CustomPluginArtifact asset, String what) {
        KongPlugin instance = KongPlugin.builder()
                .name(pluginName).config(cfg).enabled(true)
                .tags(tags == null ? null : new ArrayList<>(tags)).build();
        List<String> warnings = new ArrayList<>();
        warnings.add("Mediation '" + sequenceName + "' (API '" + apiName + "'): recognised " + what
                + " from the custom-mediator catalog → migrated to the pre-built Kong plugin '"
                + pluginName + "' (no AI). Verify the generated config.");
        return TranslatedMediationPolicy.builder()
                .wso2SourceId(apiId + ":seq:" + sequenceName).wso2SourceName(sequenceName)
                .targetApiId(apiId).targetApiName(apiName).flow(flow)
                .plugin(instance).customPlugin(asset)
                .translatable(true).warnings(warnings).build();
    }

    private static Map<String, String> classProps(Element classEl) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList kids = classEl.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (localName(e).equals("property")) {
                String name = attr(e, "name");
                if (name != null) out.put(name.toLowerCase(), attr(e, "value"));
            }
        }
        return out;
    }

    /** Parse the {@code amount >= N} thresholds → [medium, high, block] (ascending); null if fewer than 3. */
    private static int[] sortedThresholds(String script) {
        Matcher m = GE_NUMBER.matcher(script);
        TreeSet<Integer> nums = new TreeSet<>();
        while (m.find()) {
            try {
                nums.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignore) {
                // skip
            }
        }
        if (nums.size() < 3) return null;
        Iterator<Integer> it = nums.iterator();
        return new int[]{it.next(), it.next(), it.next()};
    }

    // ---------------- xml helpers ----------------

    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element root = doc.getDocumentElement();
        if ("sequence".equals(localName(root))) return root;
        NodeList seqs = root.getElementsByTagNameNS("*", "sequence");
        if (seqs.getLength() > 0 && seqs.item(0) instanceof Element e) return e;
        return root;
    }

    private static String localName(Element el) {
        String ln = el.getLocalName();
        return (ln != null ? ln : el.getNodeName()).toLowerCase();
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }
}
