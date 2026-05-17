package com.dak.order.infrastructure.catalog;

import com.dak.order.domain.exception.CatalogUnavailableException;
import com.dak.order.domain.exception.ProductOfferingNotFoundException;
import com.dak.order.domain.port.outbound.CatalogPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Component
public class CatalogRestAdapter implements CatalogPort {

    private static final Logger log = LoggerFactory.getLogger(CatalogRestAdapter.class);
    private static final String CORRELATION_MDC_KEY = "correlationId";

    // One virtual thread per offering — parks cheaply on blocking I/O, no carrier thread pinned.
    private static final Executor VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final RestClient restClient;

    public CatalogRestAdapter(@Qualifier("catalogRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @CircuitBreaker(name = "catalog", fallbackMethod = "catalogUnavailable")
    public void validateOfferings(List<String> productOfferingIds) {
        // Capture MDC before forking — child virtual threads do not inherit ThreadLocal values.
        String correlationId = MDC.get(CORRELATION_MDC_KEY);

        List<CompletableFuture<Void>> futures = productOfferingIds.stream()
                .map(id -> CompletableFuture.runAsync(
                        () -> validateWithMdc(id, correlationId), VIRTUAL_THREAD_EXECUTOR))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ProductOfferingNotFoundException pnfe) throw pnfe;
            if (cause instanceof CatalogUnavailableException cue) throw cue;
            throw new CatalogUnavailableException(cause);
        }
    }

    private void validateWithMdc(String id, String correlationId) {
        if (correlationId != null) MDC.put(CORRELATION_MDC_KEY, correlationId);
        try {
            validateSingleOffering(id);
        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }
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
}
