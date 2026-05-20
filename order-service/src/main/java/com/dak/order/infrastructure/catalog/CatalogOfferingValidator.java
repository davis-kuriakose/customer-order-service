package com.dak.order.infrastructure.catalog;

import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Validates a single product offering ID against the catalog service.
 *
 * Resilience layers — applied outer-to-inner by Spring AOP aspect ordering:
 *
 *   @Retry (outer) — retries on transient failures (5xx, network errors) before
 *   propagating. 4xx errors are in ignore-exceptions and pass through immediately.
 *   Raw Spring exceptions are intentionally NOT translated here so that @Retry
 *   can distinguish retryable (5xx, ResourceAccessException) from
 *   non-retryable (4xx) exceptions. Translation to domain exceptions happens
 *   in {@link CatalogRestAdapter#validateWithMdc} after all retry attempts finish.
 *
 *   @Cacheable (inner) — caches successful validations (returns true). On a cache
 *   hit the HTTP call is skipped entirely — also skipping retry overhead.
 *   Exceptions are never cached, so unknown or currently-unavailable offerings
 *   always re-query the catalog.
 */
@Component
public class CatalogOfferingValidator {

    static final String CACHE_NAME = "catalog-valid-offerings";

    private final CatalogHttpClient catalogHttpClient;

    public CatalogOfferingValidator(CatalogHttpClient catalogHttpClient) {
        this.catalogHttpClient = catalogHttpClient;
    }

    /**
     * Returns {@code true} when the offering exists. Throws a raw Spring
     * {@code HttpClientErrorException} or {@code ResourceAccessException}
     * on failure — the caller translates these to domain exceptions.
     */
    @Cacheable(value = CACHE_NAME, key = "#offeringId")
    @Retry(name = "catalog")
    public boolean validateOffering(String offeringId) {
        catalogHttpClient.checkOffering(offeringId);
        return true;
    }
}
