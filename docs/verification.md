# Verification evidence — 2026-11-15

Latest follow-up: **44 tests passed (24 existing + 20 stock-lifecycle tests), zero failures/errors/skips**.
StockLifecycleTest took 13.99s and InventoryTest 26.24s, including class setup. See the stock-pool closure section
below. Earlier sections retain the original baseline measurements and operational history.

## Executed

Java 21.0.13.0.3, Maven 3.10.0, Spring Boot 3.5.16, Docker Engine 29.8.2/OrbStack on an ARM64 macOS host. Docker
reported about 9GB VM memory and many unrelated running services. They were left untouched.

`mvn verify` passed **24 tests, zero failures/errors/skips**, in 30.64 seconds for the integration test class including
setup. PostgreSQL 17-alpine and Redis 7.4-alpine ran in Testcontainers. See evidence/integration-tests.txt and
evidence/concurrency-timings.txt. Tests include 100-for-one, 1000-for-100, durable cross-instance proxy duplicates,
fingerprint conflicts, rollback, terminal race, stale expiry, TTL enforcement, empty rollback, concurrent customer
limit/replenishment, reduction refusal, stock-adjustment idempotency/actor audit, durable checkout recovery after
authorization and expiry compensation, inbox duplicate/out-of-order handling, actual Redis pause/timeout, concurrent Lua
quotas, pool exhaustion and route/ownership security.

Controlled concurrency tests bound database work to eight transactions to measure correctness independently of overload
rejection. Earlier unbounded and 32-concurrent attempts exhausted the 16-connection pool; those failures are not hidden.
The application uses bounded acquisition and controlled 503. Eight is a test harness setting, not a throughput result or
a deployment recommendation.

Multi-stage Docker image built; Compose started PostgreSQL, Redis, Kafka and the app. Authenticated seed script created
real catalog/stock/sale/allocation and a reservation. A second real application container sharing all three dependencies
passed integration-tests/smoke.py: same-key simultaneous HTTP Try returned the same UUID; checkout confirmed one unit;
cancellation released another; the running worker expired a five-second hold. Persisted final pool: capacity 10,
reserved 0, committed 1, available 9. See evidence/two-instance-smoke.json. The final HTTP run used the completed
three-migration build across the Docker instance and an immutable copy of the packaged application.

A real broker/database check after smoke found 8 outbox events, 0 unpublished, 8 durable inbox records, 4 aggregate
projections and 0 counter invariant violations. This establishes local broker delivery, not broker failover correctness.
A later post-load SQL check again found zero reservation/counter mismatches and zero unpublished events.

Python demo/smoke scripts compiled; client JavaScript syntax checked with Node. Browser automation could not initialize
because the browser access host was unavailable, so visual layout and click-through verification are not claimed. REST
flows backing the client were exercised.

## Actual load observations

| Run                            | Offered | Completed |                Achieved | Dropped iterations | Results                                                                         |
|--------------------------------|--------:|----------:|------------------------:|-------------------:|---------------------------------------------------------------------------------|
| Overload, 10s arrival window   |  1000/s |       985 | 76.65/s including drain |               9016 | 3 durable responses, 32 unexpected/timeouts; threshold FAILED                   |
| Diagnostic, 10s arrival window |    10/s |        98 |  9.68/s including drain |                  3 | 30 durable responses: 3 RESERVED, 27 FAILED; 68 quota 429; no unexpected errors |

Overload latency: median 2.36s, p95 2.88s; p99 was not configured for that original run and is not inferred. Diagnostic
latency: median 209.96ms, p95 942.86ms, p99 1.32s. All are HTTP timings including demo Basic authentication; latency SLO
is not achieved. A later smoke rerun during final build contention exceeded its five-second hold before checkout; the
API correctly refused confirmation. The reusable smoke harness now defaults to a 30-second hold to separate lifecycle
correctness from resource-dependent request latency. The single demo identity intentionally hits 3/s admission and
3-unit purchase limits. HTTP 429 counts as failure in k6's default http_req_failed; the custom unexpected_errors metric
separately tracks unexpected responses/timeouts. JSON summaries are in benchmarks/results/.

The overloaded app was observed at about 175.9% CPU and 156.4MiB resident container memory, versus the second instance
at 4.7% CPU. BCrypt verification per demo request and shared-host contention are plausible contributors, not
independently isolated causal measurements. Production OIDC/JWT has a different authentication path and needs its own
benchmark. These observations do not validate 100k admission/s, 1M incoming/s, p99 <50ms or 99.99% availability.

