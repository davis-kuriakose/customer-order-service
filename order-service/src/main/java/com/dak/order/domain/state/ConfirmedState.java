package com.dak.order.domain.state;

import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.exception.InvalidStateTransitionException;
import com.dak.order.domain.exception.OrderMutationForbiddenException;

public class ConfirmedState implements OrderState {

    public static final String STATE_NAME = "CONFIRMED";

    @Override
    public String name() {
        return STATE_NAME;
    }

    @Override
    public OrderState transitionTo(String targetStateName) {
        throw new InvalidStateTransitionException(STATE_NAME, targetStateName);
    }

    @Override
    public boolean isPayloadEditable() {
        return false;
    }

    @Override
    public boolean isLocked() {
        return true;
    }

    @Override
    public void validatePatch(PatchOrderCommand patch) {
        throw new OrderMutationForbiddenException("Confirmed orders cannot be modified");
    }
}
