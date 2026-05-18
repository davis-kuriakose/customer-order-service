package com.dak.order.application.service;

import com.dak.order.domain.exception.IdempotencyConflictException;
import com.dak.order.domain.port.outbound.IdempotencyRepositoryPort;
import com.dak.order.domain.port.outbound.IdempotencyRepositoryPort.StoredResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyRepositoryPort idempotencyRepository;

    // Real ObjectMapper instance — Jackson's ObjectMapper is not designed to be spied upon
    // (Byte Buddy cannot subclass it reliably on Java 24+ without experimental mode).
    private final ObjectMapper objectMapper = new ObjectMapper();

    private IdempotencyService idempotencyService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(idempotencyRepository, objectMapper);
    }

    @Test
    void computeHash_returnsSha256HexString_of64Chars() {
        String hash = idempotencyService.computeHash("some-request-body");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
    }

    @Test
    void computeHash_sameInputProducesSameHash() {
        String hash1 = idempotencyService.computeHash("body");
        String hash2 = idempotencyService.computeHash("body");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void computeHash_differentInputProducesDifferentHash() {
        String hash1 = idempotencyService.computeHash("body-a");
        String hash2 = idempotencyService.computeHash("body-b");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeHash_isCorrectAndConsistentAcrossConcurrentVirtualThreadCalls() throws InterruptedException {
        // Verifies that ThreadLocal<MessageDigest> produces correct, identical hashes
        // across many concurrent virtual threads — no shared-state corruption.
        int threadCount = 50;
        String input = "concurrent-idempotency-test";
        String expected = idempotencyService.computeHash(input);
        String[] results = new String[threadCount];
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            threads[i] = Thread.ofVirtual().start(() -> results[idx] = idempotencyService.computeHash(input));
        }
        for (Thread t : threads) {
            t.join();
        }

        for (String result : results) {
            assertThat(result).isEqualTo(expected);
        }
    }

    @Test
    void check_returnsEmpty_whenKeyNotFound() {
        when(idempotencyRepository.findByKey("key-1")).thenReturn(Optional.empty());

        Optional<StoredResponse> result = idempotencyService.check("key-1", "hash-abc");

        assertThat(result).isEmpty();
    }

    @Test
    void check_returnsStoredResponse_whenSameHash() {
        StoredResponse stored = new StoredResponse("hash-abc", 201, "{\"id\":\"...\"}");
        when(idempotencyRepository.findByKey("key-1")).thenReturn(Optional.of(stored));

        Optional<StoredResponse> result = idempotencyService.check("key-1", "hash-abc");

        assertThat(result).contains(stored);
    }

    @Test
    void check_throwsIdempotencyConflictException_whenDifferentHash() {
        StoredResponse stored = new StoredResponse("hash-original", 201, "{\"id\":\"...\"}");
        when(idempotencyRepository.findByKey("key-1")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> idempotencyService.check("key-1", "hash-different"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("key-1");
    }

    @Test
    void store_delegatesToRepository() {
        UUID orderId = UUID.randomUUID();
        idempotencyService.store("key-1", "hash-abc", orderId, 201, "{\"id\":\"...\"}");

        verify(idempotencyRepository).store("key-1", "hash-abc", orderId, 201, "{\"id\":\"...\"}");
    }

    @Test
    void withLock_executesActionAndReturnsResult() {
        String result = idempotencyService.withLock("key-1", () -> "executed");
        assertThat(result).isEqualTo("executed");
    }

    @Test
    void withLock_releasesLock_evenWhenActionThrows() {
        assertThatThrownBy(() ->
            idempotencyService.withLock("key-1", () -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class);

        // Lock must be released — second call on the same key must not deadlock
        String result = idempotencyService.withLock("key-1", () -> "second call succeeded");
        assertThat(result).isEqualTo("second call succeeded");
    }
}
