package com.dak.order.infrastructure.persistence.repository;

import com.dak.order.domain.port.outbound.IdempotencyRepositoryPort;
import com.dak.order.infrastructure.persistence.entity.IdempotencyKeyJpaEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class IdempotencyRepositoryAdapter implements IdempotencyRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRepositoryAdapter.class);

    private final IdempotencyKeyJpaRepository jpaRepository;

    public IdempotencyRepositoryAdapter(IdempotencyKeyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<StoredResponse> findByKey(String idempotencyKey) {
        return jpaRepository.findById(idempotencyKey)
                .map(e -> new StoredResponse(e.getRequestHash(), e.getResponseStatus(), e.getResponseBody()));
    }

    @Override
    public void store(String idempotencyKey, String requestHash, UUID orderId,
                      int responseStatus, String responseBody) {
        IdempotencyKeyJpaEntity entity = new IdempotencyKeyJpaEntity();
        entity.setIdempotencyKey(idempotencyKey);
        entity.setRequestHash(requestHash);
        entity.setOrderId(orderId);
        entity.setResponseStatus(responseStatus);
        entity.setResponseBody(responseBody);
        entity.setCreatedAt(Instant.now());
        try {
            jpaRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request with the same key already stored the entry — safe to ignore.
            log.debug("Idempotency key already stored by concurrent request: {}", idempotencyKey);
        }
    }
}
