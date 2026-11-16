````
# MASTER PROMPT: Design and Develop a Production-Grade High-Concurrency Distributed Inventory Reservation System for Flash Sales Using Java 21 and Spring Boot

## 1. ROLE AND ENGINEERING RESPONSIBILITIES

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, Enterprise Solution Architect, Java 21 Concurrency Expert, Spring Boot Architect, Database Performance Engineer, E-commerce Architect, Cloud-Native Engineer, and Site Reliability Engineer** with 15+ years of experience building high-throughput, fault-tolerant transaction-processing platforms.

Your task is to **design, develop, test, benchmark, secure, document, and deploy an end-to-end High-Concurrency Distributed Inventory Reservation System for Flash Sales** using Java 21 and Spring Boot.

The system should demonstrate enterprise engineering practices comparable to the infrastructure required for Amazon, Walmart, Flipkart, and other high-volume commerce platforms.

Treat this as a real production-oriented engineering project, not merely a conceptual system design exercise.

### Your responsibilities

1. Requirements analysis
2. High-Level Design (HLD)
3. Low-Level Design (LLD)
4. Inventory domain modeling
5. Authoritative stock management
6. High-concurrency purchase admission
7. Atomic stock reservation
8. Inventory reservation lifecycle
9. Redis Lua scripting
10. PostgreSQL concurrency control
11. Stock preallocation and token-based reservation
12. Flash-sale queueing
13. Hot-SKU contention management
14. Distributed idempotency
15. Reservation expiration and recovery
16. Try-Confirm/Cancel (TCC)
17. Saga-based checkout coordination
18. Kafka event-driven processing
19. Transactional Outbox/Inbox
20. Consistency and reconciliation
21. Distributed failure recovery
22. Security and bot mitigation
23. Observability and operational controls
24. Capacity planning
25. Automated concurrency and failure testing
26. Performance benchmarking
27. Docker/Kubernetes deployment
28. Functional flash-sale demonstration
29. Complete source code and documentation

**Do not stop after HLD, LLD, architecture diagrams, pseudocode, or illustrative examples. Deliver a runnable, maintainable, tested implementation, including source code, infrastructure, load-testing scripts, and operational documentation.**

### Mandatory engineering principles

- Use Java 21 and a verified compatible Spring Boot version.
- Follow existing repository instructions, dependency versions, architecture, and coding conventions.
- Apply DDD, SOLID, Clean Architecture, and Hexagonal Architecture pragmatically.
- Prefer clear aggregate boundaries and focused modules over unnecessary microservice decomposition.
- Define the authoritative source of inventory truth.
- Never rely solely on frontend availability displays or cached stock counts.
- Never assume Redis distributed locks automatically prevent overselling.
- Enforce inventory invariants through atomic operations and durable transaction boundaries.
- Use bounded retries, queues, timeouts, connection pools, and worker concurrency.
- Handle network partitions, Redis failover, PostgreSQL failures, process crashes, and ambiguous outcomes.
- Make reservation creation, confirmation, cancellation, and expiration idempotent.
- Do not promise globally strict inventory correctness from eventually consistent counters.
- Clearly distinguish accepted purchase attempts, successful reservations, confirmed allocations, and completed orders.
- Do not use Kafka as the authoritative inventory database.
- Never fabricate test results, throughput measurements, or production-readiness claims.
- Explain significant architectural decisions, failure modes, and trade-offs.

---

# PART A — PRIMARY OBJECTIVE

## 2. BUILD A DISTRIBUTED FLASH-SALE INVENTORY SYSTEM

Build a horizontally scalable platform capable of managing inventory during extreme purchase traffic.

The system must support:

- Product/SKU inventory
- Warehouse-level stock
- Flash-sale creation
- Flash-sale schedules
- Flash-sale stock allocation
- High-volume purchase attempts
- Admission control
- Per-user purchase limits
- Atomic inventory reservations
- Reservation expiration
- Reservation confirmation
- Reservation cancellation
- Inventory release
- Stock replenishment
- Stock reconciliation
- Duplicate request prevention
- Retry handling
- Event-driven checkout integration
- Real-time inventory monitoring
- Administrative operations

### Example flash-sale scenario

An e-commerce platform launches a smartphone flash sale.

Available stock:

**10,000 units**

Incoming traffic:

**1,000,000 purchase attempts in a short burst**

The system must:

1. Authenticate and identify customers.
2. Validate flash-sale eligibility.
3. Enforce per-customer purchase limits.
4. Apply admission control and request throttling.
5. Resolve the authoritative sale and SKU configuration.
6. Reject requests when the sale is inactive.
7. Check flash-sale inventory allocation.
8. Perform an atomic reservation decision.
9. Prevent exceeding the allocated inventory.
10. Generate a unique reservation ID.
11. Persist or durably coordinate the reservation.
12. Return a reservation result.
13. Allow the customer to complete checkout.
14. Confirm the reservation after successful checkout.
15. Release inventory after cancellation or expiration.
16. Recover from crashes, delayed events, and duplicate requests.
17. Reconcile Redis allocation state with PostgreSQL.
18. Track sale progress and operational metrics.

### Fundamental correctness invariant

For every authoritative stock pool:

`Confirmed Allocations + Active Reservations <= Sellable Stock`

Define stock adjustments and already-committed deductions consistently so inventory is not double-counted.

The system must prove this invariant under the supported concurrency, failover, and reservation-expiration model.

If the architecture uses approximate inventory allocation or Redis preallocation, explicitly document its durability and overselling guarantees.

---

# PART B — FUNCTIONAL REQUIREMENTS

## 3. PRODUCT AND INVENTORY MANAGEMENT

Implement:

- Product registration
- SKU registration
- Warehouse inventory
- Stock adjustments
- Available-to-sell calculation
- Stock reservation
- Stock confirmation
- Stock release
- Inventory movement history
- Inventory audit
- Low-stock alerts

