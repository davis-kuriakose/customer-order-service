# Customer Order Platform

Production-grade Java showcase — two Spring Boot 3.3 / Java 21 microservices built with Hexagonal Architecture and Domain-Driven Design.

| Service | Port | Responsibility |
|---|---|---|
| Order Service | 8080 | Manages customer orders through a defined lifecycle |
| Catalog Service | 8081 | Owns product offerings; consulted for validation on every order write |

---

## Quick Start

**Prerequisites**: Docker + Docker Compose

```bash
docker compose up --build
```

Both services, their databases, and catalog seed data start automatically.  
The system is ready when both health checks pass (typically 60–90 s on first build).

| Endpoint | URL |
|---|---|
| Order Service API | http://localhost:8080/customer-orders |
| Order Service Swagger UI | http://localhost:8080/swagger-ui.html |
| Catalog Service API | http://localhost:8081/product-offerings |
| Order Service health | http://localhost:8080/actuator/health |
| Catalog Service health | http://localhost:8081/actuator/health |

### Default API Keys

| Variable | Default | Used as |
|---|---|---|
| `APP_API_KEY` | `dev-api-key` | `X-API-Key` header on Order Service |
| `CATALOG_API_KEY` | `internal-catalog-key` | `X-API-Key` on Catalog Service + inter-service calls |

Override by setting these as environment variables before `docker compose up`.

### Seeded Product Offerings

| ID | Name | Price |
|---|---|---|
| `po-1` | Basic Plan | $9.99 |
| `po-2` | Standard Plan | $29.99 |
| `po-3` | Premium Plan | $99.99 |

---

## API Examples

### Create an Order

```bash
curl -s -X POST http://localhost:8080/customer-orders \
  -H "X-API-Key: dev-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "category": "B2B",
    "customerId": "cust-abc",
    "siteId": "site-xyz",
    "orderItems": [{"productOfferingId": "po-1", "quantity": 2}],
    "paymentMethodType": "INVOICE"
  }'
# → 201 Created, Location: /customer-orders/{id}
```

### Idempotent Create (safe to retry)

```bash
curl -s -X POST http://localhost:8080/customer-orders \
  -H "X-API-Key: dev-api-key" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-unique-request-001" \
  -d '{ ...same body... }'
# First call  → 201, no replay header
# Retry same  → 201, X-Idempotent-Replayed: true, same order ID
# Retry diff  → 409 Conflict
```

### Advance the Order State

```bash
curl -s -X PATCH http://localhost:8080/customer-orders/{id} \
  -H "X-API-Key: dev-api-key" \
  -H "Content-Type: application/merge-patch+json" \
  -d '{"targetStateName": "PREVIEW"}'
```

Valid lifecycle: **DRAFT → PREVIEW → SUBMITTED → CONFIRMED**  
PREVIEW ↔ DRAFT is reversible. All other backward transitions are rejected with 422.

### Patch Order Fields

```bash
curl -s -X PATCH http://localhost:8080/customer-orders/{id} \
  -H "X-API-Key: dev-api-key" \
  -H "Content-Type: application/merge-patch+json" \
  -d '{"customerId": "cust-new", "siteId": "site-new"}'
# Only the fields present in the body are changed (JSON Merge Patch semantics)
```

### List Orders (paginated)

```bash
curl -s "http://localhost:8080/customer-orders?category=B2B&limit=20&offset=0" \
  -H "X-API-Key: dev-api-key"
```

---

## Running Tests

```bash
# Unit tests only — fast, no Docker required
mvn test -pl order-service -Dtest="!OrderIntegrationTest"

# All tests including integration (Testcontainers pulls postgres:16 automatically)
mvn test -pl order-service

# Both modules
mvn test
```

The integration test (`OrderIntegrationTest`) spins up a real PostgreSQL container via Testcontainers and a WireMock server to stub catalog responses. It covers idempotency replay, conflict, catalog 404→422, and catalog connection failure→503.

---

## What Was Built

### Order Service
| Feature | Detail |
|---|---|
| `POST /customer-orders` | Create with optional `Idempotency-Key` |
| `GET  /customer-orders/{id}` | Fetch by UUID |
| `GET  /customer-orders` | Paginated list, filterable by `category` |
| `PATCH /customer-orders/{id}` | JSON Merge Patch — partial update |
| Order lifecycle | DRAFT → PREVIEW → SUBMITTED → CONFIRMED via GoF State Pattern |
| Catalog validation | Every write validates offering IDs against catalog-service |
| Resilience | Resilience4j circuit breaker (10-req window, 50% threshold, 30 s wait) + 2 s HTTP timeout |
| Idempotency | SHA-256 of request body stored in DB; replay on match, 409 on hash conflict |
| Optimistic locking | `@Version` column; concurrent PATCH → 409 |
| Security | `X-API-Key` header with constant-time comparison |
| Correlation ID | `X-Correlation-ID` header propagated to catalog, written to SLF4J MDC |
| Error format | RFC 7807 ProblemDetail on all error responses |
| OpenAPI docs | Swagger UI at `/swagger-ui.html` |

