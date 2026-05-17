package com.dak.order.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderIntegrationTest {

    // ── Infrastructure ────────────────────────────────────────────────────────

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    // Started in the static initialiser so it is ready before @DynamicPropertySource runs.
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
    static void catalogProps(DynamicPropertyRegistry registry) {
        registry.add("app.catalog.base-url", wireMock::baseUrl);
    }

    @Autowired
    TestRestTemplate restTemplate;

    // Default API key matches application.yml: app.api-key: ${APP_API_KEY:dev-api-key}
    private static final String API_KEY = "dev-api-key";

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
        // Happy-path default: catalog confirms po-1 exists
        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-1"))
                .willReturn(aResponse().withStatus(200)));
    }

    // ── Idempotency scenarios ─────────────────────────────────────────────────

    @Test
    void createOrder_withoutIdempotencyKey_returns201_noReplayHeader() {
        ResponseEntity<String> response = postOrder(invoiceBody("cust-no-key", "po-1"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("Location")).startsWith("/customer-orders/");
        assertThat(response.getHeaders().getFirst("X-Idempotent-Replayed")).isNull();
    }

    @Test
    void createOrder_sameKeyAndSamePayload_secondCallReplays201() {
        String key = UUID.randomUUID().toString();
        String body = invoiceBody("cust-replay", "po-1");

        ResponseEntity<String> first = postOrder(body, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("X-Idempotent-Replayed")).isNull();

        ResponseEntity<String> second = postOrder(body, key);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void createOrder_sameKeyDifferentPayload_returns409() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = postOrder(invoiceBody("cust-conflict-a", "po-1"), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Different customerId → different hash → conflict
        ResponseEntity<String> conflict = postOrder(invoiceBody("cust-conflict-b", "po-1"), key);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── Catalog error scenarios ───────────────────────────────────────────────

    @Test
    void createOrder_unknownOffering_returns422() {
        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-unknown"))
                .willReturn(aResponse().withStatus(404)));

        ResponseEntity<String> response = postOrder(invoiceBody("cust-bad-po", "po-unknown"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void createOrder_catalogConnectionError_returns503() {
        // Override the default stub: simulate immediate connection failure for all offerings
        wireMock.resetAll();
        wireMock.stubFor(get(urlPathMatching("/product-offerings/.*"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ResponseEntity<String> response = postOrder(invoiceBody("cust-503", "po-1"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── Validation edge cases ─────────────────────────────────────────────────

    @Test
    void createOrder_missingApiKey_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(invoiceBody("cust-1", "po-1"), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/customer-orders", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createOrder_invalidRequestBody_returns400() {
        ResponseEntity<String> response = postOrder("{}", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Virtual-thread parallel validation (OPPORTUNITY 1) ───────────────────

    @Test
    void createOrder_multipleItems_allValidatedAndReturns201() {
        // Stub both offerings so parallel validation succeeds for each
        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-2"))
                .willReturn(aResponse().withStatus(200)));

        ResponseEntity<String> response = postOrder(multiItemBody("cust-multi", "po-1", "po-2"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("Location")).startsWith("/customer-orders/");
    }

    @Test
    void createOrder_multipleItems_oneUnknown_returns422() {
        wireMock.stubFor(get(urlPathMatching("/product-offerings/po-unknown"))
                .willReturn(aResponse().withStatus(404)));

        ResponseEntity<String> response = postOrder(multiItemBody("cust-bad", "po-1", "po-unknown"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── TASK-22: listOrders single-transaction count + data ──────────────────

    @Test
    void listOrders_afterCreatingOrders_returnsConsistentItemsAndTotal() {
        postOrder(invoiceBody("cust-list-1", "po-1"), null);
        postOrder(invoiceBody("cust-list-2", "po-1"), null);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", API_KEY);
        ResponseEntity<String> response = restTemplate.exchange(
                "/customer-orders?limit=20&offset=0", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"total\"");
        assertThat(response.getBody()).contains("\"items\"");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> postOrder(String body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", API_KEY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(
                "/customer-orders", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String invoiceBody(String customerId, String offeringId) {
        return """
                {
                  "category": "B2B",
                  "customerId": "%s",
                  "siteId": "site-1",
                  "orderItems": [{"productOfferingId": "%s", "quantity": 1}],
                  "paymentMethodType": "INVOICE"
                }
                """.formatted(customerId, offeringId);
    }

    private String multiItemBody(String customerId, String offeringId1, String offeringId2) {
        return """
                {
                  "category": "B2B",
                  "customerId": "%s",
                  "siteId": "site-1",
                  "orderItems": [
                    {"productOfferingId": "%s", "quantity": 1},
                    {"productOfferingId": "%s", "quantity": 2}
                  ],
                  "paymentMethodType": "INVOICE"
                }
                """.formatted(customerId, offeringId1, offeringId2);
    }
}
