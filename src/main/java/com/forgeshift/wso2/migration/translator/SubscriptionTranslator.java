package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.domain.kong.KongConsumer;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.reader.CredentialReader;
import com.forgeshift.wso2.migration.reader.CredentialReader.AppCredential;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * WSO2 Applications + Subscriptions → Kong Consumers (+ consumer-scoped
 * rate-limiting plugins based on each application's throttling tier).
 *
 * <p>Each WSO2 Application becomes one Kong Consumer (custom_id =
 * application uuid, username = application name). The translator collapses
 * subscriptions across the same Application so we only create one Consumer
 * per app, not one per (app, api) pair.
 *
 * <p>Subscription-tier rate limits are applied per-API as route-scoped
 * plugins by {@link ApiTranslator}; this translator only handles the
 * Application-tier limit (consumer-scoped).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionTranslator {

    private final MigrationProperties props;
    private final ThrottlingTierResolver tierResolver;
    private final CredentialReader credentialReader;
    private final CredentialTranslator credentialTranslator;

    public List<TranslatedConsumer> translate(List<DiscoverySnapshot> applications,
                                              List<DiscoverySnapshot> subscriptions) {
        return translate(applications, subscriptions, Map.of());
    }

    /**
     * As {@link #translate(List, List)} but with live-fetched application credentials
     * ({@code liveCreds}, keyed by applicationId) overlaid on the assessment-captured ones (live wins
     * per app) — lets the migration recreate working consumer credentials even when the assessment
     * never captured the OAuth2 keys into its collection.
     */
    public List<TranslatedConsumer> translate(List<DiscoverySnapshot> applications,
                                              List<DiscoverySnapshot> subscriptions,
                                              Map<String, List<AppCredential>> liveCreds) {
        return translate(applications, subscriptions, liveCreds, Map.of());
    }

    /**
     * As above, plus {@code schemesOverride} (apiId → securityScheme set, e.g. derived from the migrated
     * APIs' actual auth plugins) overlaid on the assessment-captured schemes — so a consumer gets the
     * credential TYPE (jwt vs key-auth) matching the API it subscribes to even when the assessment
     * security collection is empty.
     */
    public List<TranslatedConsumer> translate(List<DiscoverySnapshot> applications,
                                              List<DiscoverySnapshot> subscriptions,
                                              Map<String, List<AppCredential>> liveCreds,
                                              Map<String, Set<String>> schemesOverride) {
        // Dedupe applications by id
        Map<String, DiscoverySnapshot> appsById = new LinkedHashMap<>();
        for (DiscoverySnapshot a : applications) {
            if (a.getSourceId() != null) appsById.putIfAbsent(a.getSourceId(), a);
        }

        // Index subscriptions by applicationId so we can look up tiers
        Map<String, List<DiscoverySnapshot>> subsByApp = new HashMap<>();
        if (subscriptions != null) {
            for (DiscoverySnapshot s : subscriptions) {
                String appId = mapField(s.getPayload(), "applicationId", null);
                if (appId == null) continue;
                subsByApp.computeIfAbsent(appId, k -> new ArrayList<>()).add(s);
            }
        }

        // Load captured credentials + per-API security schemes once for the whole batch
        // (keyed by the run's company/tenant, taken from the first application snapshot).
        Map<String, List<AppCredential>> credsByApp = new LinkedHashMap<>();
        Map<String, Set<String>> schemesByApi = new LinkedHashMap<>();
        DiscoverySnapshot first = appsById.values().stream().findFirst().orElse(null);
        if (first != null && props.getCredentials().isEnabled()) {
            credsByApp.putAll(credentialReader.readCredentialsByApplication(first.getCompanyName(), first.getWso2Tenant()));
            schemesByApi.putAll(credentialReader.readSecuritySchemesByApi(first.getCompanyName(), first.getWso2Tenant()));
        }
        if (liveCreds != null && !liveCreds.isEmpty()) credsByApp.putAll(liveCreds);   // live-fetched keys win per app
        if (schemesOverride != null && !schemesOverride.isEmpty()) schemesByApi.putAll(schemesOverride); // derived from migrated APIs' auth plugins

        List<TranslatedConsumer> out = new ArrayList<>();
        for (DiscoverySnapshot app : appsById.values()) {
            out.add(translateOne(app, subsByApp.getOrDefault(app.getSourceId(), List.of()),
                    credsByApp, schemesByApi));
        }
        ensureUniqueUsernames(out);
        return out;
    }

    /**
     * Kong + decK require a UNIQUE consumer username, but WSO2 application names are NOT unique —
     * every user gets a "DefaultApplication", so two apps share that name and the bundle would carry
     * two consumers with the same username. That makes {@code deck gateway validate} abort the WHOLE
     * deploy ("inserting consumer by username defaultapplication"). Disambiguate any colliding username
     * by appending the application's unique id (already the consumer's custom_id) so each stays unique
     * and traceable; non-colliding usernames keep their clean app name.
     */
    private void ensureUniqueUsernames(List<TranslatedConsumer> consumers) {
        Map<String, Integer> counts = new HashMap<>();
        for (TranslatedConsumer c : consumers) {
            if (c.getConsumer() != null && c.getConsumer().getUsername() != null) {
                counts.merge(c.getConsumer().getUsername(), 1, Integer::sum);
            }
        }
        for (TranslatedConsumer c : consumers) {
            KongConsumer k = c.getConsumer();
            if (k != null && k.getUsername() != null && counts.getOrDefault(k.getUsername(), 0) > 1) {
                k.setUsername(k.getUsername() + "-" + slug(c.getWso2SourceId()));
            }
        }
    }

    private TranslatedConsumer translateOne(DiscoverySnapshot app, List<DiscoverySnapshot> appSubs,
                                            Map<String, List<AppCredential>> credsByApp,
                                            Map<String, Set<String>> schemesByApi) {
        Map<String, Object> p = app.getPayload() != null ? app.getPayload() : Collections.emptyMap();
        String name = app.getSourceName() != null ? app.getSourceName() : str(p.get("name"));
        String owner = str(p.get("owner"));
        String appLevelTier = str(p.get("throttlingPolicy"));

        List<String> tags = new ArrayList<>();
        tags.add(props.getTranslation().getTagPrefix() + ":" + app.getSourceId());
        tags.add(props.getTranslation().getMigratedByTag());
        if (StringUtils.hasText(owner)) tags.add("wso2-owner:" + owner);

        KongConsumer consumer = KongConsumer.builder()
                .username(slug(name))
                .custom_id(app.getSourceId())
                .tags(tags)
                .build();

        List<KongPlugin> plugins = new ArrayList<>();

        // Application-tier rate limit (consumer-scoped, applies across every API)
        if (StringUtils.hasText(appLevelTier) && !"Unlimited".equalsIgnoreCase(appLevelTier)) {
            Integer rpm = tierResolver.effectiveTierRpm(app.getCompanyName(), app.getWso2Tenant()).get(appLevelTier);
            if (rpm == null) {
                int requestCount = parseRequestCountFromTierName(appLevelTier);
                rpm = requestCount > 0 ? requestCount : props.getTranslation().getDefaultThrottleRpm();
            }
            plugins.add(rateLimit(rpm, withExtra(tags, "wso2-tier:" + appLevelTier)));
        }

        List<String> warnings = new ArrayList<>();
        if (appSubs.isEmpty()) {
            warnings.add("Application " + name + " has no subscriptions - Consumer will be created with no API access.");
        }

        // Recreate this app's Kong credentials from the captured OAuth2 keys, matching the
        // auth plugin each subscribed API uses (oauth2 → jwt_secrets, api_key → keyauth).
        List<CredentialTranslator.SecretRef> credentialManifest = new ArrayList<>();
        if (props.getCredentials().isEnabled()) {
            Set<String> schemes = schemesForSubscribedApis(appSubs, schemesByApi);
            CredentialTranslator.Result cr = credentialTranslator.attach(
                    consumer, app.getSourceId(), name,
                    credsByApp.getOrDefault(app.getSourceId(), List.of()), schemes, tags);
            warnings.addAll(cr.getWarnings());
            credentialManifest = cr.getManifest();
            if (!credentialManifest.isEmpty()) {
                List<String> refNames = credentialManifest.stream()
                        .map(CredentialTranslator.SecretRef::getReference).toList();
                log.info("[credentials] consumer {} needs {} secret reference(s) injected before apply: {}",
                        slug(name), refNames.size(), refNames);
            }
        }

        return TranslatedConsumer.builder()
                .wso2SourceId(app.getSourceId())
                .wso2SourceName(name)
                .consumer(consumer)
                .consumerPlugins(plugins)
                .warnings(warnings)
                .credentialManifest(credentialManifest)
                .build();
    }

    /** Union of securityScheme values across the APIs this app's subscriptions point at. */
    private Set<String> schemesForSubscribedApis(List<DiscoverySnapshot> appSubs,
                                                 Map<String, Set<String>> schemesByApi) {
        Set<String> schemes = new LinkedHashSet<>();
        for (DiscoverySnapshot sub : appSubs) {
            String apiId = mapField(sub.getPayload(), "apiId", null);
            if (apiId != null && schemesByApi.containsKey(apiId)) {
                schemes.addAll(schemesByApi.get(apiId));
            }
        }
        return schemes;
    }

    private KongPlugin rateLimit(int rpm, List<String> tags) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("minute", rpm);
        cfg.put("policy", "local");
        cfg.put("fault_tolerant", true);
        return KongPlugin.builder().name("rate-limiting").config(cfg).enabled(true).tags(tags).build();
    }

    /** Try to parse "10PerMin" / "50PerMin" style tier names. */
    private static int parseRequestCountFromTierName(String tier) {
        if (tier == null) return 0;
        try {
            int p = tier.indexOf("PerMin");
            if (p > 0) return Integer.parseInt(tier.substring(0, p));
        } catch (NumberFormatException ignore) {}
        return 0;
    }

    private static List<String> withExtra(List<String> base, String extra) {
        List<String> out = new ArrayList<>(base);
        if (StringUtils.hasText(extra)) out.add(extra);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String mapField(Map<String, Object> root, String key, String defaultValue) {
        if (root == null) return defaultValue;
        Object v = root.get(key);
        return v == null ? defaultValue : v.toString();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String slug(String s) {
        if (!StringUtils.hasText(s)) return "consumer";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
