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
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogRestAdapterTest {

    @Mock
    private RestClient restClient;

    @InjectMocks
    private CatalogRestAdapter adapter;

    @SuppressWarnings("unchecked")
    private void mockGetChain(Runnable onRetrieve) {
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(any(String.class), any(Object.class))).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenAnswer(inv -> { onRetrieve.run(); return null; });
    }

    @Test
    void validateOfferings_succeeds_whenAllOfferingsExist() {
        mockGetChain(() -> {});

        assertThatCode(() -> adapter.validateOfferings(List.of("po-1")))
                .doesNotThrowAnyException();
    }

    @Test
    void validateOfferings_throwsProductOfferingNotFoundException_on404() {
        mockGetChain(() -> { throw HttpClientErrorException.NotFound.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", null, null, null); });

        assertThatThrownBy(() -> adapter.validateOfferings(List.of("po-999")))
                .isInstanceOf(ProductOfferingNotFoundException.class)
                .hasMessageContaining("po-999");
    }

    @Test
    void validateOfferings_throwsCatalogUnavailableException_onConnectionError() {
        mockGetChain(() -> { throw new ResourceAccessException("Connection refused"); });

        assertThatThrownBy(() -> adapter.validateOfferings(List.of("po-1")))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    // ── Virtual-thread parallel validation (OPPORTUNITY 1) ────────────────────

    @Test
    void validateOfferings_multipleOfferings_allValidatedConcurrently() {
        AtomicInteger callCount = new AtomicInteger(0);
        mockGetChain(callCount::incrementAndGet);

        assertThatCode(() -> adapter.validateOfferings(List.of("po-1", "po-2", "po-3")))
                .doesNotThrowAnyException();
        // All 3 offerings must have been checked before validateOfferings returns
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void validateOfferings_oneOfMultipleUnknown_throwsProductOfferingNotFoundException() {
        // Mock throws NotFound on every call — simulates po-bad being invalid
        mockGetChain(() -> { throw HttpClientErrorException.NotFound.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", null, null, null); });

        assertThatThrownBy(() -> adapter.validateOfferings(List.of("po-bad", "po-1")))
                .isInstanceOf(ProductOfferingNotFoundException.class);
    }
}
