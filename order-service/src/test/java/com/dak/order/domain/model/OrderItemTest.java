package com.dak.order.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderItemTest {

    @ParameterizedTest(name = "quantity {0} is rejected")
    @ValueSource(ints = {0, -1, -100})
    void quantity_lessThanOne_throwsIllegalArgument(int invalidQty) {
        assertThatThrownBy(() -> new OrderItem("po-1", invalidQty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quantity must be >= 1");
    }

    @Test
    void quantity_equalToOne_isValid() {
        assertThatNoException().isThrownBy(() -> new OrderItem("po-1", 1));
    }

    @Test
    void nullProductOfferingId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new OrderItem(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankProductOfferingId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new OrderItem("  ", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
