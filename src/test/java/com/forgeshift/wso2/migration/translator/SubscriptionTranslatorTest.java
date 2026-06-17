package com.forgeshift.wso2.migration.translator;

import com.forgeshift.wso2.migration.config.MigrationProperties;
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
}
