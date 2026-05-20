# Design Progress Log

This document captures the step-by-step design decisions and implementation tasks completed during the development of the Customer Order Platform. It is intended for reviewers — interviewers, collaborators, or future maintainers — to understand not just *what* was built, but *how the thinking evolved* at each stage.

Tasks were defined upfront as a planning discipline, then implemented in order. Each task represents a deliberate design decision, not an ad-hoc change.

---

## Design Principles Applied Throughout

| Principle | Application |
|---|---|
| Hexagonal Architecture | Domain layer has zero framework imports; all Spring/JPA code lives in infrastructure |
| Tests alongside code | Every task that changes Java code includes unit tests in the same unit of work |
| MapStruct interfaces only | All mappers are `public interface` with `@Mapping`/`@Named` — never abstract classes — so generated code is compile-time safe and transparent |
| Method-level `@Transactional` | Never at class level; annotated only where a transaction is actually required |
| RFC 7807 error shape | All error responses use Spring's `ProblemDetail` — uniform, standard, client-friendly |

---

## Phase 1 — Foundation

### TASK-01 — Project Scaffold

Set up the Maven multi-module monorepo (`customer-order-platform`) with two child modules — `order-service` (port 8080) and `catalog-service` (port 8081).

**Key decisions:**
- Parent `pom.xml` holds shared dependency management (Spring Boot 3.3.x, Java 21, MapStruct, Resilience4j, Testcontainers, WireMock). Child POMs declare only what they specifically need.
- Multi-stage Docker builds: JDK image compiles and packages; JRE runtime image (Alpine) runs the JAR. This keeps production images small and free of build tooling.
- `docker-compose.yml` provisions both databases, both services, and health checks so `docker compose up` is the only command a reviewer needs.

---

### TASK-02 — Flyway Migrations (Order Service)

Designed the order-service database schema upfront so all structural decisions are explicit and version-controlled.

**Schema decisions:**

| Table | Design rationale |
|---|---|
| `orders` | `version BIGINT` added for optimistic locking (`@Version`). All state stored as VARCHAR (not enum) so the DB is not coupled to Java enum names. |
| `order_items` | Separate table with FK + index on `order_id` — not a JSON column — so queries against items are indexable and normalised. |
| `idempotency_keys` | `idempotency_key VARCHAR PRIMARY KEY` — the primary key constraint is the duplicate-prevention mechanism; no application-level uniqueness enforcement needed. `request_hash VARCHAR(64)` stores the SHA-256 hex digest (64 chars exactly). |

`ddl-auto: validate` is used in all environments — Flyway owns schema creation; Hibernate only validates at startup. This catches schema drift immediately.

---

### TASK-03 — Order Domain Model

Designed the core domain with zero framework dependencies. The domain layer is pure Java — no Spring annotations, no JPA imports, no Jackson. This is the most important architectural decision in the project.

**GoF State Pattern for order lifecycle:**

The order lifecycle (DRAFT → PREVIEW → SUBMITTED → CONFIRMED) is implemented as four concrete state classes (`DraftState`, `PreviewState`, `SubmittedState`, `ConfirmedState`), each encoding its own permitted transitions and validation rules. The `OrderStateFactory` maps state names to instances via a `Map<String, Supplier<OrderState>>` — no reflection, no enum coupling.

*Why State Pattern over an enum switch?* Adding a new state (`CANCELLED`, `EXPIRED`) requires one new class and one factory entry — no changes to existing code (Open/Closed Principle). An enum switch would require editing every place that branches on state.

**Immutable aggregate root:**

`Order` is fully immutable. All state changes return a new `Order` instance via `toBuilder()` copy. The inner `Builder` validates all invariants in `build()` and *collects every violation* before throwing — callers receive a complete error picture, not just the first failure (fail-all, not fail-fast).

**Value objects as Java records:**

`OrderItem`, `Customer`, `Site`, `PaymentMethod` are all Java records. Compact constructors enforce invariants: `OrderItem` rejects `quantity < 1` and blank `productOfferingId`; `PaymentMethod` rejects `DIRECT_DEBIT` without an IBAN.

**Ports (interfaces) defined in the domain:**

`OrderRepositoryPort`, `IdempotencyRepositoryPort`, and `CatalogPort` are outbound interfaces in the domain layer. The domain depends on abstractions; infrastructure implements them. This is the Dependency Inversion Principle applied to a hexagonal architecture.

