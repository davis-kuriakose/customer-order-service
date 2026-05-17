package com.dak.order.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String state,
        String category,
        String customerId,
        String siteId,
        List<OrderItemResponse> orderItems,
        String paymentMethodType,
        String paymentMethodIban,
        Instant createdAt,
        Instant updatedAt
) {}
