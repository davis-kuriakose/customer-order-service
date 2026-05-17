package com.dak.order.infrastructure.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Explicit getters/setters — avoids Lombok IDE plugin requirement and follows JPA best practices
// (JPA entities should be mutable, explicit, and free of equals/hashCode surprises from @Data).
@NamedEntityGraph(
        name = "Order.withItems",
        attributeNodes = @NamedAttributeNode("orderItems")
)
@Entity
@Table(name = "orders")
public class OrderJpaEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(nullable = false, length = 10)
    private String category;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "site_id", nullable = false)
    private String siteId;

    @Column(name = "pm_type", nullable = false, length = 20)
    private String paymentMethodType;

    @Column(name = "pm_iban", length = 50)
    private String paymentMethodIban;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItemJpaEntity> orderItems = new ArrayList<>();

    public UUID getId()                              { return id; }
    public String getState()                         { return state; }
    public String getCategory()                      { return category; }
    public String getCustomerId()                    { return customerId; }
    public String getSiteId()                        { return siteId; }
    public String getPaymentMethodType()             { return paymentMethodType; }
    public String getPaymentMethodIban()             { return paymentMethodIban; }
    public Instant getCreatedAt()                    { return createdAt; }
    public Instant getUpdatedAt()                    { return updatedAt; }
    public Long getVersion()                         { return version; }
    public List<OrderItemJpaEntity> getOrderItems()  { return orderItems; }

    public void setId(UUID id)                              { this.id = id; }
    public void setState(String state)                      { this.state = state; }
    public void setCategory(String category)                { this.category = category; }
    public void setCustomerId(String customerId)            { this.customerId = customerId; }
    public void setSiteId(String siteId)                    { this.siteId = siteId; }
    public void setPaymentMethodType(String type)           { this.paymentMethodType = type; }
    public void setPaymentMethodIban(String iban)           { this.paymentMethodIban = iban; }
    public void setCreatedAt(Instant createdAt)             { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt)             { this.updatedAt = updatedAt; }
    public void setVersion(Long version)                    { this.version = version; }
}
