package com.dak.order.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyJpaEntity {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getIdempotencyKey()       { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }

    public String getRequestHash()          { return requestHash; }
    public void setRequestHash(String v)    { this.requestHash = v; }

    public UUID getOrderId()                { return orderId; }
    public void setOrderId(UUID v)          { this.orderId = v; }

    public int getResponseStatus()          { return responseStatus; }
    public void setResponseStatus(int v)    { this.responseStatus = v; }

    public String getResponseBody()         { return responseBody; }
    public void setResponseBody(String v)   { this.responseBody = v; }

    public Instant getCreatedAt()           { return createdAt; }
    public void setCreatedAt(Instant v)     { this.createdAt = v; }
}
