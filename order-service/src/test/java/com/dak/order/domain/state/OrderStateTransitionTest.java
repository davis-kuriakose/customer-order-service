package com.dak.order.domain.state;

import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.exception.InvalidStateTransitionException;
import com.dak.order.domain.exception.OrderMutationForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateTransitionTest {

    // ── Valid transitions ────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} → {1} is allowed")
    @CsvSource({
            "DRAFT,     PREVIEW",
            "PREVIEW,   DRAFT",
            "PREVIEW,   SUBMITTED",
            "SUBMITTED, CONFIRMED"
    })
    void validTransition_doesNotThrow(String from, String to) {
        OrderState state = OrderStateFactory.from(from);
        assertThatNoException().isThrownBy(() -> state.transitionTo(to));
    }

    @ParameterizedTest(name = "{0} → {1} is rejected")
    @CsvSource({
            "DRAFT,     DRAFT",
            "DRAFT,     SUBMITTED",
            "DRAFT,     CONFIRMED",
            "PREVIEW,   PREVIEW",
            "PREVIEW,   CONFIRMED",
            "SUBMITTED, DRAFT",
            "SUBMITTED, PREVIEW",
            "SUBMITTED, SUBMITTED",
            "CONFIRMED, DRAFT",
            "CONFIRMED, PREVIEW",
            "CONFIRMED, SUBMITTED",
            "CONFIRMED, CONFIRMED"
    })
    void invalidTransition_throwsInvalidStateTransitionException(String from, String to) {
        OrderState state = OrderStateFactory.from(from);
        assertThatThrownBy(() -> state.transitionTo(to))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ── Payload editability ──────────────────────────────────────────────────

    @Test
    void draft_isPayloadEditable() {
        assertThat(new DraftState().isPayloadEditable()).isTrue();
    }

    @Test
    void preview_isPayloadEditable() {
        assertThat(new PreviewState().isPayloadEditable()).isTrue();
    }

    @Test
    void submitted_isNotPayloadEditable() {
        assertThat(new SubmittedState().isPayloadEditable()).isFalse();
    }

    @Test
    void confirmed_isLocked() {
        assertThat(new ConfirmedState().isLocked()).isTrue();
    }

    // ── Patch validation ─────────────────────────────────────────────────────

    @Test
    void submitted_validatePatch_rejectsNonStateFields() {
        PatchOrderCommand patchWithPayload = new PatchOrderCommand(null, null, "new-cust", null, null, null);
        assertThatThrownBy(() -> new SubmittedState().validatePatch(patchWithPayload))
                .isInstanceOf(OrderMutationForbiddenException.class);
    }

    @Test
    void submitted_validatePatch_allowsStateOnlyPatch() {
        PatchOrderCommand stateOnly = new PatchOrderCommand("CONFIRMED", null, null, null, null, null);
        assertThatNoException().isThrownBy(() -> new SubmittedState().validatePatch(stateOnly));
    }

    @Test
    void confirmed_validatePatch_alwaysRejects() {
        PatchOrderCommand anyPatch = new PatchOrderCommand("DRAFT", null, null, null, null, null);
        assertThatThrownBy(() -> new ConfirmedState().validatePatch(anyPatch))
                .isInstanceOf(OrderMutationForbiddenException.class);
    }

    @Test
    void draft_validatePatch_neverRejects() {
        PatchOrderCommand anyPatch = new PatchOrderCommand(null, null, "x", null, null, null);
        assertThatNoException().isThrownBy(() -> new DraftState().validatePatch(anyPatch));
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    @Test
    void factory_returnsCorrectStateType() {
        assertThat(OrderStateFactory.from("DRAFT")).isInstanceOf(DraftState.class);
        assertThat(OrderStateFactory.from("PREVIEW")).isInstanceOf(PreviewState.class);
        assertThat(OrderStateFactory.from("SUBMITTED")).isInstanceOf(SubmittedState.class);
        assertThat(OrderStateFactory.from("CONFIRMED")).isInstanceOf(ConfirmedState.class);
    }

    @Test
    void factory_throwsForUnknownState() {
        assertThatThrownBy(() -> OrderStateFactory.from("UNKNOWN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNKNOWN");
    }
}
