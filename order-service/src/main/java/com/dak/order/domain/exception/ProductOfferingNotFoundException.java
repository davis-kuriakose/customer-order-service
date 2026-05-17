package com.dak.order.domain.exception;

public class ProductOfferingNotFoundException extends RuntimeException {

    public ProductOfferingNotFoundException(String productOfferingId) {
        super("Product offering not found: " + productOfferingId);
    }
}
