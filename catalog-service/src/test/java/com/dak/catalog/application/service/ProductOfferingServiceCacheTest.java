package com.dak.catalog.application.service;

import com.dak.catalog.domain.model.ProductOffering;
import com.dak.catalog.domain.port.inbound.ProductOfferingUseCase;
import com.dak.catalog.domain.port.outbound.ProductOfferingRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies Caffeine cache behaviour for product-offering lookups.
 *
 * Uses Testcontainers to boot a real PostgreSQL so the full Spring context
 * (Flyway, JPA, Security) starts cleanly. The repository port is replaced
 * by a MockBean so we can assert exact call counts without hitting the DB.
 *
 * Key assertions:
 *   - Same ID requested N times → repository called exactly once (cache hit)
 *   - Different IDs each miss the cache → repository called once per unique ID
 *   - Cache cleared between tests so tests are independent
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ProductOfferingServiceCacheTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    // MockBean replaces the real adapter — repository calls go to Mockito, not the DB.
    @MockBean
    ProductOfferingRepositoryPort repository;

    @Autowired
    ProductOfferingUseCase service;

    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        // Ensure each test starts with an empty cache to avoid cross-test interference.
        var cache = cacheManager.getCache(ProductOfferingService.CACHE_NAME);
        if (cache != null) cache.clear();
    }

    @Test
    void getById_secondCallServedFromCache_repositoryCalledOnlyOnce() {
        String id = "po-1";
        ProductOffering offering = new ProductOffering("po-1", "Basic Plan", new BigDecimal("9.99"));
        when(repository.findById(id)).thenReturn(Optional.of(offering));

        ProductOffering first  = service.getById(id);
        ProductOffering second = service.getById(id);
        ProductOffering third  = service.getById(id);

        assertThat(first).isEqualTo(second).isEqualTo(third);
        // Repository must have been called only once — the other two calls were cache hits.
        verify(repository, times(1)).findById(id);
    }

    @Test
    void getById_differentIds_eachMissesCache_repositoryCalledPerUniqueId() {
        ProductOffering po1 = new ProductOffering("po-1", "Basic",    new BigDecimal("9.99"));
        ProductOffering po2 = new ProductOffering("po-2", "Standard", new BigDecimal("29.99"));
        when(repository.findById("po-1")).thenReturn(Optional.of(po1));
        when(repository.findById("po-2")).thenReturn(Optional.of(po2));

        service.getById("po-1");
        service.getById("po-1");  // cache hit for po-1
        service.getById("po-2");
        service.getById("po-2");  // cache hit for po-2

        verify(repository, times(1)).findById("po-1");
        verify(repository, times(1)).findById("po-2");
    }

    @Test
    void getById_notFound_throwsException_resultNotCached() {
        when(repository.findById("po-unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById("po-unknown"))
                .hasMessageContaining("po-unknown");

        // Exceptions are not cached — next call should still go to the repository.
        assertThatThrownBy(() -> service.getById("po-unknown"))
                .hasMessageContaining("po-unknown");

        verify(repository, times(2)).findById("po-unknown");
    }
}