### Catalog Service
| Feature | Detail |
|---|---|
| `GET /product-offerings` | List all |
| `GET /product-offerings/{id}` | Lookup by ID — returns 404 if unknown |
| Security | `X-API-Key` header |
| Seed data | 3 offerings via Flyway V2 migration |

---

## What Was Cut

| Item | Reason |
|---|---|
| OAuth2 / JWT | Disproportionate for a POC; API Key is stateless and env-var driven |
| Async / reactive catalog | Java 21 virtual threads give synchronous code equivalent concurrency — no reactive overhead |
| Distributed tracing (OpenTelemetry) | MDC correlation ID covers log correlation; full tracing is an infrastructure concern |
| Cancellation state | Not in scope; State Pattern makes adding it one new class + one factory entry |
| Rate limiting | Belongs at the API Gateway layer (AWS API Gateway, Kong, etc.) |
| Client-side retry | Callers are expected to retry on 503; server-side retry would risk double-creates |
| Async event publishing | No downstream consumer exists; synchronous validation keeps the model simple |

---

## Architecture Decisions

Full rationale with trade-off analysis is in [docs/DESIGN.md](docs/DESIGN.md). High-level:

| Decision | Choice | Key reason |
|---|---|---|
| Architecture | Hexagonal + DDD | Domain is framework-free; unit-testable without Spring |
| State machine | GoF State Pattern | Open/Closed — new state = one class, zero changes elsewhere |
| PATCH | JSON Merge Patch (RFC 7396) | No null/absent ambiguity |
| Idempotency | DB-backed SHA-256 | Survives restarts; race conditions handled by `PRIMARY KEY` |
| Catalog fault tolerance | Resilience4j CB + sync RestClient | Virtual threads eliminate async complexity |
| Concurrency | Optimistic locking (`@Version`) | No `SELECT FOR UPDATE` deadlocks |
| Error format | RFC 7807 ProblemDetail | Industry standard; uniform client handling |
| Mapping | MapStruct interface + `@Named` | Compile-time safety; no reflection |
| Schema management | Flyway + `ddl-auto: validate` | Schema drift caught at startup |
| Databases | One PostgreSQL per service | True data isolation; independent schema evolution |

---

## Project Structure

```
customer-order-service/
├── pom.xml                         parent reactor POM
├── docker-compose.yml
├── docs/
│   ├── DESIGN.md                   full architecture decisions
│   └── CONTEXT-*.md                per-service context
├── order-service/
│   ├── Dockerfile
│   └── src/main/java/com/dak/order/
│       ├── domain/                 zero framework dependencies
│       │   ├── model/              Order (aggregate root), value objects
│       │   ├── state/              DraftState, PreviewState, SubmittedState, ConfirmedState
│       │   ├── command/            CreateOrderCommand, PatchOrderCommand
│       │   ├── exception/          domain exceptions
│       │   └── port/               inbound (OrderUseCase) + outbound (CatalogPort, …)
│       ├── application/service/    OrderService, IdempotencyService
│       ├── infrastructure/         JPA adapters, CatalogRestAdapter, CatalogClientConfig
│       ├── web/                    controllers, DTOs, mappers, GlobalExceptionHandler
│       ├── security/               ApiKeyAuthFilter, SecurityConfig
│       └── common/                 CorrelationIdFilter
└── catalog-service/
    ├── Dockerfile
    └── src/main/java/com/dak/catalog/
        ├── domain/ application/ infrastructure/ web/ security/ common/
```

---

## Assumptions and Known Limitations

- **Single-node**: No distributed circuit-breaker state or idempotency store. Each instance is independent.
- **API key rotation** requires a rolling restart — no dynamic key refresh without a secrets manager.
- **Catalog is consulted online**: If the catalog is unreachable at write time, the request returns 503. There is no cached allowlist of known offering IDs as a fallback.
- **No retry queue**: A 503 from the order service means the caller must retry. A production system would add a retry policy at the gateway or a dead-letter queue for async flows.
- **Observability**: SLF4J MDC + `correlationId` in every log line. Spring Boot Actuator health endpoint. Full distributed tracing (OpenTelemetry/Zipkin) is not included.
- **No pagination cursor**: The list endpoint uses `limit`/`offset`, which can be inconsistent under concurrent inserts. A keyset cursor (`createdAt` + `id`) would be more stable for production.
