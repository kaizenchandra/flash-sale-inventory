# Hot SKU scaling

Admission is the first scaling boundary: reject traffic at the edge, then Redis, before a short PostgreSQL transaction.
Incoming RPS is not DB RPS. One hot sale slot, database pool row or Kafka partition remains serial regardless of pod
count. HPA must not multiply database connections past capacity (12 pods * 16 = 192 application connections before
workers/operations).

Use authenticated waiting-room tickets, per-customer session/device policy and early sold-out hints at the edge. Hints
never grant ownership. Shard admission only with explicit global quota accounting or accept its approximate protection;
it is not stock accounting.

Preallocation extension: PostgreSQL transaction moves a bounded part of available C into token-owner rows
`(pool, owner, epoch, capacity, consumed, state)`. Disjoint owner capacity plus existing committed/held units must fit
C. Every consumption references unique token ID and fenced owner epoch. Reclamation cannot be based only on lease
expiry: fence the old owner in the authoritative transaction, recover its durable consumption log, then return only
provably unused units. Redis loss requires freezing uncertain pools until recovered. Never assign overlapping ranges
after an ambiguous crash. This protocol is designed, not enabled in source, pending hot-row measurements.

Serialized queues similarly need one fenced logical owner, durable result lookup, bounded lag and inbox operation
identity. More partitions improve many SKUs, not one SKU's write contention.
