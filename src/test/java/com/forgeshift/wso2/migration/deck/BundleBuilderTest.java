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
        assertTrue(wf.contains("secrets.KONNECT_TOKEN"));
        assertFalse(wf.contains("kpat_"), "must not hardcode a Konnect PAT");
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
