package com.dak.order.infrastructure.persistence.repository;

import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.port.outbound.OrderRepositoryPort;
import com.dak.order.infrastructure.persistence.entity.OrderItemJpaEntity;
import com.dak.order.infrastructure.persistence.entity.OrderJpaEntity;
import com.dak.order.infrastructure.persistence.mapper.OrderPersistenceMapper;
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
    public Order save(Order order) {
        // Load existing to preserve @Version — prevents optimistic lock false-positives.
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
    public List<Order> findAll(OrderCategory category, int limit, int offset) {
        PageRequest page = PageRequest.of(offset / limit, limit, Sort.by("createdAt").descending());
        if (category != null) {
            return jpaRepository.findByCategory(category.name(), page)
                    .map(mapper::toDomain).toList();
        }
        return jpaRepository.findAll(page).map(mapper::toDomain).toList();
    }

    @Override
    public long countAll(OrderCategory category) {
        return category != null
                ? jpaRepository.countByCategory(category.name())
                : jpaRepository.count();
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
