package com.forgeshift.wso2.migration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** Selective endpoint-certificate migration (WSO2 endpoint certs → Kong ca_certificates). */
@Data
@EqualsAndHashCode(callSuper = true)
public class Wso2MigrateCertificatesRequest extends Wso2BaseMigrationRequest {

    @NotEmpty(message = "certificates list is required and must not be empty")
    @JsonProperty("certificates")
    @Schema(description = "WSO2 endpoint-certificate aliases to migrate",
            example = "[\"backend-cert-alias\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> certificates = new ArrayList<>();
}
