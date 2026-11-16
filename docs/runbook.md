# Operations and release gates

1. Build on Java 21; run `mvn verify`; inspect all reports. Run Compose smoke and customer UI flow.
2. Provision a fenced single-writer multi-AZ PostgreSQL cluster with acknowledged-commit durability, backup/restore
   tests, least-privilege runtime role and separate migration role. No read-replica reservation decisions.
3. Provision Redis TLS/noeviction, Kafka replicated topics with min.insync.replicas and a dead-letter topic. Local
   Compose uses one broker and has no HA guarantee. Production disables auto topic creation; precreate inventory.events
   and inventory.events.DLT with matching partition counts.
4. Build/push image, replace Kubernetes image/config placeholders, create flashsale-runtime Secret with DB_USER,
   DB_PASSWORD, SPRING_DATA_REDIS_PASSWORD and broker credentials. Deploy behind TLS ingress and authenticated gateway.
   Apply network policies and secret rotation procedures.
5. Never reformat an applied Flyway migration: whitespace changes checksums. Run backward-compatible migrations before
   rollout; reject unknown event schema versions. Keep old consumers until
   replay horizon ends. Incompatible schemas require expand/migrate/contract, not mixed-writer rollout.
6. Prove restore/failover, partition behavior, multi-pod HTTP correctness, shutdown during commit, broker DLQ/replay,
   expiry backlog and resource saturation in staging. These are release gates, not established by unit tests.

Redis outage: fail closed, confirm/cancel/GET remain database-backed. Restore cache, ramp admission slowly and jitter
retries. Never create reservations from cached permits. PostgreSQL outage: return 503, retry original key after
recovery; do not invent ownership. Ambiguous commit: same key recovers durable result. Kafka outage: reservations
continue while outbox accumulates; alert on age/storage, cap sale intake before disk exhaustion. Publisher retries
without marking success before broker acknowledgement; duplicates are expected and inbox absorbs them.

Inventory alert: pause affected sale, capture allocation/reservation/movement snapshots, inspect committed orders, run
reconciliation, obtain reviewed repair transaction. Never reset counters from cache. Stuck expiry: inspect database
locks, oldest due hold, worker throughput; scale bounded workers only within pool capacity. DLQ: repair schema/data
consumer issue, replay original event IDs to preserve deduplication, monitor projections. Retry storms: lower gateway
quotas and sale caps, ramp gradually.

SLO design: availability 99.99%, regional reservation p99 <50ms at validated load, invariant violations zero. Targets
remain unvalidated. Alert on invariant mismatch immediately, expiry lag >2*TTL, unpublished outbox age >60s, DLQ count >
0, Kafka lag growth, Redis timeouts and pool >90% for 2m. Micrometer exposes aggregate held/committed/available units,
expiry lag, outbox age/count, attempts/results/idempotency/transitions/admission/invariant counters and Spring
JVM/HTTP/Hikari metrics, with bounded labels. Per-SKU/customer IDs are excluded. Further required instrumentation:
PostgreSQL deadlock exporter, Kafka lag exporter and immutable external audit storage. Tracing bridge is present;
configure OTLP endpoint/sampling and structured JSON logging in deployment. Import docker/grafana-dashboard.json and
provision the Prometheus scrape/rules examples with a rotated ADMIN metrics token; validate panels against your exporter
versions.

Known unsupported cases: external PSP/order reconciliation, partial shipments, post-confirm returns and real carrier
integration, preallocated
tokens, multi-region writes, Redis failover topology tests, full chaos matrix, million-RPS evidence. Keep all traffic
for a pool in one home region; cross-region stock transfer requires fenced durable ownership and no concurrent old
owner. No infrastructure has been deployed beyond local verification.

Pool lifecycle: complete/cancel the sale, drain active holds, then ADMIN POST /api/v1/admin/allocations/{id}/close.
Dispatch confirmed reservations using /api/v1/admin/reservations/{id}/fulfill with the stable external
shipmentReference. Retry that exact reference after timeouts. Closure 409 means holds remain, sale state is nonterminal,
or counters disagree; never force a counter reduction. See stock-pool-lifecycle.md for equations, examples and failure
policy. Monitor inventory.committed.outstanding.units for unshipped commitments; inventory.committed.units remains
historical.
