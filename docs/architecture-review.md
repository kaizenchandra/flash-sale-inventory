# Architecture review

The per-question statements below use the shared guarantee: single fenced durable PostgreSQL writer, READ COMMITTED
atomic transitions, bounded admission and no direct counter writers. Performance and availability targets remain
unverified. Relevant detailed trade-offs and failure evidence are in concurrency-control.md, failure-scenarios.md and
verification.md.

## 1. Design for one million customers

Reject most attempts at a capacity-tested edge waiting room; shard traffic by sale/stock ownership and bound admitted
PostgreSQL work. One million arrivals is not one million successful transactions.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 2. Prevent overselling

Use a single fenced PostgreSQL authority and bounded pool capacities; conditional increments and terminal-state
transactions preserve R+K+L<=C. Fulfillment F is included in historical K; remaining demand is R+K-F.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 3. Available/reserved/confirmed

Open-pool available=C-R-K; closed availability is zero. R is expiring ownership, K historical confirmed allocation, F
fulfilled units and L released unused capacity. Warehouse allocated=sum(C-F-L) protects outstanding K-F without counting
dispatched units again.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 4. Display versus reservation

Availability reads are hints that can be stale; only a committed transaction grants ownership.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 5. Conditional updates

PostgreSQL serializes concurrent writes to the row and reevaluates the stock predicate. Affected-row count is the
decision; no read-then-unprotected-decrement.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 6. Pessimistic locks

Use when several dependent reads/mutations must agree, including terminal decisions and sale barriers. Keep transactions
bounded to prevent long lock queues.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 7. Optimistic locks

Useful for rarely changed metadata. On hot inventory every competing writer causes retries; conditional stock updates
are clearer here.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 8. Hot row contention

Every write to one pool waits for its predecessor. More pods/virtual threads cannot parallelize that critical section.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 9. Admission protects PostgreSQL

Lua rejects sale/customer quota excess before opening a DB transaction; local slots bound concurrent admitted work even
after Redis data loss.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 10. Lua atomicity

A Redis primary executes the script without interleaved commands, across the two keys in the same cluster slot. It is a
quota operation, not a stock transaction.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 11. Lua versus failover durability

Async replicas may not receive an acknowledged script result before promotion. Atomic execution does not establish
durable quorum commit.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 12. Redis-only strict inventory

Not under ordinary async-replication failover with lost writes. Strict ownership needs a compatible durable
protocol/fencing; the baseline keeps PostgreSQL authority.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 13. Redis reconciliation

There is no Redis stock mirror. Admission quota can reset safely for correctness; PostgreSQL audits compare reservation
and stock-pool sums.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 14. Token preallocation

Durably split available capacity into nonoverlapping bounded subpools, preserving the total encumbrance inequality. This
is a deferred design, not implemented optimization.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 15. Token ownership overlap

Unique token identities and owner epochs must be validated at the durable authority. Never reclaim on lease time alone.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 16. Recover unused tokens

Fence old ownership, recover durable consumption and return only proven-unused capacity; uncertainty freezes tokens
rather than overallocating.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 17. Admission versus reservation

Admission permits an attempt. RESERVED is durable temporary stock ownership. A permitted attempt may still fail
eligibility/stock/customer checks.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 18. Scoped idempotency

Primary key uses authenticated customer, sale and logical-operation key; canonical allocation+quantity fingerprint
detects conflicting reuse. Try and empty Cancel deliberately share identity.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 19. Cross-pod duplicates

PostgreSQL unique insert plus locked idempotency lookup serialize retries. All pods recover one reservation UUID; Redis
SETNX is not involved.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 20. Lost creation response

Retry original key/payload; committed records return current state. Rolled-back operations can execute again. Never
generate a new key solely because the response was lost.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 21. Reservation TTL

Store expires_at in PostgreSQL; expiry scanner locks each due reservation and conditionally transitions/reclaims in the
same transaction.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 22. Key notifications insufficient

Redis notifications can be missed and keys can disappear before durable ownership is released. PostgreSQL timestamp
scans recover after downtime.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 23. Stale expiry

