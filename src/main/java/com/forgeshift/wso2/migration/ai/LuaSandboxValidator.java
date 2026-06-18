package com.forgeshift.wso2.migration.ai;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defence-in-depth check on Lua that the AI (or a deterministic builder) produced.
 * The model is told about the sandbox in the system prompt, but we never trust that
 * alone — Konnect's plugin admission accepts many things that fault at request-time,
 * so we filter in Java first.
 *
 * <p>Two target modes:
 * <ul>
 *   <li>{@link #validate} — a Konnect <b>serverless</b> pre/post-function snippet
 *       (strict sandbox: {@link LuaSandboxSpec#FORBIDDEN_TOKENS} /
 *       {@link LuaSandboxSpec#ALLOWED_REQUIRES}).</li>
 *   <li>{@link #validateHandler} / {@link #validateSchema} — the two files of a
 *       Konnect <b>Dedicated Cloud</b> custom plugin (looser API surface —
 *       {@link LuaSandboxSpec#DEDICATED_FORBIDDEN_TOKENS} /
 *       {@link LuaSandboxSpec#DEDICATED_ALLOWED_REQUIRES}) plus STRUCTURAL checks that
 *       the files are shaped like a real Kong plugin.</li>
 * </ul>
 *
 * <p>Each check fast-collects: size budget, forbidden-token scan, {@code require()}
 * allowlist, and a balanced-delimiter sanity check to catch truncated output.
 */
@Component
public class LuaSandboxValidator {

    private static final Pattern REQUIRE_PATTERN = Pattern.compile("require\\s*\\(?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern RETURN_KEYWORD = Pattern.compile("\\breturn\\b");
    private static final Pattern SCHEMA_NAME = Pattern.compile("name\\s*=");
    /** A custom-plugin handler must implement at least one Kong request/response phase. */
    private static final Pattern PHASE_METHOD = Pattern.compile(
            ":(certificate|rewrite|access|header_filter|body_filter|response|log|preread"
                    + "|ws_handshake|ws_client_frame|ws_upstream_frame|ws_close)\\s*\\(");

    /** Validate a Konnect serverless pre/post-function snippet. */
    public Result validate(String lua, int maxBytes) {
        if (lua == null || lua.isBlank()) {
            return new Result(false, List.of("snippet is empty"));
        }
        List<String> violations = baseChecks(lua, maxBytes,
                LuaSandboxSpec.FORBIDDEN_TOKENS, LuaSandboxSpec.ALLOWED_REQUIRES, "snippet");
        return new Result(violations.isEmpty(), violations);
    }

    /** Validate a Konnect Dedicated Cloud custom-plugin {@code handler.lua}. */
    public Result validateHandler(String handlerLua, int maxBytes) {
        if (handlerLua == null || handlerLua.isBlank()) {
            return new Result(false, List.of("handler.lua is empty"));
        }
        List<String> violations = baseChecks(handlerLua, maxBytes,
                LuaSandboxSpec.DEDICATED_FORBIDDEN_TOKENS, LuaSandboxSpec.DEDICATED_ALLOWED_REQUIRES, "handler.lua");
        if (!handlerLua.contains("PRIORITY")) {
            violations.add("handler.lua does not set PRIORITY on the plugin table");
        }
        if (!handlerLua.contains("VERSION")) {
            violations.add("handler.lua does not set VERSION on the plugin table");
        }
        if (!PHASE_METHOD.matcher(handlerLua).find()) {
            violations.add("handler.lua defines no Kong phase handler (e.g. function P:access(conf) ... end)");
        }
        if (!RETURN_KEYWORD.matcher(handlerLua).find()) {
            violations.add("handler.lua does not return the plugin table");
        }
        return new Result(violations.isEmpty(), violations);
    }

    /** Validate a Konnect Dedicated Cloud custom-plugin {@code schema.lua}. */
    public Result validateSchema(String schemaLua, int maxBytes) {
        if (schemaLua == null || schemaLua.isBlank()) {
            return new Result(false, List.of("schema.lua is empty"));
        }
        List<String> violations = baseChecks(schemaLua, maxBytes,
                LuaSandboxSpec.DEDICATED_FORBIDDEN_TOKENS, LuaSandboxSpec.DEDICATED_ALLOWED_REQUIRES, "schema.lua");
        if (!SCHEMA_NAME.matcher(schemaLua).find()) {
            violations.add("schema.lua does not declare a plugin name (name = \"...\")");
        }
        if (!schemaLua.contains("fields")) {
            violations.add("schema.lua declares no fields table");
        }
        if (!RETURN_KEYWORD.matcher(schemaLua).find()) {
            violations.add("schema.lua does not return the schema table");
        }
        return new Result(violations.isEmpty(), violations);
    }

    /** Size budget + forbidden-token scan + require() allowlist + balanced-delimiter sanity. */
    private static List<String> baseChecks(String lua, int maxBytes,
                                           List<String> forbidden, List<String> allowedRequires, String label) {
        List<String> violations = new ArrayList<>();

        int bytes = lua.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            violations.add(label + " is " + bytes + " bytes, exceeds max " + maxBytes);
        }

        for (String token : forbidden) {
            if (lua.contains(token)) {
                violations.add("uses forbidden token: " + token);
            }
        }

        Matcher m = REQUIRE_PATTERN.matcher(lua);
        while (m.find()) {
            String mod = m.group(1);
            if (!allowedRequires.contains(mod)) {
                violations.add("require('" + mod + "') is not in the sandbox allowlist");
            }
        }

        int openParen = count(lua, '(');
        int closeParen = count(lua, ')');
        if (openParen != closeParen) {
            violations.add("unbalanced parentheses (" + openParen + " open vs "
                    + closeParen + " close) — likely truncated output");
        }

        int doCount = countWord(lua, "do");
        int endCount = countWord(lua, "end");
        // function/if blocks use 'end' without 'do' too — only fail when there are
        // clearly more `do`s than `end`s, never the reverse.
        if (doCount > endCount) {
            violations.add("more 'do' than 'end' keywords (" + doCount + " vs " + endCount
                    + ") — possible truncated block");
        }

        return violations;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static int countWord(String s, String word) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * @param valid      true when the snippet passes every rule
     * @param violations human-readable failure reasons; empty when valid
     */
    public record Result(boolean valid, List<String> violations) {
    }
}
