package com.dak.catalog.infrastructure.persistence.repository;

import com.dak.catalog.domain.model.ProductOffering;
import com.dak.catalog.domain.port.outbound.ProductOfferingRepositoryPort;
import com.dak.catalog.infrastructure.persistence.mapper.ProductOfferingPersistenceMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ProductOfferingRepositoryAdapter implements ProductOfferingRepositoryPort {

    private final ProductOfferingJpaRepository jpaRepository;
    private final ProductOfferingPersistenceMapper mapper;

    public ProductOfferingRepositoryAdapter(ProductOfferingJpaRepository jpaRepository,
                                            ProductOfferingPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<ProductOffering> findById(String id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<ProductOffering> findAll() {
        return jpaRepository.findAll().stream().map(mapper::toDomain).toList();
    }
}