**Unit test coverage at this stage:** 30+ tests covering all state transitions (valid + invalid), `Order.create()`, `Order.applyPatch()`, immutability, `OrderItem` and `PaymentMethod` invariants.

---

## Phase 2 — Core Order API

### TASK-04 — Create and Retrieve Endpoints

Implemented `POST /customer-orders` (create) and `GET /customer-orders/{id}` (retrieve).

**Design decisions:**
- `OrderController` is responsible only for HTTP translation — it reads the request, calls the use-case port, and builds the response. No business logic lives here.
- `OrderService` implements `OrderUseCase` (the inbound port). It coordinates between domain, repository, and catalog ports.
- `OrderWebMapper` (MapStruct interface) converts between web DTOs and domain commands/models. `@Named` default methods handle complex mappings (e.g., combining `paymentMethodType` + `paymentMethodIban` into a `PaymentMethod` value object) instead of using `expression = "java(...)"` which bypasses compile-time checking.
- `POST` returns `201 Created` with a `Location` header pointing to the new resource.

---

### TASK-05 — List Orders with Pagination

Added `GET /customer-orders?limit=20&offset=0&category=B2B`.

**Design decisions:**
- `limit` and `offset` use `@Min`/`@Max` bean validation constraints so invalid values are rejected at the HTTP layer before reaching the service.
- `category` is an optional filter — omitting it returns all categories.
- Response shape: `{ "items": [...], "total": <long>, "limit": <int>, "offset": <int> }` as specified.

---

### TASK-06 — State Transition Validation

State validation is entirely in the domain layer — not in the controller, not in the service. Each state class's `validatePatch()` method decides what is permitted.

**Validation rules encoded per state:**

| State | `validatePatch` behaviour |
|---|---|
| DRAFT | All payload edits permitted |
| PREVIEW | All payload edits permitted |
| SUBMITTED | Only `targetStateName` allowed; any other field → `OrderMutationForbiddenException` |
| CONFIRMED | All patches rejected → `OrderMutationForbiddenException` |

These exceptions map to 422 Unprocessable Entity in `GlobalExceptionHandler`.

---

### TASK-07 — PATCH with JSON Merge Patch

Implemented `PATCH /customer-orders/{id}` using JSON Merge Patch semantics (RFC 7396): fields present in the body are updated; absent fields are left untouched.

**Design decisions:**
- `PatchOrderRequest` uses all-nullable fields — null means "not present in the patch", not "set to null".
- State transitions are triggered by the field `targetStateName` (not `state`) to make the intent explicit — it is a *command* to transition, not a direct field assignment.
- Optimistic locking conflicts (two concurrent PATCHes on the same order) are caught by `@Version` and surfaced as 409 Conflict via `GlobalExceptionHandler`.
- `OrderWebMapper.toPatchCommand` uses `@Named("buildPaymentMethod")` to combine two flat fields into one `PaymentMethod` value object — the `source = "request"` MapStruct 1.6.x annotation passes the full source object to the converter method.

---

### TASK-08 — Global Exception Handler

Centralised all error-to-HTTP mapping in `GlobalExceptionHandler` (extends `ResponseEntityExceptionHandler`).

**All error responses use RFC 7807 `application/problem+json`:**

| Exception | HTTP status | Reason |
|---|---|---|
| `OrderNotFoundException` | 404 | Standard REST semantics |
| `InvalidStateTransitionException` | 422 | Not a server error — the request is syntactically valid but semantically rejected |
| `OrderMutationForbiddenException` | 422 | Same — the state machine forbids this change |
| `ProductOfferingNotFoundException` | 422 | Referenced offering does not exist — request cannot be fulfilled as given |
| `CatalogUnavailableException` | 503 | Downstream dependency unavailable |
| `IdempotencyConflictException` | 409 | Same key, different payload — a real conflict |
| Optimistic lock (`ObjectOptimisticLockingFailureException`) | 409 | Concurrent modification detected |
| Bean validation (`MethodArgumentNotValidException`) | 400 | Bad request; `errors[]` array added with field-level details |

`GlobalExceptionHandlerTest` uses no Spring context — the handler is tested as a plain Java class using `Objects.requireNonNull` to construct `MockHttpServletRequest`.

---

### TASK-09 — Idempotency

Implemented idempotent order creation via an `Idempotency-Key` request header.

