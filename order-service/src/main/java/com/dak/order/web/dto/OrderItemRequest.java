package com.dak.order.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OrderItemRequest(
        @NotBlank @Pattern(regexp = "[\\w\\-]+") String productOfferingId,
        @Min(1) int quantity
) {}
