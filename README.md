# Customer Order Platform

Two Spring Boot 3.x / Java 21 microservices for managing customer orders.

| Service | Port | Description |
|---|---|---|
| Order Service | 8080 | Manages customer orders through a lifecycle (draft → preview → submitted → confirmed) |
| Catalog Service | 8081 | Owns product offerings; consulted by order service for validation |

---

## How to Run

**Prerequisites**: Docker + Docker Compose

```bash
docker compose up
```

Both services, their databases, and catalog seed data start automatically. The system is ready when both health checks pass (typically 60–90 seconds).

**Default credentials**

| What | Value |
|---|---|
| Order Service API Key | `dev-api-key` (override with `APP_API_KEY` env var) |
| Catalog Service API Key | `internal-catalog-key` (override with `CATALOG_API_KEY` env var) |

**Seeded catalog offerings** (available immediately after compose up)

| ID | Name | Price |
|---|---|---|
| `po-1` | Basic Plan | 9.99 |
| `po-2` | Standard Plan | 29.99 |
| `po-3` | Premium Plan | 99.99 |

---

## Quick Test

```bash
# Health checks
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health

# Create an order
curl -X POST http://localhost:8080/customer-orders \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-api-key" \
  -d '{
    "category": "B2B",
    "customer": { "id": "cust-1" },
    "site": { "id": "site-1" },
    "orderItems": [{ "productOfferingId": "po-1", "quantity": 2 }],
    "paymentMethod": { "type": "INVOICE" }
  }'

# List product offerings
curl -H "X-API-Key: internal-catalog-key" http://localhost:8081/product-offerings
```

---

## What Was Built and What Was Cut

_(To be completed — see docs/DESIGN.md for architecture decisions)_

---

## Decisions and Tradeoffs

See [docs/DESIGN.md](docs/DESIGN.md) for a full explanation of every significant architectural decision.

---

## Assumptions and Known Limitations

_(To be completed)_
