# Accounting and correctness proof

For each warehouse/SKU let P be remaining physical units, S the sellable subset, A the units encumbered by sale pools,
and H cumulative dispatched units. PostgreSQL enforces `0 <= A <= S <= P` and `H >= 0`. Positive/negative stock
adjustments change P and S together; dispatch subtracts from both. H is historical and is not subtracted twice.

For each allocation:

| Counter         | Meaning                                                                   |
|-----------------|---------------------------------------------------------------------------|
| C (`capacity`)  | Original dedicated sale capacity; never rewritten by dispatch/closure     |
| R (`reserved`)  | Active temporary holds                                                    |
| K (`committed`) | Historical confirmed allocation units, including dispatched units         |
| F (`fulfilled`) | Confirmed units already dispatched, each backed by one fulfillment record |
| L (`released`)  | Unused capacity returned by explicit pool closure                         |

Constraints: `R >= 0`, `0 <= F <= K`, `L >= 0`, `R + K + L <= C`. An open pool has L=0. A closed pool has R=0 and L=C-K.
Available sale stock is `C-R-K` while open, **zero** while closed. Warehouse encumbrance is the sum of `C-F-L` across
its pools. Outstanding confirmed stock is K-F; shipped units remain CONFIRMED for idempotency and history, but no longer
own warehouse units.

The remaining-stock correctness invariant is `sum(R + K - F) <= A <= S`. Never compare lifetime K against remaining S
after dispatch. Warehouse cumulative H equals sum(F) for the supported sale-dispatch model.

Allocation creation conditionally increments A and inserts C in one transaction. Try conditionally increments R only for
an open pool when R+K+q<=C. Confirm transfers q from R to K. Cancel/expiry subtract q from R once. Dispatch requires a
locked CONFIRMED reservation without a prior fulfillment, increases F and H by q, and decreases P/S/A by q in the same
transaction. Because the confirmed quantity was already encumbered, reducing both S and A preserves their inequality.
Dispatch never frees a customer's lifetime purchase allowance.

Closure requires a terminal COMPLETED/CANCELLED sale and zero active holds; it cross-checks authoritative reservation
totals under the pool lock. It sets L=C-K and decreases A by exactly L. Physical and sellable stock do not change: these
are unused units, not dispatched units. Outstanding K-F remains encumbered. Closure before or after dispatch yields the
same final totals; F and L represent disjoint units. Repeated closure/dispatch changes no counters, ledger entries or
events. Stock reduction still refuses S<A.

Every successful dispatch/closure atomically writes counters, durable identity/state, movement audit and outbox.
Reservation-row and pool-row locks serialize competing transitions. Unique shipment references prevent reusing one
shipment identity for another reservation. Database constraints defend row bounds; cross-row equality additionally
requires all writers to use these transactions. Reconciliation compares reservation, fulfillment, ledger and warehouse
totals without resetting them.

Assumptions remain READ COMMITTED, primary-only writes, synchronous_commit enabled and one fenced PostgreSQL writer. An
asynchronous replica promotion that loses acknowledged commits invalidates the durability guarantee. This implementation
records an authorized warehouse dispatch; it does not atomically execute a real carrier operation. Partial shipments,
returns, refunds and post-confirm allocation cancellation require separate protocols.
