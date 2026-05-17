package com.dak.order.domain.exception;

public class OrderMutationForbiddenException extends RuntimeException {

    public OrderMutationForbiddenException(String message) {
        super(message);
    }
}
