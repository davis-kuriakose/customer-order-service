package com.dak.order.domain.state;

import com.dak.order.domain.command.PatchOrderCommand;

public interface OrderState {

    String name();

    OrderState transitionTo(String targetStateName);

    boolean isPayloadEditable();

    boolean isLocked();

    void validatePatch(PatchOrderCommand patch);
}
