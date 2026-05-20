package com.dak.order.infrastructure.catalog;

import com.dak.order.domain.exception.CatalogUnavailableException;
import com.dak.order.domain.exception.ProductOfferingNotFoundException;
import com.dak.order.domain.port.outbound.CatalogPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

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

    private final CatalogOfferingValidator offeringValidator;
    private final CatalogHttpExceptionMapper exceptionMapper;
    private final CacheManager cacheManager;

    public CatalogRestAdapter(CatalogOfferingValidator offeringValidator,
                              CatalogHttpExceptionMapper exceptionMapper,
                              CacheManager cacheManager) {
        this.offeringValidator = offeringValidator;
        this.exceptionMapper = exceptionMapper;
        this.cacheManager = cacheManager;
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
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ProductOfferingNotFoundException pnfe) throw pnfe;
            if (cause instanceof CatalogUnavailableException cue) throw cue;
            throw new CatalogUnavailableException(cause);
        }
    }

    /**
     * Calls the validator under the current MDC context.
     * Exception translation is fully delegated to {@link CatalogHttpExceptionMapper#call}.
     *
     * Intentionally called OUTSIDE the @Retry scope on {@link CatalogOfferingValidator#validateOffering}:
     * @Retry sees raw Spring exceptions (HttpServerErrorException, ResourceAccessException) and
     * retries them before the mapper translates them to domain exceptions.
     */
    private void validateWithMdc(String id, String correlationId) {
        if (correlationId != null) MDC.put(CORRELATION_MDC_KEY, correlationId);
        try {
            exceptionMapper.call(id, () -> {
                offeringValidator.validateOffering(id);
                return null;   // checkOffering is void; Callable<T> requires a return value
            });
        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }
    }

    /**
     * Fallback — called by @CircuitBreaker when the circuit is OPEN or after
     * {@link CatalogUnavailableException} escapes all retry attempts.
     *
     * Accepts the order only if EVERY offering was previously validated and cached locally.
     * If any offering is unknown, we fail safely with 503 rather than risk an invalid order.
     */
    @SuppressWarnings("unused")
    void catalogUnavailable(List<String> productOfferingIds, Throwable t) {
        List<String> offeringsNotInCache = productOfferingIds.stream()
                .filter(id -> !wasSuccessfullyValidatedBefore(id))
                .toList();

        if (offeringsNotInCache.isEmpty()) {
            log.warn("Catalog unreachable — accepting order; all {} offering(s) confirmed from local cache",
                    productOfferingIds.size());
            return;
        }

        log.warn("Catalog unreachable — rejecting order; {} offering(s) not in local cache: {}",
                offeringsNotInCache.size(), offeringsNotInCache);
        if (t instanceof CatalogUnavailableException e) throw e;
        throw new CatalogUnavailableException(t);
    }

    private boolean wasSuccessfullyValidatedBefore(String offeringId) {
        Cache cache = cacheManager.getCache(CatalogOfferingValidator.CACHE_NAME);
        return cache != null && cache.get(offeringId) != null;
    }
}
