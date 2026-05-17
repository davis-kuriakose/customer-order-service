package com.dak.order.web.dto;

import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.PaymentMethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(
        @NotNull OrderCategory category,
        @NotBlank String customerId,
        @NotBlank String siteId,
        @NotNull @Size(min = 1) List<@Valid OrderItemRequest> orderItems,
        @NotNull PaymentMethodType paymentMethodType,
        String paymentMethodIban
) {}
