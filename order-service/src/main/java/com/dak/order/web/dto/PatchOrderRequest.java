package com.dak.order.web.dto;

import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.PaymentMethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PatchOrderRequest(
        @Size(max = 50)  String targetStateName,
        OrderCategory category,
        @Size(max = 100) String customerId,
        @Size(max = 100) String siteId,
        @Valid @Size(max = 50) List<OrderItemRequest> orderItems,
        PaymentMethodType paymentMethodType,
        @Size(max = 34)  String paymentMethodIban
) {}
