package com.dak.order.web.dto;

public record OrderItemResponse(
        String productOfferingId,
        int quantity
) {}
