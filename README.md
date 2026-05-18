# Customer Order Platform

Two Spring Boot 3.3 / Java 21 microservices — Order Service and Catalog Service — built as a production-grade take-home submission.

| Service | Port | Responsibility |
|---|---|---|
| Order Service | 8080 | Manages customer orders through a defined lifecycle |
| Catalog Service | 8081 | Owns product offerings; consulted for validation on every order write |

---

## How to Run

**Prerequisite:** Docker + Docker Compose

```bash
docker compose up --build
```

Both services, their PostgreSQL databases, and the catalog seed data start automatically.
The system is ready when both health checks pass (typically 60–90 s on first build).

| Endpoint | URL |
|---|---|
| Order Service API | http://localhost:8080/customer-orders |
| Catalog Service API | http://localhost:8081/product-offerings |
| Order Service health | http://localhost:8080/actuator/health |
| Catalog Service health | http://localhost:8081/actuator/health |
| Order Service Swagger UI | http://localhost:8080/swagger-ui.html *(requires `SWAGGER_ENABLED=true`)* |

### Security — API Key Auth (off by default)

API key authentication is **disabled by default** so that automated test harnesses work without any header configuration. To enable it:

```bash
APP_SECURITY_ENABLED=true docker compose up --build
```

When enabled, every request must carry `X-API-Key: <key>`. The keys are:

| Variable | Default value | Used on |
|---|---|---|
| `APP_API_KEY` | `dev-api-key` | Order Service |
| `CATALOG_API_KEY` | `internal-catalog-key` | Catalog Service + inter-service calls |

When security is enabled, use the header in every request:
```bash
-H "X-API-Key: dev-api-key"
```

### Seeded Product Offerings

Available immediately after `docker compose up`:

| ID | Name | Price |
|---|---|---|
| `po-1` | Basic Plan | $9.99 |
| `po-2` | Standard Plan | $29.99 |
| `po-3` | Premium Plan | $99.99 |

### Environment Variables Reference

| Variable | Default | Description |
|---|---|---|
| `APP_SECURITY_ENABLED` | `false` | Enable X-API-Key auth on both services |
| `APP_API_KEY` | `dev-api-key` | API key for Order Service |
| `CATALOG_API_KEY` | `internal-catalog-key` | API key for Catalog Service |
| `ORDER_DB_PASSWORD` | `order_pass` | PostgreSQL password for order-db |
| `CATALOG_DB_PASSWORD` | `catalog_pass` | PostgreSQL password for catalog-db |
| `SWAGGER_ENABLED` | `false` | Expose Swagger UI and OpenAPI spec |

---

## API Contract

### Important: field naming convention

The spec shows nested objects (`customer.id`, `site.id`, `paymentMethod.type`). This implementation uses flat field names for request and response bodies — a deliberate design choice to avoid unnecessary wrapper objects in a simple value context:

| Spec field | This API | Notes |
|---|---|---|
| `customer.id` | `customerId` | flat string |
| `site.id` | `siteId` | flat string |
| `paymentMethod.type` | `paymentMethodType` | flat enum string |
| `paymentMethod.iban` | `paymentMethodIban` | flat string, only for DIRECT_DEBIT |
| `orderItems[].productOfferingId` | `items[].productOfferingId` | response field is `items` |
| `state` values | `DRAFT`, `PREVIEW`, `SUBMITTED`, `CONFIRMED` | uppercase |

**State transitions via PATCH** use the field `targetStateName` (not `state`) to make the intent explicit and avoid ambiguity between "set state directly" and "trigger a validated transition."

### Create an Order

```bash
curl -s -X POST http://localhost:8080/customer-orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-request-001" \
  -d '{
    "category": "B2B",
    "customerId": "cust-abc",
    "siteId": "site-xyz",
    "orderItems": [{"productOfferingId": "po-1", "quantity": 2}],
    "paymentMethodType": "INVOICE"
  }'
# → 201 Created, Location: /customer-orders/{id}
```

Required fields: `category`, `customerId`, `siteId`, `orderItems` (non-empty), `paymentMethodType`.  
`paymentMethodIban` is required when `paymentMethodType` is `DIRECT_DEBIT`.

### Idempotent Create (safe to retry)

```bash
# First call  → 201, no replay header
# Retry same  → 201, X-Idempotent-Replayed: true, same order returned
# Retry diff body → 409 Conflict
curl -H "Idempotency-Key: my-request-001" ...
```

### Retrieve an Order

```bash
curl http://localhost:8080/customer-orders/{id}
```

### List Orders (paginated)

```bash
curl "http://localhost:8080/customer-orders?limit=20&offset=0"
curl "http://localhost:8080/customer-orders?category=B2B&limit=10&offset=0"
```

Response: `{ "items": [...], "total": <int>, "limit": <int>, "offset": <int> }`

### Advance the Order State

