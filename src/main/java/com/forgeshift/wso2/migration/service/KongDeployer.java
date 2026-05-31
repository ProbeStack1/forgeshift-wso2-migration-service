package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.client.KonnectAdminClient;
import com.forgeshift.wso2.migration.client.KonnectUpsertResult;
import com.forgeshift.wso2.migration.domain.MigrationJob;
import com.forgeshift.wso2.migration.domain.kong.KongCaCertificate;
import com.forgeshift.wso2.migration.domain.kong.KongConsumer;
import com.forgeshift.wso2.migration.domain.kong.KongEntityType;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.domain.kong.KongRoute;
import com.forgeshift.wso2.migration.domain.kong.KongService;
import com.forgeshift.wso2.migration.domain.kong.KongTarget;
import com.forgeshift.wso2.migration.domain.kong.KongUpstream;
import com.forgeshift.wso2.migration.reader.KongKonnectCredentials;
import com.forgeshift.wso2.migration.translator.TranslatedApi;
import com.forgeshift.wso2.migration.translator.TranslatedCertificate;
import com.forgeshift.wso2.migration.translator.TranslatedConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Walks a translated plan and writes it to Konnect via KonnectAdminClient.
 *
 * <p>Dependency order is fixed:
 * <ol>
 *   <li>Upstream (if any) - so routes/services that point at it can resolve</li>
 *   <li>Targets - children of the upstream</li>
 *   <li>Service - top-level</li>
 *   <li>Routes - children of the service</li>
 *   <li>Service plugins, then route plugins, then consumer plugins</li>
 *   <li>Consumers - parent of consumer plugins, but independent of services</li>
 * </ol>
 *
 * <p>If any step fails, subsequent dependent steps are skipped for that API/consumer
 * and counted as failures - other entities continue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KongDeployer {

    private final KonnectAdminClient client;

    /** Deploy one translated API. Returns per-entity outcome counts rolled up. */
    public DeployOutcome deployApi(KongKonnectCredentials creds, TranslatedApi api,
                                   MigrationJob job) {
        DeployOutcome out = new DeployOutcome();

        // 1. Upstream (optional)
        String upstreamUuid = null;
        if (api.getUpstream() != null) {
            KonnectUpsertResult r = client.upsert(creds, KongEntityType.UPSTREAM, null,
                    job.getCompanyName(), job.getWso2Tenant(), job.getId(),
                    "upstream:" + api.getWso2SourceId(),
                    api.getUpstream().getTags(), api.getUpstream());
            tally(out, r, KongEntityType.UPSTREAM, api.getUpstream().getName());
            if ("FAILED".equals(r.getAction())) {
                log.warn("Skipping rest of API {} after upstream failure: {}",
                        api.getWso2SourceName(), r.getErrorMessage());
                return out;
            }
            upstreamUuid = r.getKongUuid();

            // 2. Targets
            for (int i = 0; i < api.getTargets().size(); i++) {
                KongTarget t = api.getTargets().get(i);
                KonnectUpsertResult tr = client.upsert(creds, KongEntityType.TARGET, upstreamUuid,
                        job.getCompanyName(), job.getWso2Tenant(), job.getId(),
                        "target:" + api.getWso2SourceId() + ":" + i,
                        t.getTags(), t);
                tally(out, tr, KongEntityType.TARGET, t.getTarget());
            }
        }

        // 3. Service
        KongService svc = api.getService();
        KonnectUpsertResult svcR = client.upsert(creds, KongEntityType.SERVICE, null,
                job.getCompanyName(), job.getWso2Tenant(), job.getId(),
                api.getWso2SourceId(), svc.getTags(), svc);
        tally(out, svcR, KongEntityType.SERVICE, svc.getName());
        if ("FAILED".equals(svcR.getAction())) {
            log.warn("Skipping rest of API {} after service failure: {}",
                    api.getWso2SourceName(), svcR.getErrorMessage());
            return out;
        }
        String serviceUuid = svcR.getKongUuid();

        // 4. Routes - parent is the service UUID
        java.util.Map<String, String> routeUuidByName = new java.util.HashMap<>();
        for (KongRoute r : api.getRoutes()) {
            r.setService(Map.of("id", serviceUuid));
            KonnectUpsertResult rr = client.upsert(creds, KongEntityType.ROUTE, serviceUuid,
                    job.getCompanyName(), job.getWso2Tenant(), job.getId(),
                    "route:" + api.getWso2SourceId() + ":" + r.getName(),
                    r.getTags(), r);
            tally(out, rr, KongEntityType.ROUTE, r.getName());
            if ("FAILED".equals(rr.getAction())) continue;
            routeUuidByName.put(r.getName(), rr.getKongUuid());
        }

        // 5. Service-scoped plugins
        for (KongPlugin pl : api.getServicePlugins()) {
            pl.setService(Map.of("id", serviceUuid));
            KonnectUpsertResult pr = client.upsert(creds, KongEntityType.PLUGIN, serviceUuid,
                    job.getCompanyName(), job.getWso2Tenant(), job.getId(),
                    "plugin:" + api.getWso2SourceId() + ":svc:" + pl.getName(),
                    pl.getTags(), pl);
            tally(out, pr, KongEntityType.PLUGIN, pl.getName() + " (service-scope)");
        }

        // 6. Route-scoped plugins
        for (Map.Entry<String, List<KongPlugin>> e : api.getRoutePlugins().entrySet()) {
            String routeUuid = routeUuidByName.get(e.getKey());
            if (routeUuid == null) continue;
            for (KongPlugin pl : e.getValue()) {
                pl.setRoute(Map.of("id", routeUuid));
                KonnectUpsertResult pr = client.upsert(creds, KongEntityType.PLUGIN, routeUuid,
                        job.getCompanyName(), job.getWso2Tenant(), job.getId(),
                        "plugin:" + api.getWso2SourceId() + ":route:" + e.getKey() + ":" + pl.getName(),
                        pl.getTags(), pl);
                tally(out, pr, KongEntityType.PLUGIN, pl.getName() + " (route:" + e.getKey() + ")");
            }
        }
        return out;
    }

    public DeployOutcome deployConsumer(KongKonnectCredentials creds, TranslatedConsumer c, MigrationJob job) {
        DeployOutcome out = new DeployOutcome();
        KongConsumer consumer = c.getConsumer();
        KonnectUpsertResult cr = client.upsert(creds, KongEntityType.CONSUMER, null,
                job.getCompanyName(), job.getWso2Tenant(), job.getId(),
                c.getWso2SourceId(), consumer.getTags(), consumer);
        tally(out, cr, KongEntityType.CONSUMER, consumer.getUsername());
        if ("FAILED".equals(cr.getAction())) {
            log.warn("Skipping plugins for consumer {} after failure: {}",
                    c.getWso2SourceName(), cr.getErrorMessage());
            return out;
        }
        String consumerUuid = cr.getKongUuid();

        for (KongPlugin pl : c.getConsumerPlugins()) {
            pl.setConsumer(Map.of("id", consumerUuid));
            KonnectUpsertResult pr = client.upsert(creds, KongEntityType.PLUGIN, consumerUuid,
                    job.getCompanyName(), job.getWso2Tenant(), job.getId(),
                    "plugin:consumer:" + c.getWso2SourceId() + ":" + pl.getName(),
                    pl.getTags(), pl);
            tally(out, pr, KongEntityType.PLUGIN, pl.getName() + " (consumer:" + consumer.getUsername() + ")");
        }
        return out;
    }

    /** Deploy one translated WSO2 endpoint certificate as a Kong ca_certificate. */
    public DeployOutcome deployCertificate(KongKonnectCredentials creds, TranslatedCertificate cert,
                                           MigrationJob job) {
        DeployOutcome out = new DeployOutcome();
        KongCaCertificate payload = cert.getCaCertificate();
        if (payload == null || payload.getCert() == null || payload.getCert().isBlank()) {
            out.failed++;
            out.errors.add("Certificate '" + cert.getWso2SourceName() + "' has no content; skipped.");
            log.warn("Skipping certificate {} — no PEM content to deploy", cert.getWso2SourceName());
            return out;
        }
        KonnectUpsertResult r = client.upsert(creds, KongEntityType.CA_CERTIFICATE, null,
                job.getCompanyName(), job.getWso2Tenant(), job.getId(),
                "cacert:" + cert.getWso2SourceId(), payload.getTags(), payload);
        tally(out, r, KongEntityType.CA_CERTIFICATE, cert.getWso2SourceName());
        return out;
    }

    /**
     * Roll up a single upsert outcome into the {@link DeployOutcome}. On
     * FAILED we now log the entity type + name + the raw Konnect error
     * body so an operator can see WHY each entity failed without having
     * to enable DEBUG. Without this, a batch of 20+ failures shows up as
     * a single counter increment and the root cause is invisible.
     */
    private static void tally(DeployOutcome o, KonnectUpsertResult r,
                              KongEntityType type, String entityName) {
        switch (r.getAction()) {
            case "CREATED" -> o.created++;
            case "UPDATED" -> o.updated++;
            case "UNCHANGED" -> o.unchanged++;
            default -> {
                o.failed++;
                if (r.getErrorMessage() != null) {
                    o.errors.add(r.getErrorMessage());
                    log.warn("Kong {} '{}' upsert FAILED: {}", type, entityName, r.getErrorMessage());
                } else {
                    log.warn("Kong {} '{}' upsert FAILED with no error message", type, entityName);
                }
            }
        }
    }

    public static class DeployOutcome {
        public int created;
        public int updated;
        public int unchanged;
        public int failed;
        public java.util.List<String> errors = new java.util.ArrayList<>();
    }
}
