package com.dak.catalog.domain.exception;

public class ProductOfferingNotFoundException extends RuntimeException {

    public ProductOfferingNotFoundException(String id) {
        super("Product offering not found: " + id);
    }
}
