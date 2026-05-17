package com.dak.order.domain.port.inbound;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.PagedOrders;

import java.util.UUID;

public interface OrderUseCase {

    Order createOrder(CreateOrderCommand command);

    Order getOrder(UUID id);

    PagedOrders listOrders(OrderCategory category, int limit, int offset);

    Order patchOrder(UUID id, PatchOrderCommand command);
}
