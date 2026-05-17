package com.dak.order.infrastructure.catalog;

import com.dak.order.domain.exception.CatalogUnavailableException;
import com.dak.order.domain.exception.ProductOfferingNotFoundException;
import com.dak.order.domain.port.outbound.CatalogPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class CatalogRestAdapter implements CatalogPort {

    private static final Logger log = LoggerFactory.getLogger(CatalogRestAdapter.class);

    private final RestClient restClient;

    public CatalogRestAdapter(@Qualifier("catalogRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @CircuitBreaker(name = "catalog", fallbackMethod = "catalogUnavailable")
    public void validateOfferings(List<String> productOfferingIds) {
        productOfferingIds.forEach(this::validateSingleOffering);
    }

    // Fallback: called when circuit is OPEN (fail-fast) or when CatalogUnavailableException escapes.
    // ProductOfferingNotFoundException is in ignoreExceptions — it bypasses this fallback entirely.
    @SuppressWarnings("unused")
    private void catalogUnavailable(List<String> productOfferingIds, Throwable t) {
        log.warn("Catalog circuit open — fast-failing validation for {} offering(s)",
                productOfferingIds.size(), t);
        if (t instanceof CatalogUnavailableException e) {
            throw e;
        }
        throw new CatalogUnavailableException(t);
    }

    private void validateSingleOffering(String id) {
        try {
            restClient.get()
                    .uri("/product-offerings/{id}", id)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductOfferingNotFoundException(id);
        } catch (RestClientException e) {
            log.warn("Catalog service unreachable when validating offering {}", id, e);
            throw new CatalogUnavailableException(e);
        }
    }
}
