package com.forgeshift.wso2.migration.config;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.time.Duration;

/**
 * Single WebClient bean for Konnect Admin API calls. Per-tenant tokens and
 * base URLs are supplied at call time by KonnectAdminClient.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final MigrationProperties props;

    @Bean(name = "konnectWebClient")
    public WebClient konnectWebClient() throws SSLException {
        HttpClient http = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(props.getKonnect().getRequestTimeoutSeconds()));
        if (props.getKonnect().isTrustSelfSigned()) {
            log.warn("Konnect WebClient configured to trust all TLS. Dev only.");
            http = http.secure(spec -> {
                try {
                    spec.sslContext(SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .build());
                } catch (SSLException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