```bash
curl -X PATCH http://localhost:8080/customer-orders/{id} \
  -H "Content-Type: application/merge-patch+json" \
  -d '{"targetStateName": "PREVIEW"}'
```

Valid lifecycle: `DRAFT → PREVIEW → SUBMITTED → CONFIRMED`  
`PREVIEW → DRAFT` is reversible. All other backward or invalid transitions return 422.

Once `SUBMITTED`: only `targetStateName` may be patched (no payload edits).  
Once `CONFIRMED`: no further changes.

### Patch Order Fields

```bash
curl -X PATCH http://localhost:8080/customer-orders/{id} \
  -H "Content-Type: application/merge-patch+json" \
  -d '{"customerId": "cust-new", "siteId": "site-new"}'
# Absent fields are untouched (JSON Merge Patch — RFC 7396)
```

### Catalog Lookup

```bash
curl http://localhost:8081/product-offerings/po-1
curl http://localhost:8081/product-offerings
```

---

## Error Responses

All errors use RFC 7807 `application/problem+json`:

```json
{ "type": "about:blank", "title": "Unprocessable Entity", "status": 422, "detail": "..." }
```

| Scenario | Status |
|---|---|
| Validation failure | 400 |
| Missing/invalid API key (when security enabled) | 401 |
| Unknown product offering | 422 |
| Invalid state transition | 422 |
| Mutation of locked order | 422 |
| Concurrent update conflict | 409 |
| Idempotency key conflict | 409 |
| Catalog unreachable | 503 |

---

## Running Tests

```bash
# Unit tests only — fast, no Docker required
mvn test -pl order-service -Dnet.bytebuddy.experimental=true \
  -Dexclude="**/integration/**"

# Full suite including integration tests (requires Docker — Testcontainers pulls postgres:16)
mvn test -Dnet.bytebuddy.experimental=true

# Catalog service unit tests
mvn test -pl catalog-service -Dnet.bytebuddy.experimental=true \
  -Dexclude="**/CatalogSecurityIntegrationTest*,**/ProductOfferingServiceCacheTest*"
```

> **Note:** `net.bytebuddy.experimental=true` is required when running on Java 24+. It is already
> configured in the parent `pom.xml` for Maven Surefire so `mvn test` picks it up automatically.

---

## What Was Built

### Order Service
| Feature | Detail |
|---|---|
| `POST /customer-orders` | Create with optional `Idempotency-Key` |
| `GET /customer-orders/{id}` | Fetch by UUID |
| `GET /customer-orders` | Paginated list (`limit`/`offset`), filterable by `category` |
| `PATCH /customer-orders/{id}` | JSON Merge Patch — partial update |
| Order lifecycle | `DRAFT → PREVIEW → SUBMITTED → CONFIRMED` via GoF State Pattern |
| Catalog validation | Every write validates offering IDs against catalog-service in parallel |
| Resilience | Resilience4j circuit breaker (10-req window, 50% threshold, 30 s wait) + 2 s HTTP timeout |
| Idempotency | SHA-256 of request body; replay on match, 409 on hash conflict; single-node race guard via `ReentrantLock` in `IdempotencyService` |
| Optimistic locking | `@Version` column; concurrent PATCH → 409 |
| Security | `X-API-Key` with constant-time comparison — opt-in via `APP_SECURITY_ENABLED` |
| Correlation ID | `X-Correlation-ID` propagated to catalog calls and SLF4J MDC |
| HTTP security headers | `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Cache-Control: no-store` |
| Error format | RFC 7807 ProblemDetail on all error responses |
| Virtual threads | `spring.threads.virtual.enabled: true`; catalog validation runs N offerings in parallel via `CompletableFuture` + virtual-thread executor |
| OpenAPI docs | Swagger UI at `/swagger-ui.html` (disabled by default; enable via `SWAGGER_ENABLED=true`) |

### Catalog Service
| Feature | Detail |
|---|---|
| `GET /product-offerings` | List all |
| `GET /product-offerings/{id}` | Lookup by ID — 404 if unknown |
| Security | `X-API-Key` (same opt-in flag) |
| Caching | Caffeine cache on `getById` — 10 min TTL, max 500 entries |
| Seed data | 3 offerings via Flyway V2 migration (`po-1`, `po-2`, `po-3`) |

---

## What Was Cut

| Item | Reason |
|---|---|
| OAuth2 / JWT | Disproportionate for a POC; API Key is stateless and env-var driven |
| Async / reactive | Java 21 virtual threads give synchronous code equivalent concurrency |
| Distributed tracing | MDC correlation ID covers log correlation; full tracing is infrastructure |
| Cancellation state | Not in scope; State Pattern makes adding it one new class + one factory entry |
| Rate limiting | Belongs at the API Gateway layer |
| Distributed idempotency lock | `ReentrantLock` is correct for single-node; Redis SETNX would be needed for multi-instance |
| Nested response objects | Flat field names (`customerId` vs `customer.id`) chosen for simplicity |

---

## Decisions and Tradeoffs

