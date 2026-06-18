package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-built Lua plugin for the known WSO2 custom Java mediator {@code HmacRequestSigner} — HMAC-SHA256
 * signs the request body and stamps the signature into a header, exactly like the original Java class
 * mediator, using Kong's bundled {@code resty.hmac}. NO AI: an engineer wrote + reviewed this Lua once,
 * and the migrator's known-custom-mediator catalog ({@link KnownCustomMediatorTranslator}) applies it
 * whenever it sees that class, filling the config from the mediator's properties.
 */
public final class HmacSignerPluginBuilder {

    private HmacSignerPluginBuilder() {}

    public static final String PLUGIN_NAME = "forgeshift-hmac-signer";

    private static final String HANDLER_LUA = """
            local hmac = require "resty.hmac"

            local HmacSigner = { PRIORITY = 802, VERSION = "1.0.0" }

            function HmacSigner:access(conf)
              local body = kong.request.get_raw_body() or ""

              local hm = hmac:new(conf.secret, hmac.ALGOS.SHA256)
              if not hm then
                kong.log.err("forgeshift-hmac-signer: could not initialise HMAC context")
                return
              end

              hm:update(body)
              local digest = hm:final()
              if digest then
                kong.service.request.set_header(conf.header_name, ngx.encode_base64(digest))
              end
            end

            return HmacSigner
            """;

    private static final String SCHEMA_LUA = """
            local typedefs = require "kong.db.schema.typedefs"

            return {
              name = "forgeshift-hmac-signer",
              fields = {
                { protocols = typedefs.protocols_http },
                { config = {
                    type = "record",
                    fields = {
                      { secret = { type = "string", required = true } },
                      { header_name = { type = "string", required = true, default = "X-Signature" } },
                    },
                } },
              },
            }
            """;

    public static CustomPluginArtifact asset() {
        return CustomPluginArtifact.builder()
                .pluginName(PLUGIN_NAME).handlerLua(HANDLER_LUA).schemaLua(SCHEMA_LUA).build();
    }

    /** Instance config from the Java mediator's properties (secret + header name). */
    public static Map<String, Object> buildConfig(String secret, String headerName) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (StringUtils.hasText(secret)) {
            cfg.put("secret", secret.trim());
        }
        cfg.put("header_name", StringUtils.hasText(headerName) ? headerName.trim() : "X-Signature");
        return cfg;
    }
}