**Design:**
1. Client sends `Idempotency-Key: <uuid>` on `POST /customer-orders`.
2. `IdempotencyService.computeHash()` produces a SHA-256 hex digest of the request body (canonical JSON via Jackson).
3. If the key has been seen before with the **same hash** → replay the stored response with `X-Idempotent-Replayed: true`.
4. If the key has been seen before with a **different hash** → 409 `IdempotencyConflictException`.
5. If the key is new → create the order, store the serialised response in `idempotency_keys`, return 201.

**Key design decisions:**
- Idempotency key is an HTTP concern — the `OrderUseCase` port does not receive it. The service layer is idempotency-unaware.
- The `idempotency_key` primary key constraint in the DB is the last line of defence against duplicate inserts; `DataIntegrityViolationException` on duplicate insert is caught silently (the first write wins).
- `IdempotencyService` owns a `ConcurrentHashMap<String, ReentrantLock>` — the `withLock()` method serialises the check→create→store sequence per key, preventing duplicate orders from concurrent requests with the same key on a single instance.
- Multi-node deployments would require a distributed lock (Redis SETNX or a DB advisory lock) — documented as a known limitation.

---

## Phase 3 — Catalog Service + Integration

### TASK-10 — Catalog Service

Built the full catalog-service as a self-contained Spring Boot application.

**Design decisions:**
- `ProductOffering` uses a String PK (`id`), not UUID — IDs like `po-1`, `po-2` are meaningful reference data identifiers.
- `price` uses `BigDecimal` — never `float` or `double` for monetary values.
- Flyway V2 seeds `po-1`, `po-2`, `po-3` so the system is usable immediately after `docker compose up`.
- The catalog service applies the same hexagonal layering as the order service: domain → application → infrastructure → web; zero framework dependencies in the domain.

---

### TASK-11 — Catalog HTTP Client (Order Service)

Implemented `CatalogRestAdapter` to call the catalog service from the order service.

**Design decisions:**
- Spring's `RestClient` is used (not `RestTemplate` or `WebClient`) — it is the modern synchronous HTTP client in Spring 6, idiomatic with virtual threads.
- A `ClientHttpRequestInterceptor` propagates the `X-Correlation-ID` header from the current MDC to outbound catalog calls, so both services share the same correlation ID in their logs.
- 404 from catalog → `ProductOfferingNotFoundException` (domain exception, maps to 422).
- All other errors (`ResourceAccessException`, non-404 HTTP errors) → `CatalogUnavailableException` (maps to 503).

---

### TASK-12 — Catalog Validation Integration

Wired catalog validation into the order create and patch flows.

**Design decisions:**
- Validation happens *before* `Order.create()` — an invalid offering is rejected before any domain object is constructed or any DB write is attempted.
- On PATCH, catalog is re-validated *only when `orderItems` is present in the patch body* — if the patch does not touch items, a catalog call is not made. This avoids unnecessary inter-service calls for state-only transitions.

---

### TASK-13 — Catalog Resilience

Added a Resilience4j circuit breaker around `CatalogRestAdapter.validateOfferings`.

**Design decisions:**
- Circuit breaker configuration: 10-request sliding window, 50% failure threshold, 30 s open-state wait, 3 probe calls in half-open.
- `ProductOfferingNotFoundException` is added to `ignore-exceptions` — a catalog 404 is a valid business response (unknown offering), not a circuit failure. The circuit should not open because clients are referencing non-existent offerings.
- `@TimeLimiter` annotation was deliberately *not* used — it only applies to `CompletableFuture`-returning methods. The 2-second deadline is enforced by `JdkClientHttpRequestFactory.setReadTimeout()` at the HTTP socket level instead.

---

## Phase 4 — Cross-Cutting Concerns

### TASK-14 — API Key Auth and Correlation ID (Order Service)

**API Key auth (`ApiKeyAuthFilter`):**
- Custom `OncePerRequestFilter` — not `@Component` (see TASK-29 for why).
- Constant-time comparison via `MessageDigest.isEqual()` prevents timing-attack extraction of the key prefix.
- Rejects with `401 application/problem+json` (consistent with all other error responses).
- Auth is opt-in via `APP_SECURITY_ENABLED` env var (default `false`) so automated test harnesses work without sending the header.

