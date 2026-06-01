package com.forgeshift.wso2.migration.ai;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defence-in-depth check on Lua snippets the AI produced. The model is told about
 * the sandbox in the system prompt, but we never trust that alone — Konnect's
 * plugin config admission accepts many things that fault at request-time, so we
 * filter in Java first.
 *
 * <p>Validation steps (fast-fail in order):
 * <ol>
 *   <li>Size budget — refuses anything beyond max-lua-bytes.</li>
 *   <li>Forbidden tokens — substring scan against {@link LuaSandboxSpec#FORBIDDEN_TOKENS}.</li>
 *   <li>require() scan — every {@code require("x")} must be in {@link LuaSandboxSpec#ALLOWED_REQUIRES}.</li>
 *   <li>Balanced delims — quick sanity check on parentheses + do/end pairs to catch truncations.</li>
 * </ol>
 */
@Component
public class LuaSandboxValidator {

    private static final Pattern REQUIRE_PATTERN = Pattern.compile("require\\s*\\(?\\s*[\"']([^\"']+)[\"']");

    public Result validate(String lua, int maxBytes) {
        List<String> violations = new ArrayList<>();

        if (lua == null || lua.isBlank()) {
            return new Result(false, List.of("snippet is empty"));
        }

        int bytes = lua.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            violations.add("snippet is " + bytes + " bytes, exceeds max " + maxBytes
                    + " — translate to an upstream service instead of pre/post-function");
        }

        for (String forbidden : LuaSandboxSpec.FORBIDDEN_TOKENS) {
            if (lua.contains(forbidden)) {
                violations.add("uses forbidden token: " + forbidden);
            }
        }

        Matcher m = REQUIRE_PATTERN.matcher(lua);
        while (m.find()) {
            String mod = m.group(1);
            if (!LuaSandboxSpec.ALLOWED_REQUIRES.contains(mod)) {
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

        return new Result(violations.isEmpty(), violations);
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