## Not executed / release gates

No 10k/100k concurrent stock workload, million-RPS run, multi-AZ database failover, Redis replica promotion, Kafka
broker loss, real PSP integration, database network partition, deliberate deadlock injection, external order
reconciliation, multi-region ownership, full chaos suite or Kubernetes rollout was executed. No production readiness
claim is made. Scripts/manifests/design notes are inputs to those exercises, not evidence they passed.

## Interrupted and resumed verification

Later reruns encountered an interrupted application process, missing Compose dependencies, a Flyway metadata timeout
inherited from runtime connections, and a slow PostgreSQL container initialization. Flyway now has a separate migration
connection; fixture startup allows three minutes. After restoring only this project's dependencies, the final 24-test
suite passed. An executable-JAR launch overlapped Maven repackaging; the runtime check was corrected to use an immutable
copy after verify completed. These failed verification attempts do not count as passing tests.

## Final runtime checks

The final cached multi-stage Docker build and Compose application started successfully. The two-instance smoke was
repeated on the final source with a 30-second hold and passed. RecoveryProbe.java injected durable
authorization-boundary fixtures: the live worker recovered one to ORDER_CONFIRMED/CAPTURED and compensated the expired
one to AUTHORIZATION_VOIDED/VOIDED; each preserved pool accounting. See evidence/checkout-recovery.txt.

A formatting pass had changed whitespace in applied V1/V2 migrations. SQL token comparison proved their definitions
identical; the original applied bytes/checksums were restored rather than changing Flyway history or resetting data.
Startup then validated the preserved database at schema version 3. Do not reformat applied migrations. The final
packaged build includes separate migration credentials/connections and bounded runtime JDBC network timeouts.

Final post-recovery database audit: 0 reservation/counter mismatches; 60 outbox events, 0 unpublished and 60 inbox
records. The Docker application's readiness endpoint, protected Prometheus stock/lag/HTTP histogram metrics and final
customer/admin HTML were verified. Served JavaScript passed Node syntax validation; browser visual QA remains
unperformed. The temporary host instance was stopped after the two-instance checks; Compose remains available on
port 18190.

## Stock-pool closure and fulfillment follow-up

Executed Java 21 `mvn verify` successfully with real PostgreSQL and Redis containers: 44 total tests, zero
failures/errors/skips. The new 20-test class covers active/overdue-hold closure rejection, protected unshipped
commitments and safe reallocation, fulfillment before/after close, cancelled-sale close, unique shipment identity and
conflicting retries, unconfirmed/cancelled/expired dispatch rejection, independent-instance duplicate dispatch, 40
duplicate close requests, close-versus-dispatch/confirm/cancel/Try races, concurrent negative adjustments, 100
dispatches with bounded eight-way work and a zero-release sold-out closure, full transaction rollback, corrupted-counter
closure refusal, database constraints, pool/warehouse/ledger reconciliation and authorization/owner access.

The migration test created a separate V3 schema containing confirmed allocations, applied V4 without changing V1-V3, and
successfully closed/dispatched the preexisting commitment. It checked preserved commitment quantities and remaining
warehouse stock. The new migration was also applied to the preserved local Compose database by the rebuilt image.

`BASE_URL=http://localhost:18190 python3 integration-tests/stock-lifecycle-smoke.py` passed against the running image.
Starting from 10 units and a five-unit pool, it confirmed two, refused unsafe/held closure, drained the remaining hold,
closed and released three, protected the unshipped two against a damaging adjustment, reallocated only eight units to
another sale, refused excess allocation, recorded one two-unit dispatch with duplicate replay, rejected a changed
shipment reference, and preserved CONFIRMED on same-key Try replay. Closing the unused replacement pool left physical=8,
sellable=8, allocated=0, fulfilled=2. Original closed pool: capacity=5, reserved=0, committed=2, fulfilled=2, released=
3, encumbered=0 and available=0. See evidence/stock-lifecycle-smoke.json.

Post-run global SQL audits found zero pool/reservation/fulfillment mismatches and zero warehouse encumbrance/fulfilled
mismatches. Kafka delivered the new InventoryFulfilled and InventoryPoolClosed facts into the durable inbox; see
evidence/stock-lifecycle-database-audit.txt. Compose configuration and JSON contracts parsed, Python smoke syntax
compiled, and new client JavaScript passed Node syntax validation. No new throughput, carrier integration,
partial-shipment, returns or visual-browser claim is made.
