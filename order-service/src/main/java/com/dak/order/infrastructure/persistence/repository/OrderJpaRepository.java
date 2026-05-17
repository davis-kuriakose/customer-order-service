package com.dak.order.infrastructure.persistence.repository;

import com.dak.order.infrastructure.persistence.entity.OrderJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, UUID> {

    // Join-fetch orderItems in a single query for all load paths — eliminates N+1.

    @Override
    @NonNull
    @EntityGraph("Order.withItems")
    Optional<OrderJpaEntity> findById(@NonNull UUID id);

    @Override
    @NonNull
    @EntityGraph("Order.withItems")
    Page<OrderJpaEntity> findAll(@NonNull Pageable pageable);

    @EntityGraph("Order.withItems")
    Page<OrderJpaEntity> findByCategory(String category, Pageable pageable);

    long countByCategory(String category);
}
