package com.dak.order.domain.model;

import java.util.List;

public record PagedOrders(List<Order> items, long total) {}