UUID, RESERVED state and version predicate fence every terminal update. An already expired A cannot release stock held
by B.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 24. TCC

Try creates temporary ownership; Confirm moves held quantity to committed; Cancel releases only an active hold. Every
phase is durable and repeatable.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 25. Empty rollback

Persist CANCELLED for the same logical Try identity before a hold exists. This records a terminal decision without
releasing nonexistent inventory.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 26. Late Try after Cancel

cancel-try uses the same idempotency key/payload; the later Try finds the tombstone. A different key is a separate
purchase attempt.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 27. Confirm/Cancel race

Lock reservation; first valid terminal transaction wins. The loser returns that terminal status and changes no stock.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 28. Checkout saga

Persist step state and stable operation IDs. Real payment authorization/capture needs outbox-driven commands, idempotent
PSP calls and durable compensation; only a local simulator is supplied.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 29. Payment after expiry

Never resurrect expired ownership. Void authorization or refund capture and cancel/review the order; reacquisition
requires a new independently authorized Try.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 30. Outbox

Write event with inventory state in one transaction, publish after commit, mark only after broker acknowledgement. Crash
between ack and mark creates a duplicate, not a missing durable event.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 31. Idempotent Kafka consumers

Insert event ID in durable inbox within the same transaction as projection. Apply only increasing aggregate versions to
tolerate reordering.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 32. Kafka authority

A log of facts is not the stock predicate database. Offsets alone do not coordinate inventory and ownership across
retries or external transactions.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 33. Hot Kafka partition

Facts key by reservation ID here and need only per-reservation versions. A future SKU-command stream remains serial for
one hot SKU; partition count alone cannot remove that limit.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 34. Retry storms

Use bounded jittered backoff, same idempotency key, gateway waiting room, local slots and dependency timeouts. Never use
unbounded immediate retries.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 35. Redis unavailable

Fail new Try admission closed with 503; confirmation, cancellation and lookup remain PostgreSQL-backed. Restore and
gradually ramp quotas.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 36. PostgreSQL unavailable

Return unavailable rather than ownership. Kafka/Redis cannot substitute for a missing authority; recover original keys
after the primary returns.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 37. Timeout after decrement

Baseline Redis operation decrements no stock. After ambiguous PostgreSQL commit, recover durable idempotency result; do
not compensate by blindly incrementing counters.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 38. Customer limits

An atomic customer_usage increment bounded by sale.customer_limit counts active and committed units across pools.
Cancels/expiry decrement once in the terminal transaction.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 39. Bots

Issuer eligibility, per-customer quotas and quantity limits are implemented. Device/session controls, waiting-room
tokens, WAF and fraud detection are deployment extensions.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 40. Pause/cancellation under load

Sale SHARE/UPDATE barrier defines ordering; after state update commits new Try is denied. Existing holds keep their TTL
and may confirm; bulk revoke is not implicit.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 41. Multi-region ownership

One home region/fenced primary per pool. Geographic distribution needs disjoint durable stock allocations, not
active-active decrement of replicated caches.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 42. Benchmark correctness

Assert persisted reservation sums, pool counters and warehouse encumbrance after controlled workloads and races. HTTP
200 counts alone are insufficient because results include FAILED.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 43. Different throughputs

Report incoming requests, Redis permitted attempts, durable transaction results, RESERVED units, confirmed allocations,
Kafka records and completed orders separately.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 44. Inconsistency metrics

Invariant mismatch, expiry/outbox lag, committed-versus-order differences, deadlocks and saturation reveal distinct
problems. Labels stay bounded; investigate identifiers in secured traces/audits.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

## 45. Production evidence

Require real multi-pod concurrency, durable DB failover, chaos/restore, PSP compensation, security review, telemetry and
representative load with stated hardware. Unit/demo success is insufficient.

Concurrency/consistency: inventory decisions remain PostgreSQL transactional; projections and admission are not
ownership. Failure behavior: retry durable operation identity or fail closed under uncertainty. Scale/performance: bound
work before the serialized stock mutation; capacity depends on measured workload. Operational trade-off: stronger stock
correctness can reduce availability or utilization, and unsupported extensions require staging evidence.

