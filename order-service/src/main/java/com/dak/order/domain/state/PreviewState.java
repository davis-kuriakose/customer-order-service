package com.dak.order.domain.state;

import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.exception.InvalidStateTransitionException;

public class PreviewState implements OrderState {

    public static final String STATE_NAME = "PREVIEW";

    @Override
    public String name() {
        return STATE_NAME;
    }

    @Override
    public OrderState transitionTo(String targetStateName) {
        return switch (targetStateName) {
            case DraftState.STATE_NAME     -> new DraftState();
            case SubmittedState.STATE_NAME -> new SubmittedState();
            default -> throw new InvalidStateTransitionException(STATE_NAME, targetStateName);
        };
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
        // No restrictions in PREVIEW — all fields patchable
    }
}
