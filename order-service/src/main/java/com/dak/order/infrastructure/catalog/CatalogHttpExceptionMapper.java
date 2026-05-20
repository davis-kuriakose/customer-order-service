package com.dak.order.infrastructure.catalog;

import com.dak.order.domain.exception.CatalogUnavailableException;
import com.dak.order.domain.exception.ProductOfferingNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.Callable;

/**
 * Translates Spring HTTP exceptions thrown by catalog-service calls into domain exceptions.
 *
 * This is a Spring-managed component so it can be injected and mocked in tests
 * without requiring PowerMock or static method interception.
 *
 * Usage — every catalog endpoint call passes through here:
 *
 *   // void endpoint
 *   exceptionMapper.call(offeringId, () -> { catalogHttpClient.checkOffering(offeringId); return null; });
 *
 *   // value-returning endpoint (same method, no overload needed)
 *   String details = exceptionMapper.call(offeringId, () -> catalogHttpClient.getDetails(offeringId));
 *
 * Translation rules:
 *   404  → ProductOfferingNotFoundException  (surfaces as 422 — invalid reference in request)
 *   5xx  → CatalogUnavailableException       (surfaces as 503 — catalog server is failing)
 *   net  → CatalogUnavailableException       (surfaces as 503 — network/timeout error)
 *   4xx* → CatalogUnavailableException + error log (config/contract problem, investigate)
 *
 * Why not in RestClient.defaultStatusHandler?
 * The HTTP client does not carry the resource ID needed to construct domain exceptions
 * (e.g. ProductOfferingNotFoundException requires the offering ID, not just the URL path).
 *
 * Retry interaction: this translator is called from CatalogRestAdapter.validateWithMdc(),
 * which is OUTSIDE the @Retry scope on CatalogOfferingValidator.validateOffering().
 * @Retry sees the raw Spring exceptions and retries 5xx/network errors before they
 * reach this class. Translation happens only after all retry attempts are exhausted.
 */
@Component
class CatalogHttpExceptionMapper {

    private static final Logger log = LoggerFactory.getLogger(CatalogHttpExceptionMapper.class);

    /**
     * Executes {@code call} and maps any Spring HTTP exception to the appropriate domain
     * exception. {@code resourceId} is used in exception messages and log entries.
     *
     * @param resourceId the identifier of the resource being requested (e.g. offering ID)
     * @param call       the HTTP operation to execute; return {@code null} for void endpoints
     * @param <T>        return type of the HTTP operation
     * @return the value returned by {@code call}
     */
    <T> T call(String resourceId, Callable<T> call) {
        try {
            return call.call();

        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductOfferingNotFoundException(resourceId);

        } catch (HttpServerErrorException | ResourceAccessException e) {
            // 5xx or network failure — retries already exhausted upstream
            throw new CatalogUnavailableException(e);

        } catch (HttpClientErrorException e) {
            // Unexpected 4xx — not retried. Indicates wrong API key, bad request shape,
            // or a contract mismatch. Log as error so the team investigates immediately.
            log.error("Unexpected {} from catalog for resource '{}' — verify API key and request contract",
                    e.getStatusCode(), resourceId);
            throw new CatalogUnavailableException(e);

        } catch (RuntimeException e) {
            throw e;

        } catch (Exception e) {
            // Callable.call() declares checked Exception; re-wrap any unexpected checked exception
            throw new CatalogUnavailableException(e);
        }
    }
}
