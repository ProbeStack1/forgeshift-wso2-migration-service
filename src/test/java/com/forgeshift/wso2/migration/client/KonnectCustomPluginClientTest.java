package com.forgeshift.wso2.migration.client;

import org.junit.jupiter.api.Test;

import static com.forgeshift.wso2.migration.client.KonnectCustomPluginClient.ControlPlaneType.CUSTOM_PLUGIN_CAPABLE;
import static com.forgeshift.wso2.migration.client.KonnectCustomPluginClient.ControlPlaneType.SERVERLESS;
import static com.forgeshift.wso2.migration.client.KonnectCustomPluginClient.ControlPlaneType.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class KonnectCustomPluginClientTest {

    @Test
    void classify_mapsClusterTypeToCustomPluginSupport() {
        // dedicated / hybrid / self-managed run real data planes → custom plugins work
        assertThat(KonnectCustomPluginClient.classify("CLUSTER_TYPE_CONTROL_PLANE")).isEqualTo(CUSTOM_PLUGIN_CAPABLE);
        assertThat(KonnectCustomPluginClient.classify("CLUSTER_TYPE_K8S_INGRESS_CONTROLLER")).isEqualTo(CUSTOM_PLUGIN_CAPABLE);
        // serverless forbids custom plugins
        assertThat(KonnectCustomPluginClient.classify("CLUSTER_TYPE_SERVERLESS_V1")).isEqualTo(SERVERLESS);
        // unknown / absent → conservative
        assertThat(KonnectCustomPluginClient.classify(null)).isEqualTo(UNKNOWN);
        assertThat(KonnectCustomPluginClient.classify("")).isEqualTo(UNKNOWN);
        assertThat(KonnectCustomPluginClient.classify("CLUSTER_TYPE_SOMETHING_NEW")).isEqualTo(UNKNOWN);
    }
}