Distinguish:

- Physical stock
- Sellable stock
- Reserved stock
- Committed allocation
- Flash-sale allocated stock
- Available flash-sale stock

Do not use ambiguous stock counters with conflicting meanings.

---

## 4. FLASH-SALE MANAGEMENT

Support:

- Flash-sale creation
- Flash-sale activation
- Scheduled start/end
- Sale-specific SKU allocation
- Per-customer limits
- Maximum purchase quantity
- Reservation TTL
- Admission policies
- Sale suspension
- Sale cancellation
- Sale completion

Define sale states:

- DRAFT
- SCHEDULED
- ACTIVE
- PAUSED
- COMPLETED
- CANCELLED

Implement valid state transitions.

### Important race

When a sale is paused or terminated, in-flight reservations must follow an explicit policy.

Do not assume changing a boolean flag immediately cancels every request already admitted.

---

# PART C — NON-FUNCTIONAL REQUIREMENTS

## 5. SCALE AND PERFORMANCE TARGETS

Treat these as design targets requiring validation.

| Dimension | Target |
|---|---|
| Peak incoming purchase attempts | 1 million RPS scale scenario |
| Initial admission throughput | 100,000 RPS target |
| Reservation commit throughput | Workload-derived and benchmarked |
| Concurrent customers | 1 million+ |
| Flash-sale SKUs | 10,000+ |
| Hot SKU demand | Hundreds of thousands of attempts per SKU |
| Inventory correctness | No overselling under documented guarantees |
| Reservation decision latency | p99 below 50 ms within a region, under defined conditions |
| Availability | 99.99% design target |
| Reservation durability | Explicitly defined |
| Idempotency | Durable logical-operation deduplication |
| Deployment | Multi-AZ Kubernetes |
| Multi-region | Architectural extension |
| Observability | Metrics, logs, distributed traces |

Differentiate:

- Incoming RPS
- Admitted RPS
- Reservation attempts/sec
- Successful reservations/sec
- Kafka messages/sec
- Checkout completions/sec
- Confirmed orders/sec

Do not treat all incoming purchase attempts as database transactions.

---

# PART D — ARCHITECTURE OPTIONS

## 6. COMPARE INVENTORY RESERVATION APPROACHES

Evaluate the following.

### Option A — PostgreSQL-Only Reservations

Use:

- Conditional SQL updates
- Row-level locking
- Short ACID transactions
- Unique reservation constraints

Example conceptual SQL:

```sql
UPDATE inventory
SET reserved_quantity = reserved_quantity + :quantity
WHERE sku_id = :skuId
  AND sellable_quantity - reserved_quantity >= :quantity;
```

Treat this as an illustrative model; finalize schema, stock deductions, and concurrency rules before implementation.

Advantages:

- Strong transactional guarantees
- Simpler recovery
- Clear authoritative state

Limitations:

- Hot-row contention
- Database write saturation
- Connection pool exhaustion under uncontrolled traffic

### Option B — Redis-First Reservation

Use:

- Redis atomic Lua scripts
- Stock counters
- Reservation keys
- TTL
- Asynchronous database persistence

Advantages:

- Low-latency atomic coordination on a Redis primary
- High in-memory operation throughput

Risks:

- Redis failover and acknowledged-write loss
- Redis/PostgreSQL divergence
- Reservation loss
- Ambiguous responses
- Recovery complexity

### Option C — Redis Admission + PostgreSQL Authoritative Reservation

Use Redis to reduce admitted contention while PostgreSQL makes the authoritative reservation decision.

### Option D — Preallocated Inventory Tokens

Allocate bounded stock portions to independent partitions or workers.

Evaluate:

- Token ownership
- Allocation durability
- Rebalancing
- Unused-token reclamation
- Crash recovery
- Fencing
- Overselling bounds

### Option E — Queue-Based Serialized Reservations

Partition commands by SKU or inventory ownership.

Use one logical authoritative consumer per partition with durable state.

Evaluate:

- Serialization
- Throughput
- Queue lag
- Rebalance
- Retry behavior
- Hot partitions

### Required decision

Compare all approaches on:

- Correctness
- Latency
- Throughput
- Hot-key contention
- Durability
- Availability
- Consistency
- Operational complexity
- Recovery
- Cost

**Recommend a PostgreSQL-authoritative baseline with bounded Redis admission, then implement Redis preallocation or partitioned serialized processing only when justified by measured contention.**

---

# PART E — HIGH-LEVEL DESIGN

## 7. REFERENCE ARCHITECTURE

Consider:

1. API Gateway
2. Authentication Service
3. Flash Sale Management
4. Eligibility Validator
5. Admission Controller
6. Distributed Rate Limiter
7. Inventory Reservation Engine
8. Redis
9. PostgreSQL
10. Reservation Lifecycle Manager
11. Kafka
12. Transactional Outbox
13. Transactional Inbox
14. Reservation Expiration Worker
15. Inventory Reconciliation Worker
16. Checkout/Order Integration
17. Monitoring and Audit

Provide Mermaid diagrams for:

- Overall architecture
- Flash-sale admission
- Atomic reservation
- Hot-SKU handling
- Redis Lua execution
- PostgreSQL reservation
- Reservation confirmation
- Reservation cancellation
- Expiration
- Kafka processing
- Redis failure
- PostgreSQL failure
- Inventory reconciliation

Separate the high-throughput admission path from authoritative inventory mutations.

---

# PART F — LOW-LEVEL DESIGN

## 8. DOMAIN MODEL

Design concepts such as:

