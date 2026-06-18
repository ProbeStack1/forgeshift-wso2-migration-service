package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.CustomPluginArtifact;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-built Lua plugin for the known WSO2 custom JavaScript fraud/risk-scoring script mediator —
 * scores a transaction from its amount + channel, stamps {@code X-Risk-Level}, and rejects high-risk
 * high-value transactions with a 403, exactly like the original JS. NO AI: an engineer wrote + reviewed
 * this Lua once; the migrator's catalog ({@link KnownCustomMediatorTranslator}) applies it when it
 * recognises the risk-scoring script, filling the thresholds it parses out of the script.
 */
public final class RiskScoringPluginBuilder {

    private RiskScoringPluginBuilder() {}

    public static final String PLUGIN_NAME = "forgeshift-risk-scoring";

    private static final String HANDLER_LUA = """
            local RiskScoring = { PRIORITY = 800, VERSION = "1.0.0" }

            function RiskScoring:access(conf)
              local amount = tonumber(kong.request.get_header(conf.amount_header)) or 0
              local channel = kong.request.get_header(conf.channel_header) or "unknown"

              local risk = "low"
              if amount >= conf.high_amount or channel == "web" then
                risk = "high"
              elseif amount >= conf.medium_amount then
                risk = "medium"
              end

              kong.service.request.set_header(conf.risk_header, risk)

              if risk == "high" and amount >= conf.block_amount then
                return kong.response.exit(403, { error = "Transaction blocked: risk threshold exceeded" })
              end
            end

            return RiskScoring
            """;

    private static final String SCHEMA_LUA = """
            local typedefs = require "kong.db.schema.typedefs"

            return {
              name = "forgeshift-risk-scoring",
              fields = {
                { protocols = typedefs.protocols_http },
                { config = {
                    type = "record",
                    fields = {
                      { amount_header = { type = "string", required = true, default = "X-Txn-Amount" } },
                      { channel_header = { type = "string", required = true, default = "X-Channel" } },
                      { risk_header = { type = "string", required = true, default = "X-Risk-Level" } },
                      { medium_amount = { type = "number", required = true, default = 2000 } },
                      { high_amount = { type = "number", required = true, default = 10000 } },
                      { block_amount = { type = "number", required = true, default = 50000 } },
                    },
                } },
              },
            }
            """;

    public static CustomPluginArtifact asset() {
        return CustomPluginArtifact.builder()
                .pluginName(PLUGIN_NAME).handlerLua(HANDLER_LUA).schemaLua(SCHEMA_LUA).build();
    }

    /** Instance config from the thresholds parsed out of the script (nulls fall back to schema defaults). */
    public static Map<String, Object> buildConfig(Integer mediumAmount, Integer highAmount, Integer blockAmount) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (mediumAmount != null) cfg.put("medium_amount", mediumAmount);
        if (highAmount != null) cfg.put("high_amount", highAmount);
        if (blockAmount != null) cfg.put("block_amount", blockAmount);
        return cfg;
    }
}
