package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.domain.kong.KongCaCertificate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Kong objects produced for one WSO2 endpoint certificate. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslatedCertificate {
    private String wso2SourceId;     // the WSO2 certificate alias
    private String wso2SourceName;
    private KongCaCertificate caCertificate;
    @Builder.Default private List<String> warnings = new ArrayList<>();
}
