package com.dak.order.domain.port.outbound;

import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepositoryPort {

    Order save(Order order);

    Optional<Order> findById(UUID id);

    List<Order> findAll(OrderCategory category, int limit, int offset);

    long countAll(OrderCategory category);
}