- Product
- SKU
- Warehouse
- InventoryStock
- InventoryAllocation
- FlashSale
- FlashSaleItem
- PurchaseAttempt
- Reservation
- ReservationItem
- ReservationStatus
- InventoryMovement
- InventoryToken
- ReservationPolicy
- IdempotencyRecord
- OutboxEvent
- InboxRecord
- ReconciliationTask

Use Java 21 records, enums, and sealed interfaces where appropriate.

Define:

- Aggregate boundaries
- Domain invariants
- Application services
- Repository contracts
- Transaction boundaries
- State machines
- Concurrency guarantees

Avoid excessive abstractions.

---

# PART G — POSTGRESQL INVENTORY CORRECTNESS

## 9. DATABASE MODEL

Use PostgreSQL as the authoritative store for the baseline.

Design tables such as:

- products
- skus
- warehouses
- inventory
- inventory_movements
- flash_sales
- flash_sale_items
- inventory_allocations
- inventory_reservations
- reservation_items
- idempotency_records
- outbox_events
- processed_events
- reconciliation_tasks

Provide:

- Primary keys
- Foreign keys
- Unique constraints
- Check constraints
- Indexes
- Optimistic version columns
- Audit timestamps
- Expiration indexes
- Retention policies

Use Flyway.

### Critical database invariants

- Stock quantities cannot become invalid.
- Reservation creation is atomic.
- Flash-sale allocation cannot exceed available eligible stock.
- Same logical request cannot reserve inventory twice.
- Confirmed allocation cannot be released as an ordinary active hold.
- Expiration cannot release a newer reservation.
- Concurrent stock adjustment and reservation operations preserve accounting invariants.

---

## 10. DATABASE CONCURRENCY STRATEGIES

Compare:

1. Optimistic locking (`@Version`)
2. Pessimistic row locking
3. Conditional SQL updates
4. Serializable isolation
5. SKU-partitioned command processing
6. Inventory counter sharding

Evaluate:

- Lock contention
- Transaction retries
- Deadlocks
- Throughput
- Correctness
- Operational complexity

For the baseline, prefer short, conditional PostgreSQL updates.

Use appropriate transaction isolation.

Implement tests with actual PostgreSQL transactions rather than mocked repositories.

---

# PART H — REDIS ATOMIC RESERVATION

## 11. REDIS DATA MODEL

Design Redis keys where justified.

Examples:

- `flashsale:{saleId}:stock:{skuId}`
- `flashsale:{saleId}:reservation:{reservationId}`
- `flashsale:{saleId}:customer:{customerId}`
- `flashsale:{saleId}:admission`

Define:

- Key ownership
- TTL
- Serialization
- Expiration semantics
- Memory limits
- Atomicity scope
- Redis Cluster hash slots

Do not assume Lua scripts can atomically modify arbitrary keys across Redis Cluster slots.

---

## 12. LUA-BASED ATOMIC OPERATIONS

Implement an atomic Lua script for the selected Redis-side reservation or admission operation.

Evaluate checks such as:

1. Sale active
2. Stock/token availability
3. Customer limit
4. Idempotency
5. Reservation identity
6. Quota decrement
7. Reservation state creation

### Mandatory properties

- No negative stock counter
- No repeated decrement for same logical operation
- Bounded execution time
- No large key scans
- Atomic state changes within the supported Redis scope
- Explicit failure codes
- TTL/cleanup strategy

### Critical limitation

Redis Lua atomicity on one primary does not automatically guarantee durability across asynchronous replication and failover.

Explain how this affects inventory correctness.

Do not claim Redis-only reservation is strongly durable without a compatible persistence and failover protocol.

---

# PART I — RESERVATION STATE MACHINE

## 13. RESERVATION LIFECYCLE

Define:

- REQUESTED
- RESERVED
- CONFIRMING
- CONFIRMED
- CANCEL_REQUESTED
- CANCELLED
- EXPIRED
- FAILED

Choose a minimal coherent state machine.

Define legal transitions.

### Fundamental rules

- CONFIRMED cannot become EXPIRED.
- CANCELLED cannot become CONFIRMED.
- EXPIRED cannot be confirmed unless an explicit safe recovery protocol permits it.
- Repeated confirm/cancel calls are idempotent.
- Stale workers cannot alter newer reservation versions.
- Every terminal transition preserves stock accounting.

Use atomic conditional updates.

---

# PART J — RESERVATION EXPIRATION

## 14. TTL AND EXPIRATION

Support:

- Reservation expiry timestamp
- Background expiry scans
- Expiration event processing
- Safe stock reclamation
- Retry
- Recovery after worker crash

Do not rely solely on Redis keyspace notifications.

Use authoritative reservation state and ownership tokens.

### Critical scenario

1. Reservation A holds one unit.
2. Reservation A expires.
3. Inventory is released.
4. Reservation B acquires the unit.
5. A delayed expiration worker processes Reservation A again.

The delayed worker must not release Reservation B's inventory.

Implement conditional release and version-aware state transitions.

---

# PART K — DISTRIBUTED IDEMPOTENCY

## 15. IDEMPOTENCY MODEL

Use:

`Idempotency-Key: <unique-key>`

Scope the key by authenticated customer/tenant, sale, operation, and applicable business identity.

Persist:

- Idempotency key
- Customer ID
- Sale ID
- SKU/quantity fingerprint
- Reservation ID
- Operation state
- Response reference
- Created timestamp
- Retention

### Required behavior

| Scenario | Expected |
|---|---|
| First request | Attempt one logical reservation |
| Same key + same payload | Return existing result |
| Same key + different payload | Conflict |
| Concurrent duplicate requests | One logical reservation |
| Lost response | Recover original result |
| Already confirmed | Return current confirmed state |
| Expired reservation | Return documented terminal result |
| Client retry across pods | No duplicate reservation |

Use database uniqueness as the durable authority where applicable.

Do not rely solely on Redis SETNX.

---

# PART L — TRY-CONFIRM/CANCEL (TCC)

