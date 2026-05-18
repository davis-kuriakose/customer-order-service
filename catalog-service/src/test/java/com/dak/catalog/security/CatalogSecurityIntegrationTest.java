package com.dak.catalog.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for API key security in catalog-service.
 *
 * Verifies end-to-end filter behaviour after TASK-29 removed @Component from
 * ApiKeyAuthFilter: the filter must still enforce authentication correctly when
 * it is constructed and registered solely through SecurityConfig, with no
 * duplicate auto-registration by Spring Boot's FilterRegistrationAutoConfiguration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CatalogSecurityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    // Default API key from application.yml: app.api-key: ${APP_API_KEY:internal-catalog-key}
    private static final String VALID_API_KEY = "internal-catalog-key";

    @Autowired
    TestRestTemplate restTemplate;

    // ── API key enforcement ───────────────────────────────────────────────────

    @Test
    void withValidApiKey_returns200() {
        ResponseEntity<String> response = get("/product-offerings", VALID_API_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withMissingApiKey_returns401() {
        ResponseEntity<String> response = get("/product-offerings", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Unauthorized");
    }

    @Test
    void withWrongApiKey_returns401() {
        ResponseEntity<String> response = get("/product-offerings", "wrong-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownOffering_withValidKey_returns404() {
        ResponseEntity<String> response = get("/product-offerings/po-does-not-exist", VALID_API_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Actuator exemption ────────────────────────────────────────────────────

    @Test
    void actuatorHealth_exemptFromApiKey_returns200() {
        // Actuator endpoints must be reachable without an API key (for load-balancer probes).
        ResponseEntity<String> response = get("/actuator/health", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Seeded data smoke test ────────────────────────────────────────────────

    @Test
    void getSeededOffering_po1_returns200_withCorrectId() {
        ResponseEntity<String> response = get("/product-offerings/po-1", VALID_API_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"id\":\"po-1\"");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ResponseEntity<String> get(String path, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
