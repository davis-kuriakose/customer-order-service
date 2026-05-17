package com.dak.order.domain.exception;

public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(Throwable cause) {
        super("Catalog service is unavailable", cause);
    }

    public CatalogUnavailableException(String message) {
        super(message);
    }
}
