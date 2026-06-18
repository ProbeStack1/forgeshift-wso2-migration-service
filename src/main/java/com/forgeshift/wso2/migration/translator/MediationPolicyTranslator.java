package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.AiTranslationResult;
import com.forgeshift.wso2.migration.ai.CustomPluginLuaGenerator;
import com.forgeshift.wso2.migration.ai.MediationPolicyAiTranslator;
import com.forgeshift.wso2.migration.ai.TargetMode;
import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one WSO2 mediation sequence into a deployable Kong serverless plugin (or a
 * documented manual-review item) by running it through {@link MediationPolicyAiTranslator}
 * and wrapping the validated Lua in a {@code pre-function}/{@code post-function} plugin.
 *
 * <p>Never throws and never produces unsafe output: only Lua that passed the AI
 * translator's sandbox + confidence gates becomes a plugin. Everything else comes
 * back as a {@link TranslatedMediationPolicy} with {@code plugin == null} plus a
 * clear warning (and, when available, an external-service stub).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediationPolicyTranslator {

    private final MediationPolicyAiTranslator ai;
    private final CustomPluginLuaGenerator customPluginGenerator;
    private final MigrationProperties props;

    public boolean aiEnabled() {
        return ai.isEnabled();
    }

    public TranslatedMediationPolicy translate(String apiId, String apiName,
                                               String sequenceName, String synapseXml, String flow) {
        return translate(apiId, apiName, sequenceName, synapseXml, flow, TargetMode.SERVERLESS_INLINE);
    }

    /**
     * As {@link #translate(String, String, String, String, String)} but for a known target gateway
     * {@code mode}. In {@code CUSTOM_PLUGIN} mode the sequence is first offered to the AI custom-plugin
     * generator (Target 2); if that yields a usable, sandbox-clean handler+schema the result carries
     * both the plugin instance and the uploadable {@link CustomPluginArtifact}. Otherwise (and always
     * in serverless mode) it falls back to the inline pre/post-function path below — unchanged.
     */
    public TranslatedMediationPolicy translate(String apiId, String apiName,
                                               String sequenceName, String synapseXml, String flow, TargetMode mode) {
        String sourceId = apiId + ":seq:" + sequenceName;

        List<String> tags = new ArrayList<>();
        tags.add(props.getTranslation().getTagPrefix() + ":" + sourceId);
        tags.add(props.getTranslation().getMigratedByTag());
        tags.add("wso2-mediation:" + sequenceName);
        tags.add("wso2-member-api:" + apiId);
        tags.add("wso2-flow:" + (flow == null ? "in" : flow));

        // KNOWN CUSTOM MEDIATOR CATALOG FIRST (no AI): a recognised enterprise custom Java/JS mediator
        // (e.g. an HMAC-signer class mediator, a risk-scoring script) maps to its pre-built, reviewed Lua
        // plugin with config pulled from the policy. Custom-plugin mode only (these are Lua plugins).
        if (mode == TargetMode.CUSTOM_PLUGIN) {
            TranslatedMediationPolicy known = KnownCustomMediatorTranslator.translate(
                    apiId, apiName, sequenceName, synapseXml, flow, tags);
            if (known != null) {
                return known;
            }
        }

        // DETERMINISTIC path FIRST (no AI): a legacy Synapse sequence that is pure header manipulation
        // maps directly to a Kong request/response-transformer — rule-based, any instance, works in both
        // gateway modes. Only a fully-supported, literal, non-branching sequence is accepted here; a
        // branching/coded/dynamic one falls through (and is never partially or wrongly migrated).
        SynapseMediationTranslator.Result det = SynapseMediationTranslator.translate(synapseXml, flow, tags, mode);
        if (det.isDeterministic()) {
            List<String> warnings = new ArrayList<>();
            warnings.add("Mediation '" + sequenceName + "' (API '" + apiName + "') migrated deterministically to a "
                    + det.getPlugin().getName() + " — no AI used.");
            return TranslatedMediationPolicy.builder()
                    .wso2SourceId(sourceId).wso2SourceName(sequenceName)
                    .targetApiId(apiId).targetApiName(apiName).flow(flow)
                    .plugin(det.getPlugin())
                    .customPlugin(det.getCustomPlugin())   // non-null only for the conditional Lua plugin (asset to upload)
                    .translatable(true).warnings(warnings).build();
        }

        // Target 2: on a custom-plugin-capable CP, try to generate a real custom plugin first.
        if (mode == TargetMode.CUSTOM_PLUGIN && customPluginGenerator.isEnabled()) {
            TranslatedMediationPolicy cp = tryCustomPlugin(apiId, apiName, sequenceName, synapseXml, flow, sourceId, tags);
            if (cp != null) return cp;
            // not usable → fall through to the serverless inline path below.
        }

        AiTranslationResult r = ai.translate(sequenceName, synapseXml, flow,
                "Mediation sequence for WSO2 API '" + apiName + "' (flow=" + flow + ").");

        List<String> warnings = new ArrayList<>();
        KongPlugin plugin = null;

        if (r.isUsableLua()) {
            String pluginName = r.isPostFunction() ? "post-function" : "pre-function";
            String phase = r.getPhase() != null && !r.getPhase().isBlank() ? r.getPhase() : "access";
            Map<String, Object> config = new LinkedHashMap<>();
            config.put(phase, List.of(r.getLua()));    // Konnect serverless: {phase: [luaSnippet]}
            plugin = KongPlugin.builder()
                    .name(pluginName)
                    .config(config)
                    .enabled(true)
                    .tags(tags)
                    .build();
            if (r.getNotes() != null && !r.getNotes().isBlank()) {
                warnings.add("Mediation '" + sequenceName + "' (API '" + apiName + "'): " + r.getNotes());
            }
            if (r.getUnsupportedApis() != null && !r.getUnsupportedApis().isEmpty()) {
                warnings.add("Mediation '" + sequenceName + "' partially translated; review: " + r.getUnsupportedApis());
            }
        } else {
            String why = r.getReason() != null ? r.getReason()
                    : (r.getViolations() == null || r.getViolations().isEmpty()
                        ? "AI could not produce safe Lua"
                        : String.join("; ", r.getViolations()));
            warnings.add("Mediation sequence '" + sequenceName + "' for API '" + apiName
                    + "' was NOT auto-migrated (manual review required): " + why
                    + (r.getExternalServiceStub() != null && !r.getExternalServiceStub().isBlank()
                        ? " A backend stub was generated for manual deployment." : ""));
        }

        return TranslatedMediationPolicy.builder()
                .wso2SourceId(sourceId)
                .wso2SourceName(sequenceName)
                .targetApiId(apiId)
                .targetApiName(apiName)
                .flow(flow)
                .plugin(plugin)
                .translatable(r.isTranslatable())
                .externalServiceStub(r.getExternalServiceStub())
                .warnings(warnings)
                .build();
    }

    /** Try to generate a Dedicated Cloud custom plugin for this sequence; null when not usable. */
    private TranslatedMediationPolicy tryCustomPlugin(String apiId, String apiName, String sequenceName,
                                                      String synapseXml, String flow, String sourceId, List<String> tags) {
        String pluginName = customPluginName(apiId, sequenceName);
        AiTranslationResult cr = customPluginGenerator.generate(pluginName, sequenceName, synapseXml,
                "Mediation sequence for WSO2 API '" + apiName + "' (flow=" + flow + ").");
        if (!cr.isUsableCustomPlugin()) {
            return null;
        }
        CustomPluginArtifact asset = CustomPluginArtifact.builder()
                .pluginName(pluginName).handlerLua(cr.getHandlerLua()).schemaLua(cr.getSchemaLua()).build();
        KongPlugin instance = KongPlugin.builder()
                .name(pluginName).config(new LinkedHashMap<>()).enabled(true).tags(tags).build();
        List<String> warnings = new ArrayList<>();
        warnings.add("Mediation '" + sequenceName + "' (API '" + apiName + "') migrated to the Kong custom plugin '"
                + pluginName + "' — verify the generated handler/schema before relying on it.");
        if (cr.getNotes() != null && !cr.getNotes().isBlank()) {
            warnings.add("Mediation '" + sequenceName + "': " + cr.getNotes());
        }
        if (cr.getUnsupportedApis() != null && !cr.getUnsupportedApis().isEmpty()) {
            warnings.add("Mediation '" + sequenceName + "' partially translated; review: " + cr.getUnsupportedApis());
        }
        return TranslatedMediationPolicy.builder()
                .wso2SourceId(sourceId).wso2SourceName(sequenceName)
                .targetApiId(apiId).targetApiName(apiName).flow(flow)
                .plugin(instance).customPlugin(asset)
                .translatable(true).warnings(warnings).build();
    }

    /** Kong-safe, deterministic plugin name for a mediation sequence's custom plugin. */
    private static String customPluginName(String apiId, String sequenceName) {
        String slug = (apiId + "-" + sequenceName).toLowerCase()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-$", "");
        }
        return "forgeshift-med-" + slug;
    }
}
