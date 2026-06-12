package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.config.MigrationProperties.Credentials.SecretHandling;
import com.forgeshift.wso2.migration.domain.kong.KongConsumer;
import com.forgeshift.wso2.migration.reader.CredentialReader.AppCredential;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialTranslatorTest {

    private CredentialTranslator translator(SecretHandling mode) {
        MigrationProperties props = new MigrationProperties();
        props.getCredentials().setSecretHandling(mode);
        return new CredentialTranslator(props);
    }

    private static AppCredential cred() {
        return AppCredential.builder()
                .applicationId("app-uuid-5").applicationName("Seed05App")
                .keyType("PRODUCTION").keyManager("Resident Key Manager")
                .consumerKey("ck-abcdef123").consumerSecret("cs-supersecret")
                .supportedGrantTypes(List.of("client_credentials"))
                .build();
    }

    private static KongConsumer consumer() {
        return KongConsumer.builder().username("seed05app").custom_id("app-uuid-5").build();
    }

    @Test
    void oauth2Scheme_emitsJwtSecretKeyedOnConsumerKey_withKmCertReference() {
        KongConsumer c = consumer();
        CredentialTranslator.Result r = translator(SecretHandling.ENV).attach(
                c, "app-uuid-5", "Seed05App", List.of(cred()), Set.of("oauth2"), List.of("tag1"));

        assertThat(c.getKeyauth_credentials()).isNull();
        assertThat(c.getJwt_secrets()).hasSize(1);
        Map<String, Object> jwt = c.getJwt_secrets().get(0);
        assertThat(jwt).containsEntry("key", "ck-abcdef123")          // the azp/client-id claim (not secret)
                .containsEntry("algorithm", "RS256")
                .containsEntry("rsa_public_key", "${WSO2_KM_PUBLIC_KEY_RESIDENT_KEY_MANAGER}");
        // the KM public key is the operator's to supply (no value)
        assertThat(r.getManifest()).anySatisfy(ref -> {
            assertThat(ref.getReference()).isEqualTo("WSO2_KM_PUBLIC_KEY_RESIDENT_KEY_MANAGER");
            assertThat(ref.getValue()).isNull();
        });
    }

    @Test
    void apiKeyScheme_emitsKeyAuthAsEnvReference_withValueInManifest() {
        KongConsumer c = consumer();
        CredentialTranslator.Result r = translator(SecretHandling.ENV).attach(
                c, "app-uuid-5", "Seed05App", List.of(cred()), Set.of("api_key"), List.of("tag1"));

        assertThat(c.getJwt_secrets()).isNull();
        assertThat(c.getKeyauth_credentials()).hasSize(1);
        assertThat(c.getKeyauth_credentials().get(0))
                .containsEntry("key", "${WSO2_CRED_SEED05APP_PRODUCTION_KEY}");
        // the key value (consumer key) is carried for the pipeline to inject — never inlined in YAML
        assertThat(r.getManifest()).anySatisfy(ref -> {
            assertThat(ref.getReference()).isEqualTo("WSO2_CRED_SEED05APP_PRODUCTION_KEY");
            assertThat(ref.getValue()).isEqualTo("ck-abcdef123");
            assertThat(ref.getField()).isEqualTo("keyauth-key");
        });
    }

    @Test
    void vaultMode_emitsVaultReference() {
        KongConsumer c = consumer();
        translator(SecretHandling.VAULT).attach(
                c, "app-uuid-5", "Seed05App", List.of(cred()), Set.of("api_key"), null);

        assertThat(c.getKeyauth_credentials().get(0).get("key").toString())
                .isEqualTo("{vault://env/wso2-cred-seed05app-production-key}");
    }

    @Test
    void inlineMode_putsRawKey_andRecordsNoManifestEntry() {
        KongConsumer c = consumer();
        CredentialTranslator.Result r = translator(SecretHandling.INLINE).attach(
                c, "app-uuid-5", "Seed05App", List.of(cred()), Set.of("api_key"), null);

        assertThat(c.getKeyauth_credentials().get(0)).containsEntry("key", "ck-abcdef123");
        assertThat(r.getManifest()).noneSatisfy(ref ->
                assertThat(ref.getField()).isEqualTo("keyauth-key"));
    }

    @Test
    void bothSchemes_emitBothCredentialTypes() {
        KongConsumer c = consumer();
        translator(SecretHandling.ENV).attach(
                c, "app-uuid-5", "Seed05App", List.of(cred()), Set.of("oauth2", "api_key"), null);

        assertThat(c.getJwt_secrets()).hasSize(1);
        assertThat(c.getKeyauth_credentials()).hasSize(1);
    }

    @Test
    void noCapturedCredentials_warnsAndEmitsNothing() {
        KongConsumer c = consumer();
        CredentialTranslator.Result r = translator(SecretHandling.ENV).attach(
                c, "app-uuid-5", "Seed05App", List.of(), Set.of("oauth2"), null);

        assertThat(c.getJwt_secrets()).isNull();
        assertThat(c.getKeyauth_credentials()).isNull();
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("no OAuth2 credentials were captured"));
    }

    @Test
    void unknownScheme_defaultsToJwtWithWarning() {
        KongConsumer c = consumer();
        CredentialTranslator.Result r = translator(SecretHandling.ENV).attach(
                c, "app-uuid-5", "Seed05App", List.of(cred()), Set.of(), null);

        assertThat(c.getJwt_secrets()).hasSize(1);
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("security scheme unknown"));
    }
}
