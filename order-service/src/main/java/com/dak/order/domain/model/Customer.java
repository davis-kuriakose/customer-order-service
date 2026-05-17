package com.dak.order.domain.model;

public record Customer(String id) {

    public Customer {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Customer id must not be blank");
        }
    }
}
