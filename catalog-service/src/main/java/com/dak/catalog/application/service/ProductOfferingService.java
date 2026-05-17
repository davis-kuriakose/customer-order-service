package com.dak.catalog.application.service;

import com.dak.catalog.domain.exception.ProductOfferingNotFoundException;
import com.dak.catalog.domain.model.ProductOffering;
import com.dak.catalog.domain.port.inbound.ProductOfferingUseCase;
import com.dak.catalog.domain.port.outbound.ProductOfferingRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductOfferingService implements ProductOfferingUseCase {

    private final ProductOfferingRepositoryPort repository;

    public ProductOfferingService(ProductOfferingRepositoryPort repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductOffering getById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ProductOfferingNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductOffering> getAll() {
        return repository.findAll();
    }
}
