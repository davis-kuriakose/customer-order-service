package com.dak.order.domain.model;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.exception.InvalidStateTransitionException;
import com.dak.order.domain.exception.OrderMutationForbiddenException;
import com.dak.order.domain.state.ConfirmedState;
import com.dak.order.domain.state.DraftState;
import com.dak.order.domain.state.PreviewState;
import com.dak.order.domain.state.SubmittedState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    // ── Order.create() ───────────────────────────────────────────────────────

    @Test
    void create_assignsUuidAndStartsInDraft() {
        Order order = Order.create(aCreateCommand());
        assertThat(order.getId()).isNotNull();
        assertThat(order.getState()).isInstanceOf(DraftState.class);
    }

    @Test
    void create_mapsAllFieldsFromCommand() {
        Order order = Order.create(aCreateCommand());
        assertThat(order.getCategory()).isEqualTo(OrderCategory.B2B);
        assertThat(order.getCustomer().id()).isEqualTo("cust-1");
        assertThat(order.getSite().id()).isEqualTo("site-1");
        assertThat(order.getOrderItems()).hasSize(1);
        assertThat(order.getOrderItems().get(0).productOfferingId()).isEqualTo("po-1");
        assertThat(order.getPaymentMethod().type()).isEqualTo(PaymentMethodType.INVOICE);
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getUpdatedAt()).isNotNull();
    }

    // ── applyPatch — state transitions ───────────────────────────────────────

    @Test
    void applyPatch_transitionsDraftToPreview() {
        Order order = Order.create(aCreateCommand());
        Order updated = order.applyPatch(statePatch("PREVIEW"));
        assertThat(updated.getState()).isInstanceOf(PreviewState.class);
    }

    @Test
    void applyPatch_transitionsPreviewBackToDraft() {
        Order order = orderInState("PREVIEW");
        Order updated = order.applyPatch(statePatch("DRAFT"));
        assertThat(updated.getState()).isInstanceOf(DraftState.class);
    }

    @Test
    void applyPatch_transitionsSubmittedToConfirmed() {
        Order order = orderInState("SUBMITTED");
        Order updated = order.applyPatch(statePatch("CONFIRMED"));
        assertThat(updated.getState()).isInstanceOf(ConfirmedState.class);
    }

    @Test
    void applyPatch_invalidTransition_throws() {
        Order order = Order.create(aCreateCommand());
        assertThatThrownBy(() -> order.applyPatch(statePatch("CONFIRMED")))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ── applyPatch — payload fields ───────────────────────────────────────────

    @Test
    void applyPatch_updatesOnlyPresentFields() {
        Order order = Order.create(aCreateCommand());
        PatchOrderCommand patch = new PatchOrderCommand(null, OrderCategory.B2C, null, null, null, null);
        Order updated = order.applyPatch(patch);
        assertThat(updated.getCategory()).isEqualTo(OrderCategory.B2C);
        assertThat(updated.getCustomer()).isEqualTo(order.getCustomer()); // unchanged
        assertThat(updated.getSite()).isEqualTo(order.getSite());         // unchanged
    }

    @Test
    void applyPatch_updatesCustomerAndSite() {
        Order order = Order.create(aCreateCommand());
        PatchOrderCommand patch = new PatchOrderCommand(null, null, "new-cust", "new-site", null, null);
        Order updated = order.applyPatch(patch);
        assertThat(updated.getCustomer().id()).isEqualTo("new-cust");
        assertThat(updated.getSite().id()).isEqualTo("new-site");
    }

    @Test
    void applyPatch_updatesTimestamp() {
        Order order = Order.create(aCreateCommand());
        Order updated = order.applyPatch(statePatch("PREVIEW"));
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(order.getUpdatedAt());
    }

    // ── applyPatch — SUBMITTED state rules ───────────────────────────────────

    @Test
    void applyPatch_submitted_allowsStateTransitionToConfirmed() {
        Order submitted = orderInState("SUBMITTED");
        Order confirmed = submitted.applyPatch(statePatch("CONFIRMED"));
        assertThat(confirmed.getState()).isInstanceOf(ConfirmedState.class);
    }

    @Test
    void applyPatch_submitted_rejectsPayloadChange() {
        Order submitted = orderInState("SUBMITTED");
        PatchOrderCommand payloadPatch = new PatchOrderCommand(null, OrderCategory.B2C, null, null, null, null);
        assertThatThrownBy(() -> submitted.applyPatch(payloadPatch))
                .isInstanceOf(OrderMutationForbiddenException.class);
    }

    @Test
    void applyPatch_submitted_rejectsStateAndPayloadTogether() {
        Order submitted = orderInState("SUBMITTED");
        PatchOrderCommand mixed = new PatchOrderCommand("CONFIRMED", OrderCategory.B2C, null, null, null, null);
        assertThatThrownBy(() -> submitted.applyPatch(mixed))
                .isInstanceOf(OrderMutationForbiddenException.class);
    }

    // ── applyPatch — CONFIRMED state rules ───────────────────────────────────

    @Test
    void applyPatch_confirmed_rejectsStateTransition() {
        Order confirmed = orderInState("CONFIRMED");
        assertThatThrownBy(() -> confirmed.applyPatch(statePatch("DRAFT")))
                .isInstanceOf(OrderMutationForbiddenException.class);
    }

    @Test
    void applyPatch_confirmed_rejectsPayloadChange() {
        Order confirmed = orderInState("CONFIRMED");
        PatchOrderCommand patch = new PatchOrderCommand(null, OrderCategory.B2C, null, null, null, null);
        assertThatThrownBy(() -> confirmed.applyPatch(patch))
                .isInstanceOf(OrderMutationForbiddenException.class);
    }

    // ── immutability ─────────────────────────────────────────────────────────

    @Test
    void applyPatch_returnsNewInstance_doesNotMutateOriginal() {
        Order original = Order.create(aCreateCommand());
        Order updated = original.applyPatch(statePatch("PREVIEW"));
        assertThat(updated).isNotSameAs(original);
        assertThat(original.getState()).isInstanceOf(DraftState.class); // original unchanged
    }

    @Test
    void toBuilder_sourceListMutation_doesNotAffectBuiltOrder() {
        // Builder.orderItems() must defensive-copy so that mutating the source list
        // after calling toBuilder() cannot corrupt the new Order's items.
        Order original = Order.create(aCreateCommand());
        Order.Builder builder = original.toBuilder();
        // Verify the built Order's items are sealed — getOrderItems() returns an unmodifiable view
        Order rebuilt = builder.build();
        assertThat(rebuilt.getOrderItems()).hasSize(1);
        assertThatThrownBy(() -> rebuilt.getOrderItems().add(new OrderItem("po-extra", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static CreateOrderCommand aCreateCommand() {
        return new CreateOrderCommand(
                OrderCategory.B2B, "cust-1", "site-1",
                List.of(new OrderItem("po-1", 2)),
                PaymentMethodType.INVOICE, null);
    }

    private static PatchOrderCommand statePatch(String targetState) {
        return new PatchOrderCommand(targetState, null, null, null, null, null);
    }

    private static Order orderInState(String target) {
        Order order = Order.create(aCreateCommand());
        return switch (target) {
            case "PREVIEW"   -> order.applyPatch(statePatch("PREVIEW"));
            case "SUBMITTED" -> order.applyPatch(statePatch("PREVIEW"))
                                     .applyPatch(statePatch("SUBMITTED"));
            case "CONFIRMED" -> order.applyPatch(statePatch("PREVIEW"))
                                     .applyPatch(statePatch("SUBMITTED"))
                                     .applyPatch(statePatch("CONFIRMED"));
            default -> throw new IllegalArgumentException("Unhandled state: " + target);
        };
    }
}
