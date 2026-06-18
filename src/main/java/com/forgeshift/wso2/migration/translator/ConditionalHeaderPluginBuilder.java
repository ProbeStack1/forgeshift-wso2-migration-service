package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;

/**
 * The fixed, reusable <b>conditional header</b> custom Lua plugin ({@value #PLUGIN_NAME}) — a
 * hand-written (no-AI) target for WSO2 Synapse conditional sequences whose branches only set/remove
 * HTTP headers:
 * <ul>
 *   <li>{@code <filter source="$trp:H" regex="..">} (then/else) → a {@code rules} entry, and</li>
 *   <li>{@code <switch source="$trp:H"><case regex="..">…</case><default>…</default></switch>} →
 *       a {@code switches} entry (first matching case wins, else the default).</li>
 * </ul>
 *
 * <p>The handler is generic; per-API behaviour is entirely config, so the asset uploads ONCE and every
 * conditional sequence references it by name — same pattern as {@link CustomScopeRolePluginBuilder}.
 * Only valid on a custom-plugin-capable (Dedicated Cloud / self-managed) control plane.
 */
public final class ConditionalHeaderPluginBuilder {

    private ConditionalHeaderPluginBuilder() {}

    public static final String PLUGIN_NAME = "forgeshift-conditional-headers";

    private static final String HANDLER_LUA = """
            local ConditionalHeaders = { PRIORITY = 801, VERSION = "1.1.0" }

            local function apply_ops(flow, set_list, remove_list)
              if set_list then
                for _, pair in ipairs(set_list) do
                  local idx = string.find(pair, ":", 1, true)
                  if idx then
                    local name = string.sub(pair, 1, idx - 1)
                    local value = string.sub(pair, idx + 1)
                    if flow == "response" then
                      kong.response.set_header(name, value)
                    else
                      kong.service.request.set_header(name, value)
                    end
                  end
                end
              end
              if remove_list then
                for _, hname in ipairs(remove_list) do
                  if flow == "response" then
                    kong.response.clear_header(hname)
                  else
                    kong.service.request.clear_header(hname)
                  end
                end
              end
            end

            local function matches(value, regex)
              if not regex or regex == "" then return false end
              return ngx.re.match(value, "^(?:" .. regex .. ")$", "jo") ~= nil
            end

            local function run(conf)
              local flow = conf.flow or "request"
              for _, rule in ipairs(conf.rules or {}) do
                local v = kong.request.get_header(rule.source_header) or ""
                if matches(v, rule.regex) then
                  apply_ops(flow, rule.then_set, rule.then_remove)
                else
                  apply_ops(flow, rule.else_set, rule.else_remove)
                end
              end
              for _, sw in ipairs(conf.switches or {}) do
                local v = kong.request.get_header(sw.source_header) or ""
                local applied = false
                for _, c in ipairs(sw.cases or {}) do
                  if matches(v, c.regex) then
                    apply_ops(flow, c.set, c.remove)
                    applied = true
                    break
                  end
                end
                if not applied then
                  apply_ops(flow, sw.default_set, sw.default_remove)
                end
              end
            end

            function ConditionalHeaders:access(conf)
              if (conf.flow or "request") ~= "response" then
                run(conf)
              end
            end

            function ConditionalHeaders:header_filter(conf)
              if conf.flow == "response" then
                run(conf)
              end
            end

            return ConditionalHeaders
            """;

    private static final String SCHEMA_LUA = """
            local typedefs = require "kong.db.schema.typedefs"

            return {
              name = "forgeshift-conditional-headers",
              fields = {
                { protocols = typedefs.protocols_http },
                { config = {
                    type = "record",
                    fields = {
                      { flow = { type = "string", required = true, default = "request",
                                 one_of = { "request", "response" } } },
                      { rules = {
                          type = "array",
                          required = true,
                          default = {},
                          elements = {
                            type = "record",
                            fields = {
                              { source_header = { type = "string", required = true } },
                              { regex = { type = "string", required = true } },
                              { then_set = { type = "array", elements = { type = "string" }, default = {} } },
                              { then_remove = { type = "array", elements = { type = "string" }, default = {} } },
                              { else_set = { type = "array", elements = { type = "string" }, default = {} } },
                              { else_remove = { type = "array", elements = { type = "string" }, default = {} } },
                            },
                          },
                      } },
                      { switches = {
                          type = "array",
                          required = true,
                          default = {},
                          elements = {
                            type = "record",
                            fields = {
                              { source_header = { type = "string", required = true } },
                              { cases = {
                                  type = "array",
                                  default = {},
                                  elements = {
                                    type = "record",
                                    fields = {
                                      { regex = { type = "string", required = true } },
                                      { set = { type = "array", elements = { type = "string" }, default = {} } },
                                      { remove = { type = "array", elements = { type = "string" }, default = {} } },
                                    },
                                  },
                              } },
                              { default_set = { type = "array", elements = { type = "string" }, default = {} } },
                              { default_remove = { type = "array", elements = { type = "string" }, default = {} } },
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
}
