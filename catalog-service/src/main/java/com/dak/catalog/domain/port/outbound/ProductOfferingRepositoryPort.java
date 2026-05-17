package com.dak.catalog.domain.port.outbound;

import com.dak.catalog.domain.model.ProductOffering;

import java.util.List;
import java.util.Optional;

public interface ProductOfferingRepositoryPort {

    Optional<ProductOffering> findById(String id);

    List<ProductOffering> findAll();
}
