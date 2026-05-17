package com.dak.order.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record OrderItemRequest(
        @NotBlank String productOfferingId,
        @Min(1) int quantity
) {}
