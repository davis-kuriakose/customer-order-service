package com.dak.catalog.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "product_offerings")
public class ProductOfferingJpaEntity {

    @Id
    @Column(nullable = false, length = 50)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    public String getId()           { return id; }
    public void setId(String v)     { this.id = v; }

    public String getName()         { return name; }
    public void setName(String v)   { this.name = v; }

    public BigDecimal getPrice()        { return price; }
    public void setPrice(BigDecimal v)  { this.price = v; }
}
