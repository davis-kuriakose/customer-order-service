package com.dak.order.web.dto;

import java.util.List;

public record PagedResponse<T>(
        List<T> items,
        long total,
        int limit,
        int offset
) {}
