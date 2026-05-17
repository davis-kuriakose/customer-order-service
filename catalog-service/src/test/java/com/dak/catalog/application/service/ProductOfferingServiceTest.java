package com.dak.catalog.application.service;

import com.dak.catalog.domain.exception.ProductOfferingNotFoundException;
import com.dak.catalog.domain.model.ProductOffering;
import com.dak.catalog.domain.port.outbound.ProductOfferingRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductOfferingServiceTest {

    @Mock
    private ProductOfferingRepositoryPort repository;

    @InjectMocks
    private ProductOfferingService service;

    private static final ProductOffering PO_1 =
            new ProductOffering("po-1", "Basic Plan", new BigDecimal("9.99"));

    @Test
    void getById_returnsOffering_whenFound() {
        when(repository.findById("po-1")).thenReturn(Optional.of(PO_1));

        ProductOffering result = service.getById("po-1");

        assertThat(result).isEqualTo(PO_1);
        assertThat(result.name()).isEqualTo("Basic Plan");
        assertThat(result.price()).isEqualByComparingTo("9.99");
    }

    @Test
    void getById_throwsProductOfferingNotFoundException_whenNotFound() {
        when(repository.findById("po-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById("po-999"))
                .isInstanceOf(ProductOfferingNotFoundException.class)
                .hasMessageContaining("po-999");
    }

    @Test
    void getAll_returnsAllOfferings() {
        ProductOffering po2 = new ProductOffering("po-2", "Standard Plan", new BigDecimal("29.99"));
        when(repository.findAll()).thenReturn(List.of(PO_1, po2));

        List<ProductOffering> result = service.getAll();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ProductOffering::id).containsExactly("po-1", "po-2");
    }
}
