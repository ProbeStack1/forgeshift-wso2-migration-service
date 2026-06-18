package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of translating one WSO2 mediation sequence to Kong.
 *
 * <p>When the AI produced safe, validated Lua, {@link #plugin} is a Kong
 * serverless {@code pre-function}/{@code post-function} plugin to attach to the
 * target API's service. When it could not be auto-translated, {@link #plugin} is
 * null and {@link #warnings} (+ {@link #externalServiceStub}) explain the manual
 * follow-up — nothing unsafe is ever deployed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslatedMediationPolicy {

    /** Unique id for the mapping/tag, e.g. {@code <apiId>:seq:<sequenceName>}. */
    private String wso2SourceId;
    private String wso2SourceName;        // the sequence name

    /** The WSO2 API whose Kong service this plugin attaches to (resolved at deploy time). */
    private String targetApiId;
    private String targetApiName;
    private String flow;                  // in / out / fault

    /** The Kong serverless plugin to deploy — null when not auto-translatable. SERVERLESS_INLINE mode. */
    private KongPlugin plugin;

    /**
     * The Dedicated Cloud custom-plugin asset to upload + reference — null in serverless mode or when
     * not auto-translatable. CUSTOM_PLUGIN mode: the bundle gets a {@link KongPlugin} instance named
     * {@code customPlugin.pluginName}; the asset itself is uploaded out-of-band (decK can't carry it).
     */
    private CustomPluginArtifact customPlugin;

    private boolean translatable;
    private String externalServiceStub;   // backend skeleton for manual cases

    @Builder.Default private List<String> warnings = new ArrayList<>();

    /** True only when there is safe, validated output to deploy (inline plugin OR custom-plugin asset). */
    public boolean isDeployable() {
        return plugin != null || customPlugin != null;
    }
}
