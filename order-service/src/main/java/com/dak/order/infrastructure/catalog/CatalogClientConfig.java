package com.dak.order.infrastructure.catalog;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class CatalogClientConfig {

    @Bean
    public RestClient catalogRestClient(
            @Value("${app.catalog.base-url}") String baseUrl,
            @Value("${app.catalog.api-key}") String apiKey) {

        // Shared HttpClient with connection pooling (keep-alive) and HTTP/2 multiplexing.
        // connectTimeout enforces the 2-second deadline for establishing the TCP connection.
        // SimpleClientHttpRequestFactory (the previous default) opened a new TCP connection
        // per request with no keep-alive — expensive under high request rates.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        // Per-request read timeout: each HTTP call to catalog-service is cancelled
        // after 2 seconds if no response bytes arrive. This is the active deadline
        // for catalog validation — not the Resilience4j timelimiter config block
        // (which was removed because @TimeLimiter only applies to CompletableFuture methods).
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-API-Key", apiKey)
                .requestInterceptor((request, body, execution) -> {
                    // Forward the active correlation ID so the catalog service logs share the same trace.
                    String correlationId = MDC.get("correlationId");
                    if (correlationId != null) {
                        request.getHeaders().set("X-Correlation-ID", correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
