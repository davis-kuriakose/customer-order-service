package com.dak.order.application.service;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.exception.OrderNotFoundException;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.port.inbound.OrderUseCase;
import com.dak.order.domain.port.outbound.OrderRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

@Service
public class OrderService implements OrderUseCase {

    private static final Logger log = Logger.getLogger(OrderService.class.getName());

    private final OrderRepositoryPort orderRepositoryPort;

    public OrderService(OrderRepositoryPort orderRepositoryPort) {
        this.orderRepositoryPort = orderRepositoryPort;
    }

    @Override
    @Transactional
    public Order createOrder(CreateOrderCommand command, String idempotencyKey) {
        // idempotencyKey persistence handled in TASK-09
        Order order = Order.create(command);
        log.info("Creating order id=" + order.getId() + " category=" + order.getCategory());
        return orderRepositoryPort.save(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Order getOrder(UUID id) {
        return orderRepositoryPort.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> listOrders(OrderCategory category, int limit, int offset) {
        return orderRepositoryPort.findAll(category, limit, offset);
    }

    @Override
    @Transactional(readOnly = true)
    public long countOrders(OrderCategory category) {
        return orderRepositoryPort.countAll(category);
    }

    @Override
    @Transactional
    public Order patchOrder(UUID id, PatchOrderCommand command) {
        Order order = orderRepositoryPort.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        Order patched = order.applyPatch(command);
        return orderRepositoryPort.save(patched);
    }
}
