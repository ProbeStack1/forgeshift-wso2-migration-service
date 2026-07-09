package com.forgeshift.wso2.migration.deck;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The org-spec ci-cd.yml: the generated workflow must carry MIGRATION_ID as a workflow
 * variable and one sequential matrix leg per decK file, each leg reporting its own
 * trackingIds to the migration-status endpoint.
 */
class BundleBuilderMatrixTest {

    private BundleBuilder builderWithCallback(String callbackBase) {
        MigrationProperties props = new MigrationProperties();
        props.getDeck().setCallbackBaseUrl(callbackBase);
        props.getDeck().setBundleDir(System.getProperty("java.io.tmpdir") + "/kong-bundles-test");
        return new BundleBuilder(props);
    }

    @Test
    void matrixMode_generatesPerLegWorkflow_withMigrationIdAndTracking() {
        BundleBuilder builder = builderWithCallback("https://probestack.io/wso2/migration/v1");
        List<DeckMatrixEntry> matrix = List.of(
                new DeckMatrixEntry("kong/dev/consumers.yaml", "trk-consumer-1"),
                new DeckMatrixEntry("kong/dev/api-custompolicyapi-1-0-0.yaml", "trk-api-1,trk-product-1"));

        BundleResult bundle = builder.build("job42", "dev", "probestack-wso2-kong",
                "https://us.api.konghq.com", null,
                Map.of("kong/dev/consumers.yaml", "c: 1",
                       "kong/dev/api-custompolicyapi-1-0-0.yaml", "s: 1"),
                matrix);

        String wf = bundle.getRepoFileContents().get(".github/workflows/deploy-dev.yml");
        assertThat(wf).as("workflow generated").isNotNull();
        // MIGRATION_ID as a workflow variable (org spec).
        assertThat(wf).contains("MIGRATION_ID: \"job42\"");
        // Sequential, non-fail-fast matrix — one leg per decK file.
        assertThat(wf).contains("max-parallel: 1").contains("fail-fast: false");
        assertThat(wf).contains("- path: \"kong/dev/consumers.yaml\"")
                .contains("tracking: \"trk-consumer-1\"")
                .contains("- path: \"kong/dev/api-custompolicyapi-1-0-0.yaml\"")
                .contains("tracking: \"trk-api-1,trk-product-1\"");
        // Each leg applies ITS file and reports ITS trackingIds to the status endpoint.
        assertThat(wf).contains("kong_config_path: ${{ matrix.path }}");
        assertThat(wf).contains("result_callback_url: 'https://probestack.io/wso2/migration/v1"
                + "/wso2/migration-status?migrationId=job42&trackingIds=${{ matrix.tracking }}'");
        // Never a push trigger.
        assertThat(wf).doesNotContain("push:");
    }

    @Test
    void withoutMatrixOrCallback_fallsBackToLegacySingleApplyWorkflow() {
        // No callback base configured → legacy workflow even when a matrix is passed.
        BundleBuilder builder = builderWithCallback("");
        BundleResult bundle = builder.build("job43", "dev", "cp",
                "https://us.api.konghq.com", null,
                Map.of("kong/dev/api-x.yaml", "s: 1"),
                List.of(new DeckMatrixEntry("kong/dev/api-x.yaml", "trk-1")));
        String wf = bundle.getRepoFileContents().get(".github/workflows/deploy-dev.yml");
        assertThat(wf).doesNotContain("matrix").doesNotContain("MIGRATION_ID");
        assertThat(wf).contains("kong_config_path: kong/dev");
        assertThat(wf).contains("result_callback_url: ${{ inputs.result_callback_url }}");
    }
}
