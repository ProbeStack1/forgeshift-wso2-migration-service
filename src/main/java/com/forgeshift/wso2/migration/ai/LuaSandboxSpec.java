package com.forgeshift.wso2.migration.ai;

import java.util.List;

/**
 * Defines the Lua API surface that Kong Konnect <em>serverless</em> pre-function
 * and post-function plugins are permitted to use. The Konnect serverless sandbox
 * is far stricter than self-managed Kong's pre-function plugin:
 *
 * <ul>
 *   <li>No filesystem I/O (io.*, package.path)</li>
 *   <li>No process control (os.execute, os.exit)</li>
 *   <li>No cosocket TCP/UDP (ngx.socket.tcp/udp)</li>
 *   <li>No arbitrary require() — only a small set of safe modules</li>
 *   <li>No FFI — luajit ffi.* is blocked</li>
 *   <li>No global mutation — snippets share state only through {@code kong.ctx.shared}</li>
 * </ul>
 *
 * <p>{@link MediationPolicyAiTranslator} pastes this list into the system prompt so
 * Claude only emits portable code, and {@link LuaSandboxValidator} re-checks the
 * output before it ever reaches Kong. Keep ALLOWED/FORBIDDEN aligned with
 * Konnect's documented sandbox.
 */
public final class LuaSandboxSpec {

    private LuaSandboxSpec() {
    }

    /** Top-level Lua namespaces and PDK functions that the sandbox permits. */
    public static final List<String> ALLOWED_NAMESPACES = List.of(
            // Kong PDK — request/response/service/log/ctx
            "kong.request.",
            "kong.response.",
            "kong.service.request.",
            "kong.service.response.",
            "kong.client.",
            "kong.ctx.shared",
            "kong.ctx.plugin",
            "kong.log.",
            "kong.node.",
            "kong.router.",
            // Safe OpenResty surface
            "ngx.req.",
            "ngx.var.",
            "ngx.header",
            "ngx.status",
            "ngx.now",
            "ngx.time",
            "ngx.encode_base64",
            "ngx.decode_base64",
            "ngx.encode_args",
            "ngx.decode_args",
            "ngx.escape_uri",
            "ngx.unescape_uri",
            "ngx.hmac_sha1",
            "ngx.md5",
            "ngx.sha1_bin",
            "ngx.crc32_long",
            "ngx.re.match",
            "ngx.re.gmatch",
            "ngx.re.find",
            "ngx.re.sub",
            "ngx.re.gsub",
            // Lua stdlib (string/table/math)
            "string.",
            "table.",
            "math.",
            "os.time",
            "os.date",
            "tostring",
            "tonumber",
            "pairs",
            "ipairs",
            "type",
            "pcall",
            "xpcall",
            "error",
            "select",
            "unpack",
            "next");

    /** Modules that may appear inside {@code require(...)}. */
    public static final List<String> ALLOWED_REQUIRES = List.of(
            "cjson",
            "cjson.safe",
            "resty.sha256",
            "resty.string",
            "resty.aes",
            "resty.hmac");

    /**
     * Hard-forbidden tokens. If any of these substrings appear in the generated
     * Lua, the validator rejects the snippet — Konnect serverless will either
     * refuse the config or fault at runtime. Order matters for diagnostics: the
     * validator reports the first match.
     */
    public static final List<String> FORBIDDEN_TOKENS = List.of(
            "io.open",
            "io.read",
            "io.write",
            "io.lines",
            "io.popen",
            "os.execute",
            "os.exit",
            "os.remove",
            "os.rename",
            "os.tmpname",
            "package.path",
            "package.cpath",
            "package.loadlib",
            "loadfile",
            "dofile",
            "loadstring",
            "load(",                    // dynamic code-loading from a string
            "debug.",                   // debug library — bypasses sandbox
            "ffi.",                     // LuaJIT FFI — host process access
            "ngx.socket.tcp",
            "ngx.socket.udp",
            "ngx.thread.spawn",
            "ngx.timer.at",             // background timers — not allowed in pre-function
            "ngx.timer.every",
            "ngx.shared.",              // shared dict access blocked in serverless
            "ngx.location.capture",     // subrequests blocked in serverless
            "ngx.exec",
            "rawset",
            "rawget",
            "setfenv",
            "getfenv",
            "_G[",                      // global-table indexing
            "_ENV");

    /** Compose the human-readable allowlist block the LLM sees in its system prompt. */
    public static String allowlistDocBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("ALLOWED Kong/OpenResty/Lua APIs in Konnect serverless pre/post-function sandbox:\n");
        for (String s : ALLOWED_NAMESPACES) sb.append("  ").append(s).append("\n");
        sb.append("\nALLOWED require() modules:\n");
        for (String s : ALLOWED_REQUIRES) sb.append("  ").append(s).append("\n");
        sb.append("\nFORBIDDEN — do not emit any of these tokens:\n");
        for (String s : FORBIDDEN_TOKENS) sb.append("  ").append(s).append("\n");
        return sb.toString();
    }
}
