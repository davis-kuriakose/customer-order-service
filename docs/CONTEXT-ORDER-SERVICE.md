# Order Service — Deep Context

> Supplement to `.claude/CONTEXT.md`. Read both when working on order-service.

---

## Module

- **Directory**: `order-service/`
- **Port**: 8080
- **Package root**: `com.dak.order`
- **Database**: `order_db` on port 5432
- **Spring Boot main class**: `com.dak.order.OrderServiceApplication`

---

## API Contract

```
POST   /customer-orders                              Create order
GET    /customer-orders/{id}                         Get order by ID
GET    /customer-orders?limit=20&offset=0&category=  List orders (paged)
PATCH  /customer-orders/{id}                         Partial update (merge-patch)
```

All endpoints require `X-API-Key` header.
PATCH requires `Content-Type: application/merge-patch+json`.
POST accepts optional `Idempotency-Key` header.

---

## Order Resource Shape

```json
{
  "id": "UUID",
  "state": "draft | preview | submitted | confirmed",
  "category": "B2B | B2C",
  "customer": { "id": "string" },
  "site": { "id": "string" },
  "orderItems": [{ "productOfferingId": "string", "quantity": 1 }],
  "paymentMethod": { "type": "DIRECT_DEBIT | INVOICE", "iban": "string or omitted" },
  "createdAt": "ISO 8601",
  "updatedAt": "ISO 8601"
}
```

---

## State Machine

```
DRAFT ──────────▶ PREVIEW
  ▲                  │
  └──────────────────┘  (revert)
                    │
                    ▼
               SUBMITTED ──▶ CONFIRMED
```

| State | Payload editable | State patchable | Notes |
|---|---|---|---|
| DRAFT | Yes | → PREVIEW only | Full edit allowed |
| PREVIEW | Yes | → DRAFT or SUBMITTED | Full edit allowed |
| SUBMITTED | No | → CONFIRMED only | Any non-state patch field → 422 |
| CONFIRMED | No | No | All patches → 422 |

---

## Package Structure

```
com.dak.order/
├── domain/
│   ├── model/           Order (aggregate), OrderItem, Customer, Site,
│   │                    PaymentMethod, OrderCategory, PaymentMethodType
│   ├── state/           OrderState (interface), DraftState, PreviewState,
│   │                    SubmittedState, ConfirmedState, OrderStateFactory
│   ├── command/         CreateOrderCommand, PatchOrderCommand
│   ├── port/
│   │   ├── inbound/     OrderUseCase
│   │   └── outbound/    OrderRepositoryPort, IdempotencyRepositoryPort, CatalogPort
│   └── exception/       OrderNotFoundException, InvalidStateTransitionException,
│                        OrderMutationForbiddenException, ProductOfferingNotFoundException,
│                        CatalogUnavailableException, IdempotencyConflictException
├── application/
│   └── service/         OrderService (implements OrderUseCase), IdempotencyService
├── infrastructure/
│   ├── persistence/
│   │   ├── entity/      OrderJpaEntity (@Entity, @Version), OrderItemJpaEntity,
│   │   │                IdempotencyKeyJpaEntity
│   │   ├── repository/  OrderJpaRepository (Spring Data), IdempotencyKeyJpaRepository,
│   │   │                OrderRepositoryAdapter, IdempotencyRepositoryAdapter
│   │   └── mapper/      OrderPersistenceMapper (MapStruct)
│   └── catalog/
│       ├── dto/         ProductOfferingDto
│       ├── CatalogRestAdapter (implements CatalogPort, @CircuitBreaker @TimeLimiter)
│       └── CatalogClientConfig (RestClient bean)
├── web/
│   ├── controller/      OrderController
│   ├── dto/             CreateOrderRequest, PatchOrderRequest (all nullable),
│   │                    OrderResponse, PagedResponse
│   ├── mapper/          OrderWebMapper (MapStruct)
│   └── exception/       GlobalExceptionHandler (extends ResponseEntityExceptionHandler)
├── security/            ApiKeyAuthFilter, SecurityConfig
└── common/              CorrelationIdFilter
```

---

## Domain Model Key Points

### Order Aggregate
```java
@Value @Builder(toBuilder = true)
public class Order {
    UUID id;
    OrderState state;           // interface — reconstructed via OrderStateFactory
    OrderCategory category;
    Customer customer;          // Java record
    Site site;                  // Java record
    List<OrderItem> orderItems; // unmodifiable
    PaymentMethod paymentMethod;
    Instant createdAt;
    Instant updatedAt;

    // Domain behavior — returns NEW Order (immutable)
    public Order transitionTo(String targetStateName) { ... }
    public Order applyPatch(PatchOrderCommand patch) { ... }

    // Static factory — validates required fields
    public static Order create(CreateOrderCommand cmd) { ... }
}
```

### OrderState Interface
```java
public interface OrderState {
    String name();                              // persisted as VARCHAR
    OrderState transitionTo(String target);     // throws InvalidStateTransitionException
    boolean isPayloadEditable();
    boolean isLocked();
    void validatePatch(PatchOrderCommand patch); // state-specific patch rules
}
```

### Value Objects (Java records — immutable, compact constructors validate)
```
OrderItem(String productOfferingId, int quantity)  — quantity >= 1
Customer(String id)
Site(String id)
PaymentMethod(PaymentMethodType type, String iban) — iban required if DIRECT_DEBIT
```

---

## Idempotency Flow

```
POST /customer-orders + Idempotency-Key: <key>

1. Look up key in idempotency_keys table
   ├── Not found → create order, store (key, SHA-256(body), response, orderId)
   │               return 201 Created
   │
   ├── Found, SHA-256 MATCH → return stored response body
   │                          add X-Idempotent-Replayed: true header
   │                          return same status code as original
   │
   └── Found, SHA-256 MISMATCH → throw IdempotencyConflictException → 409
```

Race condition: concurrent posts with same key → both try INSERT → second gets `DataIntegrityViolationException` on PK → re-read and apply match/conflict logic.

---

## Flyway Migrations

```
V1__create_orders_table.sql         orders (id, state, category, customer_id, site_id,
                                           pm_type, pm_iban, created_at, updated_at, version)
V2__create_order_items_table.sql    order_items (id, order_id FK, product_offering_id, quantity)
V3__create_idempotency_keys_table.sql idempotency_keys (idempotency_key PK, request_hash,
                                                         order_id FK, response_status,
                                                         response_body, created_at)
```

---

## Resilience4j (Catalog)

Configured in `application.yml` under `resilience4j:`.
- Circuit breaker: 10-request sliding window, 50% failure rate threshold, 30s open wait, 3 half-open calls
- TimeLimiter: 2s timeout
- On open circuit or timeout → `CatalogUnavailableException` → `GlobalExceptionHandler` → 503

---

## Exception → HTTP Status Mapping

| Exception | Status | Notes |
|---|---|---|
| `OrderNotFoundException` | 404 | |
| `InvalidStateTransitionException` | 422 | |
| `OrderMutationForbiddenException` | 422 | patch on submitted/confirmed |
| `ProductOfferingNotFoundException` | 422 | unknown offering in items |
| `CatalogUnavailableException` | 503 | circuit open or timeout |
| `IdempotencyConflictException` | 409 | same key, different payload |
| Optimistic lock (`ObjectOptimisticLockingFailureException`) | 409 | concurrent patch |
| `MethodArgumentNotValidException` | 400 | bean validation failure |
