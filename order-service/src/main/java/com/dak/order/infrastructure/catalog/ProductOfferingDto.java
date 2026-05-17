package com.dak.order.infrastructure.catalog;

import java.math.BigDecimal;

public record ProductOfferingDto(String id, String name, BigDecimal price) {}
