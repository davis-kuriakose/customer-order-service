package com.dak.catalog.web.dto;

import java.math.BigDecimal;

public record ProductOfferingResponse(String id, String name, BigDecimal price) {}
