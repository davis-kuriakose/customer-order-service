package com.dak.order.domain.model;

public record OrderItem(String productOfferingId, int quantity) {

    public OrderItem {
        if (productOfferingId == null || productOfferingId.isBlank()) {
            throw new IllegalArgumentException("Product offering id must not be blank");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be >= 1, got: " + quantity);
        }
    }
}
