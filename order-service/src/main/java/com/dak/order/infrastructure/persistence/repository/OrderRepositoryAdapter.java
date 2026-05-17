package com.dak.order.infrastructure.persistence.repository;

import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.PagedOrders;
import com.dak.order.domain.port.outbound.OrderRepositoryPort;
import com.dak.order.infrastructure.persistence.entity.OrderItemJpaEntity;
import com.dak.order.infrastructure.persistence.entity.OrderJpaEntity;
import com.dak.order.infrastructure.persistence.mapper.OrderPersistenceMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class OrderRepositoryAdapter implements OrderRepositoryPort {

    private final OrderJpaRepository jpaRepository;
    private final OrderPersistenceMapper mapper;

    public OrderRepositoryAdapter(OrderJpaRepository jpaRepository, OrderPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Order create(Order order) {
        // Fresh entity: @Version is null → Spring Data JPA isNew() returns true
        // → em.persist() (INSERT) is called directly, no preceding SELECT.
        OrderJpaEntity entity = new OrderJpaEntity();
        mapper.updateEntity(entity, order);
        syncItems(entity, order);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Order save(Order order) {
        // Load existing entity to preserve @Version — without it Hibernate cannot perform
        // optimistic-locking checks and would throw OptimisticLockException on every update.
        OrderJpaEntity entity = jpaRepository.findById(order.getId())
                .orElseGet(OrderJpaEntity::new);
        mapper.updateEntity(entity, order);
        syncItems(entity, order);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public PagedOrders findAll(OrderCategory category, int limit, int offset) {
        PageRequest page = PageRequest.of(offset / limit, limit, Sort.by("createdAt").descending());
        Page<OrderJpaEntity> pageResult;
        if (category != null) {
            pageResult = jpaRepository.findByCategory(category.name(), page);
        } else {
            // Spring Data fires both the data query and a COUNT within the same transaction.
            // getTotalElements() surfaces the count without a second @Transactional call.
            pageResult = jpaRepository.findAll(page);
        }
        List<Order> items = pageResult.map(mapper::toDomain).toList();
        return new PagedOrders(items, pageResult.getTotalElements());
    }

    // Mapper sets field values; adapter controls parent-ref assignment and UUID generation.
    private void syncItems(OrderJpaEntity entity, Order order) {
        entity.getOrderItems().clear();
        order.getOrderItems().forEach(item -> {
            OrderItemJpaEntity ie = mapper.toItemEntity(item);
            ie.setOrder(entity);
            entity.getOrderItems().add(ie);
        });
    }
}
