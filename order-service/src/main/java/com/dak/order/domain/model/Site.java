package com.dak.order.domain.model;

public record Site(String id) {

    public Site {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Site id must not be blank");
        }
    }
}
