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
    @Mapping(target = "paymentMethod", qualifiedByName = "paymentMethodFromEntity")
    Order toDomain(OrderJpaEntity entity);

    // ── Domain → Entity (update in-place) ────────────────────────────────────
    // orderItems ignored here — adapter clears and rebuilds the collection
    // to control parent reference assignment and UUID generation.

    @Mapping(target = "state",             source = "state.name")
    @Mapping(target = "category",          source = "category.name")
    @Mapping(target = "customerId",        source = "customer.id")
    @Mapping(target = "siteId",            source = "site.id")
    @Mapping(target = "paymentMethodType", source = "paymentMethod.type.name")
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
    @Named("paymentMethodFromEntity")
    default PaymentMethod paymentMethodFromEntity(OrderJpaEntity entity) {
        return new PaymentMethod(
                PaymentMethodType.valueOf(entity.getPaymentMethodType()),
                entity.getPaymentMethodIban());
    }
}