> See [docs/DESIGN_PROGRESS.md](docs/DESIGN_PROGRESS.md) for the full step-by-step design log — every task, the problem it addressed, and why that approach was chosen.

| Decision | Choice | Rationale |
|---|---|---|
| Architecture | Hexagonal + DDD | Domain is framework-free; fully unit-testable without Spring context |
| State machine | GoF State Pattern (not enum) | Open/Closed — new state = one class, zero changes elsewhere |
| PATCH semantics | JSON Merge Patch (RFC 7396) + `targetStateName` | No null/absent ambiguity; `targetStateName` makes state transitions explicit |
| Idempotency | DB-backed SHA-256 + per-key `ReentrantLock` | Survives restarts; single-node race condition prevented without schema changes |
| Catalog fault tolerance | Resilience4j circuit breaker + 2 s socket timeout | Virtual threads remove need for reactive patterns |
| Catalog validation | Parallel `CompletableFuture.allOf()` with virtual-thread executor | N offerings validated in one round-trip instead of N serial calls |
| Concurrency | Optimistic locking (`@Version`) | No `SELECT FOR UPDATE` deadlocks |
| Transaction scope | `@Transactional` at method level only; removed from `createOrder`/`patchOrder` | Catalog HTTP calls must not hold a DB connection from the pool |
| Error format | RFC 7807 ProblemDetail | Industry standard; uniform client handling |
| Object mapping | MapStruct interface + `@Named` converters | Compile-time safety; no reflection |
| Schema management | Flyway + `ddl-auto: validate` | Schema drift caught at startup, not at runtime |
| Security | `X-API-Key`, opt-in via env var | Security-first design showcase; opt-out default means test harnesses work unmodified |
| Databases | One PostgreSQL per service | True data isolation; independent schema evolution |

---

## Project Structure

```
customer-order-service/
├── pom.xml                         parent reactor POM
├── docker-compose.yml
├── .env.example                    all env vars documented
├── docs/
│   ├── DESIGN.md                   full architecture decisions
│   └── CONTEXT-*.md                per-service context
├── order-service/
│   ├── Dockerfile
│   └── src/main/java/com/dak/order/
│       ├── domain/                 zero framework dependencies
│       │   ├── model/              Order (aggregate root), value objects, PagedOrders
│       │   ├── state/              DraftState, PreviewState, SubmittedState, ConfirmedState
│       │   ├── command/            CreateOrderCommand, PatchOrderCommand
│       │   ├── exception/          domain exceptions
│       │   └── port/               inbound (OrderUseCase) + outbound (CatalogPort, ...)
│       ├── application/service/    OrderService, IdempotencyService (incl. per-key lock)
│       ├── infrastructure/         JPA adapters, CatalogRestAdapter, CatalogClientConfig
│       ├── web/                    controllers, DTOs, mappers, GlobalExceptionHandler
│       ├── security/               ApiKeyAuthFilter, SecurityConfig (opt-in)
│       └── common/                 CorrelationIdFilter (with log-injection prevention)
└── catalog-service/
    ├── Dockerfile
    └── src/main/java/com/dak/catalog/
        ├── domain/ application/ infrastructure/ web/ security/ common/
```

---

## Assumptions and Known Limitations

- **Security is opt-in**: `APP_SECURITY_ENABLED=false` by default. The design is security-first — the filter, constant-time comparison, and HTTP security headers are all in place; the flag just controls whether it is enforced. Set `APP_SECURITY_ENABLED=true` in any environment where callers are expected to authenticate.
- **State values are uppercase**: `DRAFT`, `PREVIEW`, `SUBMITTED`, `CONFIRMED`. The spec diagram uses lowercase; this implementation uses uppercase to match Java enum naming convention. Transition is triggered by `targetStateName` in the PATCH body (not `state`).
- **Flat response fields**: `customerId` / `siteId` / `paymentMethodType` rather than nested `customer.id` / `site.id` / `paymentMethod.type`. This is a deliberate deviation documented here.
- **Single-node idempotency race guard**: The per-key `ReentrantLock` in `IdempotencyService` prevents duplicate orders from concurrent same-key requests on one instance. Multi-node deployments would need a distributed lock (e.g. Redis SETNX or a DB advisory lock).
- **Offset pagination**: `limit`/`offset` pagination uses Spring Data's page model (`offset / limit` page index). Offsets must be multiples of `limit` for correct results. A keyset cursor (`createdAt` + `id`) would be more stable under concurrent inserts.
- **Catalog consulted online**: If the catalog is unreachable at write time, the request returns 503 after the circuit breaker fires. There is no cached allowlist of known offering IDs as a fallback.
- **API key rotation** requires a rolling restart — no dynamic key refresh without a secrets manager.
- **Java 24+ compatibility**: Byte Buddy (bundled with Mockito) requires `-Dnet.bytebuddy.experimental=true` on Java 24+. This is pre-configured in the parent `pom.xml`.
