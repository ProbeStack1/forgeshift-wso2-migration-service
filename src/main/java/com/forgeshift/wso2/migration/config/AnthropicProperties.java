package com.forgeshift.wso2.migration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Anthropic (Claude) API used by the mediation-policy AI
 * translator.
 *
 * <p>The translator is <b>off</b> unless {@code anthropic.api-key} is set — so the
 * migration service keeps working out-of-the-box (mediation policies just come
 * back as "needs manual review") when no key is provisioned. The key is
 * intentionally NOT hardcoded; supply it via config / secret / env var:
 * {@code anthropic.api-key=...} or {@code ANTHROPIC_API_KEY}.
 *
 * <pre>
 *   anthropic.api-key=                            # required to enable translation
 *   anthropic.base-url=https://api.anthropic.com
 *   anthropic.model=claude-sonnet-4-6
 *   anthropic.api-version=2023-06-01
 *   anthropic.max-tokens=4096
 *   anthropic.temperature=0.0                     # deterministic translation
 *   anthropic.connect-timeout-ms=10000
 *   anthropic.read-timeout-ms=60000
 *   anthropic.min-confidence=0.7                  # drop below this → manual fallback
 *   anthropic.max-lua-bytes=51200                 # 50 KiB cap per translated snippet
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "anthropic")
public class AnthropicProperties {

    /**
     * Master AI switch — OFF by default. While off, ALL AI translation is disabled regardless of the
     * key, and the migration runs purely on the deterministic (rule-based) translators. Flip to true
     * (and supply {@code api-key}) to re-enable AI in the future: {@code anthropic.enabled=true}.
     */
    private boolean enabled = false;

    /** Empty by default → AI translation disabled until a key is configured. Never hardcode a key here. */
    private String apiKey = "";
    private String baseUrl = "https://api.anthropic.com";
    private String model = "claude-sonnet-4-6";
    private String apiVersion = "2023-06-01";
    private int maxTokens = 4096;
    private double temperature = 0.0;
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 60_000;

    /** Below this self-reported confidence we discard the AI Lua and fall back to manual review. */
    private double minConfidence = 0.7;

    /** Hard cap on generated Lua size — Konnect serverless plugin config has a per-entity size budget. */
    private int maxLuaBytes = 51_200;

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