## 16. TCC INVENTORY PROTOCOL

Implement:

### TRY

Provisionally reserve eligible stock.

### CONFIRM

Convert reservation into committed allocation.

### CANCEL

Release reserved inventory.

Provide APIs:

- `POST /api/v1/reservations/try`
- `POST /api/v1/reservations/{id}/confirm`
- `POST /api/v1/reservations/{id}/cancel`

### Required failure cases

- Duplicate Try
- Duplicate Confirm
- Duplicate Cancel
- Cancel before Try completion
- Confirm/Cancel race
- Expired Try
- Stale request
- Worker crash
- Network timeout

Persist terminal decisions and protect against resurrection of cancelled operations.

Do not claim TCC itself makes every external side effect atomic.

---

# PART M — SAGA AND CHECKOUT INTEGRATION

## 17. DISTRIBUTED CHECKOUT WORKFLOW

Design an integration with an Order Service.

Example:

1. Create pending order.
2. Try inventory reservation.
3. Initiate payment authorization.
4. Confirm inventory.
5. Confirm order.
6. Capture payment according to provider contract.
7. Trigger fulfillment.

Define compensations:

- Cancel reservation
- Cancel order
- Void authorization
- Refund captured payment where necessary

Use durable Saga state.

Do not assume external payment authorization and inventory reservation are one ACID transaction.

Keep the inventory engine independently testable without requiring a full e-commerce platform.

---

# PART N — KAFKA AND EVENT PROCESSING

## 18. EVENT-DRIVEN ARCHITECTURE

Define events such as:

- FlashSaleStarted
- ReservationRequested
- InventoryReserved
- ReservationRejected
- ReservationConfirmed
- ReservationCancelled
- ReservationExpired
- InventoryAdjusted
- InventoryReconciled

Use:

- Event ID
- Aggregate ID
- Aggregate version
- Schema version
- Correlation ID
- Timestamp

Design Kafka topic partitioning.

Consider SKU/stock-pool ID as a partition key for ordering-sensitive events.

Explain hot partition limitations.

Do not assume adding Kafka partitions automatically removes contention on one authoritative stock pool.

---

## 19. TRANSACTIONAL OUTBOX AND INBOX

Implement:

- Atomic inventory state + outbox persistence
- Reliable outbox publishing
- Idempotent consumers
- Durable inbox deduplication
- Retry
- Dead-letter handling
- Replay
- Lag monitoring

### Critical rule

Kafka's at-least-once delivery must not cause duplicate reservation, confirmation, or stock release.

Use state transitions and unique operation IDs, not consumer offset commits alone.

---

# PART O — HOT-SKU AND FLASH-SALE SCALABILITY

## 20. HOT-KEY PROBLEM

Consider:

One SKU has 10,000 units.

One million customers attempt to reserve it.

Analyze:

- PostgreSQL hot-row contention
- Redis hot key
- Lock contention
- Kafka hot partition
- Connection pool saturation
- Retry storms
- Queue lag

Compare:

1. Admission throttling
2. Stock token pools
3. Bucketed stock allocations
4. Inventory partitioning
5. Serialized SKU processing
6. Virtual waiting rooms
7. Async reservation queues

Do not partition inventory without addressing global stock accounting and token ownership.

---

## 21. STOCK TOKEN PREALLOCATION

Evaluate preallocating inventory into bounded token pools.

Example:

Total available stock: 10,000

Allocate safe nonoverlapping token ranges to processing partitions.

Implement or design:

- Atomic allocation
- Token ownership
- Durable allocation
- Consumption
- Return of unused tokens
- Expiration
- Crash recovery
- Rebalancing
- Fencing

### Critical requirement

The sum of all outstanding allocated stock tokens and committed units must never exceed the authoritative sellable quantity.

If using Redis for these allocations, document the durability/consistency model and reconcile failover uncertainty.

---

# PART P — ADMISSION CONTROL AND BOT PROTECTION

## 22. REQUEST ADMISSION

Implement:

- API rate limiting
- Per-customer concurrency limits
- Per-SKU admission limits
- Per-tenant quotas where applicable
- Request deduplication
- Waiting-room tokens
- Load shedding
- Backpressure

Use Redis for appropriate high-throughput admission controls.

### Important distinction

Admission grants permission to attempt a reservation.

It does not guarantee stock ownership.

Do not return a confirmed reservation before authoritative allocation succeeds.

---

## 23. BOT AND ABUSE PREVENTION

Implement or document:

- Authentication
- Verified customer eligibility
- Rate limiting
- Device/session controls
- Replay protection
- Purchase quantity limits
- Signed admission tokens
- Administrative abuse monitoring

Do not rely exclusively on IP-based limits.

Do not use a CAPTCHA provider as the only correctness or fairness mechanism.

---

# PART Q — FAILURE RECOVERY AND RECONCILIATION

## 24. INVENTORY RECONCILIATION ENGINE

Implement a reconciliation process comparing:

- Authoritative inventory
- Reservations
- Confirmed allocations
- Redis admission/token state
- Expired reservations
- Outstanding checkout workflows
- Inventory movement history

Detect:

- Missing reservation persistence
- Duplicate consumption
- Orphaned reservation
- Reservation expired but not released
- Redis/PostgreSQL divergence
- Unknown confirmation state
- Incorrect stock counters

### Reconciliation requirements

- Durable work records
- Idempotent repairs
- Bounded batch processing
- Version checks
- Audit records
- Manual-review support

Never silently reset counters without checking authoritative committed allocations.

---

## 25. REDIS FAILURE SCENARIOS

Analyze:

- Redis primary crash
- Replica promotion
- Replication lag
- Lost write
- Network timeout after Lua execution
- Redis Cluster resharding
- Hot-key overload
- Redis memory eviction
- Connection exhaustion

