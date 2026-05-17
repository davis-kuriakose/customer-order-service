package com.dak.order.domain.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("A different request was already submitted with idempotency key: " + idempotencyKey);
    }
}
