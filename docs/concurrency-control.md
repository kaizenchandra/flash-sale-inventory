# Concurrency

READ COMMITTED is sufficient because capacity mutations serialize on the stock-pool row and recheck predicates in SQL.
Sale SHARE lock is held through Try commit, imposing a barrier against pause/complete/cancel. State changes are
conditional UPDATEs. Allocation creation locks the sale then conditionally changes warehouse capacity. Existing holds
remain valid after sale pause/end/cancel until their TTL; new Try is denied.

| Technique           | Use / trade-off                                                                         |
|---------------------|-----------------------------------------------------------------------------------------|
| Optimistic version  | Good for low-contention metadata; retry storms on hot pools                             |
| Pessimistic lock    | Used for reading dependent pool/terminal state decisions; keep transaction short        |
| Conditional UPDATE  | Authoritative stock predicate and customer limit; checks affected rows                  |
| Serializable        | Broad predicate protection; requires bounded serialization retry, not needed here       |
| Serialized commands | Queue lag replaces concurrent requests; single-owner fencing required                   |
| Counter sharding    | Must allocate nonoverlapping durable capacities; sums alone cannot enforce global stock |

Try lock order: sale SHARE, idempotency, allocation, customer usage, new reservation. Terminal flow: existing
reservation, allocation, usage. Reconciliation locks allocations and only reads reservation rows using MVCC.
Deadlocks/timeouts roll back atomically and return 503; clients retry the same key using bounded exponential jitter.
There is no unbounded automatic transaction retry. Hikari is capped at 16, acquisition 1s, PostgreSQL statement timeout
3s. Admission has 32 local slots and 200ms Redis deadline. Gateway must also bound connections, bodies and request rates
for all endpoints.

Pool closure/dispatch lock ordering and the revised ownership equations are specified in stock-pool-lifecycle.md.
Dispatch locks reservation->pool->warehouse, while close locks sale SHARE->pool->warehouse without taking reservation
locks. Reconciliation audits parents in a separate transaction from its pool batch.
