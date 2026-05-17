package com.dak.order.infrastructure.catalog;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class CatalogClientConfig {

    @Bean
    public RestClient catalogRestClient(
            @Value("${app.catalog.base-url}") String baseUrl,
            @Value("${app.catalog.api-key}") String apiKey) {

        return RestClient.builder()
                .baseUrl(baseUrl)
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
