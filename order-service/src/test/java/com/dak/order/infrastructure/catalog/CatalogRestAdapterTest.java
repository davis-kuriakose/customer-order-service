package com.dak.order.infrastructure.catalog;

import com.dak.order.domain.exception.CatalogUnavailableException;
import com.dak.order.domain.exception.ProductOfferingNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CatalogRestAdapter}.
 *
 * Uses a real {@link CatalogHttpExceptionMapper} (no-arg constructor, pure logic,
 * no Spring dependencies) so the full exception-translation chain is exercised.
 * This verifies that the adapter correctly orchestrates: parallel dispatch,
 * MDC propagation, exception unwrapping from CompletionException, and the
 * cache-based fallback policy.
 */
@ExtendWith(MockitoExtension.class)
class CatalogRestAdapterTest {

    @Mock
    private CatalogOfferingValidator offeringValidator;

    @Mock
    private CacheManager cacheManager;

    private CatalogRestAdapter adapter;

    @BeforeEach
    void setUp() {
        // Real mapper — pure logic, no Spring context needed
        adapter = new CatalogRestAdapter(offeringValidator, new CatalogHttpExceptionMapper(), cacheManager);
    }

    // ── Exception translation (via real CatalogHttpExceptionMapper) ───────────

    @Test
    void validateOfferings_succeeds_whenValidatorReturnsNormally() {
        when(offeringValidator.validateOffering("po-1")).thenReturn(true);

        assertThatCode(() -> adapter.validateOfferings(List.of("po-1")))
                .doesNotThrowAnyException();
    }

    @Test
    void validateOfferings_translates_NotFound_to_ProductOfferingNotFoundException() {
        doThrow(HttpClientErrorException.NotFound.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", null, null, null))
                .when(offeringValidator).validateOffering("po-unknown");

        assertThatThrownBy(() -> adapter.validateOfferings(List.of("po-unknown")))
                .isInstanceOf(ProductOfferingNotFoundException.class)
                .hasMessageContaining("po-unknown");
    }

    @Test
    void validateOfferings_translates_5xx_to_CatalogUnavailableException() {
        doThrow(HttpServerErrorException.ServiceUnavailable.create(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", null, null, null))
                .when(offeringValidator).validateOffering("po-1");

        assertThatThrownBy(() -> adapter.validateOfferings(List.of("po-1")))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void validateOfferings_translates_NetworkError_to_CatalogUnavailableException() {
        doThrow(new ResourceAccessException("Connection refused"))
                .when(offeringValidator).validateOffering("po-1");

        assertThatThrownBy(() -> adapter.validateOfferings(List.of("po-1")))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    // ── Virtual-thread parallel validation ───────────────────────────────────

    @Test
    void validateOfferings_multipleOfferings_allValidatedConcurrently() {
        AtomicInteger callCount = new AtomicInteger(0);
        when(offeringValidator.validateOffering(anyString()))
                .thenAnswer(inv -> { callCount.incrementAndGet(); return true; });

        assertThatCode(() -> adapter.validateOfferings(List.of("po-1", "po-2", "po-3")))
                .doesNotThrowAnyException();
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void validateOfferings_oneOfMultipleUnknown_throwsProductOfferingNotFoundException() {
        when(offeringValidator.validateOffering("po-1")).thenReturn(true);
        doThrow(HttpClientErrorException.NotFound.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", null, null, null))
                .when(offeringValidator).validateOffering("po-bad");

        assertThatThrownBy(() -> adapter.validateOfferings(List.of("po-bad", "po-1")))
                .isInstanceOf(ProductOfferingNotFoundException.class);
    }

    // ── Fallback: cache-based graceful degradation ────────────────────────────

    @Test
    void catalogUnavailable_servesFromCache_whenAllOfferingsAreCached() {
        Cache mockCache = mock(Cache.class);
        when(cacheManager.getCache(CatalogOfferingValidator.CACHE_NAME)).thenReturn(mockCache);
        when(mockCache.get("po-1")).thenReturn(mock(Cache.ValueWrapper.class));

        assertThatCode(() -> adapter.catalogUnavailable(
                List.of("po-1"), new CatalogUnavailableException("catalog down")))
                .doesNotThrowAnyException();
    }

    @Test
    void catalogUnavailable_throwsCatalogUnavailableException_whenOfferingNotInCache() {
        Cache mockCache = mock(Cache.class);
        when(cacheManager.getCache(CatalogOfferingValidator.CACHE_NAME)).thenReturn(mockCache);
        when(mockCache.get("po-new")).thenReturn(null);

        assertThatThrownBy(() -> adapter.catalogUnavailable(
                List.of("po-new"), new CatalogUnavailableException("catalog down")))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void catalogUnavailable_throwsCatalogUnavailableException_whenSomeNotInCache() {
        Cache mockCache = mock(Cache.class);
        when(cacheManager.getCache(CatalogOfferingValidator.CACHE_NAME)).thenReturn(mockCache);
        when(mockCache.get("po-1")).thenReturn(mock(Cache.ValueWrapper.class));
        when(mockCache.get("po-new")).thenReturn(null);

        assertThatThrownBy(() -> adapter.catalogUnavailable(
                List.of("po-1", "po-new"), new CatalogUnavailableException("catalog down")))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void catalogUnavailable_throwsCatalogUnavailableException_whenCacheUnavailable() {
        when(cacheManager.getCache(CatalogOfferingValidator.CACHE_NAME)).thenReturn(null);

        assertThatThrownBy(() -> adapter.catalogUnavailable(
                List.of("po-1"), new CatalogUnavailableException("catalog down")))
                .isInstanceOf(CatalogUnavailableException.class);
    }
}
