package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
import com.forgeshift.wso2.migration.reader.CredentialReader;
import com.forgeshift.wso2.migration.reader.DiscoverySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-username uniqueness: WSO2 application names are NOT unique (every user gets a
 * "DefaultApplication"), but Kong/decK require unique consumer usernames — a duplicate makes
 * {@code deck gateway validate} abort the whole deploy. The translator must disambiguate.
 */
class SubscriptionTranslatorTest {

    private SubscriptionTranslator translator() {
        MigrationProperties props = new MigrationProperties();
        props.getCredentials().setEnabled(false); // skip the credential reader (needs Mongo) for a pure unit test
        return new SubscriptionTranslator(props, new ThrottlingTierResolver(null, props), null,
                new CredentialTranslator(props));
    }

    private static DiscoverySnapshot app(String id, String name) {
        return DiscoverySnapshot.builder().sourceId(id).sourceName(name)
                .companyName("acme").wso2Tenant("carbon.super")
                .payload(Map.of("name", name)).build();
    }

    @Test
    void duplicateAppNames_produceUniqueConsumerUsernames() {
        List<TranslatedConsumer> out = translator().translate(
                List.of(app("id-aaa", "DefaultApplication"), app("id-bbb", "DefaultApplication")),
                List.of());

        List<String> usernames = out.stream().map(c -> c.getConsumer().getUsername()).toList();
        assertThat(usernames).as("two DefaultApplications must NOT share a username").doesNotHaveDuplicates();
        assertThat(usernames).allMatch(u -> u.startsWith("defaultapplication"));
        // custom_id keeps each app's unique id for traceability
        assertThat(out.stream().map(c -> c.getConsumer().getCustom_id()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("id-aaa", "id-bbb");
    }

    @Test
    void distinctAppNames_keepCleanUsernames() {
        // No collision → names stay clean (no id suffix appended).
        List<TranslatedConsumer> out = translator().translate(
                List.of(app("id-1", "MobileApp"), app("id-2", "PartnerPortal")),
                List.of());
        List<String> usernames = out.stream().map(c -> c.getConsumer().getUsername()).toList();
        assertThat(usernames).containsExactlyInAnyOrder("mobileapp", "partnerportal");
    }

    /** With credentials enabled, a CredentialReader whose Mongo calls fail returns empty (try/caught). */
    private SubscriptionTranslator translatorWithCreds() {
        MigrationProperties props = new MigrationProperties();   // credentials.enabled = true by default
        CredentialReader reader = new CredentialReader(null, props); // Mongo NPE → swallowed → empty
        return new SubscriptionTranslator(props, new ThrottlingTierResolver(null, props),
                reader, new CredentialTranslator(props));
    }

    @Test
    void liveCreds_recreateAWorkingJwtCredential() {
        String pem = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA\n-----END PUBLIC KEY-----\n";
        Map<String, List<CredentialReader.AppCredential>> live = Map.of("id-1", List.of(
                CredentialReader.AppCredential.builder()
                        .applicationId("id-1").applicationName("MobileApp")
                        .keyType("PRODUCTION").keyManager("Resident Key Manager")
                        .consumerKey("CONSUMER_KEY_ABC").keyManagerPublicKeyPem(pem).build()));

        List<TranslatedConsumer> out = translatorWithCreds().translate(
                List.of(app("id-1", "MobileApp")), List.of(), live);

        var jwts = out.get(0).getConsumer().getJwt_secrets();
        assertThat(jwts).as("live-fetched key → a jwt credential").isNotNull().hasSize(1);
        assertThat(jwts.get(0))
                .containsEntry("key", "CONSUMER_KEY_ABC")      // azp the WSO2 token carries
                .containsEntry("algorithm", "RS256")
                .containsEntry("rsa_public_key", pem);         // KM public key inlined → self-contained
    }

    private static DiscoverySnapshot sub(String appId, String apiId) {
        return DiscoverySnapshot.builder().sourceId(appId + ":" + apiId).sourceName("sub")
                .companyName("acme").wso2Tenant("carbon.super")
                .payload(Map.of("applicationId", appId, "apiId", apiId)).build();
    }

    @Test
    void apiKeyScheme_fromOverride_recreatesKeyAuthCredentialNotJwt() {
        Map<String, List<CredentialReader.AppCredential>> live = Map.of("id-1", List.of(
                CredentialReader.AppCredential.builder()
                        .applicationId("id-1").applicationName("MobileApp")
                        .keyType("PRODUCTION").keyManager("Resident Key Manager")
                        .consumerKey("KEY123").build()));
        // The app subscribes to api-x, which the migrated API exposes as key-auth (api_key).
        Map<String, java.util.Set<String>> schemes = Map.of("api-x", java.util.Set.of("api_key"));

        List<TranslatedConsumer> out = translatorWithCreds().translate(
                List.of(app("id-1", "MobileApp")), List.of(sub("id-1", "api-x")), live, schemes);

        var ka = out.get(0).getConsumer().getKeyauth_credentials();
        assertThat(ka).as("api_key scheme → key-auth credential").isNotNull().hasSize(1);
        assertThat(ka.get(0)).containsEntry("key", "KEY123");  // INLINE: consumer key is the Kong key
        assertThat(out.get(0).getConsumer().getJwt_secrets())
                .as("api_key-only API → no jwt credential").isNull();
    }
}
