package com.dak.order.infrastructure.catalog;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Declarative HTTP interface for the Catalog Service.
 *
 * All transport concerns — base URL, auth header, connection pooling, read timeout,
 * correlation ID propagation — are configured once in {@link CatalogClientConfig}.
 * Each method here expresses only the HTTP verb, path, and parameters.
 *
 * Spring generates the implementation at startup via {@code HttpServiceProxyFactory}.
 * Adding a new catalog endpoint = adding one method here, nothing else.
 *
 * Error handling: Spring's RestClient throws {@code HttpClientErrorException} for 4xx
 * and {@code HttpServerErrorException} for 5xx. Translation to domain exceptions happens
 * in {@link CatalogRestAdapter#validateWithMdc}.
 */
@HttpExchange("/product-offerings")
public interface CatalogHttpClient {

    /**
     * Checks that the given offering ID exists in the catalog.
     * Returns normally on 200; throws {@code HttpClientErrorException.NotFound} on 404;
     * throws {@code HttpServerErrorException} on 5xx.
     */
    @GetExchange("/{id}")
    void checkOffering(@PathVariable String id);
}