**Correlation ID (`CorrelationIdFilter`):**
- Reads `X-Correlation-ID` from the request; generates a UUID if absent or if the value contains unsafe characters (log-injection prevention: only `[a-zA-Z0-9\-]{1,64}` accepted).
- Writes the correlation ID to SLF4J MDC and echoes it in the response header.
- MDC is always cleared in a `finally` block — virtual threads reuse carriers, so un-cleared MDC would leak to the next request.

---

### TASK-15 — Unit Test Completeness

Reviewed the full state machine test matrix and added missing self-loop tests (DRAFT→DRAFT, PREVIEW→PREVIEW, etc.) — 26 state-transition tests in total covering all valid transitions and all rejected ones.

---

### TASK-16 — Integration Tests

`OrderIntegrationTest` uses Testcontainers (real PostgreSQL container) and WireMock (stubbed catalog service) in a `@SpringBootTest(RANDOM_PORT)` context.

**Scenarios verified end-to-end:**
- Create without idempotency key → 201, no replay header
- Idempotency replay (same key + payload) → 201, `X-Idempotent-Replayed: true`
- Idempotency conflict (same key, different payload) → 409
- Unknown offering (catalog returns 404) → 422
- Catalog connection failure → 503
- Missing API key (when security enabled) → 401
- Invalid request body → 400
- Multi-item order with all valid offerings → 201
- Multi-item order with one unknown offering → 422
- List orders after creation → consistent `items` and `total`

WireMock is started in a static initialiser to guarantee it is up before `@DynamicPropertySource` configures `app.catalog.base-url`.

---

### TASK-17 — README

Wrote `README.md` covering all exercise requirements: how to run, what was built and cut, decisions and tradeoffs, assumptions and known limitations. The README documents API field naming conventions, state value casing, and the security flag default so a test harness operator has everything they need.

---

## Phase 5 — Performance & Production Readiness

At this point the system was functionally complete. The following tasks address production-readiness concerns identified by a performance review.

### TASK-18 — Fix N+1 Query on Order Items

**Problem:** `@OneToMany(fetch = FetchType.EAGER)` on `OrderJpaEntity.orderItems` caused Hibernate to issue one SELECT per order when loading a list, resulting in N+1 queries.

**Fix:**
- Changed `FetchType.EAGER` → `FetchType.LAZY` on the `orderItems` collection.
- Added `@NamedEntityGraph("Order.withItems")` at the entity class level.
- Annotated all three `OrderJpaRepository` query methods (`findById`, `findAll(Pageable)`, `findByCategory`) with `@EntityGraph("Order.withItems")`.

This instructs Hibernate to issue a single JOIN query per repository call instead of N+1 SELECTs, while preserving lazy loading elsewhere.

---

### TASK-19 — Remove Dead @TimeLimiter Config

The `resilience4j.timelimiter` block in `application.yml` was inert — `@TimeLimiter` only applies to methods returning `CompletableFuture`. `CatalogRestAdapter.validateOfferings()` is synchronous (`void`), so the config was never evaluated. Removed it and replaced with a comment explaining that the active deadline is the 2 s socket timeout in `CatalogClientConfig`.

*Lesson:* Inert configuration is dangerous — it creates a false sense of protection and confuses future maintainers.

---

### TASK-20 — HTTP Connection Pooling for Catalog Client

**Problem:** `SimpleClientHttpRequestFactory` opens a new TCP connection per request — no keep-alive, no connection reuse. Under load, this means a new TCP handshake (+ TLS handshake if HTTPS) on every catalog call.

**Fix:** Replaced with `JdkClientHttpRequestFactory` backed by a shared `java.net.http.HttpClient`. The JDK `HttpClient` maintains a connection pool with HTTP/1.1 keep-alive and HTTP/2 multiplexing enabled by default. No additional dependency — both classes are in the JDK or `spring-web`.

---

### TASK-21 — Move Catalog Validation Outside @Transactional

**Problem:** `OrderService.createOrder()` had `@Transactional`. This meant a DB connection was checked out from the Hikari pool *before* the catalog HTTP call started and held open during the entire round-trip. Under concurrent load, this exhausts the pool while threads are blocked on network I/O.

**Fix:** Removed `@Transactional` from `createOrder()` and `patchOrder()`. The catalog validation (potentially multiple parallel HTTP calls) now completes before any DB connection is acquired. Spring Data JPA's own `@Transactional` on repository methods provides the transaction boundary for DB operations only.

*This is a subtle but important design decision that most implementations get wrong.*

---

### TASK-VT1 — Parallel Catalog Offering Validation (Virtual Threads)

