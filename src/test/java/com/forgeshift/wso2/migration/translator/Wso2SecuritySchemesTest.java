package com.forgeshift.wso2.migration.translator;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Wso2SecuritySchemesTest {

    @Test
    void mandatoryFlagIsNotMistakenForApiKeyScheme() {
        // The regression: the WSO2 flag contains the substring "api_key" but is NOT the api_key scheme.
        // An OAuth2-only API carrying the flag must come out jwt-only — never get a stray key-auth.
        List<String> oauth2Only = List.of("oauth_basic_auth_api_key_mandatory", "oauth2");
        assertThat(Wso2SecuritySchemes.hasOauth2(oauth2Only)).isTrue();
        assertThat(Wso2SecuritySchemes.hasApiKey(oauth2Only)).isFalse();
        assertThat(Wso2SecuritySchemes.isEitherAuthAccepted(oauth2Only)).isFalse();
    }

    @Test
    void apiKeyOnlyWithFlag_isApiKeyOnly() {
        List<String> apiKeyOnly = List.of("api_key", "oauth_basic_auth_api_key_mandatory");
        assertThat(Wso2SecuritySchemes.hasApiKey(apiKeyOnly)).isTrue();
        assertThat(Wso2SecuritySchemes.hasOauth2(apiKeyOnly)).isFalse();
        assertThat(Wso2SecuritySchemes.isEitherAuthAccepted(apiKeyOnly)).isFalse();
    }

    @Test
    void bothSchemes_mandatory_isAnd_notEither() {
        List<String> both = List.of("oauth2", "api_key", "oauth_basic_auth_api_key_mandatory");
        assertThat(Wso2SecuritySchemes.hasOauth2(both)).isTrue();
        assertThat(Wso2SecuritySchemes.hasApiKey(both)).isTrue();
        assertThat(Wso2SecuritySchemes.isEitherAuthAccepted(both)).isFalse();   // mandatory → both required (AND)
    }

    @Test
    void bothSchemes_optional_isEither_or() {
        List<String> both = List.of("oauth2", "api_key", "oauth_basic_auth_api_key_optional");
        assertThat(Wso2SecuritySchemes.isEitherAuthAccepted(both)).isTrue();    // optional → either works (OR)
    }

    @Test
    void toleratesNullCaseAndWhitespace() {
        assertThat(Wso2SecuritySchemes.hasApiKey(null)).isFalse();
        assertThat(Wso2SecuritySchemes.hasOauth2(Arrays.asList(null, " OAuth2 "))).isTrue();
        assertThat(Wso2SecuritySchemes.hasApiKey(List.of(" API_KEY "))).isTrue();
    }
}
