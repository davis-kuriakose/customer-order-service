package com.dak.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductOfferingTest {

    @Test
    void construction_succeeds_withValidFields() {
        ProductOffering po = new ProductOffering("po-1", "Basic Plan", new BigDecimal("9.99"));
        assertThat(po.id()).isEqualTo("po-1");
        assertThat(po.name()).isEqualTo("Basic Plan");
        assertThat(po.price()).isEqualByComparingTo("9.99");
    }

    @Test
    void construction_succeeds_withZeroPrice() {
        // Free offerings are valid
        ProductOffering po = new ProductOffering("po-free", "Free Tier", BigDecimal.ZERO);
        assertThat(po.price()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void construction_throws_whenIdIsBlank() {
        assertThatThrownBy(() -> new ProductOffering("", "Basic Plan", new BigDecimal("9.99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void construction_throws_whenIdIsNull() {
        assertThatThrownBy(() -> new ProductOffering(null, "Basic Plan", new BigDecimal("9.99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void construction_throws_whenNameIsBlank() {
        assertThatThrownBy(() -> new ProductOffering("po-1", "  ", new BigDecimal("9.99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void construction_throws_whenPriceIsNull() {
        assertThatThrownBy(() -> new ProductOffering("po-1", "Basic Plan", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
    }

    @Test
    void construction_throws_whenPriceIsNegative() {
        assertThatThrownBy(() -> new ProductOffering("po-1", "Basic Plan", new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }
}
