package com.forgeshift.wso2.migration.domain.kong;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An uploadable Konnect Dedicated Cloud custom plugin: the two self-contained Lua
 * files plus the plugin name they register under.
 *
 * <p>This is the <b>asset</b> (handler.lua + schema.lua) that is POSTed to
 * {@code .../core-entities/custom-plugins} out-of-band, <b>before</b> the decK bundle
 * references a plugin <b>instance</b> of the same {@link #pluginName}. decK cannot
 * carry the Lua itself, so a {@code CustomPluginArtifact} never goes into the bundle
 * YAML — only the matching {@link KongPlugin} instance (name + config) does.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomPluginArtifact {

    /** Plugin name — must equal schema.lua's {@code name} and the bundle plugin instance's name. */
    private String pluginName;

    /** Full handler.lua source (the executable plugin code). */
    private String handlerLua;

    /** Full schema.lua source (the config schema). */
    private String schemaLua;

    /** True only when all three parts are present — safe to upload. */
    public boolean isComplete() {
        return pluginName != null && !pluginName.isBlank()
                && handlerLua != null && !handlerLua.isBlank()
                && schemaLua != null && !schemaLua.isBlank();
    }
}
