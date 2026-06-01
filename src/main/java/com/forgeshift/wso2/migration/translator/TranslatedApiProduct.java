package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import com.forgeshift.wso2.migration.domain.kong.KongRoute;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Kong objects produced for one WSO2 API Product. A product has no backend of its
 * own, so it produces only {@link ProductRoute routes} — each route is created
 * under the product's context and attached to a <em>member API's</em> Kong service
 * (resolved from {@code entity_mappings} at deploy time).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslatedApiProduct {
    private String wso2SourceId;     // the WSO2 API Product id
    private String wso2SourceName;
    private String wso2SourceVersion;

    @Builder.Default private List<ProductRoute> routes = new ArrayList<>();
    @Builder.Default private List<String> warnings = new ArrayList<>();

    /** Distinct member API ids this product depends on (must be migrated first). */
    public Set<String> memberApiIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ProductRoute r : routes) {
            if (r.getMemberApiId() != null) ids.add(r.getMemberApiId());
        }
        return ids;
    }

    /** One Kong route plus the member API whose Kong service it must attach to. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductRoute {
        private KongRoute route;
        private String memberApiId;
        /** Plugins to attach to this route: the product's policies/security copied per route, plus any per-operation tier. */
        @Builder.Default private List<KongPlugin> plugins = new ArrayList<>();
    }
}
