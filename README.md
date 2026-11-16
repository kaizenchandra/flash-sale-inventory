# Flash-sale inventory

Java 21 / Spring Boot 3.5.16 modular monolith. PostgreSQL is authoritative; Redis admits attempts; Kafka distributes
recoverable inventory facts. This is a production-oriented baseline with explicit deployment gaps, not a certification
of production readiness.

## Run

```sh
cd flash-sale-inventory
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn verify                         # Docker daemon required for Testcontainers
docker compose up --build -d
python3 flash-sale-demo/demo.py
# Open http://localhost:8080
```

For OrbStack, if Testcontainers cannot locate Docker, set `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` before running Maven.

Demo credentials: `customer:customer-local`, `admin:admin-local`. Basic authentication is enabled **only** by the
explicit `demo` profile. Never expose this profile publicly. Production uses OIDC issuer discovery, issuer/time/audience
validation, signed JWTs and `roles` containing CUSTOMER or ADMIN.

Run the app against Compose infrastructure without its container:

```sh
docker compose up -d postgres redis kafka
SPRING_PROFILES_ACTIVE=demo KAFKA_SERVERS=localhost:9092 MESSAGING_ENABLED=false mvn spring-boot:run
```

The broker advertises `kafka:9092` to containers; use the full Compose application for messaging, or configure an
external broker listener before running messaging on the host. Disabled messaging retains the outbox for later delivery.

## Guarantees

Each sale allocation is a bounded PostgreSQL stock pool. Warehouse `allocated <= sellable <= physical`, and each pool
`reserved + committed <= capacity`. Transactions update reservation state, counters, customer usage, ledger and outbox
together. Pool `fulfilled` counts dispatched committed units; `released` counts unused closed capacity. Warehouse
encumbrance equals sum(`capacity-fulfilled-released`). Cross-pod duplicate Try operations converge through database
uniqueness. Confirm/cancel/expire serialize on
the reservation row; stale expiration cannot release another hold. A Redis permit confers no inventory ownership.

A reservation response has a separate `status`: RESERVED grants a temporary hold; FAILED grants nothing; CONFIRMED is an
allocation, not proof of payment or fulfillment. The checkout endpoint simulates payment and a pending order; it does
not contact a real payment provider.

## Layout and contracts

- `src/main/java/com/example/flashsale`: API, application services, persistence, admission, security, recovery and
  messaging.
- `src/main/resources/db/migration`: Flyway constraints and indexes.
- `src/main/resources/redis/admission.lua`: bounded atomic admission.
- `src/main/resources/static/index.html`: functional customer/admin client.
- `src/test`: actual PostgreSQL/Redis concurrency, lifecycle and security checks.
- `docs/openapi.json`: API contract; `docs/architecture.md`: design; `docs/verification.md`: evidence.
- `docker-compose.yml`, `docker/Dockerfile`, `helm/kubernetes.yaml`: local infrastructure and deployment baseline.
- `benchmarks`: reproducible workload generator; high-scale capacity remains unverified.

## Limits before production

Stock-pool closure and full-reservation dispatch are implemented; see `docs/stock-pool-lifecycle.md`.
Closure releases unused capacity only after a terminal sale has no active holds. Unshipped confirmed units remain
encumbered; idempotent dispatch deducts warehouse physical/sellable/allocated units once. Partial shipments, returns,
post-confirm cancellation and real carrier integration remain gaps. Reconciliation audits reservation, shipment,
closure-ledger and warehouse totals, flagging discrepancies for manual review without resetting counters. Redis
has no authoritative stock mirror to reconcile. Token preallocation is deliberately deferred until contention
measurements justify its complexity.

The Kubernetes file requires managed PostgreSQL/Redis/Kafka, a real image, identity configuration, `flashsale-runtime`
Secret, ingress/TLS, network policies and migration-role separation. The verified local Compose stack is running
at http://localhost:18190, using isolated Compose project flashsale-verification. No external environment has been
deployed. See the
detailed gaps and operational gates in `docs/runbook.md`.
