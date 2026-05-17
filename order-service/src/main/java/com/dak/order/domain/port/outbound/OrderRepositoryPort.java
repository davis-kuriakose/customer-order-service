package com.dak.order.domain.port.outbound;

import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.PagedOrders;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepositoryPort {

    Order save(Order order);

    Optional<Order> findById(UUID id);

    PagedOrders findAll(OrderCategory category, int limit, int offset);
}
