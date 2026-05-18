package com.dak.order.infrastructure.persistence.mapper;

import com.dak.order.domain.model.*;
import com.dak.order.domain.state.OrderState;
import com.dak.order.domain.state.OrderStateFactory;
import com.dak.order.infrastructure.persistence.entity.OrderItemJpaEntity;
import com.dak.order.infrastructure.persistence.entity.OrderJpaEntity;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface OrderPersistenceMapper {

    // ── Entity → Domain ──────────────────────────────────────────────────────
    // MapStruct auto-maps List<OrderItemJpaEntity> → List<OrderItem> via toItem().
    // Named converters handle types that cannot be auto-resolved.

    @Mapping(target = "state",         source = "state",      qualifiedByName = "stateFromString")
    @Mapping(target = "category",      source = "category",   qualifiedByName = "categoryFromString")
    @Mapping(target = "customer",      source = "customerId", qualifiedByName = "customerFromId")
    @Mapping(target = "site",          source = "siteId",     qualifiedByName = "siteFromId")
    // source = "entity" passes the whole source parameter to paymentMethodFromEntity — MapStruct 1.6.x
    // requires the parameter name explicitly when a @Named method takes the full source object.
    @Mapping(target = "paymentMethod", source = "entity",     qualifiedByName = "paymentMethodFromEntity")
    Order toDomain(OrderJpaEntity entity);

    // ── Domain → Entity (update in-place) ────────────────────────────────────
    // orderItems ignored here — adapter clears and rebuilds the collection
    // to control parent reference assignment and UUID generation.
    // state.name(), category.name(), type.name() are not JavaBean getters —
    // @Named methods call .name() explicitly instead of property chaining.

    @Mapping(target = "state",             source = "state",              qualifiedByName = "stateToName")
    @Mapping(target = "category",          source = "category",           qualifiedByName = "categoryToName")
    @Mapping(target = "customerId",        source = "customer.id")
    @Mapping(target = "siteId",            source = "site.id")
    @Mapping(target = "paymentMethodType", source = "paymentMethod.type", qualifiedByName = "paymentMethodTypeToName")
    @Mapping(target = "paymentMethodIban", source = "paymentMethod.iban")
    @Mapping(target = "version",           ignore = true)
    @Mapping(target = "orderItems",        ignore = true)
    void updateEntity(@MappingTarget OrderJpaEntity entity, Order order);

    // ── OrderItem ─────────────────────────────────────────────────────────────
    // toItem() is also used automatically by MapStruct when mapping the List
    // in toDomain(). toItemEntity() leaves `order` unset — adapter sets it.

    OrderItem toItem(OrderItemJpaEntity entity);

    @Mapping(target = "id",    expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "order", ignore = true)
    OrderItemJpaEntity toItemEntity(OrderItem item);

    // ── Named type converters ─────────────────────────────────────────────────

    @Named("stateFromString")
    default OrderState stateFromString(String state) {
        return OrderStateFactory.from(state);
    }

    @Named("categoryFromString")
    default OrderCategory categoryFromString(String category) {
        return OrderCategory.valueOf(category);
    }

    @Named("customerFromId")
    default Customer customerFromId(String customerId) {
        return new Customer(customerId);
    }

    @Named("siteFromId")
    default Site siteFromId(String siteId) {
        return new Site(siteId);
    }

    // Takes the full entity because PaymentMethod requires two fields (type + iban).
    // source = "entity" in @Mapping passes the whole source parameter — required in MapStruct 1.6.x.
    @Named("paymentMethodFromEntity")
    default PaymentMethod paymentMethodFromEntity(OrderJpaEntity entity) {
        return new PaymentMethod(
                PaymentMethodType.valueOf(entity.getPaymentMethodType()),
                entity.getPaymentMethodIban());
    }

    @Named("stateToName")
    default String stateToName(OrderState state) {
        return state != null ? state.name() : null;
    }

    @Named("categoryToName")
    default String categoryToName(OrderCategory category) {
        return category != null ? category.name() : null;
    }

    @Named("paymentMethodTypeToName")
    default String paymentMethodTypeToName(PaymentMethodType type) {
        return type != null ? type.name() : null;
    }
}
