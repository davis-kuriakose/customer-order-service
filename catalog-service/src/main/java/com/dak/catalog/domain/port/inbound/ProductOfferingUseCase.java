package com.dak.catalog.domain.port.inbound;

import com.dak.catalog.domain.model.ProductOffering;

import java.util.List;

public interface ProductOfferingUseCase {

    /**
     * Returns the product offering with the given ID.
     *
     * @throws com.dak.catalog.domain.exception.ProductOfferingNotFoundException if no offering exists with that ID
     */
    ProductOffering getById(String id);

    List<ProductOffering> getAll();
}
