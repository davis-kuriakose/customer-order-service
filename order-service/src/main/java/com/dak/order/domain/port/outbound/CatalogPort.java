package com.dak.order.domain.port.outbound;

import java.util.List;

public interface CatalogPort {

    /**
     * Validates that all given product offering IDs exist in the catalog.
     * Throws ProductOfferingNotFoundException for any unknown ID.
     * Throws CatalogUnavailableException if the catalog cannot be reached.
     */
    void validateOfferings(List<String> productOfferingIds);
}
