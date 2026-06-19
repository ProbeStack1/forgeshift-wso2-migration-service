package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-built Lua plugin for a complex, realistic bank "secure gateway" custom policy — a single WSO2
 * Synapse/JS op-policy that does FOUR things in one request hook: (1) validates a required OAuth2 scope
 * from the bearer JWT's claims, (2) enforces a per-request transaction amount limit, (3) enriches the
 * backend request with identity + verification headers, and (4) HMAC-SHA256 signs the transaction so the
 * backend can verify integrity — rejecting (403) on a missing scope or an over-limit amount.
 *
 * <p>Demonstrates that the catalog approach scales to a substantial policy: the engineer writes this
 * ~100-line reviewed Lua once, registers it against the policy's signature in
 * {@link KnownCustomMediatorTranslator}, and the migrator emits it (filling config parsed from the
 * policy) for every API that uses it. NO AI.
 */
public final class SecureGatewayPluginBuilder {

    private SecureGatewayPluginBuilder() {}

    public static final String PLUGIN_NAME = "forgeshift-secure-gateway";

    private static final String HANDLER_LUA = """
            local cjson = require "cjson.safe"
            local hmac = require "resty.hmac"

            local SecureGateway = { PRIORITY = 760, VERSION = "1.0.0" }

            -- base64url decode (JWT segments are base64url, not standard base64)
            local function b64url_decode(s)
              if not s then return nil end
              s = s:gsub("-", "+"):gsub("_", "/")
              local rem = #s % 4
              if rem == 2 then s = s .. "=="
              elseif rem == 3 then s = s .. "=" end
              return ngx.decode_base64(s)
            end

            -- decode the JWT payload (the jwt plugin already verified the signature upstream)
            local function jwt_claims(authz)
              if not authz then return nil end
              local token = authz:gsub("^[Bb]earer%s+", "")
              local segs = {}
              for seg in token:gmatch("[^%.]+") do segs[#segs + 1] = seg end
              if #segs < 2 then return nil end
              local raw = b64url_decode(segs[2])
              if not raw then return nil end
              return cjson.decode(raw)
            end

            -- true when the JWT carries the required scope (string "a b c" or array form)
            local function has_scope(claims, required)
              if required == nil or required == "" then return true end
              if not claims then return false end
              local scope = claims.scope or claims.scp
              if type(scope) == "table" then
                for _, s in ipairs(scope) do
                  if s == required then return true end
                end
                return false
              end
              if type(scope) == "string" then
                for s in scope:gmatch("%S+") do
                  if s == required then return true end
                end
              end
              return false
            end

            function SecureGateway:access(conf)
              local claims = jwt_claims(kong.request.get_header("Authorization"))

              -- (1) required-scope check
              if not has_scope(claims, conf.required_scope) then
                return kong.response.exit(403,
                  { error = "Forbidden: required scope '" .. conf.required_scope .. "' not present" })
              end

              -- (2) per-request amount limit
              local amount = tonumber(kong.request.get_header(conf.amount_header)) or 0
              if amount > conf.amount_limit then
                return kong.response.exit(403,
                  { error = "Transaction amount exceeds gateway limit", limit = conf.amount_limit })
              end

              -- (3) identity + verification header enrichment for the backend
              local subject = "anonymous"
              if claims then subject = claims.sub or claims.azp or claims.client_id or subject end
              kong.service.request.set_header("X-Gateway-User", tostring(subject))
              kong.service.request.set_header("X-Secure-Gateway", "forgeshift")
              kong.service.request.set_header("X-Request-Verified", "true")

              -- (4) HMAC-SHA256 sign the transaction (amount + channel) for backend integrity checks
              local channel = kong.request.get_header(conf.channel_header) or "unknown"
              local hm = hmac:new(conf.hmac_secret, hmac.ALGOS.SHA256)
              if hm then
                hm:update(tostring(amount) .. ":" .. channel)
                local digest = hm:final()
                if digest then
                  kong.service.request.set_header(conf.signature_header, ngx.encode_base64(digest))
                end
              end
            end

            return SecureGateway
            """;

    private static final String SCHEMA_LUA = """
            local typedefs = require "kong.db.schema.typedefs"

            return {
              name = "forgeshift-secure-gateway",
              fields = {
                { protocols = typedefs.protocols_http },
                { config = {
                    type = "record",
                    fields = {
                      { required_scope = { type = "string", required = true, default = "bank:access" } },
                      { amount_header = { type = "string", required = true, default = "X-Txn-Amount" } },
                      { amount_limit = { type = "number", required = true, default = 100000 } },
                      { channel_header = { type = "string", required = true, default = "X-Channel" } },
                      { hmac_secret = { type = "string", required = true, default = "change-me" } },
                      { signature_header = { type = "string", required = true, default = "X-Signature" } },
                    },
                } },
              },
            }
            """;

    public static CustomPluginArtifact asset() {
        return CustomPluginArtifact.builder()
                .pluginName(PLUGIN_NAME).handlerLua(HANDLER_LUA).schemaLua(SCHEMA_LUA).build();
    }

    /** Instance config parsed from the WSO2 policy script (nulls fall back to schema defaults). */
    public static Map<String, Object> buildConfig(String requiredScope, Integer amountLimit, String hmacSecret) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (StringUtils.hasText(requiredScope)) cfg.put("required_scope", requiredScope.trim());
        if (amountLimit != null) cfg.put("amount_limit", amountLimit);
        if (StringUtils.hasText(hmacSecret)) cfg.put("hmac_secret", hmacSecret.trim());
        return cfg;
    }
}
