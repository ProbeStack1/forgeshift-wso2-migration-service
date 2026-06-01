package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.ai.AiTranslationResult;
import com.forgeshift.wso2.migration.ai.MediationPolicyAiTranslator;
import com.forgeshift.wso2.migration.config.MigrationProperties;
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
    private final MigrationProperties props;

    public boolean aiEnabled() {
        return ai.isEnabled();
    }

    public TranslatedMediationPolicy translate(String apiId, String apiName,
                                               String sequenceName, String synapseXml, String flow) {
        String sourceId = apiId + ":seq:" + sequenceName;

        List<String> tags = new ArrayList<>();
        tags.add(props.getTranslation().getTagPrefix() + ":" + sourceId);
        tags.add(props.getTranslation().getMigratedByTag());
        tags.add("wso2-mediation:" + sequenceName);
        tags.add("wso2-member-api:" + apiId);
        tags.add("wso2-flow:" + (flow == null ? "in" : flow));

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
}
