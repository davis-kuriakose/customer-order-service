package com.dak.order.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resilience integration tests verifying retry, circuit-breaker, and cache-fallback
 * behaviour using an in-process WireMock server to simulate catalog-service responses.
 *
 * Evidence for:
 *  - Retry fires on 5xx (WireMock stateful scenarios)
 *  - Retry does NOT fire on 404
 *  - Timeout triggers retry (WireMock fixed delay > 2s socket timeout)
 *  - Circuit opens after repeated failures
 *  - Cache fallback: orders accepted when circuit is OPEN for known offerings
 *  - Cache fallback: 503 when circuit is OPEN for never-validated offerings
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CatalogResilienceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static final WireMockServer wireMock =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.catalog.base-url", wireMock::baseUrl);
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired CacheManager cacheManager;

    private static final String API_KEY = "dev-api-key";

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        // Reset the circuit breaker to CLOSED with empty metrics window
        circuitBreakerRegistry.circuitBreaker("catalog").reset();
        // Evict the local offering validation cache so tests are independent
        Objects.requireNonNull(cacheManager.getCache("catalog-valid-offerings")).clear();
    }

    // ── RETRY: 5xx triggers retry, second attempt succeeds ───────────────────

    @Test
    void retry_recoversFrom503_onSecondAttempt_orderCreated() {
        // First call returns 503; second returns 200 — simulates transient catalog blip
        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-retry"))
                .inScenario("retry-success")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));

        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-retry"))
                .inScenario("retry-success")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        ResponseEntity<String> response = postOrder("cust-retry-ok", "po-retry");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── RETRY: both attempts fail → 503 to caller ────────────────────────────

    @Test
    void retry_exhausted_bothAttemptsFail_returns503() {
        wireMock.stubFor(get(urlPathMatching("/product-offerings/.*"))
                .willReturn(aResponse().withStatus(503)));

        ResponseEntity<String> response = postOrder("cust-retry-fail", "po-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── RETRY: 404 is NOT retried ─────────────────────────────────────────────

    @Test
    void noRetry_on404_immediateUnprocessableEntity() {
        // 404 is in ignore-exceptions — must return 422 immediately, no retry
        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-missing"))
                .willReturn(aResponse().withStatus(404)));

        long start = System.currentTimeMillis();
        ResponseEntity<String> response = postOrder("cust-404", "po-missing");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // No retry wait time — should complete well under 500ms (vs 200ms retry wait + HTTP round-trip)
        assertThat(elapsed).isLessThan(500);
    }

    // ── RETRY: timeout triggers retry ────────────────────────────────────────

    @Test
    void retry_afterReadTimeout_secondAttemptSucceeds_orderCreated() {
        // First call stalls for 3s (> 2s socket timeout) — triggers ResourceAccessException
        // Second call responds immediately
        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-slow"))
                .inScenario("timeout-retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(3000))
                .willSetStateTo("fast"));

        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-slow"))
                .inScenario("timeout-retry")
                .whenScenarioStateIs("fast")
                .willReturn(aResponse().withStatus(200)));

        ResponseEntity<String> response = postOrder("cust-timeout-ok", "po-slow");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── CIRCUIT BREAKER: opens after repeated failures ───────────────────────

    @Test
    void circuitBreaker_opensAfterRepeatedFailures_fastFailsSubsequentCalls() {
        // Use CONNECTION_RESET (fast) rather than 503 to avoid waiting for timeouts
        wireMock.stubFor(get(urlPathMatching("/product-offerings/.*"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // Drain the sliding window with failures (need > 50% of 10 = 6+ failures)
        for (int i = 0; i < 12; i++) {
            postOrder("cust-drain-" + i, "po-fail");
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("catalog").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ── CACHE FALLBACK: known offering accepted when circuit is OPEN ──────────

    @Test
    void cacheServesOrder_whenCircuitOpen_forPreviouslyValidatedOffering() {
        // Step 1: Pre-validate po-cached so it enters the local Caffeine cache
        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-cached"))
                .willReturn(aResponse().withStatus(200)));
        assertThat(postOrder("cust-preload", "po-cached").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // Step 2: Force-open the circuit with fast-failing requests
        wireMock.stubFor(get(urlPathMatching("/product-offerings/.*"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        for (int i = 0; i < 12; i++) {
            postOrder("cust-open-" + i, "po-fail");
        }
        assertThat(circuitBreakerRegistry.circuitBreaker("catalog").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // Step 3: Order for the cached offering is accepted even with circuit OPEN
        ResponseEntity<String> result = postOrder("cust-from-cache", "po-cached");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── CACHE FALLBACK: unknown offering fails safely when circuit is OPEN ────

    @Test
    void cacheRejectsSafely_whenCircuitOpen_forUnknownOffering() {
        // Force-open the circuit
        wireMock.stubFor(get(urlPathMatching("/product-offerings/.*"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        for (int i = 0; i < 12; i++) {
            postOrder("cust-open2-" + i, "po-fail");
        }
        assertThat(circuitBreakerRegistry.circuitBreaker("catalog").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // po-never-seen was never validated — not in cache → must return 503, not 201
        ResponseEntity<String> result = postOrder("cust-uncached", "po-never-seen");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> postOrder(String customerId, String offeringId) {
        String body = """
                {
                  "category": "B2B",
                  "customerId": "%s",
                  "siteId": "site-1",
                  "orderItems": [{"productOfferingId": "%s", "quantity": 1}],
                  "paymentMethodType": "INVOICE"
                }
                """.formatted(customerId, offeringId);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", API_KEY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/customer-orders", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