Define safe fallback behavior.

For strict inventory correctness, fail closed or fall back to an authoritative database path where feasible.

Do not convert Redis uncertainty into a confirmed reservation.

---

# PART R — REST API DESIGN

## 26. REQUIRED REST APIS

### Flash-sale administration

- `POST /api/v1/flash-sales`
- `GET /api/v1/flash-sales`
- `GET /api/v1/flash-sales/{saleId}`
- `POST /api/v1/flash-sales/{saleId}/activate`
- `POST /api/v1/flash-sales/{saleId}/pause`
- `POST /api/v1/flash-sales/{saleId}/complete`

### Inventory

- `GET /api/v1/inventory/{skuId}`
- `POST /api/v1/inventory/{skuId}/adjustments`
- `GET /api/v1/flash-sales/{saleId}/availability`

### Reservation

- `POST /api/v1/reservations/try`
- `GET /api/v1/reservations/{reservationId}`
- `POST /api/v1/reservations/{reservationId}/confirm`
- `POST /api/v1/reservations/{reservationId}/cancel`

### Customer reservations

- `GET /api/v1/customers/me/reservations`

### Administration

- `GET /api/v1/admin/inventory/reconciliation`
- `POST /api/v1/admin/inventory/reconciliation/run`
- `GET /api/v1/admin/flash-sales/{saleId}/metrics`

For every API provide:

- Authentication
- Authorization
- Request/response JSON
- Validation
- Idempotency
- Status codes
- Error contracts
- Rate limits
- Pagination
- OpenAPI specification

Use RFC 9457 Problem Details where appropriate.

---

# PART S — JAVA 21 + SPRING BOOT STACK

## 27. RECOMMENDED TECHNOLOGY STACK

| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot, verified compatible release |
| Build | Maven |
| REST API | Spring Web MVC |
| Security | Spring Security/OAuth2 Resource Server |
| Authoritative DB | PostgreSQL |
| Persistence | Spring Data JDBC/JPA or jOOQ, justified |
| Migrations | Flyway |
| High-speed admission | Redis |
| Atomic Redis operations | Lua scripting |
| Messaging | Apache Kafka |
| Transactional messaging | Outbox/Inbox |
| Resilience | Resilience4j |
| Observability | Micrometer/OpenTelemetry |
| Unit tests | JUnit 5/Mockito |
| Integration tests | Testcontainers |
| Performance | k6/Gatling |
| Containers | Docker |
| Deployment | Kubernetes |
| Monitoring | Prometheus/Grafana |

Verify exact compatible versions from official documentation.

Evaluate Java 21 virtual threads for blocking I/O where appropriate.

Do not assume virtual threads increase throughput of a contended PostgreSQL inventory row.

---

# PART T — PROJECT STRUCTURE

## 28. SOURCE CODE ORGANIZATION

Create a maintainable Maven project.

```text
flash-sale-inventory/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── inventory-invariants.md
│   ├── reservation-lifecycle.md
│   ├── concurrency-control.md
│   ├── redis-atomicity.md
│   ├── tcc-protocol.md
│   ├── saga-integration.md
│   ├── hot-sku-scaling.md
│   ├── reconciliation.md
│   ├── capacity-planning.md
│   ├── security.md
│   ├── runbook.md
│   └── decisions/
├── src/
│   ├── main/
│   │   ├── java/com/example/flashsale/
│   │   │   ├── api/
│   │   │   ├── domain/
│   │   │   ├── application/
│   │   │   ├── inventory/
│   │   │   ├── reservation/
│   │   │   ├── flashsale/
│   │   │   ├── admission/
│   │   │   ├── idempotency/
│   │   │   ├── reconciliation/
│   │   │   ├── messaging/
│   │   │   ├── infrastructure/
│   │   │   │   ├── persistence/
│   │   │   │   └── redis/
│   │   │   ├── security/
│   │   │   └── configuration/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── db/migration/
│   │       └── redis/
│   └── test/
│       └── java/
├── flash-sale-demo/
├── integration-tests/
├── benchmarks/
├── docker/
├── helm/
└── .github/workflows/
```

Adapt package boundaries based on cohesion.

Do not introduce independent microservices before justified scaling or ownership requirements.

---

# PART U — REQUIRED PRODUCTION FAILURE SCENARIOS

## 29. ANALYZE THESE 40 SCENARIOS

1. Two customers reserve the final stock unit.
2. One thousand customers reserve the same SKU.
3. One million requests arrive during sale opening.
4. Same customer submits duplicate requests.
5. Same idempotency key is reused with different quantities.
6. Reservation commits but API response is lost.
7. Client retries against another Spring Boot pod.
8. Redis Lua succeeds but client times out.
9. Redis primary crashes after decrement.
10. Redis replica is promoted with stale data.
11. PostgreSQL becomes unavailable.
12. Kafka becomes unavailable.
13. Kafka delivers ReservationRequested twice.
14. Events arrive out of order.
15. Reservation expires while payment is pending.
16. Confirm and Cancel execute simultaneously.
17. Expiration worker releases stock twice.
18. Old expiration worker processes a newer reservation.
19. Confirmation succeeds but response is lost.
20. Inventory reservation persists but event publication fails.
21. Outbox publishes the same event twice.
22. Reconciliation worker crashes.
23. Redis and PostgreSQL stock counts differ.
24. Two inventory partitions consume overlapping tokens.
25. Token allocation owner crashes.
26. Stock is replenished during active reservations.
27. Stock is reduced while holds exist.
28. Sale is paused during active requests.
29. Sale ends while reservations remain active.
30. Customer exceeds per-user purchase limit concurrently.
31. Redis hot key becomes a bottleneck.
32. Kafka partition becomes hot.
33. PostgreSQL connection pool is exhausted.
34. Retry storm occurs after recovery.
35. Kubernetes pod terminates during reservation.
36. Database transaction deadlocks.
37. A network partition isolates a worker.
38. Cross-region requests compete for one stock pool.
39. Mixed application versions use incompatible reservation schemas.
40. A completed order exists without a matching authoritative inventory allocation.

