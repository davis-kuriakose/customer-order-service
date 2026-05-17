package com.dak.order.domain.port.inbound;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;

import java.util.List;
import java.util.UUID;

public interface OrderUseCase {

    Order createOrder(CreateOrderCommand command);

    Order getOrder(UUID id);

    List<Order> listOrders(OrderCategory category, int limit, int offset);

    long countOrders(OrderCategory category);

    Order patchOrder(UUID id, PatchOrderCommand command);
}
