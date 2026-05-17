package com.dak.order.domain.model;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.state.DraftState;
import com.dak.order.domain.state.OrderState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Order aggregate root — immutable.
 *
 * Design decision: immutable over mutable aggregate.
 * All state changes return a new Order instance instead of mutating in place.
 * This makes the model inherently thread-safe under high concurrent load
 * without requiring external synchronisation.
 *
 * The Builder validates all required invariants in build(), collecting every
 * violation before throwing, so callers receive a complete error picture.
 */
public final class Order {

    private static final Logger log = Logger.getLogger(Order.class.getName());

    private final UUID id;
    private final OrderState state;
    private final OrderCategory category;
    private final Customer customer;
    private final Site site;
    private final List<OrderItem> orderItems;
    private final PaymentMethod paymentMethod;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Order(Builder b) {
        this.id            = b.id;
        this.state         = b.state;
        this.category      = b.category;
        this.customer      = b.customer;
        this.site          = b.site;
        this.orderItems    = b.orderItems;
        this.paymentMethod = b.paymentMethod;
        this.createdAt     = b.createdAt;
        this.updatedAt     = b.updatedAt;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId()                    { return id; }
    public OrderState getState()           { return state; }
    public OrderCategory getCategory()     { return category; }
    public Customer getCustomer()          { return customer; }
    public Site getSite()                  { return site; }
    public List<OrderItem> getOrderItems() { return orderItems; }
    public PaymentMethod getPaymentMethod(){ return paymentMethod; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getUpdatedAt()          { return updatedAt; }

    // ── Identity (by id only — DDD aggregate root convention) ─────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    @Override
    public String toString() {
        return "Order{id=" + id + ", state=" + state.name() + ", category=" + category + "}";
    }

    // ── Builder entry points ──────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        return new Builder()
                .id(id).state(state).category(category)
                .customer(customer).site(site).orderItems(orderItems)
                .paymentMethod(paymentMethod).createdAt(createdAt).updatedAt(updatedAt);
    }

    // ── Domain factory ────────────────────────────────────────────────────────

    public static Order create(CreateOrderCommand cmd) {
        return Order.builder()
                .id(UUID.randomUUID())
                .state(new DraftState())
                .category(cmd.category())
                .customer(new Customer(cmd.customerId()))
                .site(new Site(cmd.siteId()))
                .orderItems(List.copyOf(cmd.orderItems()))
                .paymentMethod(new PaymentMethod(cmd.paymentMethodType(), cmd.paymentMethodIban()))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ── Domain behaviour ──────────────────────────────────────────────────────

    public Order applyPatch(PatchOrderCommand patch) {
        this.state.validatePatch(patch);

        Builder builder = this.toBuilder().updatedAt(Instant.now());

        if (patch.hasStateTransition()) {
            builder.state(this.state.transitionTo(patch.targetStateName()));
        }
        if (patch.category() != null)      builder.category(patch.category());
        if (patch.customerId() != null)    builder.customer(new Customer(patch.customerId()));
        if (patch.siteId() != null)        builder.site(new Site(patch.siteId()));
        if (patch.orderItems() != null)    builder.orderItems(List.copyOf(patch.orderItems()));
        if (patch.paymentMethod() != null) builder.paymentMethod(patch.paymentMethod());

        return builder.build();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private UUID id;
        private OrderState state;
        private OrderCategory category;
        private Customer customer;
        private Site site;
        private List<OrderItem> orderItems;
        private PaymentMethod paymentMethod;
        private Instant createdAt;
        private Instant updatedAt;

        private Builder() {}

        public Builder id(UUID id)                       { this.id = id;                   return this; }
        public Builder state(OrderState state)           { this.state = state;             return this; }
        public Builder category(OrderCategory category)  { this.category = category;       return this; }
        public Builder customer(Customer customer)       { this.customer = customer;       return this; }
        public Builder site(Site site)                   { this.site = site;               return this; }
        public Builder orderItems(List<OrderItem> items) { this.orderItems = items;        return this; }
        public Builder paymentMethod(PaymentMethod pm)   { this.paymentMethod = pm;        return this; }
        public Builder createdAt(Instant createdAt)      { this.createdAt = createdAt;     return this; }
        public Builder updatedAt(Instant updatedAt)      { this.updatedAt = updatedAt;     return this; }

        /**
         * Validates all domain invariants before constructing the Order.
         * Collects every violation and throws with the full error list —
         * callers receive a complete picture, not just the first failure.
         * Safe default: null orderItems treated as empty list for error reporting clarity.
         */
        public Order build() {
            List<String> errors = new ArrayList<>();

            if (id == null)            errors.add("id is required");
            if (state == null)         errors.add("state is required");
            if (category == null)      errors.add("category is required");
            if (customer == null)      errors.add("customer is required");
            if (site == null)          errors.add("site is required");
            if (paymentMethod == null) errors.add("paymentMethod is required");
            if (orderItems == null || orderItems.isEmpty())
                                       errors.add("orderItems must contain at least one item");

            if (!errors.isEmpty()) {
                String message = "Order construction failed: " + String.join("; ", errors);
                log.severe(message);
                throw new IllegalStateException(message);
            }

            return new Order(this);
        }
    }
}
