package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Builds the deterministic <b>OAuth2 scope enforcement</b> custom plugin (Target 1).
 *
 * <p>WSO2 binds required scopes to operations; today the migration emits {@code jwt} for the
 * security scheme but never enforces those scopes, so the authorization control vanishes in Kong
 * with no warning (a <i>silent drop</i>). This builder closes that gap WITHOUT the AI path:
 *
 * <ul>
 *   <li>{@link #asset()} — a single, fixed, reusable Konnect Dedicated Cloud custom plugin
 *       ({@value #PLUGIN_NAME}): {@code handler.lua} decodes the bearer JWT (the {@code jwt} plugin
 *       already verified the signature), reads the {@code scope} claim, and 403s when the request's
 *       operation requires a scope the token lacks. The logic is generic; per-API behaviour comes
 *       entirely from config, so the asset is uploaded ONCE and every API references it by name.</li>
 *   <li>{@link #buildConfig} — the per-API instance config (the scope rules), grouped by HTTP method.
 *       Method-based matching is exact (no path-template ambiguity); a future revision can add
 *       per-path rules via the optional {@code path} field already present in the schema.</li>
 * </ul>
 *
 * <p>Being deterministic, the output never trips the AI confidence gate; it is still re-checked by
 * {@code LuaSandboxValidator.validateHandler/validateSchema} for defence-in-depth at wiring time.
 */
@Component
public class CustomScopeRolePluginBuilder {

    /** Fixed name shared by the uploaded asset, the schema, and every bundle plugin instance. */
    public static final String PLUGIN_NAME = "forgeshift-oauth-scope";

    // handler.lua — generic; reads only its `conf`. Runs in access phase at a LOW priority so it
    // executes AFTER Kong's jwt plugin has authenticated the request.
    private static final String HANDLER_LUA = """
            local cjson = require "cjson.safe"

            local ScopeEnforcer = { PRIORITY = 805, VERSION = "1.0.0" }

            -- Decode (without re-verifying) the bearer JWT payload; the jwt plugin already
            -- validated the signature upstream of this plugin.
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

            local function token_scopes(conf)
              local claims = decode_claims()
              if not claims then return {} end
              local raw = claims[conf.scope_claim or "scope"]
              local set = {}
              if type(raw) == "string" then
                for s in raw:gmatch("%S+") do set[s] = true end
              elseif type(raw) == "table" then
                for _, s in ipairs(raw) do set[s] = true end
              end
              return set
            end

            local function method_matches(methods, m)
              if methods == nil or #methods == 0 then return true end
              for _, x in ipairs(methods) do
                if string.upper(x) == m then return true end
              end
              return false
            end

            local function path_matches(rule_path, req_path)
              if rule_path == nil or rule_path == "" then return true end
              if req_path == rule_path then return true end
              return string.sub(req_path, 1, #rule_path + 1) == rule_path .. "/"
            end

            function ScopeEnforcer:access(conf)
              local rules = conf.rules or {}
              if #rules == 0 then return end
              local m = kong.request.get_method()
              local p = kong.request.get_path()
              local matched = nil
              for _, r in ipairs(rules) do
                if method_matches(r.methods, m) and path_matches(r.path, p) then
                  matched = r
                  break
                end
              end
              if matched == nil then
                if (conf.unmatched or "allow") == "deny" then
                  return kong.response.exit(403, { message = "No scope rule matches this operation" })
                end
                return
              end
              local required = matched.scopes or {}
              if #required == 0 then return end
              local have = token_scopes(conf)
              local missing = {}
              for _, s in ipairs(required) do
                if not have[s] then missing[#missing + 1] = s end
              end
              if #missing > 0 then
                return kong.response.exit(403, { message = "Missing required scope(s): " .. table.concat(missing, ", ") })
              end
            end

            return ScopeEnforcer
            """;

    private static final String SCHEMA_LUA = """
            local typedefs = require "kong.db.schema.typedefs"

            return {
              name = "forgeshift-oauth-scope",
              fields = {
                { protocols = typedefs.protocols_http },
                { config = {
                    type = "record",
                    fields = {
                      { scope_claim = { type = "string", required = true, default = "scope" } },
                      { unmatched = { type = "string", required = true, default = "allow",
                                      one_of = { "allow", "deny" } } },
                      { rules = {
                          type = "array",
                          required = true,
                          default = {},
                          elements = {
                            type = "record",
                            fields = {
                              { methods = { type = "array", elements = { type = "string" }, default = {} } },
                              { path = { type = "string" } },
                              { scopes = { type = "array", elements = { type = "string" }, default = {} } },
                            },
                          },
                      } },
                    },
                } },
              },
            }
            """;

    /** The fixed, reusable custom-plugin asset — upload once per control plane. */
    public CustomPluginArtifact asset() {
        return CustomPluginArtifact.builder()
                .pluginName(PLUGIN_NAME)
                .handlerLua(HANDLER_LUA)
                .schemaLua(SCHEMA_LUA)
                .build();
    }

    /**
     * Build the per-API instance config from the API's operations, grouping the methods that share
     * the same required scope-set into one rule. Returns empty when the API declares no scopes —
     * then no plugin instance should be attached (nothing to enforce).
     *
     * @param context    the API context (reserved for future per-path rules; unused in method-mode)
     * @param operations the WSO2 {@code operations[]} list (each with {@code verb} + {@code scopes})
     */
    public Optional<Map<String, Object>> buildConfig(String context, List<Map<String, Object>> operations) {
        // scope-set (sorted, so {a,b} == {b,a}) → the methods that require it
        LinkedHashMap<List<String>, LinkedHashSet<String>> byScopes = new LinkedHashMap<>();
        if (operations != null) {
            for (Map<String, Object> op : operations) {
                List<String> scopes = scopesOf(op);
                if (scopes.isEmpty()) continue;
                String verb = upper(str(op.get("verb")));
                if (!StringUtils.hasText(verb)) continue;
                byScopes.computeIfAbsent(scopes, k -> new LinkedHashSet<>()).add(verb);
            }
        }
        if (byScopes.isEmpty()) return Optional.empty();

        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map.Entry<List<String>, LinkedHashSet<String>> e : byScopes.entrySet()) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("methods", new ArrayList<>(e.getValue()));
            rule.put("scopes", e.getKey());
            rules.add(rule);
        }

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("scope_claim", "scope");
        cfg.put("unmatched", "allow");
        cfg.put("rules", rules);
        return Optional.of(cfg);
    }

    /** Required scopes for an operation, as a sorted, de-duplicated list (the grouping key). */
    private static List<String> scopesOf(Map<String, Object> op) {
        TreeSet<String> out = new TreeSet<>();
        Object s = op.get("scopes");
        if (s instanceof List<?> l) {
            for (Object x : l) if (x != null && StringUtils.hasText(x.toString())) out.add(x.toString().trim());
        } else if (s instanceof String str && StringUtils.hasText(str)) {
            out.add(str.trim());
        }
        // some WSO2 shapes use a singular "scope"
        Object single = op.get("scope");
        if (single instanceof String str && StringUtils.hasText(str)) out.add(str.trim());
        return new ArrayList<>(out);
    }

    private static String upper(String s) { return s == null ? null : s.toUpperCase(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
}
