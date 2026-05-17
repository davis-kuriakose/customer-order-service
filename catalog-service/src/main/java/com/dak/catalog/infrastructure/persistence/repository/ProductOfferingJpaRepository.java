package com.dak.catalog.infrastructure.persistence.repository;

import com.dak.catalog.infrastructure.persistence.entity.ProductOfferingJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOfferingJpaRepository extends JpaRepository<ProductOfferingJpaEntity, String> {}
