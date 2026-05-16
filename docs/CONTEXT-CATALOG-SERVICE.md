# Catalog Service — Deep Context

> Supplement to `.claude/CONTEXT.md`. Read both when working on catalog-service.

---

## Module

- **Directory**: `catalog-service/`
- **Port**: 8081
- **Package root**: `com.dak.catalog`
- **Database**: `catalog_db` on port 5433
- **Spring Boot main class**: `com.dak.catalog.CatalogServiceApplication`

---

## API Contract

```
GET  /product-offerings/{id}     Get offering by ID (used by order-service for validation)
GET  /product-offerings          List all offerings (for manual testing / visibility)
```

All endpoints require `X-API-Key` header.
The key expected is configured via env var `APP_API_KEY` (set to `CATALOG_API_KEY` value in docker-compose).

---

## ProductOffering Resource Shape

```json
{
  "id": "string",
  "name": "string",
  "price": 9.99
}
```

---

## Seeded Data (must be present after `docker compose up`)

| ID | Name | Price |
|---|---|---|
| `po-1` | Basic Plan | 9.99 |
| `po-2` | Standard Plan | 29.99 |
| `po-3` | Premium Plan | 99.99 |

Seeded via Flyway `V2__seed_product_offerings.sql`.

---

## Package Structure

```
com.dak.catalog/
├── domain/
│   ├── model/           ProductOffering (Java record: id, name, price)
│   ├── port/
│   │   ├── inbound/     ProductOfferingUseCase
│   │   └── outbound/    ProductOfferingRepositoryPort
│   └── exception/       ProductOfferingNotFoundException
├── application/
│   └── service/         ProductOfferingService (implements ProductOfferingUseCase)
├── infrastructure/
│   └── persistence/
│       ├── entity/      ProductOfferingJpaEntity (@Entity, id is String not UUID)
│       ├── repository/  ProductOfferingJpaRepository (Spring Data),
│       │                ProductOfferingRepositoryAdapter
│       └── mapper/      ProductOfferingPersistenceMapper (MapStruct)
├── web/
│   ├── controller/      ProductOfferingController
│   ├── dto/             ProductOfferingResponse
│   ├── mapper/          ProductOfferingWebMapper (MapStruct)
│   └── exception/       GlobalExceptionHandler (RFC 7807 ProblemDetail)
├── security/            ApiKeyAuthFilter, SecurityConfig
└── common/              CorrelationIdFilter
```

---

## Domain Model

```java
// Java record — immutable, no JPA annotations
public record ProductOffering(String id, String name, BigDecimal price) {}
```

```java
// JPA entity — in infrastructure/persistence/entity/
@Entity
@Table(name = "product_offerings")
public class ProductOfferingJpaEntity {
    @Id
    private String id;          // string PK, not UUID
    private String name;
    private BigDecimal price;
}
```

---

## Flyway Migrations

```
V1__create_product_offerings_table.sql    product_offerings (id VARCHAR PK, name, price DECIMAL)
V2__seed_product_offerings.sql            INSERT po-1, po-2, po-3
```

---

## Security

Same `ApiKeyAuthFilter` pattern as order-service. Validates `X-API-Key` header against `app.api-key` config property. In docker-compose, `APP_API_KEY` is set to `${CATALOG_API_KEY:-internal-catalog-key}`.

The order-service sends `X-API-Key: <CATALOG_API_KEY>` on all catalog requests, configured via `app.catalog.api-key` in order-service's `application.yml`.

---

## Exception → HTTP Status Mapping

| Exception | Status |
|---|---|
| `ProductOfferingNotFoundException` | 404 |
| `MethodArgumentNotValidException` | 400 |

---

## Notes

- Catalog service has no state machine, no idempotency, no outbound HTTP calls — it is intentionally simple.
- No Resilience4j dependency in catalog-service (it is the callee, not the caller).
- The `ProductOfferingJpaEntity.id` is `String` (e.g., `"po-1"`) — NOT a UUID. This is intentional per the spec.
- List endpoint (`GET /product-offerings`) is not required by the spec but added for manual testing convenience.
