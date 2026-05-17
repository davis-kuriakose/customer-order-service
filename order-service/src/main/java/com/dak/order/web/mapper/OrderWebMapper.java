package com.dak.order.web.mapper;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.OrderItem;
import com.dak.order.domain.model.PaymentMethod;
import com.dak.order.domain.model.PaymentMethodType;
import com.dak.order.domain.state.OrderState;
import com.dak.order.web.dto.CreateOrderRequest;
import com.dak.order.web.dto.OrderItemRequest;
import com.dak.order.web.dto.OrderItemResponse;
import com.dak.order.web.dto.OrderResponse;
import com.dak.order.web.dto.PatchOrderRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface OrderWebMapper {

    CreateOrderCommand toCommand(CreateOrderRequest request);

    // paymentMethodType + paymentMethodIban are two source fields that combine into one
    // PaymentMethod value object — @Named method handles this cleanly instead of an expression.
    @Mapping(target = "paymentMethod", qualifiedByName = "buildPaymentMethod")
    PatchOrderCommand toPatchCommand(PatchOrderRequest request);

    OrderItem toOrderItem(OrderItemRequest request);

    // state.name() and category.name() are not JavaBean getters, so MapStruct cannot
    // resolve them via property chaining. @Named methods call .name() explicitly.
    @Mapping(target = "state",             source = "state",              qualifiedByName = "stateToName")
    @Mapping(target = "category",          source = "category",           qualifiedByName = "categoryToName")
    @Mapping(target = "customerId",        source = "customer.id")
    @Mapping(target = "siteId",            source = "site.id")
    @Mapping(target = "paymentMethodType", source = "paymentMethod.type", qualifiedByName = "paymentMethodTypeToName")
    @Mapping(target = "paymentMethodIban", source = "paymentMethod.iban")
    OrderResponse toResponse(Order order);

    OrderItemResponse toItemResponse(OrderItem item);

    // Combines paymentMethodType + paymentMethodIban into a PaymentMethod value object.
    // Returns null when no payment method type is provided (patch semantics — no update).
    @Named("buildPaymentMethod")
    default PaymentMethod buildPaymentMethod(PatchOrderRequest request) {
        if (request.paymentMethodType() == null) return null;
        return new PaymentMethod(request.paymentMethodType(), request.paymentMethodIban());
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
