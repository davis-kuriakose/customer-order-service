package com.dak.order.domain.port.outbound;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepositoryPort {

    Optional<StoredResponse> findByKey(String idempotencyKey);

    void store(String idempotencyKey, String requestHash, UUID orderId,
               int responseStatus, String responseBody);

    record StoredResponse(String requestHash, int responseStatus, String responseBody) {}
}
