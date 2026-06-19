package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-built Kong Lua plugin for the WSO2 built-in {@code jsonToXML} / {@code xmlToJson} message-format
 * mediation policies. WSO2 converts the message body between JSON and XML (Synapse {@code messageType});
 * Kong has no built-in (free) equivalent, so the migrator's catalog
 * ({@link KnownCustomMediatorTranslator}/{@link OperationPolicyTranslator}) emits this reviewed Lua
 * plugin instead of dropping the policy to manual review. NO AI: an engineer wrote + reviewed this once.
 *
 * <p>Config: {@code direction} (json_to_xml | xml_to_json) and {@code flow} (request | response) — set
 * from the WSO2 policy name + the flow it was attached to. json_to_xml is exact; xml_to_json handles
 * element/text/array trees (attributes/CDATA are best-effort).
 */
public final class JsonXmlPluginBuilder {

    private JsonXmlPluginBuilder() {}

    public static final String PLUGIN_NAME = "forgeshift-json-xml";

    private static final String HANDLER_LUA = """
            local cjson = require "cjson.safe"

            local JsonXml = { PRIORITY = 770, VERSION = "1.0.0" }

            local function xml_escape(s)
              s = tostring(s)
              s = s:gsub("&", "&amp;")
              s = s:gsub("<", "&lt;")
              s = s:gsub(">", "&gt;")
              s = s:gsub('"', "&quot;")
              return s
            end

            local function to_xml(value, name)
              if type(value) == "table" then
                local n = 0
                for _ in pairs(value) do n = n + 1 end
                if n > 0 and #value == n then
                  local parts = {}
                  for i = 1, #value do parts[i] = to_xml(value[i], name) end
                  return table.concat(parts)
                end
                local parts = {}
                for k, v in pairs(value) do parts[#parts + 1] = to_xml(v, tostring(k)) end
                return "<" .. name .. ">" .. table.concat(parts) .. "</" .. name .. ">"
              end
              return "<" .. name .. ">" .. xml_escape(value) .. "</" .. name .. ">"
            end

            local function json_to_xml(body, root)
              local decoded = cjson.decode(body)
              if decoded == nil then return nil end
              return '<?xml version="1.0" encoding="UTF-8"?>' .. to_xml(decoded, root or "root")
            end

            local function xml_to_table(xml)
              local pos = 1
              local function parse(close_for)
                local node = {}
                local text = {}
                while pos <= #xml do
                  local c = xml:sub(pos, pos)
                  if c == "<" then
                    if xml:sub(pos, pos + 3) == "<!--" then
                      local e = xml:find("-->", pos, true); pos = (e or #xml) + 3
                    elseif xml:sub(pos, pos + 1) == "<?" then
                      local e = xml:find("?>", pos, true); pos = (e or #xml) + 2
                    elseif xml:sub(pos, pos + 1) == "</" then
                      local _, e = xml:find("^</%s*[%w_:%-]+%s*>", pos)
                      pos = (e or pos) + 1
                      break
                    else
                      local _, e, tag = xml:find("^<%s*([%w_:%-]+)", pos)
                      if not tag then pos = pos + 1 else
                        local gt = xml:find(">", e, true) or #xml
                        local selfclose = xml:sub(gt - 1, gt - 1) == "/"
                        pos = gt + 1
                        local val
                        if selfclose then val = "" else val = parse(tag) end
                        if node[tag] == nil then
                          node[tag] = val
                        elseif type(node[tag]) == "table" and node[tag].__list then
                          node[tag][#node[tag] + 1] = val
                        else
                          node[tag] = { __list = true, node[tag], val }
                        end
                      end
                    end
                  else
                    local nxt = xml:find("<", pos, true) or (#xml + 1)
                    text[#text + 1] = xml:sub(pos, nxt - 1)
                    pos = nxt
                  end
                end
                local has = false
                for _ in pairs(node) do has = true; break end
                if not has then
                  return (table.concat(text):gsub("^%s+", ""):gsub("%s+$", ""))
                end
                for _, v in pairs(node) do
                  if type(v) == "table" and v.__list then v.__list = nil end
                end
                return node
              end
              return parse(nil)
            end

            local function convert(body, conf)
              if conf.direction == "xml_to_json" then
                return cjson.encode(xml_to_table(body)), "application/json"
              end
              local xml = json_to_xml(body, conf.root_element)
              if xml then return xml, "application/xml" end
              return nil, nil
            end

            function JsonXml:access(conf)
              if conf.flow ~= "request" then return end
              local body = kong.request.get_raw_body()
              if not body or body == "" then return end
              local out, ctype = convert(body, conf)
              if out then
                kong.service.request.set_raw_body(out)
                kong.service.request.set_header("Content-Type", ctype)
              end
            end

            function JsonXml:response(conf)
              if conf.flow ~= "response" then return end
              local body = kong.service.response.get_raw_body()
              if not body or body == "" then return end
              local out, ctype = convert(body, conf)
              if out then
                kong.response.set_raw_body(out)
                kong.response.set_header("Content-Type", ctype)
              end
            end

            return JsonXml
            """;

    private static final String SCHEMA_LUA = """
            local typedefs = require "kong.db.schema.typedefs"

            return {
              name = "forgeshift-json-xml",
              fields = {
                { protocols = typedefs.protocols_http },
                { config = {
                    type = "record",
                    fields = {
                      { direction = { type = "string", required = true, default = "json_to_xml",
                          one_of = { "json_to_xml", "xml_to_json" } } },
                      { flow = { type = "string", required = true, default = "request",
                          one_of = { "request", "response" } } },
                      { root_element = { type = "string", required = true, default = "root" } },
                    },
                } },
              },
            }
            """;

    public static CustomPluginArtifact asset() {
        return CustomPluginArtifact.builder()
                .pluginName(PLUGIN_NAME).handlerLua(HANDLER_LUA).schemaLua(SCHEMA_LUA).build();
    }

    /** Instance config from the WSO2 policy: direction (json_to_xml | xml_to_json) + flow (request | response). */
    public static Map<String, Object> buildConfig(String direction, String flow) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("direction", direction);
        cfg.put("flow", flow);
        return cfg;
    }
}
