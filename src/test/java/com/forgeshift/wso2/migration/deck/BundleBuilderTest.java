package com.forgeshift.wso2.migration.deck;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleBuilderTest {

    @Test
    void buildsZipWithThreeEntriesAndApplyModeWorkflow(@TempDir Path tmp) throws IOException {
        MigrationProperties props = new MigrationProperties();
        props.getDeck().setBundleDir(tmp.toString());
        props.getDeck().setCallbackBaseUrl("https://probestack.io/wso2/migration/v1");
        BundleBuilder bb = new BundleBuilder(props);

        BundleResult res = bb.build("job123", "dev", "my-cp", "https://us.api.konghq.com",
                Map.of("kong/dev/api-petstore.yaml", "_format_version: \"3.0\"\n"));

        assertEquals(3, res.getFiles().size()); // 1 kong file + workflow + README
        assertTrue(res.getDownloadUrl().endsWith("/migrations/job123/bundle"));

        Map<String, String> files = unzip(Files.readAllBytes(Path.of(res.getBundlePath())));
        assertTrue(files.containsKey("kong/dev/api-petstore.yaml"));
        assertTrue(files.containsKey(".github/workflows/deploy-dev.yml"));
        assertTrue(files.containsKey("README.md"));

        String wf = files.get(".github/workflows/deploy-dev.yml");
        assertTrue(wf.contains("kong_config_path: kong/dev"));
        assertTrue(wf.contains("deck_mode: apply"));
        assertTrue(wf.contains("control_plane_name: my-cp"));
        assertTrue(wf.contains("konnect_addr: https://us.api.konghq.com"));
        // The callback URL is NOT baked into the (shared, static) workflow — it's a dispatch input,
        // and the per-job target URL is carried on the BundleResult for the dispatch call.
        assertTrue(wf.contains("result_callback_url: ${{ inputs.result_callback_url }}"),
                "callback URL must be a workflow_dispatch input, not hard-coded per job");
        assertFalse(wf.contains("/migrations/job123/deck-result"),
                "per-job callback URL must NOT be baked into the shared workflow file");
        assertEquals("https://probestack.io/wso2/migration/v1/migrations/job123/deck-result",
                res.getCallbackUrl());
        assertEquals("deploy-dev.yml", res.getWorkflowFile());
        assertTrue(wf.contains("workflow_dispatch:"), "must be dispatch-triggered");
        assertFalse(wf.contains("push:"), "must NOT have a push trigger (avoids stray runs)");
        assertTrue(wf.contains("secrets.KONNECT_TOKEN"));
        assertFalse(wf.contains("kpat_"), "must not hardcode a Konnect PAT");
    }

    @Test
    void workflowReadsPlaintextVariableWhenKonnectTokenViaVariableEnabled(@TempDir Path tmp) throws IOException {
        MigrationProperties props = new MigrationProperties();
        props.getDeck().setBundleDir(tmp.toString());
        props.getDeck().setKonnectTokenViaVariable(true);   // TEST mode (no encrypted secret)
        BundleBuilder bb = new BundleBuilder(props);

        BundleResult res = bb.build("job123", "dev", "my-cp", "https://us.api.konghq.com",
                Map.of("kong/dev/api-petstore.yaml", "_format_version: \"3.0\"\n"));

        String wf = unzip(Files.readAllBytes(Path.of(res.getBundlePath())))
                .get(".github/workflows/deploy-dev.yml");
        assertTrue(wf.contains("konnect_token: ${{ vars.KONNECT_TOKEN }}"),
                "TEST mode must read the token from a plaintext Actions variable");
        assertFalse(wf.contains("secrets.KONNECT_TOKEN"));
    }

    @Test
    void workflowCanInlineResolvedKonnectTokenForTestPipeline(@TempDir Path tmp) throws IOException {
        MigrationProperties props = new MigrationProperties();
        props.getDeck().setBundleDir(tmp.toString());
        props.getDeck().setKonnectTokenViaVariable(true);
        BundleBuilder bb = new BundleBuilder(props);

        BundleResult res = bb.build("job123", "dev", "my-cp", "https://us.api.konghq.com",
                "kpat_test_token", Map.of("kong/dev/api-petstore.yaml", "_format_version: \"3.0\"\n"));

        String wf = unzip(Files.readAllBytes(Path.of(res.getBundlePath())))
                .get(".github/workflows/deploy-dev.yml");
        assertTrue(wf.contains("konnect_token: 'kpat_test_token'"));
        assertFalse(wf.contains("vars.KONNECT_TOKEN"));
        assertFalse(wf.contains("secrets.KONNECT_TOKEN"));
    }

    @Test
    void kongConfigPathFollowsThePerApiSubdir(@TempDir Path tmp) throws IOException {
        MigrationProperties props = new MigrationProperties();
        props.getDeck().setBundleDir(tmp.toString());
        BundleBuilder bb = new BundleBuilder(props);

        // Files nested under a per-API subdir → apply path is that subdir, not the whole kong/dev.
        BundleResult res = bb.build("job9", "dev", "cp", "https://us.api.konghq.com",
                Map.of("kong/dev/orders-1-0/api-orders-1-0.yaml", "_format_version: \"3.0\"\n",
                        "kong/dev/orders-1-0/consumers.yaml", "_format_version: \"3.0\"\n"));

        assertEquals("kong/dev/orders-1-0", res.getKongConfigPath());
        String wf = unzip(Files.readAllBytes(Path.of(res.getBundlePath())))
                .get(".github/workflows/deploy-dev.yml");
        assertTrue(wf.contains("kong_config_path: kong/dev/orders-1-0"));
    }

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                out.put(e.getName(), new String(zis.readAllBytes()));
            }
        }
        return out;
    }
}
