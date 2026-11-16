# Scope and acceptance

Supported: single tenant, one SKU/warehouse stock pool per Try; product/SKU/warehouse registration; warehouse
adjustments; dedicated sale allocations; sale lifecycle; customer limits; authenticated admission; temporary holds; TCC;
durable deduplication; expiry; simulated checkout; outbox/inbox; audit and manual-review reconciliation; browser demo
and deployment assets.

A purchase attempt is traffic. Admission is permission to attempt a transaction. RESERVED is durable provisional
ownership. CONFIRMED is committed inventory ownership. Payment, order completion and fulfillment are distinct facts.

Targets, not results: 1M incoming attempts/s, 100k admission/s across partitioned sales, regional p99 <50ms, 99.99%
availability. A single hot pool is serialized at PostgreSQL. One million concurrent customers needs an edge waiting
room, signed eligibility sessions, bounded load and distributed generators. The supplied configuration intentionally
admits only 100 attempts/s/sale with 32 local in-flight transactions and 16 database connections.

Acceptance requires persisted no-oversell accounting after concurrent success/failure paths, stable same-key results,
fingerprint conflicts, terminal-state immutability, protected customer ownership and admin routes, recoverable
unpublished events and explicitly recorded test evidence. Multi-AZ failover, external payments and scale SLOs require
additional validation before launch.

Implemented follow-up: explicit pool closure and full-reservation dispatch, with warehouse accounting and durable
shipment identity. Confirmed-but-unshipped stock remains protected after sale completion/closure. Partial shipments,
returns and real carrier connectivity are out of this increment's scope.
