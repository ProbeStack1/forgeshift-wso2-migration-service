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
 * Translates one WSO2 mediation sequence (Synapse XML) into a Kong Konnect
 * serverless Lua snippet using Anthropic Claude.
 *
 * <p>Mirrors the Apigee migration service's {@code AiPolicyTranslator} but the
 * input is Synapse XML (not JavaScript) and the prompt maps Synapse mediators to
 * Kong Lua. The robustness scaffolding is identical and is the whole point:
 * <ul>
 *   <li>The output is requested as strict JSON so it parses reliably.</li>
 *   <li>Every translation is gated by {@link LuaSandboxValidator} — the model's
 *       allowlist adherence is good but not perfect, and the validator is the
 *       source of truth for what reaches Konnect.</li>
 *   <li>A confidence gate discards low-confidence Lua even if it parses.</li>
 *   <li>A fully-populated {@link AiTranslationResult} is returned on every path
 *       (HTTP error, parse error, low confidence) so callers never handle
 *       exceptions — they just check {@link AiTranslationResult#isUsableLua()}.</li>
 * </ul>
 *
 * Disabled (returns {@code translatable=false}) unless {@code anthropic.api-key}
 * is configured.
 */
@Service
public class MediationPolicyAiTranslator {

    private static final Logger log = LoggerFactory.getLogger(MediationPolicyAiTranslator.class);

    private final AnthropicProperties props;
    private final LuaSandboxValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;

    public MediationPolicyAiTranslator(AnthropicProperties props, LuaSandboxValidator validator) {
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
     * Translate one mediation sequence.
     *
     * @param policyName  the WSO2 sequence / mediation policy name (logging + prompt)
     * @param synapseXml  the Synapse XML of the sequence. May be null/blank — then we
     *                    return translatable=false rather than guessing.
     * @param flowHint    "in" / "out" / "fault" — the flow the sequence runs in.
     * @param contextHint free-form context for the model (e.g. "runs after the jwt
     *                    plugin, so kong.client.get_consumer() is populated"). May be null.
     */
    public AiTranslationResult translate(String policyName, String synapseXml,
                                         String flowHint, String contextHint) {
        if (!isEnabled()) {
            return AiTranslationResult.builder()
                    .translatable(false).valid(false)
                    .reason("anthropic.api-key not configured — AI translation disabled")
                    .build();
        }
        if (synapseXml == null || synapseXml.isBlank()) {
            return AiTranslationResult.builder()
                    .translatable(false).valid(false)
                    .reason("no Synapse XML body found for mediation policy '" + policyName + "'")
                    .build();
        }

        try {
            String json = callClaude(policyName, synapseXml, flowHint, contextHint);
            AiTranslationResult parsed = parse(json, flowHint);

            // Sandbox check — model output is never trusted blindly.
            if (parsed.isTranslatable() && parsed.getLua() != null) {
                LuaSandboxValidator.Result v = validator.validate(parsed.getLua(), props.getMaxLuaBytes());
                parsed.setValid(v.valid());
                parsed.setViolations(v.violations());
                if (!v.valid()) {
                    log.warn("AI translation for mediation '{}' failed sandbox: {}", policyName, v.violations());
                }
            }

            // Confidence gate — drop low-confidence output even if it parses + validates.
            if (parsed.isUsableLua() && parsed.getConfidence() < props.getMinConfidence()) {
                log.info("AI translation for mediation '{}' below confidence threshold ({} < {}) — discarding Lua",
                        policyName, parsed.getConfidence(), props.getMinConfidence());
                parsed.setValid(false);
                List<String> v = new ArrayList<>(parsed.getViolations());
                v.add("confidence " + parsed.getConfidence() + " < threshold " + props.getMinConfidence());
                parsed.setViolations(v);
            }
            return parsed;

        } catch (Exception e) {
            log.warn("AI translation for mediation '{}' errored: {}", policyName, e.toString());
            return AiTranslationResult.builder()
                    .translatable(false).valid(false)
                    .reason("Claude call failed: " + e.getMessage())
                    .build();
        }
    }

    // ── Anthropic call ────────────────────────────────────────────────────────

    private String callClaude(String policyName, String synapseXml, String flowHint, String contextHint) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("max_tokens", props.getMaxTokens());
        body.put("temperature", props.getTemperature());

        // System prompt — cacheable; identical across every translation in a run.
        ArrayNode systemArr = mapper.createArrayNode();
        ObjectNode sysBlock = systemArr.addObject();
        sysBlock.put("type", "text");
        sysBlock.put("text", systemPrompt());
        sysBlock.set("cache_control", mapper.createObjectNode().put("type", "ephemeral"));
        body.set("system", systemArr);

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt(policyName, synapseXml, flowHint, contextHint));
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
                + "You are an expert API gateway migration assistant. You translate WSO2 API Manager\n"
                + "mediation sequences (Synapse XML) into Lua snippets that run inside Kong Konnect\n"
                + "serverless pre-function or post-function plugins.\n\n"
                + "Konnect serverless does NOT support uploading custom plugins. The ONLY way to run\n"
                + "custom code is through pre-function / post-function snippets attached to Kong phases:\n"
                + "certificate, rewrite, access, header_filter, body_filter, log. Snippets in the same\n"
                + "phase share state via kong.ctx.shared.\n\n"
                + LuaSandboxSpec.allowlistDocBlock() + "\n"
                + "Synapse → Kong translation rules:\n"
                + "  - <property name=\"X\" value=\"Y\" scope=\"transport\"/> on the IN flow →\n"
                + "      kong.service.request.set_header(\"X\", \"Y\"); on the OUT flow → kong.response.set_header(\"X\", \"Y\")\n"
                + "  - <property name=\"X\" value=\"Y\"/> (synapse/default scope, a flow variable) → kong.ctx.shared.X = \"Y\"\n"
                + "  - <property name=\"X\" action=\"remove\" scope=\"transport\"/> → kong.service.request.clear_header(\"X\")\n"
                + "  - <header name=\"X\" value=\"Y\"/> → set header for the current flow\n"
                + "  - <log .../> with <property> children → kong.log.info(...) building the same fields\n"
                + "  - <payloadFactory> (replace body) → on IN flow kong.service.request.set_raw_body(...),\n"
                + "      on OUT flow buffer/replace in body_filter via kong.response.set_raw_body(...)\n"
                + "  - <filter source=.. regex=..> / <switch> → translate to Lua if/then/end using request data\n"
                + "  - <script language=\"js\"> → translate the JavaScript logic to equivalent Lua\n"
                + "  - <enrich> that copies headers/body → header/body manipulation as above\n"
                + "  - <makefault>/fault handling → kong.response.exit(status, body, headers)\n\n"
                + "Return translatable=false (and a reason + external_service_stub) when the sequence uses:\n"
                + "  - <send>, <call>, <callout>, <send receive=..>  (external service callout)\n"
                + "  - <class name=..>  (custom Java mediator)\n"
                + "  - <xslt>, <xquery>, <fastXSLT>  (XSLT/XQuery transforms)\n"
                + "  - <dblookup>, <dbreport>  (database access)\n"
                + "  - <sequence key=..>  referencing another sequence you cannot see\n"
                + "  - <throttle> → recommend a Kong rate-limiting plugin instead (not Lua)\n"
                + "  - <cache> → recommend a Kong proxy-cache plugin instead (not Lua)\n"
                + "  - any FORBIDDEN token above, or anything you cannot translate with confidence >= 0.7\n\n"
                + "Output format — return EXACTLY ONE fenced JSON code block, nothing else:\n"
                + "```json\n"
                + "{\n"
                + "  \"translatable\": true|false,\n"
                + "  \"phase\": \"access|header_filter|body_filter|log\",\n"
                + "  \"post_function\": true|false,\n"
                + "  \"confidence\": 0.0-1.0,\n"
                + "  \"lua\": \"-- the Lua snippet without an enclosing pcall, ready to splice into the pre/post-function config array\",\n"
                + "  \"unsupported_apis\": [\"list of Synapse mediators you could not represent\"],\n"
                + "  \"reason\": \"present only when translatable=false — one sentence why\",\n"
                + "  \"external_service_stub\": \"present only when translatable=false — a minimal Spring Boot Java controller skeleton implementing the same logic, for the user to deploy and route to from Kong\",\n"
                + "  \"notes\": \"any caveats reviewers should see\"\n"
                + "}\n"
                + "```\n";
    }

    private String userPrompt(String policyName, String synapseXml, String flowHint, String contextHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("WSO2 mediation policy / sequence: ").append(policyName).append("\n");
        sb.append("Flow (in|out|fault): ").append(flowHint == null ? "in" : flowHint).append("\n");
        if (contextHint != null && !contextHint.isBlank()) {
            sb.append("Migration context: ").append(contextHint).append("\n");
        }
        sb.append("\nSynapse XML:\n```xml\n").append(synapseXml).append("\n```\n");
        sb.append("\nReturn the JSON object now.\n");
        return sb.toString();
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private AiTranslationResult parse(String anthropicJson, String flowHint) throws Exception {
        JsonNode root = mapper.readTree(anthropicJson);
        JsonNode contentArr = root.path("content");
        if (!contentArr.isArray() || contentArr.isEmpty()) {
            return AiTranslationResult.builder()
                    .translatable(false).valid(false)
                    .reason("empty response from Claude")
                    .build();
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
                    .translatable(false).valid(false)
                    .reason("Claude response had no JSON block")
                    .notes(text.toString())
                    .build();
        }

        JsonNode obj = mapper.readTree(inner);
        boolean translatable = obj.path("translatable").asBoolean(false);

        List<String> unsupported = new ArrayList<>();
        if (obj.path("unsupported_apis").isArray()) {
            obj.path("unsupported_apis").forEach(n -> unsupported.add(n.asText()));
        }

        return AiTranslationResult.builder()
                .translatable(translatable)
                .phase(obj.path("phase").asText(defaultPhase(flowHint, obj.path("post_function").asBoolean(false))))
                .postFunction(obj.path("post_function").asBoolean(false))
                .confidence(obj.path("confidence").asDouble(0.0))
                .lua(obj.path("lua").asText(null))
                .unsupportedApis(unsupported)
                .reason(obj.path("reason").asText(null))
                .externalServiceStub(obj.path("external_service_stub").asText(null))
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

    private String defaultPhase(String flowHint, boolean postFn) {
        if (postFn) return "header_filter";
        return "in".equalsIgnoreCase(flowHint) || "request".equalsIgnoreCase(flowHint) || flowHint == null
                ? "access"
                : "header_filter";
    }
}
