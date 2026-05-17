package com.dak.order.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentMethodTest {

    @Test
    void directDebit_withNullIban_throwsIllegalArgument() {
        assertThatThrownBy(() -> new PaymentMethod(PaymentMethodType.DIRECT_DEBIT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IBAN is required");
    }

    @Test
    void directDebit_withBlankIban_throwsIllegalArgument() {
        assertThatThrownBy(() -> new PaymentMethod(PaymentMethodType.DIRECT_DEBIT, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IBAN is required");
    }

    @Test
    void directDebit_withValidIban_isValid() {
        assertThatNoException().isThrownBy(
                () -> new PaymentMethod(PaymentMethodType.DIRECT_DEBIT, "DE89370400440532013000"));
    }

    @Test
    void invoice_withoutIban_isValid() {
        assertThatNoException().isThrownBy(
                () -> new PaymentMethod(PaymentMethodType.INVOICE, null));
    }

    @Test
    void nullType_throwsIllegalArgument() {
        assertThatThrownBy(() -> new PaymentMethod(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