For each provide:

- Root cause
- Overselling risk
- Data integrity risk
- Customer impact
- Detection
- Prevention
- Recovery
- Automated test
- Residual risk

---

# PART V — AUTOMATED TESTING

## 30. UNIT TESTS

Cover:

- Stock calculations
- Sale eligibility
- Reservation state transitions
- Quantity validation
- Purchase limits
- Idempotency fingerprinting
- Reservation expiration
- TCC Try/Confirm/Cancel
- Cancellation
- Reconciliation decisions
- Retry classification

---

## 31. POSTGRESQL INTEGRATION TESTS

Use Testcontainers.

Verify:

- Atomic conditional updates
- Unique constraints
- Reservation creation
- Confirmation
- Cancellation
- Expiration
- Stock adjustments
- Concurrent last-unit reservations
- Transaction rollback
- Deadlock handling
- Inventory accounting

Do not substitute H2 or mocked repositories for PostgreSQL concurrency correctness.

---

## 32. REDIS INTEGRATION TESTS

Verify:

- Lua atomicity
- Concurrent token consumption
- Idempotent repeated requests
- Burst admission
- TTL behavior
- Key naming
- Cluster slot constraints where supported

Simulate Redis failover where the available test infrastructure permits.

Do not claim failover consistency from single-node Redis tests.

---

## 33. CONCURRENCY TESTS

Run controlled workloads such as:

- 100 requests for 1 unit
- 1,000 requests for 100 units
- 10,000 requests for 1,000 units
- 100,000 attempts for 10,000 units where resources permit

Verify:

- Successful reservations never exceed available stock.
- No duplicate logical reservation.
- No negative authoritative inventory.
- Confirm/Cancel races preserve accounting.
- Idempotent retries return consistent outcomes.
- Stock is correctly reclaimed after expiry.
- Multi-instance behavior is correct.

Use deterministic assertions based on persisted inventory state, not only HTTP success counts.

---

## 34. FAILURE AND CHAOS TESTS

Simulate:

- Redis restart
- PostgreSQL timeout
- Kafka outage
- Worker crash
- Lost response
- Duplicate message
- Out-of-order message
- Reservation expiry
- Retry storm
- Connection pool exhaustion
- Pod termination
- Network partition where practical

Document actual execution results.

---

# PART W — PERFORMANCE ENGINEERING

## 35. CAPACITY PLANNING

Calculate:

1. Incoming purchase attempts/sec
2. Admission rejection rate
3. Successful reservations/sec
4. PostgreSQL write load
5. Redis commands/sec
6. Kafka events/sec
7. Reservation table growth
8. Reservation TTL expiration workload
9. Hot-SKU contention
10. Worker concurrency
11. Network bandwidth
12. Database connection pool requirements
13. Redis memory
14. Reconciliation workload
15. Infrastructure cost

Use explicit assumptions.

Example:

`Reservation success ratio = successful reservations / total reservation attempts`

`Required reservation processing capacity = admitted attempts/sec`

`Expiration processing demand ≈ expiring active reservations/sec`

Do not infer database capacity from incoming API throughput.

---

## 36. LOAD TESTING

Create reproducible k6/Gatling tests for:

- 1,000 requests/sec
- 10,000 requests/sec
- 100,000 requests/sec
- 500,000 requests/sec
- 1 million requests/sec as a scale target

Benchmark separately:

- Redis admission
- PostgreSQL reservation
- Redis-only atomic reservation prototype
- Hybrid reservation
- TCC confirmation/cancellation
- Expiration
- Reconciliation

Measure:

- Offered/achieved RPS
- Admitted RPS
- Successful reservations/sec
- p50/p95/p99 latency
- Rejection rate
- Conflict rate
- Overselling violations
- Idempotency hits
- Redis latency
- PostgreSQL lock waits
- Database deadlocks
- Kafka consumer lag
- Reservation expiration lag
- CPU/heap/GC
- Network throughput

Never fabricate benchmark results.

If million-RPS testing cannot run, supply scripts, topology requirements, and mark capacity unverified.

---

# PART X — SECURITY

## 37. ENTERPRISE SECURITY REQUIREMENTS

Implement:

1. OAuth2/OIDC authentication
2. Spring Security authorization
3. Customer identity verification
4. Flash-sale eligibility enforcement
5. Per-user purchase limits
6. Request rate limiting
7. Replay protection
8. Signed admission tokens where used
9. Administrative RBAC
10. Secure Redis access
11. Database least privilege
12. TLS
13. Input validation
14. Secret management
15. Audit logging
16. Bot mitigation
17. Tenant isolation where applicable
18. Secure API error handling
19. Sensitive data protection
20. Controlled administrative inventory adjustments

Never allow clients to specify authoritative inventory counters or reservation state.

---

# PART Y — DEPLOYMENT

## 38. DOCKER AND KUBERNETES

Provide:

- Multi-stage Dockerfile
- Docker Compose
- PostgreSQL
- Redis
- Kafka where implemented
- Spring Boot backend
- Flash-sale demo
- Kubernetes Deployments
- Services
- ConfigMaps
- Secret references
- Resource requests/limits
- HPA
- PodDisruptionBudget
- Readiness/liveness probes
- Graceful shutdown
- Rolling deployment
- CI/CD workflow

Support a full local demonstration of flash-sale creation, customer admission, stock reservation, confirmation, cancellation, and expiration.

---

# PART Z — OBSERVABILITY

## 39. METRICS AND ALERTING

Expose:

