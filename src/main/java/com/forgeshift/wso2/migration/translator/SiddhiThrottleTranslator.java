package com.forgeshift.wso2.migration.translator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DETERMINISTIC (no-AI) recognizer for the ONE canonical WSO2 custom-throttling Siddhi idiom —
 * a single {@code timeBatch(<n> <unit>)} window plus a {@code count(...) >= <limit>} threshold —
 * mapping it to a Kong built-in {@code rate-limiting} config.
 *
 * <p>Example WSO2 custom-policy Siddhi:
 * <pre>
 *   FROM EligibilityStream[isEligible==true]#throttler:timeBatch(1 min)
 *   SELECT throttleKey, (count(messageID) &gt;= 5) as isThrottled, ...
 *   INSERT ALL EVENTS INTO ResultStream;
 * </pre>
 * → {@code rate-limiting { minute = 5 }}.
 *
 * <p>Deliberately narrow: only a SINGLE window + a SINGLE count threshold whose window is one of the
 * Kong-expressible periods (second/minute/hour/day, incl. the 60s→minute / 60m→hour / 24h→day
 * normalisations) is recognised. Anything with joins, multiple windows, multiple counts, or a window
 * Kong can't express returns empty → the caller keeps it as a manual-review warning rather than
 * mistranslating arbitrary stream logic. Built-in plugin output, so it works on ANY control plane.
 */
public final class SiddhiThrottleTranslator {

    private SiddhiThrottleTranslator() {}

    private static final Pattern TIME_BATCH = Pattern.compile(
            "timeBatch\\s*\\(\\s*(\\d+)\\s*(seconds?|sec|minutes?|min|hours?|days?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_THRESHOLD = Pattern.compile(
            "count\\s*\\([^)]*\\)\\s*(?:>=|>)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private record Parsed(long limit, long windowValue, String unit) {}

    /** Parse the canonical single-window/single-count idiom; null when it isn't recognised. */
    private static Parsed parse(String siddhiQuery) {
        if (siddhiQuery == null || siddhiQuery.isBlank()) return null;
        // Exactly one window and one count — more than one means joins / multi-tier logic we won't guess.
        Long window = singleMatchLong(TIME_BATCH, siddhiQuery, 1);
        String unitRaw = singleMatchGroup(TIME_BATCH, siddhiQuery, 2);
        Long limit = singleMatchLong(COUNT_THRESHOLD, siddhiQuery, 1);
        if (window == null || unitRaw == null || limit == null) return null;
        return new Parsed(limit, window, unitRaw.toLowerCase());
    }

    /**
     * @return a Kong {@code rate-limiting} config map, or empty when the Siddhi isn't the canonical
     *         idiom OR its window can't be expressed exactly as a Kong period (e.g. 5 min).
     */
    public static Optional<Map<String, Object>> toRateLimitConfig(String siddhiQuery) {
        Parsed p = parse(siddhiQuery);
        if (p == null) return Optional.empty();
        String field = kongPeriod(p.windowValue, p.unit);
        if (field == null) return Optional.empty();   // a window Kong can't express exactly (e.g. 5 min)

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put(field, (int) p.limit);
        cfg.put("policy", "local");
        cfg.put("fault_tolerant", true);
        cfg.put("hide_client_headers", false);
        return Optional.of(cfg);
    }

    /**
     * Normalise the canonical idiom to requests-per-minute (the unit {@link ThrottlingTierResolver}
     * works in). More permissive than {@link #toRateLimitConfig} — ANY window normalises to a rate
     * (e.g. {@code timeBatch(5 min)} + {@code count >= 100} → 20/min). Empty when not the idiom.
     */
    public static Optional<Integer> toRequestsPerMinute(String siddhiQuery) {
        Parsed p = parse(siddhiQuery);
        if (p == null) return Optional.empty();
        double unitMin = unitMinutes(p.unit);
        if (unitMin <= 0) return Optional.empty();
        double windowMinutes = unitMin * (p.windowValue <= 0 ? 1 : p.windowValue);
        return Optional.of((int) Math.max(1, Math.round(p.limit / windowMinutes)));
    }

    private static double unitMinutes(String unit) {
        if (unit.startsWith("sec")) return 1.0 / 60.0;
        if (unit.startsWith("min")) return 1.0;
        if (unit.startsWith("hour")) return 60.0;
        if (unit.startsWith("day")) return 1440.0;
        return -1;
    }

    /** Map a (value, unit) window to a Kong rate-limiting period field, or null if not expressible. */
    private static String kongPeriod(long value, String unit) {
        boolean sec = unit.startsWith("sec");
        boolean min = unit.startsWith("min");
        boolean hour = unit.startsWith("hour");
        boolean day = unit.startsWith("day");
        if (value == 1) {
            if (sec) return "second";
            if (min) return "minute";
            if (hour) return "hour";
            if (day) return "day";
        }
        if (value == 60 && sec) return "minute";   // 60 seconds → per minute
        if (value == 60 && min) return "hour";      // 60 minutes → per hour
        if (value == 24 && hour) return "day";       // 24 hours → per day
        return null;
    }

    /** The captured group as a long, but ONLY if the pattern matches exactly once. */
    private static Long singleMatchLong(Pattern p, String s, int group) {
        String g = singleMatchGroup(p, s, group);
        if (g == null) return null;
        try {
            return Long.parseLong(g);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The captured group, but ONLY if the pattern matches exactly once (else ambiguous → null). */
    private static String singleMatchGroup(Pattern p, String s, int group) {
        Matcher m = p.matcher(s);
        if (!m.find()) return null;
        String first = m.group(group);
        if (m.find()) return null;   // more than one occurrence → ambiguous, refuse
        return first;
    }
}
