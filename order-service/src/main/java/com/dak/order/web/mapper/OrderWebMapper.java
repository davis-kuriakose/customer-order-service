package com.dak.order.web.mapper;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderItem;
import com.dak.order.web.dto.CreateOrderRequest;
import com.dak.order.web.dto.OrderItemRequest;
import com.dak.order.web.dto.OrderItemResponse;
import com.dak.order.web.dto.OrderResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderWebMapper {

    CreateOrderCommand toCommand(CreateOrderRequest request);

    OrderItem toOrderItem(OrderItemRequest request);

    @Mapping(target = "state",             source = "state.name")
    @Mapping(target = "category",          source = "category.name")
    @Mapping(target = "customerId",        source = "customer.id")
    @Mapping(target = "siteId",            source = "site.id")
    @Mapping(target = "paymentMethodType", source = "paymentMethod.type.name")
    @Mapping(target = "paymentMethodIban", source = "paymentMethod.iban")
    OrderResponse toResponse(Order order);

    OrderItemResponse toItemResponse(OrderItem item);
}