**Problem:** With N items in an order, catalog validation was serial — N HTTP round-trips in sequence. Latency = N × catalog_latency.

**Fix:** Replaced the serial `forEach` with `CompletableFuture.allOf()` backed by `Executors.newVirtualThreadPerTaskExecutor()`. Each offering ID is validated on its own virtual thread concurrently. Latency = 1 × catalog_latency regardless of N.

**Thread model considerations:**
- MDC uses `ThreadLocal`, which is *not* inherited across thread boundaries. The correlation ID is captured on the parent thread before forking and re-set in each child virtual thread.
- `CompletionException` is unwrapped at the join point — `ProductOfferingNotFoundException` and `CatalogUnavailableException` are re-thrown directly; other `Throwable` causes are wrapped in `CatalogUnavailableException`.
- `ProductOfferingNotFoundException` stays in Resilience4j `ignore-exceptions` — it must not count as a circuit failure even when thrown from a virtual thread.

---

### TASK-22 — Eliminate Second DB Transaction for Pagination Count

**Problem:** The original `listOrders` implementation made two separate calls to the repository — one for the data page and one for the total count. Spring Data JPA wraps each in its own transaction, meaning two round-trips to the DB.

**Fix:** Introduced `PagedOrders` — a domain record `(List<Order> items, long total)`. Spring Data's `Page<T>` result from `findAll(Pageable)` already contains both the data and the total count (`Page.getTotalElements()`) from a single transaction. `OrderRepositoryAdapter.findAll()` populates `PagedOrders` from this single `Page` result, eliminating the second transaction entirely.

---

### TASK-23 — Missing Database Indexes

Added `V4__add_indexes.sql` with three indexes:

| Index | Column | Justification |
|---|---|---|
| `idx_orders_state` | `orders.state` | Supports `WHERE state = ?` queries (listing by state, circuit-breaker exclusion) |
| `idx_orders_customer_id` | `orders.customer_id` | Supports future `WHERE customer_id = ?` queries |
| `idx_idempotency_order_id` | `idempotency_keys.order_id` | Supports lookup of idempotency record by order ID |

---

### TASK-24 — ThreadLocal MessageDigest for SHA-256 Hashing

**Problem:** `MessageDigest.getInstance("SHA-256")` acquires an internal JCA provider lock on every call — an unnecessary synchronisation point under concurrent load.

**Fix:** `IdempotencyService` holds a `static final ThreadLocal<MessageDigest>` initialised with `ThreadLocal.withInitial()`. The JCA provider lookup happens once per thread (once per virtual thread in a virtual-thread model), not once per request. `MessageDigest.digest()` auto-resets the instance after use, so the per-thread instance is safely reusable.

This pattern is safe for virtual threads: each virtual thread gets its own `ThreadLocal` slot. Verified with a 50-thread concurrent test in `IdempotencyServiceTest`.

---

### TASK-25 — Skip SELECT Before INSERT for New Orders

**Problem:** Spring Data JPA's `CrudRepository.save()` calls `em.merge()` when the entity has an `@Version` field that is not null (existing entity). For brand-new orders, `save()` issues a `SELECT` first to determine whether to `INSERT` or `UPDATE` — an unnecessary round-trip.

**Fix:** Added a separate `create(Order)` method to `OrderRepositoryPort` and `OrderRepositoryAdapter`. The `create()` method builds a fresh `OrderJpaEntity` with no `@Version` set (null). Spring Data's `isNew()` check sees `@Version == null` → calls `em.persist()` directly → `INSERT` with no preceding `SELECT`.

`save(Order)` is retained for updates, where loading the existing entity first is necessary to preserve the `@Version` value for optimistic-locking checks.

---

### TASK-26 — Swagger UI Controlled by Environment Variable

Swagger UI and the OpenAPI spec are controlled by `SWAGGER_ENABLED` (default `false`). This follows the 12-factor configuration principle — behaviour is controlled by environment, not by code changes. Default is `false` (safe for production); operators set `SWAGGER_ENABLED=true` for development or demo environments.

---

### TASK-27 — Explicit Graceful Shutdown Timeout

Set `spring.lifecycle.timeout-per-shutdown-phase: 20s` explicitly in both services. Spring Boot's default (30 s) was undocumented and implicit. 20 s is chosen to be shorter than the typical load-balancer connection-drain window (30 s) — the LB stops routing new traffic before Spring forcibly terminates in-flight requests.

---

