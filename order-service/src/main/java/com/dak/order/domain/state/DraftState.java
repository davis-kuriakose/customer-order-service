package com.dak.order.domain.state;

import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.exception.InvalidStateTransitionException;

public class DraftState implements OrderState {

    public static final String STATE_NAME = "DRAFT";

    @Override
    public String name() {
        return STATE_NAME;
    }

    @Override
    public OrderState transitionTo(String targetStateName) {
        if (PreviewState.STATE_NAME.equals(targetStateName)) {
            return new PreviewState();
        }
        throw new InvalidStateTransitionException(STATE_NAME, targetStateName);
    }

    @Override
    public boolean isPayloadEditable() {
        return true;
    }

    @Override
    public boolean isLocked() {
        return false;
    }

    @Override
    public void validatePatch(PatchOrderCommand patch) {
        // No restrictions in DRAFT — all fields patchable
    }
}
