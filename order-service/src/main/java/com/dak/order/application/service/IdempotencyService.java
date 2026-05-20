package com.dak.order.application.service;

import com.dak.order.domain.exception.IdempotencyConflictException;
import com.dak.order.domain.port.outbound.IdempotencyRepositoryPort;
import com.dak.order.domain.port.outbound.IdempotencyRepositoryPort.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    // MessageDigest is NOT thread-safe and cannot be shared. MessageDigest.getInstance()
    // performs a JCA provider lookup on every call (synchronized internally). ThreadLocal
    // gives each thread its own instance — the provider lookup happens once per thread,
    // not once per request. After digest() is called, MessageDigest auto-resets itself,
    // so the instance is safely reusable within the same thread.
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    });

    // Per-key lock map — keeps the check→create→store sequence atomic for a given key.
    // Caffeine cache with TTL + max-size prevents unbounded growth when callers use many
    // unique idempotency keys. 5-minute access expiry is safe because order creation
    // (catalog HTTP + DB write) completes well within that window.
    // Single-instance guard only; a distributed lock (e.g. Redis SETNX) is needed for multi-node.
    private final Cache<String, ReentrantLock> locks = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();

    private final IdempotencyRepositoryPort idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepositoryPort idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes {@code action} under a per-key lock so that two concurrent requests
     * for the same idempotency key cannot both pass {@link #check} and produce duplicates.
     */
    public <T> T withLock(String key, Supplier<T> action) {
        ReentrantLock lock = locks.get(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks whether an idempotency key has been seen before.
     *
     * @return empty   — key is new; caller should proceed with the operation
     * @return present — same hash seen before; caller should replay the stored response
     * @throws IdempotencyConflictException if the key exists with a different request hash
     */
    @Transactional(readOnly = true)
    public Optional<StoredResponse> check(String key, String requestHash) {
        return idempotencyRepository.findByKey(key).map(stored -> {
            if (!stored.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(key);
            }
            return stored;
        });
    }

    @Transactional
    public void store(String key, String requestHash, UUID orderId, int status, String responseBody) {
        idempotencyRepository.store(key, requestHash, orderId, status, responseBody);
    }

    /**
     * Serialises {@code obj} to canonical JSON and returns the SHA-256 hex digest.
     * Two requests with identical field values will produce the same hash.
     */
    public String computeHash(Object obj) {
        try {
            String json = objectMapper.writeValueAsString(obj);
            byte[] hash = SHA_256.get().digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise request for hashing", e);
        }
    }
}