### TASK-28 — Caffeine Cache for Catalog Product Offering Lookups

Product offering data is read-only reference data. Every order write validates each offering ID against the catalog service, which internally queries the DB. Under load, these are redundant DB reads for stable data.

**Fix:** Added `@Cacheable` on `ProductOfferingService.getById()` backed by Caffeine. Configuration: `maximumSize=500`, `expireAfterWrite=10m`. Cache misses (unknown offering IDs) are intentionally *not* cached — `ProductOfferingNotFoundException` must always re-query the DB in case the offering was added recently.

Verified with `ProductOfferingServiceCacheTest` (Testcontainers + real DB): same ID × 3 → repository called once; different IDs → each misses cache; exception → always re-queries.

---

## Phase 6 — Security Hardening

A dedicated code review and security review identified the following issues, which were addressed together.

### TASK-29 — Fix ApiKeyAuthFilter Double Registration

**Problem:** When `ApiKeyAuthFilter extends OncePerRequestFilter` is annotated with `@Component`, Spring Boot auto-registers it as a standalone servlet filter *and* adds it to the Spring Security filter chain — causing each request to run through the filter twice.

**Fix:** Removed `@Component`. The filter is constructed explicitly inside `SecurityConfig.filterChain()` so it only exists in the security filter chain. `OncePerRequestFilter` prevents double execution within a single chain, but the double registration itself was the bug.

---

### TASK-30 — Security Hardening Pass

A post-completion code review and security review identified and addressed the following:

**Log injection via `X-Correlation-ID` (HIGH)**
The correlation ID header was accepted verbatim — an attacker could embed `\n` (newline) to forge log lines. Fixed by validating the header against `[a-zA-Z0-9\-]{1,64}` and replacing any non-conforming value with a generated UUID.

**Actuator bypass scope too broad (HIGH)**
`shouldNotFilter` used `uri.startsWith("/actuator/")` which would silently bypass auth for any newly enabled actuator endpoint (metrics, env, beans, etc.). Narrowed to `equals("/actuator/health")` and `equals("/actuator/info")` — matching exactly what `SecurityConfig.permitAll()` allows.

**Idempotency race condition (HIGH)**
The check→create→store sequence in `OrderController` was not atomic. Two concurrent requests with the same idempotency key could both pass `check()` before either had called `store()`, resulting in two orders being created for one key. Fixed by adding a `ConcurrentHashMap<String, ReentrantLock>` to `IdempotencyService`, with a `withLock(key, action)` method that serialises the sequence per key. The lock was placed in the service layer (not the controller) to keep the controller focused on HTTP translation.

**Idempotency-Key input validation (HIGH)**
The `Idempotency-Key` header had no length cap or character restriction. An arbitrarily long key could be persisted as a primary key. Fixed with `@Size(max=255) @Pattern(regexp="[\\w\\-]+")` on the controller parameter.

