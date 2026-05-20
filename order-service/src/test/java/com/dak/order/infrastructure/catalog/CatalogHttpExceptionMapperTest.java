package com.dak.order.infrastructure.catalog;

import com.dak.order.domain.exception.CatalogUnavailableException;
import com.dak.order.domain.exception.ProductOfferingNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for all four translation rules in {@link CatalogHttpExceptionMapper}.
 * No Spring context needed — the mapper is pure logic with no dependencies.
 */
class CatalogHttpExceptionMapperTest {

    private final CatalogHttpExceptionMapper mapper = new CatalogHttpExceptionMapper();

    @Test
    void call_returnsValue_whenCallSucceeds() throws Exception {
        String result = mapper.call("po-1", () -> "success");
        assertThat(result).isEqualTo("success");
    }

    @Test
    void call_completesWithoutException_forVoidEndpoints() {
        // void endpoints return null from the Callable — no exception should be thrown
        assertThatCode(() -> mapper.<Void>call("po-1", () -> null))
                .doesNotThrowAnyException();
    }

    @Test
    void call_translates_404_to_ProductOfferingNotFoundException_withResourceId() {
        assertThatThrownBy(() -> mapper.call("po-missing", () -> {
            throw HttpClientErrorException.NotFound.create(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", null, null, null);
        }))
                .isInstanceOf(ProductOfferingNotFoundException.class)
                .hasMessageContaining("po-missing");
    }

    @Test
    void call_translates_5xx_to_CatalogUnavailableException() {
        assertThatThrownBy(() -> mapper.call("po-1", () -> {
            throw HttpServerErrorException.ServiceUnavailable.create(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Service Unavailable", null, null, null);
        }))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void call_translates_NetworkError_to_CatalogUnavailableException() {
        assertThatThrownBy(() -> mapper.call("po-1", () -> {
            throw new ResourceAccessException("Connection refused");
        }))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void call_translates_unexpected4xx_to_CatalogUnavailableException() {
        // 401 Unauthorized — indicates wrong API key or broken contract
        assertThatThrownBy(() -> mapper.call("po-1", () -> {
            throw HttpClientErrorException.Unauthorized.create(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Unauthorized", null, null, null);
        }))
                .isInstanceOf(CatalogUnavailableException.class);
    }
}
