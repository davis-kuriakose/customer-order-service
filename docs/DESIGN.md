# Architecture Design

## System Overview

Two independently deployable Spring Boot 3.x services (Java 21) in a Maven multi-module monorepo.

| Service | Module | Port | Responsibility |
|---|---|---|---|
| Order Service | `order-service/` | 8080 | Manages customer orders through a lifecycle |
| Catalog Service | `catalog-service/` | 8081 | Owns product offerings, consulted for validation |

```
docker compose up  →  both services + databases ready, catalog pre-seeded
```

---

## Repository Structure

Single monorepo with Maven multi-module parent at root.

```
customer-order-service/        ← git root
├── pom.xml                    ← parent reactor POM (shared dep management)
├── docker-compose.yml
├── order-service/             ← Spring Boot module, port 8080
└── catalog-service/           ← Spring Boot module, port 8081
```

**Why monorepo + multi-module**: Single repo satisfies the `.git/` folder submission requirement. The parent POM governs shared dependency versions (Spring Boot, Lombok, MapStruct, Resilience4j) in one place. Each module builds to an independently executable JAR and runs in its own container. Docker uses the root as build context so the parent POM is accessible during multi-stage builds.

---

## Architecture Pattern

**Hexagonal Architecture (Ports and Adapters) + Domain-Driven Design**

```
HTTP Request
    │
    ▼
[Web Layer]          controllers, DTOs, HTTP-specific concerns
    │  OrderWebMapper (MapStruct)
    ▼
[Domain Model]       pure Java — aggregates, value objects, state machine
    │
[Application Layer]  orchestrates domain + outbound ports, owns transactions
    │
    ├── [Persistence Adapter]   OrderRepositoryAdapter → JPA entities
    └── [Catalog Adapter]       CatalogRestAdapter → HTTP call
```

**Domain models are framework-free**: No Spring, JPA, or HTTP annotations cross into the domain package. Domain logic is unit-testable without starting a Spring context.

**Three mapping layers** (all MapStruct-generated):
- Web DTO → Domain command (inbound)
- Domain model → Web DTO (outbound)
- Domain model ↔ JPA entity (persistence)

**Package root**:
- `com.dak.order.*` — Order Service
- `com.dak.catalog.*` — Catalog Service

---

## Key Decisions

### State Machine — GoF State Pattern

**Decision**: Each order state is a concrete class implementing the `OrderState` interface: `DraftState`, `PreviewState`, `SubmittedState`, `ConfirmedState`.

**Why not enum**: Enum violates the Open/Closed Principle — adding a state or changing state-specific behavior requires modifying the enum file. With the State Pattern, each state class is closed for modification; adding a `CancelledState` = one new class + one factory entry, zero changes to existing states.

**Persistence**: State name stored as a `VARCHAR` column. Reconstructed on load by `OrderStateFactory` — a registry map of `String → Supplier<OrderState>`.

**State rules enforced inside each state class**:
- `DraftState` / `PreviewState` — payload fully editable
- `SubmittedState` — only `state` field patchable; any other field in patch body → 422
- `ConfirmedState` — fully locked; any patch → 422

### PATCH — JSON Merge Patch (RFC 7396)

**Decision**: Fields present in the request body are applied; absent fields are left unchanged. `Content-Type: application/merge-patch+json`.

**Why**: Standard, predictable semantics with no null/absent ambiguity. State-specific rules constrain which fields are patchable at each lifecycle stage.

### Idempotency — DB-backed SHA-256 Hash

**Decision**: `Idempotency-Key` header on `POST /customer-orders`. The SHA-256 hash of the request body and the serialized response are stored in an `idempotency_keys` table.
- Same key + same hash → replay stored response + `X-Idempotent-Replayed: true` header
- Same key + different hash → 409 Conflict

**Why DB (not cache)**: Survives service restarts. Race conditions on concurrent duplicate keys resolved by the database `PRIMARY KEY` constraint — caught via `DataIntegrityViolationException`, then re-read to apply match/conflict logic.

### Catalog Communication — Synchronous HTTP + Resilience4j

**Decision**: Spring `RestClient` (synchronous). Resilience4j circuit breaker (10-request window, 50% failure threshold, 30s open wait) + 2s `TimeLimiter`.

**Why sync over reactive**: Java 21 virtual threads (`spring.threads.virtual.enabled=true`) give synchronous code the concurrency characteristics of async without the programming model overhead.

**On unavailability**: Open circuit → `CatalogUnavailableException` → 503. Fail-fast is correct — silently accepting unvalidated offerings would corrupt order data.

**Resilience4j is production-ready**: It is the standard library for fault tolerance in Spring Boot 3.x, integrates with Actuator health indicators and metrics, and uses annotations that keep business logic clean.

### Concurrency — Optimistic Locking

**Decision**: `@Version BIGINT` column on the `orders` table. Concurrent `PATCH` on the same order → Hibernate detects the stale version → 409 Conflict returned to caller.

**Why not pessimistic**: `SELECT FOR UPDATE` serializes all writers and risks deadlocks under high concurrency. Optimistic locking is appropriate for read-heavy, low-contention workloads (most order updates are non-concurrent).

### Error Handling — RFC 7807 ProblemDetail

**Decision**: Spring Boot 3.x native `ProblemDetail` via `ResponseEntityExceptionHandler`. All errors share the same shape: `type`, `title`, `status`, `detail`, `instance`, optional `errors[]` array for field validation.

**Why RFC 7807**: Industry standard. Clients handle all error responses uniformly across all endpoints and error types.

### Security — API Key

**Decision**: `X-API-Key` header, value configured via `APP_API_KEY` environment variable. A `OncePerRequestFilter` validates on every request. Actuator `/health` and `/info` are excluded.

| Auth option | Assessment |
|---|---|
| Basic Auth | Credentials in every request, no expiry, no rotation — not acceptable |
| API Key | Stateless, env-var driven, no external dependency — correct for a POC |
| JWT / OAuth2 | Production-correct but requires auth server or token issuance — disproportionate for this scope |

**Inter-service**: Catalog service is secured with the same mechanism, keyed by `CATALOG_API_KEY`. Order service passes it as `X-API-Key` on all outbound catalog calls.

**Production path**: OAuth2/OIDC (Keycloak or AWS Cognito), JWT Bearer tokens, service-to-service via client credentials grant.

### Observability — Correlation ID

**Decision**: `X-Correlation-ID` header. Generated (UUID) if absent. Written to SLF4J MDC under key `correlationId`. Included in every log line. Propagated forward to catalog service calls via `RestClient` interceptor.

### Persistence — PostgreSQL + Flyway

**Decision**: One PostgreSQL database per service (port 5432 for orders, port 5433 for catalog). Flyway for versioned, reproducible migrations. JPA DDL auto set to `validate` — schema must match entities at startup.

**Why separate databases**: True data isolation. Neither service can bypass the other's API to access its data. Schema changes are independent.

**Why Flyway**: Version-controlled SQL. The `validate` mode catches schema drift at startup before requests arrive.

---

## Concurrency and Production Readiness

| Concern | Mechanism |
|---|---|
| Concurrent PATCH on same order | `@Version` optimistic lock → 409 |
| Concurrent duplicate idempotency POST | DB `PRIMARY KEY` uniqueness → 409 |
| High request throughput | Java 21 virtual threads (enabled globally) |
| DB connection management | HikariCP, pool size tuned per service |
| Catalog call failures | Resilience4j circuit breaker + timeout |
| Graceful shutdown | `server.shutdown: graceful` |
