package com.dak.order.domain.command;

import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.OrderItem;
import com.dak.order.domain.model.PaymentMethodType;

import java.util.List;

public record CreateOrderCommand(
        OrderCategory category,
        String customerId,
        String siteId,
        List<OrderItem> orderItems,
        PaymentMethodType paymentMethodType,
        String paymentMethodIban
) {}
