package com.dak.order.infrastructure.persistence.entity;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItemJpaEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderJpaEntity order;

    @Column(name = "product_offering_id", nullable = false)
    private String productOfferingId;

    @Column(nullable = false)
    private int quantity;

    public UUID getId()                    { return id; }
    public OrderJpaEntity getOrder()       { return order; }
    public String getProductOfferingId()   { return productOfferingId; }
    public int getQuantity()               { return quantity; }

    public void setId(UUID id)                             { this.id = id; }
    public void setOrder(OrderJpaEntity order)             { this.order = order; }
    public void setProductOfferingId(String offeringId)    { this.productOfferingId = offeringId; }
    public void setQuantity(int quantity)                  { this.quantity = quantity; }
}
