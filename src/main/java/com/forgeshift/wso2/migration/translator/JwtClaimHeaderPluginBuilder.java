package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed, reusable <b>JWT claim → header</b> custom Lua plugin ({@value #PLUGIN_NAME}) — a
 * hand-written (no-AI) target for the declarative subset of WSO2 JWT/token customizations: copying a
 * claim out of the (already jwt-verified) bearer token into a request header the backend can read,
 * the way WSO2 backends consumed claims from the {@code X-JWT-Assertion} assertion.
 *
 * <p>The handler decodes the bearer JWT (signature already verified upstream by Kong's {@code jwt}
 * plugin), and for each configured {@code {claim, header}} mapping sets that request header from the
 * claim value (arrays are joined with commas). Per-API behaviour is pure config, so the asset uploads
 * once and every API references it by name — same pattern as {@link CustomScopeRolePluginBuilder}.
 *
 * <p>Scope note: this covers <b>copy-claim-to-header</b> deterministically. A WSO2 customization that
 * <i>computes</i> claim values via a custom {@code JWTGenerator} / script is arbitrary code and stays
 * manual; a literal claim-add is just a {@code request-transformer} header (already handled).
 */
public final class JwtClaimHeaderPluginBuilder {

    private JwtClaimHeaderPluginBuilder() {}

    public static final String PLUGIN_NAME = "forgeshift-jwt-claim-headers";

    private static final String HANDLER_LUA = """
            local cjson = require "cjson.safe"

            local JwtClaimHeaders = { PRIORITY = 803, VERSION = "1.0.0" }

            local function decode_claims()
              local auth = kong.request.get_header("authorization")
              if not auth then return nil end
              local token = auth:match("[Bb]earer%s+(.+)")
              if not token then return nil end
              local header, payload = token:match("([^%.]+)%.([^%.]+)")
              if not payload then return nil end
              payload = payload:gsub("-", "+"):gsub("_", "/")
              local pad = #payload % 4
              if pad > 0 then payload = payload .. string.rep("=", 4 - pad) end
              local json = ngx.decode_base64(payload)
              if not json then return nil end
              return cjson.decode(json)
            end

            function JwtClaimHeaders:access(conf)
              local mappings = conf.mappings or {}
              if #mappings == 0 then return end
              local claims = decode_claims()
              if not claims then return end
              for _, m in ipairs(mappings) do
                local v = claims[m.claim]
                if v ~= nil then
                  if type(v) == "table" then v = table.concat(v, ",") end
                  kong.service.request.set_header(m.header, tostring(v))
                end
              end
            end

            return JwtClaimHeaders
            """;

    private static final String SCHEMA_LUA = """
            local typedefs = require "kong.db.schema.typedefs"

            return {
              name = "forgeshift-jwt-claim-headers",
              fields = {
                { protocols = typedefs.protocols_http },
                { config = {
                    type = "record",
                    fields = {
                      { mappings = {
                          type = "array",
                          required = true,
                          default = {},
                          elements = {
                            type = "record",
                            fields = {
                              { claim = { type = "string", required = true } },
                              { header = { type = "string", required = true } },
                            },
                          },
                      } },
                    },
                } },
              },
            }
            """;

    /** The fixed, reusable custom-plugin asset — upload once per control plane. */
    public static CustomPluginArtifact asset() {
        return CustomPluginArtifact.builder()
                .pluginName(PLUGIN_NAME).handlerLua(HANDLER_LUA).schemaLua(SCHEMA_LUA).build();
    }

    /**
     * Build the per-API instance config from a {@code claim → header} map. Returns empty when there is
     * nothing to map (then no plugin instance should be attached).
     */
    public static Optional<Map<String, Object>> buildConfig(Map<String, String> claimToHeader) {
        if (claimToHeader == null || claimToHeader.isEmpty()) return Optional.empty();
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (Map.Entry<String, String> e : claimToHeader.entrySet()) {
            if (!StringUtils.hasText(e.getKey()) || !StringUtils.hasText(e.getValue())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("claim", e.getKey().trim());
            m.put("header", e.getValue().trim());
            mappings.add(m);
        }
        if (mappings.isEmpty()) return Optional.empty();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("mappings", mappings);
        return Optional.of(cfg);
    }
}
