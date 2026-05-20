package com.dak.order.infrastructure.catalog;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class CatalogClientConfig {

    /**
     * Declarative HTTP client for the Catalog Service.
     *
     * All shared transport concerns are configured here — once — and apply automatically
     * to every method on {@link CatalogHttpClient}:
     *   - Base URL from environment
     *   - X-API-Key authentication header
     *   - Connection pooling via shared JDK HttpClient (keep-alive + HTTP/2)
     *   - 2-second connect + read timeout
     *   - X-Correlation-ID propagation from SLF4J MDC
     *
     * Adding new catalog endpoints: add a method to {@link CatalogHttpClient}.
     * No changes here.
     */
    @Bean
    public CatalogHttpClient catalogHttpClient(
            @Value("${app.catalog.base-url}") String baseUrl,
            @Value("${app.catalog.api-key}") String apiKey) {

        // Shared HttpClient: connection pooling with keep-alive and HTTP/2 multiplexing.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-API-Key", apiKey)
                .requestInterceptor((request, body, execution) -> {
                    // Propagate active correlation ID so catalog logs share the same trace.
                    String correlationId = MDC.get("correlationId");
                    if (correlationId != null) {
                        request.getHeaders().set("X-Correlation-ID", correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(CatalogHttpClient.class);
    }
}