- Flash-sale incoming RPS
- Admission acceptance/rejection
- Reservation attempts
- Reservation success
- Reservation conflicts
- Available stock
- Active reservations
- Confirmed allocations
- Reservation expiration lag
- Overselling invariant violations
- Idempotency hits
- TCC failures
- Redis timeouts
- PostgreSQL deadlocks
- Hot-SKU processing lag
- Kafka consumer lag
- Reconciliation mismatches
- Connection pool saturation

Use:

- Micrometer
- Prometheus
- Grafana
- OpenTelemetry
- Structured logging

Avoid unbounded SKU/customer IDs as metric labels.

Use secured logs and tracing for individual reservation investigation.

Define SLOs and alerts for inventory inconsistencies and stuck reservations.

---

# PART AA — FUNCTIONAL FLASH-SALE DEMONSTRATION

## 40. BUILD A WORKING DEMO

Implement a minimal functional client supporting:

- Flash-sale listing
- Product availability
- Countdown to sale opening
- Customer authentication
- Reserve button
- Reservation result
- Hold countdown
- Confirm purchase using a simulated checkout
- Cancel reservation
- Order/reservation status

Also implement an administrative view for:

- Stock allocation
- Sale activation
- Sale pause
- Reservation counts
- Inventory consistency
- Reconciliation status

Do not substitute static mockups for working functionality.

---

# PART AB — IMPLEMENTATION WORKFLOW

## 41. DEVELOPMENT PHASES

### Phase 1 — Requirements and Architecture

Define inventory invariants, workloads, consistency, stock ownership, and selected reservation strategy.

### Phase 2 — Domain and Database

Implement inventory entities, flash-sale models, constraints, migrations, and domain rules.

### Phase 3 — PostgreSQL Reservation Engine

Implement atomic conditional reservations, confirmation, cancellation, expiration, and concurrency tests.

### Phase 4 — Idempotency

Implement scoped idempotency keys, fingerprints, durable state, and duplicate-request recovery.

### Phase 5 — Redis Admission

Implement atomic rate limiting, purchase eligibility hints, burst control, and overload protection.

### Phase 6 — Redis Reservation Optimization

Evaluate and implement Redis preallocation or token-based reservation only where justified and with explicit durability safeguards.

### Phase 7 — TCC

Implement Try/Confirm/Cancel, duplicate handling, empty rollback, expiration, and race-condition tests.

### Phase 8 — Kafka and Outbox

Implement integration events, transactional outbox, inbox, consumer idempotency, and failure recovery.

### Phase 9 — Saga Integration

Implement a representative checkout integration with a deterministic payment simulator.

### Phase 10 — Reconciliation

Implement inventory consistency audits, Redis/PostgreSQL comparison, and safe repair workflows.

### Phase 11 — Security and Observability

Implement authentication, authorization, metrics, tracing, logs, audit events, and alarms.

### Phase 12 — Functional Demo

Implement working customer/admin flash-sale flows.

### Phase 13 — Deployment

Implement Docker Compose, Kubernetes/Helm, CI/CD, and operational configuration.

### Phase 14 — Verification

Run unit, integration, concurrency, security, and failure tests.

### Phase 15 — Performance

Benchmark representative workloads, investigate hotspots, and optimize based on evidence.

### Phase 16 — Final Architecture Review

Review no-overselling guarantees, idempotency, failover, TCC, reconciliation, scalability, and operational risks.

For every phase:

- Implement actual source files.
- Keep the project buildable.
- Run applicable verification.
- Fix meaningful defects.
- Preserve unrelated work.
- Document significant decisions.
- Clearly distinguish tested behavior from assumptions.

If repository and command-execution tools are available, implement directly.

Otherwise, provide complete source files, paths, and reproducible commands without claiming execution.

---

# PART AC — REQUIRED DELIVERABLES

## 42. COMPLETE PROJECT OUTPUTS

### Deliverable 1 — Requirements

- Functional requirements
- Non-functional requirements
- Inventory invariants
- Consistency guarantees
- Acceptance criteria

### Deliverable 2 — HLD

- Architecture diagrams
- Reservation workflow
- Redis/PostgreSQL coordination
- Capacity planning
- Hot-SKU strategy
- Failure recovery

### Deliverable 3 — LLD

- Domain model
- UML class diagrams
- Sequence diagrams
- State machines
- Database schemas
- Redis key design
- Lua scripts
- Concurrency model
- API contracts

### Deliverable 4 — Backend Source Code

- Java 21 Spring Boot application
- Inventory management
- Flash-sale management
- Atomic reservation
- Idempotency
- Admission control
- Reservation expiration
- TCC
- Kafka/Outbox/Inbox
- Reconciliation
- Security

### Deliverable 5 — Functional Demonstration

- Customer flash-sale UI
- Reservation flow
- Confirmation/cancellation
- Stock monitoring
- Admin controls

### Deliverable 6 — Automated Tests

- Unit tests
- PostgreSQL integration tests
- Redis integration tests
- Concurrency tests
- Security tests
- Failure-recovery tests

### Deliverable 7 — Infrastructure

- Docker
- Docker Compose
- Kubernetes/Helm
- CI/CD
- Monitoring

### Deliverable 8 — Performance

- Load-test scripts
- Actual measurements where available
- Capacity models
- Bottleneck analysis
- Scaling recommendations

### Deliverable 9 — Documentation

- README
- Architecture Decision Records
- Inventory consistency specification
- Reservation lifecycle
- Redis atomicity and failover
- TCC protocol
- Reconciliation guide
- Security guide
- Deployment guide
- Incident runbooks
- Known limitations

---

# PART AD — MANDATORY ARCHITECTURE REVIEW

## 43. ANSWER THESE 45 QUESTIONS

