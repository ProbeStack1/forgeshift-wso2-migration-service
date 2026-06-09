package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshotReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a WSO2 throttling-tier name (e.g. {@code Bronze}) to its <b>real</b> requests-per-minute
 * limit, sourced from the throttling policies the discovery service captured into
 * {@code discovery_wso2_throttlingpolicies}.
 *
 * <p>Background: an API/operation/application only references a tier by <i>name</i> ({@code Bronze}).
 * The actual number (Bronze = 1000/min) lives in the tier <i>definition</i>. Historically the
 * translators used a small hard-coded {@code translation.throttling-tier-map} ({@code Bronze:10}…)
 * which did not match WSO2 at all. This resolver replaces that guesswork with the discovered values,
 * keeping the configured map only as a fallback when discovery data is unavailable.
 *
 * <p>The number is read from each policy's structured {@code defaultLimit.requestCount}/{@code timeUnit}
 * when present, otherwise parsed out of the human-readable {@code description}
 * ("Allows 1000 requests per minute") — WSO2's <i>list</i> endpoints omit {@code defaultLimit}, so in
 * practice the description is the reliable source. Everything is normalised to requests-per-minute,
 * the window the Kong {@code rate-limiting} plugin is configured with here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThrottlingTierResolver {

    private static final String RESOURCE = "throttlingpolicies";

    /** Nullable in unit tests; when null the resolver simply returns the configured fallback map. */
    private final DiscoverySnapshotReader snapshotReader;
    private final MigrationProperties props;

    /** company|tenant → (tierName → requests-per-minute). Tier definitions are effectively static. */
    private final Map<String, Map<String, Integer>> cache = new ConcurrentHashMap<>();

    /**
     * Effective {@code tierName → requests-per-minute} map for (company, tenant): the configured
     * {@code translation.throttling-tier-map} overlaid with the REAL limits discovered from WSO2.
     * Discovered values win; if discovery data is missing the configured map is returned unchanged,
     * so behaviour degrades gracefully rather than failing the migration.
     */
    public Map<String, Integer> effectiveTierRpm(String company, String tenant) {
        Map<String, Integer> effective = new HashMap<>(props.getTranslation().getThrottlingTierMap());
        if (snapshotReader == null || !StringUtils.hasText(company)) {
            return effective;
        }
        try {
            effective.putAll(cache.computeIfAbsent(company + "|" + tenant, k -> loadDiscovered(company, tenant)));
        } catch (Exception e) {
            log.warn("Throttling tier resolve failed for {}/{} — using configured fallback map: {}",
                    company, tenant, e.toString());
        }
        return effective;
    }

    private Map<String, Integer> loadDiscovered(String company, String tenant) {
        List<DiscoverySnapshot> snaps = snapshotReader.findLatestRevision(company, tenant, RESOURCE);
        if (snaps == null || snaps.isEmpty()) {
            log.info("No discovered throttling policies for {}/{} — using configured fallback tier map.",
                    company, tenant);
            return Map.of();
        }
        // Prefer subscription tiers (what APIs subscribe on); advanced then application fill any gaps
        // but never override a subscription value (names like "Unlimited" repeat across families).
        Map<Integer, List<DiscoverySnapshot>> byRank = new TreeMap<>();
        for (DiscoverySnapshot s : snaps) {
            byRank.computeIfAbsent(rank(policyType(s)), k -> new ArrayList<>()).add(s);
        }
        Map<String, Integer> out = new HashMap<>();
        for (List<DiscoverySnapshot> group : byRank.values()) {
            for (DiscoverySnapshot s : group) {
                String name = s.getSourceName();
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                Integer rpm = rpmFromSnapshot(s);
                if (rpm != null) {
                    out.putIfAbsent(name, rpm);
                }
            }
        }
        log.info("Resolved {} throttling tier limit(s) from discovery for {}/{}.", out.size(), company, tenant);
        return out;
    }

    private static String policyType(DiscoverySnapshot s) {
        Map<String, Object> p = s.getPayload();
        if (p == null) {
            return null;
        }
        Object t = p.get("__policyType");
        if (t == null) {
            t = p.get("policyType");
        }
        return t == null ? null : t.toString();
    }

    private static int rank(String policyType) {
        if (policyType == null) {
            return 3;
        }
        return switch (policyType.toLowerCase()) {
            case "subscription" -> 0;
            case "advanced" -> 1;
            case "application" -> 2;
            default -> 3;
        };
    }

    /** Requests-per-minute for one policy snapshot; null when unlimited / not a request-count limit. */
    private static Integer rpmFromSnapshot(DiscoverySnapshot s) {
        Map<String, Object> p = s.getPayload();
        if (p == null) {
            return null;
        }
        if (p.get("defaultLimit") instanceof Map<?, ?> limit) {
            Long count = asLong(limit.get("requestCount"));
            if (count != null) {
                return toPerMinute(count, str(limit.get("timeUnit")), asInt(limit.get("unitTime")));
            }
        }
        return parseRpm(str(p.get("description")));
    }

    private static final Pattern RATE = Pattern.compile(
            "(\\d[\\d,]*)\\s+(?:request|event|call)s?(?:\\(s\\))?\\s+per\\s+"
                    + "(second|sec|minute|min|hour|hr|day|week|month|year)",
            Pattern.CASE_INSENSITIVE);

    /** Parse "Allows N requests per &lt;unit&gt;" → requests-per-minute; null when no numeric rate. */
    static Integer parseRpm(String description) {
        if (description == null) {
            return null;
        }
        Matcher m = RATE.matcher(description);
        if (!m.find()) {
            return null;
        }
        try {
            return toPerMinute(Long.parseLong(m.group(1).replace(",", "")), m.group(2), 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Normalise any (count, window, multiplier) to requests-per-minute. */
    private static Integer toPerMinute(long count, String unit, Integer unitTime) {
        if (count <= 0) {
            return null;
        }
        double minutes = windowMinutes(unit) * (unitTime == null || unitTime <= 0 ? 1 : unitTime);
        long perMin = minutes <= 0 ? count : Math.round(count / minutes);
        return (int) Math.max(1, Math.min(perMin, Integer.MAX_VALUE));
    }

    private static double windowMinutes(String unit) {
        if (unit == null) {
            return 1.0;
        }
        return switch (unit.toLowerCase()) {
            case "sec", "second", "s" -> 1.0 / 60.0;
            case "min", "minute", "m" -> 1.0;
            case "hour", "hr", "h" -> 60.0;
            case "day", "d" -> 1440.0;
            case "week", "w" -> 10080.0;
            case "month" -> 43200.0;
            case "year" -> 525600.0;
            default -> 1.0;
        };
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? null : Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
