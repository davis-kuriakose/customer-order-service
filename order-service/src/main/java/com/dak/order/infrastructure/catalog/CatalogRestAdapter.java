package com.dak.order.infrastructure.catalog;

import com.dak.order.domain.exception.CatalogUnavailableException;
import com.dak.order.domain.exception.ProductOfferingNotFoundException;
import com.dak.order.domain.port.outbound.CatalogPort;
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
    public void validateOfferings(List<String> productOfferingIds) {
        productOfferingIds.forEach(this::validateSingleOffering);
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