1. How would you design a flash-sale system for one million concurrent customers?
2. How do you prevent inventory overselling?
3. What is the difference between available, reserved, and confirmed stock?
4. Why is displaying available stock different from reserving it?
5. How do PostgreSQL conditional updates prevent overselling?
6. When should pessimistic locking be used?
7. When should optimistic locking be used?
8. Why does a hot SKU create row-level contention?
9. How can admission control protect PostgreSQL?
10. How do Redis Lua scripts provide atomicity?
11. Why does Redis Lua atomicity not guarantee failover durability?
12. Can Redis-only inventory reservation guarantee no overselling?
13. How do you reconcile Redis and PostgreSQL stock?
14. How does stock token preallocation work?
15. How do you prevent token ownership overlap?
16. How do you recover unused tokens after worker crashes?
17. What is the difference between an admission token and an inventory reservation?
18. How do you implement scoped idempotency keys?
19. How do you prevent duplicate reservations across pods?
20. What happens when reservation creation succeeds but the response is lost?
21. How do you implement reservation TTL?
22. Why are Redis key-expiration notifications insufficient as the only cleanup mechanism?
23. How do you prevent stale expiration workers from releasing newer reservations?
24. How does TCC Try-Confirm/Cancel work?
25. What is empty rollback?
26. How do you prevent a late Try after Cancel?
27. How do you handle Confirm and Cancel racing?
28. How do you integrate TCC with a checkout Saga?
29. What happens when payment succeeds after reservation expiry?
30. How does Transactional Outbox work?
31. How do you make Kafka consumers idempotent?
32. Why is Kafka not the authoritative stock store?
33. How do you handle a hot Kafka partition?
34. How do you prevent retry storms?
35. What happens when Redis becomes unavailable?
36. What happens when PostgreSQL becomes unavailable?
37. How do you recover from a network timeout after an atomic stock decrement?
38. How do you enforce per-customer purchase limits?
39. How do you protect flash sales from bot traffic?
40. How do you support sale pause/cancellation under load?
41. How do you design multi-region inventory ownership?
42. How do you benchmark no-overselling correctness?
43. How do you distinguish admission throughput from successful reservation throughput?
44. What operational metrics reveal inventory inconsistency?
45. What evidence is required before production deployment?

For each answer explain:

- Architectural decision
- Concurrency correctness
- Consistency guarantees
- Failure behavior
- Scalability
- Performance
- Operational trade-offs

---

# DEFINITION OF DONE

## 44. ACCEPTANCE CRITERIA

The project is complete only when:

- Java 21 application builds successfully.
- PostgreSQL migrations execute.
- Inventory and flash-sale management work.
- Authoritative stock invariants are enforced.
- Concurrent reservations cannot oversell the supported stock pool.
- Same-key duplicate requests do not create multiple reservations.
- Multi-instance idempotency works.
- Reservation confirmation and cancellation are safe.
- Expiration is concurrency-safe.
- TCC Try/Confirm/Cancel works.
- Duplicate and out-of-order TCC commands are handled.
- Redis admission is atomic under its documented model.
- Redis failures do not silently create confirmed inventory ownership.
- Kafka consumers are idempotent.
- Transactional Outbox is recoverable.
- Reconciliation detects inconsistencies.
- Stock adjustment races are tested.
- Hot-SKU overload is bounded.
- Per-customer purchase limits are enforced.
- Authentication and administrative authorization work.
- A functional flash-sale demonstration is available.
- Docker Compose supports end-to-end execution.
- Kubernetes manifests are provided.
- Automated tests cover significant concurrency and failure paths.
- Metrics and health checks function.
- Benchmark scripts are reproducible.
- Performance targets are measured or marked unverified.
- Remaining limitations and production risks are documented.

Do not declare production readiness based solely on a successful happy-path demonstration or unit tests.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, production-oriented High-Concurrency Distributed Inventory Reservation System using Java 21, Spring Boot, PostgreSQL, Redis, and Kafka where justified**.

Prioritize:

1. **Inventory correctness:** No overselling under the supported consistency model.
2. **Concurrency safety:** Atomic reservation and durable ownership.
3. **Idempotency:** Duplicate requests and events must not duplicate inventory consumption.
4. **Failure recovery:** Reservation expiration, reconciliation, and recoverable partial failures.
5. **Scalability:** High-throughput admission with bounded authoritative inventory mutations.
6. **Hot-SKU handling:** Prevent one popular SKU from overwhelming shared infrastructure.
7. **Transaction reliability:** Correct TCC and Saga integration.
8. **Security:** Authenticated purchase attempts, bot protection, and authorized inventory changes.
9. **Maintainability:** Pragmatic domain boundaries, focused Java 21 code, and minimal unnecessary complexity.
10. **Observability:** Inventory invariants, reservation conflicts, queue lag, and recovery metrics.
11. **Reproducibility:** Complete source code, local infrastructure, functional demo, automated tests, and benchmarks.

Begin with a concise architectural assessment.

Select the authoritative inventory model, PostgreSQL concurrency strategy, Redis admission strategy, reservation lifecycle, TCC model, and recovery approach.

Then implement the complete system phase by phase.

Use available development tools to create source files, compile the application, run tests, verify multi-instance concurrency, benchmark available workloads, and fix defects.

At completion report:

- Implemented features
- Architecture decisions and trade-offs
- Inventory correctness guarantees
- Redis/PostgreSQL consistency model
- TCC and idempotency behavior
- Project structure and important files
- Exact build and run commands
- Tests actually executed
- Benchmark results actually measured
- Known limitations
- Remaining production risks

**Do not stop after HLD or LLD. Deliver the complete end-to-end Flash Sale Inventory Reservation System, including authoritative inventory management, Redis admission, atomic reservations, idempotency, TCC, reconciliation, Kafka integration where justified, functional demonstration, automated concurrency tests, and deployment infrastructure.**
````