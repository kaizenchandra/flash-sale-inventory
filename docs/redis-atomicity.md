# Redis admission

Keys: `admission:{saleUUID}:epochSecond` and `admission:{saleUUID}:epochSecond:sha256(customer)` share a cluster hash
slot. The Lua script reads both integer counters, checks bounded quotas, increments only on acceptance and applies 2s
cleanup TTL. It touches exactly two keys and does no scans. Codes: 0 permitted, 1 sale quota, 2 customer quota. The
fixed window admits up to two window quotas around a boundary; this is documented burst behavior. Repeated attempts
consume admission quota; durable idempotency is checked in PostgreSQL after admission. No Redis stock decrement or
reservation keys exist.

Lost response after Lua means an extra admission quota may be consumed. Redis failover/eviction/restart may reset quotas
and increase load; it cannot create stock ownership. Connection failure, overload and unknown script result fail closed
with 503. Local concurrency bound remains active even if Redis loses all keys. Use noeviction, TLS, ACL-scoped
credentials and memory/latency alerts. Cluster script atomicity is limited to one slot; adding nodes does not split this
sale's hot key.

No Redis-only reservation optimization is implemented: no measurement justifies exchanging durable stock simplicity for
failover uncertainty. Redis replica tests here establish no failover guarantees.
