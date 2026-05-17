package com.dak.order.domain.state;

import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.exception.InvalidStateTransitionException;
import com.dak.order.domain.exception.OrderMutationForbiddenException;

public class SubmittedState implements OrderState {

    public static final String STATE_NAME = "SUBMITTED";

    @Override
    public String name() {
        return STATE_NAME;
    }

    @Override
    public OrderState transitionTo(String targetStateName) {
        if (ConfirmedState.STATE_NAME.equals(targetStateName)) {
            return new ConfirmedState();
        }
        throw new InvalidStateTransitionException(STATE_NAME, targetStateName);
    }

    @Override
    public boolean isPayloadEditable() {
        return false;
    }

    @Override
    public boolean isLocked() {
        return false;
    }

    @Override
    public void validatePatch(PatchOrderCommand patch) {
        if (patch.hasNonStateFields()) {
            throw new OrderMutationForbiddenException(
                    "Only a state transition is allowed when order is SUBMITTED");
        }
    }
}
