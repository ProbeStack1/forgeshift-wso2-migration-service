package com.forgeshift.wso2.migration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeshift.wso2.migration.config.AnthropicProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Target 2: translates one WSO2 mediation sequence (Synapse XML) into a Kong <b>custom plugin</b>
 * — two self-contained Lua files (handler.lua + schema.lua) — for a Konnect Dedicated Cloud data
 * plane, using Anthropic Claude.
 *
 * <p>The sibling of {@link MediationPolicyAiTranslator}: it reuses the same Claude plumbing,
 * {@link AnthropicProperties}, confidence gate, and JSON-extraction approach, but emits a real
 * plugin instead of a serverless pre/post-function snippet. Output is validated by
 * {@link LuaSandboxValidator#validateHandler}/{@link LuaSandboxValidator#validateSchema} (the
 * DEDICATED sandbox) before it is ever deemed usable. A fully-populated {@link AiTranslationResult}
 * (always {@code mode=CUSTOM_PLUGIN}) is returned on every path so callers just check
 * {@link AiTranslationResult#isUsableCustomPlugin()}.
 *
 * <p>Disabled (returns {@code translatable=false}) unless {@code anthropic.api-key} is configured.
 */
@Service
public class CustomPluginLuaGenerator {

    private static final Logger log = LoggerFactory.getLogger(CustomPluginLuaGenerator.class);

    private final AnthropicProperties props;
    private final LuaSandboxValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;

    public CustomPluginLuaGenerator(AnthropicProperties props, LuaSandboxValidator validator) {
        this.props = props;
        this.validator = validator;

        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getConnectTimeoutMs())
                .doOnConnected(c -> c.addHandlerLast(
                        new ReadTimeoutHandler(props.getReadTimeoutMs(), TimeUnit.MILLISECONDS)));

        this.webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .defaultHeader("anthropic-version", props.getApiVersion())
                .defaultHeader("content-type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /**
     * Generate a custom plugin for one mediation sequence.
     *
     * @param pluginName  the exact Kong plugin name the asset will register under; schema.lua's
     *                    {@code name} MUST equal this (validated).
     * @param policyName  the WSO2 sequence name (logging + prompt).
     * @param synapseXml  the Synapse XML; null/blank → translatable=false.
     * @param contextHint free-form context for the model. May be null.
     */
    public AiTranslationResult generate(String pluginName, String policyName,
                                        String synapseXml, String contextHint) {
        if (!isEnabled()) {
            return notTranslatable("anthropic.api-key not configured — AI custom-plugin generation disabled");
        }
        if (synapseXml == null || synapseXml.isBlank()) {
            return notTranslatable("no Synapse XML body found for mediation policy '" + policyName + "'");
        }
        try {
            String json = callClaude(pluginName, policyName, synapseXml, contextHint);
            AiTranslationResult parsed = parse(json);

            // DEDICATED sandbox + structural checks on BOTH files — model output is never trusted blindly.
            if (parsed.isTranslatable() && parsed.getHandlerLua() != null && parsed.getSchemaLua() != null) {
                List<String> violations = new ArrayList<>();
                violations.addAll(validator.validateHandler(parsed.getHandlerLua(), props.getMaxLuaBytes()).violations());
                violations.addAll(validator.validateSchema(parsed.getSchemaLua(), props.getMaxLuaBytes()).violations());
                if (!parsed.getSchemaLua().contains(pluginName)) {
                    violations.add("schema.lua name does not match the required plugin name '" + pluginName + "'");
                }
                parsed.setValid(violations.isEmpty());
                parsed.setViolations(violations);
                if (!violations.isEmpty()) {
                    log.warn("AI custom-plugin generation for '{}' failed validation: {}", policyName, violations);
                }
            }

            // Confidence gate — drop low-confidence output even if it parses + validates.
            if (parsed.isUsableCustomPlugin() && parsed.getConfidence() < props.getMinConfidence()) {
                log.info("AI custom-plugin generation for '{}' below confidence threshold ({} < {}) — discarding",
                        policyName, parsed.getConfidence(), props.getMinConfidence());
                parsed.setValid(false);
                List<String> v = new ArrayList<>(parsed.getViolations());
                v.add("confidence " + parsed.getConfidence() + " < threshold " + props.getMinConfidence());
                parsed.setViolations(v);
            }
            return parsed;

        } catch (Exception e) {
            log.warn("AI custom-plugin generation for '{}' errored: {}", policyName, e.toString());
            return notTranslatable("Claude call failed: " + e.getMessage());
        }
    }

    private static AiTranslationResult notTranslatable(String reason) {
        return AiTranslationResult.builder()
                .translatable(false).valid(false).mode(TargetMode.CUSTOM_PLUGIN).reason(reason).build();
    }

    // ── Anthropic call ────────────────────────────────────────────────────────

    private String callClaude(String pluginName, String policyName, String synapseXml, String contextHint) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("max_tokens", props.getMaxTokens());
        body.put("temperature", props.getTemperature());

        ArrayNode systemArr = mapper.createArrayNode();
        ObjectNode sysBlock = systemArr.addObject();
        sysBlock.put("type", "text");
        sysBlock.put("text", systemPrompt());
        sysBlock.set("cache_control", mapper.createObjectNode().put("type", "ephemeral"));
        body.set("system", systemArr);

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt(pluginName, policyName, synapseXml, contextHint));
        body.set("messages", messages);

        return webClient.post()
                .uri("/v1/messages")
                .header("x-api-key", props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .block();
    }

    private String systemPrompt() {
        return ""
                + "You are an expert API gateway migration assistant. You translate a WSO2 API Manager\n"
                + "mediation sequence (Synapse XML) into a Kong CUSTOM PLUGIN: two self-contained Lua files,\n"
                + "handler.lua and schema.lua, that run on a Konnect Dedicated Cloud data plane.\n\n"
                + LuaSandboxSpec.dedicatedAllowlistDocBlock() + "\n"
                + "handler.lua: define a plugin table with an integer PRIORITY and a string VERSION, implement\n"
                + "ONLY the Kong phase methods you need (function P:access(conf) / P:header_filter(conf) /\n"
                + "P:body_filter(conf) / P:log(conf)), read/write via the Kong PDK, and `return` the table.\n"
                + "schema.lua: `local typedefs = require \"kong.db.schema.typedefs\"` then return a table\n"
                + "{ name = \"<PLUGIN_NAME>\", fields = { { protocols = typedefs.protocols_http },\n"
                + "  { config = { type = \"record\", fields = { ... } } } } }. Put any tunable values from the\n"
                + "sequence into config fields WITH sensible defaults (so the migration can fill them).\n"
                + "The plugin name MUST be EXACTLY the value given in the user message.\n\n"
                + "Synapse → Kong PDK mapping:\n"
                + "  - <property name=\"X\" value=\"Y\" scope=\"transport\"/> IN  → kong.service.request.set_header(\"X\",\"Y\")\n"
                + "  - same on the OUT/response flow → kong.response.set_header(\"X\",\"Y\")\n"
                + "  - <property ... action=\"remove\" scope=\"transport\"/> → kong.service.request.clear_header(\"X\")\n"
                + "  - <log> with <property> children → kong.log.info(...) building the same fields\n"
                + "  - <payloadFactory>/body replace → kong.service.request.set_raw_body / kong.response.set_raw_body\n"
                + "  - <filter>/<switch> → Lua if/then/end on request/response data\n"
                + "  - <makefault>/fault handling → kong.response.exit(status, body, headers)\n"
                + "  - external call-out you can implement with resty.http → require \"resty.http\" (allowed)\n\n"
                + "Return translatable=false (+reason) when the sequence needs a custom Java mediator (<class>),\n"
                + "XSLT/XQuery (<xslt>/<xquery>), database access (<dblookup>/<dbreport>), an unseen referenced\n"
                + "sequence (<sequence key=..>), or anything you cannot implement with confidence >= 0.7.\n\n"
                + "Output — return EXACTLY ONE fenced JSON code block, nothing else:\n"
                + "```json\n"
                + "{\n"
                + "  \"translatable\": true|false,\n"
                + "  \"plugin_name\": \"the exact plugin name from the user message\",\n"
                + "  \"handler_lua\": \"the COMPLETE handler.lua source\",\n"
                + "  \"schema_lua\": \"the COMPLETE schema.lua source\",\n"
                + "  \"confidence\": 0.0-1.0,\n"
                + "  \"unsupported_apis\": [\"Synapse mediators you could not represent\"],\n"
                + "  \"reason\": \"present only when translatable=false — one sentence why\",\n"
                + "  \"notes\": \"any caveats reviewers should see\"\n"
                + "}\n"
                + "```\n";
    }

    private String userPrompt(String pluginName, String policyName, String synapseXml, String contextHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plugin name (use EXACTLY this for handler + schema name): ").append(pluginName).append("\n");
        sb.append("WSO2 mediation policy / sequence: ").append(policyName).append("\n");
        if (contextHint != null && !contextHint.isBlank()) {
            sb.append("Migration context: ").append(contextHint).append("\n");
        }
        sb.append("\nSynapse XML:\n```xml\n").append(synapseXml).append("\n```\n");
        sb.append("\nReturn the JSON object now.\n");
        return sb.toString();
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    /** Parse the Anthropic API response envelope into an AiTranslationResult. Package-private for tests. */
    AiTranslationResult parse(String anthropicJson) throws Exception {
        JsonNode root = mapper.readTree(anthropicJson);
        JsonNode contentArr = root.path("content");
        if (!contentArr.isArray() || contentArr.isEmpty()) {
            return notTranslatable("empty response from Claude");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : contentArr) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText()).append("\n");
            }
        }
        String inner = extractJsonBlock(text.toString());
        if (inner == null) {
            return AiTranslationResult.builder()
                    .translatable(false).valid(false).mode(TargetMode.CUSTOM_PLUGIN)
                    .reason("Claude response had no JSON block").notes(text.toString()).build();
        }

        JsonNode obj = mapper.readTree(inner);
        List<String> unsupported = new ArrayList<>();
        if (obj.path("unsupported_apis").isArray()) {
            obj.path("unsupported_apis").forEach(n -> unsupported.add(n.asText()));
        }
        return AiTranslationResult.builder()
                .translatable(obj.path("translatable").asBoolean(false))
                .mode(TargetMode.CUSTOM_PLUGIN)
                .confidence(obj.path("confidence").asDouble(0.0))
                .handlerLua(obj.path("handler_lua").asText(null))
                .schemaLua(obj.path("schema_lua").asText(null))
                .unsupportedApis(unsupported)
                .reason(obj.path("reason").asText(null))
                .notes(obj.path("notes").asText(null))
                .build();
    }

    /** Pull the JSON between the first ```json fence and its closer; fall back to the first balanced {...}. */
    private String extractJsonBlock(String s) {
        int start = s.indexOf("```json");
        if (start >= 0) {
            int bodyStart = s.indexOf('\n', start);
            int end = s.indexOf("```", bodyStart + 1);
            if (bodyStart > 0 && end > bodyStart) {
                return s.substring(bodyStart + 1, end).trim();
            }
        }
        int brace = s.indexOf('{');
        int closing = s.lastIndexOf('}');
        if (brace >= 0 && closing > brace) {
            return s.substring(brace, closing + 1);
        }
        return null;
    }
}
