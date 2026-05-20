package com.dak.catalog.domain.model;

import java.math.BigDecimal;

/**
 * Product offering value object.
 * Compact constructor validates all invariants at construction time —
 * consistent with OrderItem and PaymentMethod in the order-service domain.
 */
public record ProductOffering(String id, String name, BigDecimal price) {

    public ProductOffering {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("ProductOffering id must not be blank");
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("ProductOffering name must not be blank");
        if (price == null)
            throw new IllegalArgumentException("ProductOffering price must not be null");
        if (price.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("ProductOffering price must not be negative, got: " + price);
    }
}
