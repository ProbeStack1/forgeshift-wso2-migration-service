package com.forgeshift.wso2.migration.service;

import com.forgeshift.wso2.migration.bundle.Wso2ApiBundle;
import com.forgeshift.wso2.migration.bundle.Wso2ApiBundleParser;
import com.forgeshift.wso2.migration.client.Wso2BundleClient;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import com.forgeshift.wso2.migration.reader.Wso2Credentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the DOWNLOADING_BUNDLES phase for one (companyName, wso2Tenant)
 * scope. Acquires one OAuth token from WSO2 and reuses it across every API
 * download; parses each ZIP into a {@link Wso2ApiBundle}; collects per-API
 * failures so the caller can surface them as warnings and fall back to
 * JSON-only translation for the affected APIs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Wso2BundleDownloadService {

    private final Wso2BundleClient bundleClient;
    private final Wso2ApiBundleParser bundleParser;

    /**
     * Download + parse bundles for every API in {@code apiSnapshots}. The
     * returned {@link Result} carries one bundle per successful sourceId and
     * one failure message per failed sourceId — the caller decides what to
     * do with each (per-API skip-and-warn in our flow).
     *
     * <p>If {@code creds.source} is {@code "missing"} we short-circuit:
     * no token call, every API ends up in {@link Result#failures} with a
     * "no WSO2 profile" message, and the caller falls back to JSON-only
     * translation for all of them.
     */
    public Result download(Wso2Credentials creds, List<DiscoverySnapshot> apiSnapshots) {
        Map<String, Wso2ApiBundle> bundles = new HashMap<>();
        Map<String, String> failures = new HashMap<>();

        if (apiSnapshots == null || apiSnapshots.isEmpty()) {
            return new Result(bundles, failures);
        }
        if (creds == null || "missing".equals(creds.getSource())) {
            String msg = "No WSO2 profile resolved for the requested tenant — "
                    + "bundle download skipped, translation will use JSON payload only";
            log.warn(msg);
            for (DiscoverySnapshot s : apiSnapshots) {
                failures.put(s.getSourceId(), msg);
            }
            return new Result(bundles, failures);
        }

        String token;
        try {
            token = bundleClient.acquireToken(creds);
            log.info("Acquired WSO2 token for company={} profile={} ({}...)",
                    creds.getCompanyName(), creds.getProfileName(),
                    token.length() > 6 ? token.substring(0, 6) : token);
        } catch (Exception e) {
            String msg = "WSO2 token acquisition failed: " + e.getMessage();
            log.warn(msg);
            for (DiscoverySnapshot s : apiSnapshots) {
                failures.put(s.getSourceId(), msg);
            }
            return new Result(bundles, failures);
        }

        for (DiscoverySnapshot s : apiSnapshots) {
            try {
                byte[] zip = bundleClient.exportApi(token, creds, s.getSourceId());
                Wso2ApiBundle bundle = bundleParser.parse(zip);
                bundles.put(s.getSourceId(), bundle);
                log.debug("Bundle parsed for API {} (sequences={} certs={}+{})",
                        s.getSourceId(),
                        bundle.getSequences().size(),
                        bundle.getEndpointCerts().size(),
                        bundle.getClientCerts().size());
            } catch (Exception e) {
                String msg = "Bundle download/parse failed for API "
                        + s.getSourceName() + " (" + s.getSourceId() + "): " + e.getMessage();
                log.warn(msg);
                failures.put(s.getSourceId(), msg);
            }
        }
        return new Result(bundles, failures);
    }

    /** Outcome of a bundle-download batch. */
    public static final class Result {
        public final Map<String, Wso2ApiBundle> bundles;
        public final Map<String, String> failures;

        public Result(Map<String, Wso2ApiBundle> bundles, Map<String, String> failures) {
            this.bundles = bundles;
            this.failures = failures;
        }
    }
}
