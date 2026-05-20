package com.dak.order.infrastructure.catalog;

import com.dak.order.domain.exception.CatalogUnavailableException;
import com.dak.order.domain.exception.ProductOfferingNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpServerErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/**
 * Unit tests for {@link CatalogOfferingValidator}.
 *
 * Important: {@code @Cacheable} and {@code @Retry} aspects are NOT active here
 * (no Spring context). These tests verify only the raw HTTP-to-exception translation
 * that the validator delegates to {@link CatalogHttpClient}. The retry and caching
 * behaviour is covered by {@link CatalogResilienceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class CatalogOfferingValidatorTest {

    @Mock
    private CatalogHttpClient catalogHttpClient;

    @InjectMocks
    private CatalogOfferingValidator validator;

    @Test
    void validateOffering_returnsTrue_whenCatalogReturns200() {
        // checkOffering is void — Mockito stubs void methods to do nothing by default
        assertThat(validator.validateOffering("po-1")).isTrue();
    }

    @Test
    void validateOffering_propagates_HttpNotFound_on404() {
        // @Retry is configured to ignore HttpClientErrorException — the raw 404 propagates
        // immediately without retrying. CatalogRestAdapter.validateWithMdc translates it.
        doThrow(HttpClientErrorException.NotFound.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", null, null, null))
                .when(catalogHttpClient).checkOffering("po-unknown");

        assertThatThrownBy(() -> validator.validateOffering("po-unknown"))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void validateOffering_propagates_HttpServerErrorException_on5xx() {
        // @Retry retries this — but without Spring context, no retry here.
        // After retries the raw exception propagates to CatalogRestAdapter.validateWithMdc.
        doThrow(HttpServerErrorException.ServiceUnavailable.create(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", null, null, null))
                .when(catalogHttpClient).checkOffering("po-1");

        assertThatThrownBy(() -> validator.validateOffering("po-1"))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void validateOffering_propagates_ResourceAccessException_onNetworkError() {
        doThrow(new ResourceAccessException("Connection refused"))
                .when(catalogHttpClient).checkOffering("po-1");

        assertThatThrownBy(() -> validator.validateOffering("po-1"))
                .isInstanceOf(ResourceAccessException.class);
    }
}
