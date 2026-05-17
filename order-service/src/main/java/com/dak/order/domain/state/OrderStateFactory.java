package com.dak.order.domain.state;

import java.util.Map;
import java.util.function.Supplier;

public final class OrderStateFactory {

    private static final Map<String, Supplier<OrderState>> REGISTRY = Map.of(
            DraftState.STATE_NAME,     DraftState::new,
            PreviewState.STATE_NAME,   PreviewState::new,
            SubmittedState.STATE_NAME, SubmittedState::new,
            ConfirmedState.STATE_NAME, ConfirmedState::new
    );

    private OrderStateFactory() {}

    public static OrderState from(String stateName) {
        Supplier<OrderState> supplier = REGISTRY.get(stateName);
        if (supplier == null) {
            throw new IllegalStateException("Unknown order state: " + stateName);
        }
        return supplier.get();
    }
}
