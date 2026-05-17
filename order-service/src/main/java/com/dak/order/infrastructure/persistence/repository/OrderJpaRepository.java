package com.dak.order.infrastructure.persistence.repository;

import com.dak.order.infrastructure.persistence.entity.OrderJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, UUID> {

    Page<OrderJpaEntity> findByCategory(String category, Pageable pageable);

    long countByCategory(String category);
}
