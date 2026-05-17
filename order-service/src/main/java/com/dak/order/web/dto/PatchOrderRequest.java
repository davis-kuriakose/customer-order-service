package com.dak.order.web.dto;

import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.PaymentMethodType;

import java.util.List;

public record PatchOrderRequest(
        String targetStateName,
        OrderCategory category,
        String customerId,
        String siteId,
        List<OrderItemRequest> orderItems,
        PaymentMethodType paymentMethodType,
        String paymentMethodIban
) {}
