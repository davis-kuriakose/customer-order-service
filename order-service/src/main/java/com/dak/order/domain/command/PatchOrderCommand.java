package com.dak.order.domain.command;

import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.OrderItem;
import com.dak.order.domain.model.PaymentMethod;

import java.util.List;

public record PatchOrderCommand(
        String targetStateName,
        OrderCategory category,
        String customerId,
        String siteId,
        List<OrderItem> orderItems,
        PaymentMethod paymentMethod
) {

    public boolean hasStateTransition() {
        return targetStateName != null;
    }

    public boolean hasNonStateFields() {
        return category != null
                || customerId != null
                || siteId != null
                || orderItems != null
                || paymentMethod != null;
    }
}
