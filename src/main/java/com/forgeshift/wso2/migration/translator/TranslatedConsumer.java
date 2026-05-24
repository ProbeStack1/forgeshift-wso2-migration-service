package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.KongConsumer;
import com.forgeshift.wso2.migration.domain.kong.KongPlugin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslatedConsumer {
    private String wso2SourceId;         // WSO2 application id (Consumer = Application)
    private String wso2SourceName;
    private KongConsumer consumer;
    /** Consumer-scoped plugins (e.g. rate-limiting for a specific app's tier). */
    @Builder.Default private List<KongPlugin> consumerPlugins = new ArrayList<>();
    @Builder.Default private List<String> warnings = new ArrayList<>();
}
