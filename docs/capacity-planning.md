# Capacity model and benchmark protocol

Illustrative, not measured: 1M incoming attempts/s, edge admits 1% = 10k/s; 100 sale pools at 100/s each feed
PostgreSQL. If 10k sale units sell out over 10s, maximum useful success rate is 1k/s. Database capacity must handle
**admitted attempts**, including rejected/idempotent operations, rather than only successful holds. At 10k/s and mean
transaction residence 5ms, Little's law implies ~50 concurrent DB operations before safety margin; a single contended
pool has different limits.

Each new success writes idempotency, reservation, usage, allocation, movement and outbox (~6 logical writes plus
indexes/WAL). Each terminal state adds reservation, allocation, usage (release), movement and outbox. At 1k new holds/s
and 1k terminal/s, expect roughly 11k logical row writes/s, not a database throughput claim. Two event transitions per
hold gives ~2k Kafka messages/s before retries. At 600 bytes/event that is ~1.2MB/s payload plus protocol/replication
overhead. Thirty-second TTL with all holds abandoned approaches the accepted success rate in expiry/s; configured 100/s
worker per instance must be sized against backlog.

100k holds/day at estimated 1KB per hold plus idempotency/indexes is at least 100MB/day before WAL/replication and
lifecycle history. Measure real relation/index/WAL sizes. Redis admission memory approximates 2s * active accepted
customer keys/s * key/value overhead; at 10k/s and 200 bytes/key about 4MB before allocator/replication overhead.
Validate `MEMORY USAGE`; estimates are not sizing guarantees.

Benchmark stages: isolate Lua using redis-benchmark/EVALSHA; run direct PostgreSQL test harness for commit contention;
run hybrid k6; run confirm/cancel mix; run expiry backlog and reconciliation. No Redis-only inventory prototype is
shipped because it is outside the chosen correctness model. Measure offered/achieved RPS, dropped iterations, admitted
results, RESERVED rate, FAILED conflicts, latency by HTTP/business status, idempotency hits, PG
locks/deadlocks/WAL/pool, Redis latency, Kafka lag and expiry lag. Save generator and server CPU/network profiles
alongside JSON outputs. Assert persisted invariants after every run.

`RATE=1000 k6 run benchmarks/reservations.js` requires SALE_ID, ALLOCATION_ID and TOKEN. Use an identity token pool for
realistic customers: one token intentionally hits customer quotas. Scripts enumerate up to 1M RPS but do not establish
capacity. Large stages need distributed k6 workers, enough NIC/CPU, edge TLS capacity, realistic independently issued
identities, managed multi-AZ database/Redis/broker topology and failure injection. Never increase admission caps without
measuring lock residence and queue growth. Cloud cost is deployment/region dependent: budget compute-hours + provisioned
DB/WAL/IOPS + cache memory + Kafka replicated storage + egress; no dollar estimate is asserted.
