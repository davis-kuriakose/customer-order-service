package com.dak.order.integration;

import com.github.tomakehurst.wiremock.client.WireMock;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toxiproxy integration tests — simulates TCP-level network failures between
 * order-service and catalog-service.
 *
 *  Architecture:
 *   [Order Service JVM]
 *         │  HTTP → Toxiproxy container → WireMock container
 *         │
 *   Both containers share a Docker network. The Spring Boot context is configured
 *   to connect to the Toxiproxy port, not WireMock directly.
 *   Toxics are applied per-test and removed in @BeforeEach.
 *
 * Scenarios covered:
 *  - Network latency > socket timeout → ResourceAccessException → retry → 503
 *  - Connection cut (catalog unreachable) → ResourceAccessException → retry → 503
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CatalogToxiproxyIntegrationTest {

    static final Network NETWORK = Network.newNetwork();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withNetwork(NETWORK);

    /** WireMock runs as a container so Toxiproxy (also a container) can reach it by hostname. */
    @Container
    static GenericContainer<?> wireMockContainer =
            new GenericContainer<>("wiremock/wiremock:3.9.2")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("wiremock")
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/__admin/").forStatusCode(200));

    @Container
    static ToxiproxyContainer toxiproxy =
            new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
                    .withNetwork(NETWORK);

    static ToxiproxyContainer.ContainerProxy catalogProxy;
    static WireMock wireMockClient;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        // Proxy: toxiproxy → wiremock:8080 (both on shared Docker network)
        catalogProxy = toxiproxy.getProxy(wireMockContainer, 8080);
        // Order service connects to Toxiproxy, not WireMock directly
        registry.add("app.catalog.base-url",
                () -> "http://" + toxiproxy.getHost() + ":" + catalogProxy.getProxyPort());

        wireMockClient = new WireMock(
                wireMockContainer.getHost(), wireMockContainer.getMappedPort(8080));
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired CacheManager cacheManager;

    private static final String API_KEY = "dev-api-key";

    @BeforeEach
    void resetState() throws Exception {
        // Remove all toxics between tests so each test starts from a clean network
        for (var toxic : catalogProxy.toxics().getAll()) {
            toxic.remove();
        }
        catalogProxy.setConnectionCut(false);
        circuitBreakerRegistry.circuitBreaker("catalog").reset();
        Objects.requireNonNull(cacheManager.getCache("catalog-valid-offerings")).clear();
        wireMockClient.resetToDefaultMappings();
        // Default: all offerings return 200
        wireMockClient.register(get(urlPathMatching("/product-offerings/.*"))
                .willReturn(aResponse().withStatus(200)));
    }

    // ── SCENARIO 1: Network latency > socket timeout ─────────────────────────

    @Test
    void latencyToxic_exceedsSocketTimeout_triggersRetry_returns503() throws Exception {
        // Add 3000ms downstream latency — greater than the 2s JdkClientHttpRequestFactory timeout.
        // Both retry attempts will time out → ResourceAccessException → 503
        catalogProxy.toxics()
                .latency("high-latency", ToxicDirection.DOWNSTREAM, 3000);

        long start = System.currentTimeMillis();
        ResponseEntity<String> response = postOrder("cust-latency", "po-1");
        long elapsed = System.currentTimeMillis() - start;

        // Both retry attempts timed out: 2 × 2s = ~4s + 200ms retry wait
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        // Should NOT have waited indefinitely (bounded by timeout × attempts)
        assertThat(elapsed).isLessThan(7000);
    }

    // ── SCENARIO 2: Connection cut — catalog completely unreachable ───────────

    @Test
    void connectionCut_catalogUnreachable_returns503_afterRetries() throws Exception {
        // Toxiproxy drops all new connections immediately — simulates catalog being down
        catalogProxy.setConnectionCut(true);

        ResponseEntity<String> response = postOrder("cust-cut", "po-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── SCENARIO 3: Connection cut + cached offering → accepted from cache ────

    @Test
    void connectionCut_cachedOffering_acceptedFromCache() throws Exception {
        // Pre-warm the cache: one successful order before cutting the connection
        assertThat(postOrder("cust-warm", "po-1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // Open the circuit so the fallback fires (cut + drain sliding window)
        catalogProxy.setConnectionCut(true);
        for (int i = 0; i < 12; i++) {
            postOrder("cust-drain-" + i, "po-fail");
        }

        // Circuit is OPEN; po-1 is in the local cache — order should be accepted
        ResponseEntity<String> result = postOrder("cust-from-cache", "po-1");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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