**No HTTP security headers (MEDIUM)**
Added `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and `Cache-Control: no-store` to both `SecurityConfig` instances.

**`PatchOrderRequest` had no validation (MEDIUM)**
All fields were unconstrained. Added `@Size` bounds on all string fields and `@Valid @Size(max=50)` on the items list. Added `@Valid` to the PATCH endpoint's `@RequestBody`.

**`productOfferingId` accepted arbitrary strings (MEDIUM)**
Added `@Pattern(regexp="[\\w\\-]+")` alongside the existing `@NotBlank` to reject structurally invalid offering IDs before they reach the catalog HTTP layer.

**`Order.Builder` passed mutable list reference (MEDIUM — code review)**
`toBuilder()` passed the existing `orderItems` list reference directly to the `Builder` without copying. A caller holding the `Builder` reference could mutate the list before `build()`. Fixed by wrapping with `List.copyOf()` in `Builder.orderItems()`.

**Swagger UI defaulted to enabled (MEDIUM)**
Changed the default from `true` to `false`. Operators must explicitly opt in via `SWAGGER_ENABLED=true`.

**DB passwords hardcoded in docker-compose.yml (CRITICAL)**
Passwords were literal strings in `docker-compose.yml`. Changed to `${ORDER_DB_PASSWORD:-order_pass}` / `${CATALOG_DB_PASSWORD:-catalog_pass}` env vars. Both are documented in `.env.example`.

---

## Phase 7 — Java-Architect Review (TASK-31)

A second architectural review pass identified four additional production-readiness issues.

### Fix 1 — `CatalogHttpExceptionMapper`: static utility → injectable `@Component`

**Problem:** The mapper was a `final class` with a `static void execute(String, Runnable)` method. Two weaknesses: (1) `static` cannot be injected or mocked without PowerMock, making it harder to test and verify in isolation; (2) `Runnable` has no return type, so a future catalog endpoint that returns a value (e.g. `getOfferingDetails()`) would force adding an overload or a separate method.

**Fix:** Changed to `@Component class` with `<T> T call(String resourceId, Callable<T> call)`. The generic `Callable<T>` handles both void endpoints (return `null`) and value-returning endpoints with a single method. Injected into `CatalogRestAdapter` via constructor. The retry interaction is preserved: `exceptionMapper.call()` is called in `validateWithMdc()` which is **outside** the `@Retry` scope — `@Retry` still sees raw Spring exceptions and retries correctly before the mapper translates them to domain exceptions.

**Key architecture note:** Why not `RestClient.defaultStatusHandler()`? The HTTP client has no domain context — it cannot construct `ProductOfferingNotFoundException(offeringId)` because it only sees the URL, not the structured offering ID. Domain translation must happen where both the HTTP exception AND the resource identifier are available — i.e. in the adapter layer.

### Fix 2 — `OrderRepositoryAdapter.save()`: silent `orElseGet` → explicit `orElseThrow`

**Problem:** `jpaRepository.findById(order.getId()).orElseGet(OrderJpaEntity::new)` silently created a new blank entity if the ID was not found. Since `patchOrder` validates the order exists before calling `save()`, a missing entity at this point is a data integrity bug — a concurrent delete, a race condition, or a programming error. The silent fallback masked the bug and allowed corrupted state to be written.

**Fix:** Changed to `orElseThrow(() -> new IllegalStateException("Order " + id + " disappeared between read and update"))`. Bugs surface immediately with a clear message rather than producing silent data corruption.

### Fix 3 — `IdempotencyService.locks`: unbounded `ConcurrentHashMap` → Caffeine cache

**Problem:** `ConcurrentHashMap<String, ReentrantLock>` never evicts entries. Under sustained load with many unique idempotency keys, the map grows without bound — a memory leak. Each entry is a `ReentrantLock` object that is never cleaned up after the lock is released.

**Fix:** Replaced with a Caffeine `Cache<String, ReentrantLock>` (`expireAfterAccess(5m)`, `maximumSize(10_000)`). Caffeine evicts entries that have not been accessed for 5 minutes — safe because order creation completes in seconds. The `maximumSize` cap prevents unbounded growth under any load pattern. `locks.get(key, k -> new ReentrantLock())` replaces `computeIfAbsent` — same atomic semantics, automatic eviction.

### Fix 4 — `ProductOffering`: no invariant validation → compact constructor

**Problem:** `ProductOffering(String id, String name, BigDecimal price)` accepted null ID, null name, and null/negative price. This was inconsistent with the order-service domain where `OrderItem` and `PaymentMethod` records both validate invariants in their compact constructors. An anemic value object with no guards is not a proper domain model.

**Fix:** Added a compact constructor validating: `id`/`name` not blank, `price` not null, `price` not negative. This is the same pattern as `OrderItem` (quantity ≥ 1) and `PaymentMethod` (IBAN required for DIRECT_DEBIT) — consistent enforcement at the value-object boundary.

---

## Summary

| Phase | Tasks | Focus |
|---|---|---|
| Foundation | TASK-01 to TASK-03 | Scaffold, schema design, domain model, State Pattern |
| Core Order API | TASK-04 to TASK-09 | CRUD, state transitions, PATCH semantics, error handling, idempotency |
| Catalog Service | TASK-10 to TASK-13 | Catalog service, HTTP client, validation integration, circuit breaker |
| Cross-Cutting | TASK-14 to TASK-17 | Security filter, correlation ID, integration tests, documentation |
| Performance | TASK-18 to TASK-28, TASK-VT1 | N+1 fix, connection pooling, transaction scope, virtual threads, indexes, caching |
| Security Hardening | TASK-29 to TASK-30 | Filter registration, input validation, log injection, HTTP headers, race condition |
| Architect Review | TASK-31 | Exception mapper refactor, save() guard, locks memory fix, domain invariants |

**Total unit tests at completion: 135+ (order-service) + 13+ (catalog-service) — 0 failures.**
