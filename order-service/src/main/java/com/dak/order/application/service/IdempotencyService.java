package com.dak.order.application.service;

import com.dak.order.domain.exception.IdempotencyConflictException;
import com.dak.order.domain.port.outbound.IdempotencyRepositoryPort;
import com.dak.order.domain.port.outbound.IdempotencyRepositoryPort.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final IdempotencyRepositoryPort idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepositoryPort idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
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
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise request for hashing", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }
}
