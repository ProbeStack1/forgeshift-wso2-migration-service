package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.domain.kong.KongRoute;
import com.forgeshift.wso2.migration.domain.kong.KongService;
import com.forgeshift.wso2.migration.domain.kong.KongTarget;
import com.forgeshift.wso2.migration.domain.kong.KongUpstream;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** All Kong objects produced for one WSO2 API. The orchestrator deploys them in order. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslatedApi {
    private String wso2SourceId;     // the WSO2 API uuid
    private String wso2SourceName;
    private String wso2SourceVersion;

    private KongService service;
    @Builder.Default private List<KongRoute> routes = new ArrayList<>();
    private KongUpstream upstream;          // null when no upstream is needed
    @Builder.Default private List<KongTarget> targets = new ArrayList<>();
    /** Plugins scoped to this service (rate-limiting, cors, jwt, key-auth, response-transformer, ...). */
    @Builder.Default private List<KongPlugin> servicePlugins = new ArrayList<>();
    /** Per-route plugins, keyed by the route's name. */
    @Builder.Default private java.util.Map<String, List<KongPlugin>> routePlugins = new java.util.HashMap<>();

    /** Translator warnings that should land in the migration report. */
    @Builder.Default private List<String> warnings = new ArrayList<>();
}
