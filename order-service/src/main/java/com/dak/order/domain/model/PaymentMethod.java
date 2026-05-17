package com.dak.order.domain.model;

public record PaymentMethod(PaymentMethodType type, String iban) {

    public PaymentMethod {
        if (type == null) {
            throw new IllegalArgumentException("Payment method type must not be null");
        }
        if (type == PaymentMethodType.DIRECT_DEBIT && (iban == null || iban.isBlank())) {
            throw new IllegalArgumentException("IBAN is required for DIRECT_DEBIT payment method");
        }
    }
}
