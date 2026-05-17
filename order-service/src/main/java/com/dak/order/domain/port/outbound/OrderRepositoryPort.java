package com.dak.order.domain.port.outbound;

import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.PagedOrders;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepositoryPort {

    /**
     * Persists a brand-new order. No SELECT is issued before the INSERT.
     * The JPA entity's {@code @Version} starts null, which Spring Data JPA detects as
     * "new" and delegates directly to {@code EntityManager.persist()}.
     */
    Order create(Order order);

    /**
     * Updates an existing order. Loads the current JPA entity first to preserve the
     * {@code @Version} value required for optimistic-locking checks.
     */
    Order save(Order order);

    Optional<Order> findById(UUID id);

    PagedOrders findAll(OrderCategory category, int limit, int offset);
}
