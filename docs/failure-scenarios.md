# Failure matrix

No failover or chaos case is claimed tested unless named below. For every baseline case Redis does not own stock;
overselling risk becomes material if writers bypass PostgreSQL or acknowledged database commits are lost. Detection
combines returned states, database audits, error rates, dependency health and the named scenario signals.

## 1. Final unit contention

Root cause: Concurrent stock claims. Prevention/recovery: Pool conditional increment + CHECK; loser gets
FAILED/SOLD_OUT. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or
unsupported protocols require the additional controls described. Customer impact: rejection, delay or durable terminal
result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and
reconciliation evidence relevant to this cause. Automated evidence: thousandForHundred, hundredForLastUnit. Residual
risk: Single-row lock latency remains.

## 2. One thousand customers

Root cause: Hot-row lock queue. Prevention/recovery: Bound admission and pool; count persisted RESERVED rows.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: thousandForHundred. Residual risk: Throughput bounded by primary.

## 3. Million opening attempts

Root cause: Burst exceeds service capacity. Prevention/recovery: Gateway waiting room, Redis rejection, local semaphore;
jitter retries. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or
unsupported protocols require the additional controls described. Customer impact: rejection, delay or durable terminal
result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and
reconciliation evidence relevant to this cause. Automated evidence: benchmarks/reservations.js (scale unexecuted).
Residual risk: Edge/distributed generator capacity unverified.

## 4. Duplicate customer submissions

Root cause: Retry or double click. Prevention/recovery: Scoped database unique key; return original reservation.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: concurrentDuplicates. Residual risk: Different keys are distinct
attempts subject to customer limit.

## 5. Key reused with different quantity

Root cause: Logical identity collision. Prevention/recovery: Fingerprint comparison returns 409. Overselling/integrity:
supported atomic transactions preserve counters and ownership; external or unsupported protocols require the additional
controls described. Customer impact: rejection, delay or durable terminal result, never an inferred stock grant.
Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation evidence relevant to this
cause. Automated evidence: conflictingFingerprint. Residual risk: Client must retain original payload.

## 6. Commit then lost response

Root cause: Ambiguous network outcome. Prevention/recovery: Retry original key or GET; never allocate a replacement
blindly. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported
protocols require the additional controls described. Customer impact: rejection, delay or durable terminal result, never
an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: concurrentDuplicates models replay; packet loss untested. Residual
risk: Client may remain uncertain until DB recovers.

## 7. Retry on another pod

Root cause: Local state is absent. Prevention/recovery: Unique DB claim shared across instances. Overselling/integrity:
supported atomic transactions preserve counters and ownership; external or unsupported protocols require the additional
controls described. Customer impact: rejection, delay or durable terminal result, never an inferred stock grant.
Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation evidence relevant to this
cause. Automated evidence: duplicateAcrossIndependentServiceInstances. Residual risk: Full multi-pod HTTP routing still
staging gate.

## 8. Lua success then timeout

Root cause: Admission response lost. Prevention/recovery: Fail closed; permit may be wasted; no stock mutation yet.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: Not fault-injected. Residual risk: Temporary false rejection.

## 9. Redis primary crash after permit

Root cause: Cache process loss. Prevention/recovery: Quota reset can increase load; PG still enforces ownership.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: Not fault-injected. Residual risk: Admission fairness degraded.

## 10. Stale Redis promotion

Root cause: Async replication loses counters. Prevention/recovery: Keep PG authority and local bound; ramp caps after
recovery. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported
protocols require the additional controls described. Customer impact: rejection, delay or durable terminal result, never
an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: Not fault-injected. Residual risk: Burst protection temporarily
approximate.

## 11. PostgreSQL unavailable

Root cause: Authority inaccessible. Prevention/recovery: No ownership reply; 503 and same-key recovery.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: databasePoolExhaustionFailsBoundedly covers acquisition failure
only. Residual risk: HA failover durability must be proven.

## 12. Kafka unavailable

Root cause: Broker or network outage. Prevention/recovery: Outbox retains facts; monitor age/disk; retry publish.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: Not broker-fault tested. Residual risk: Disk growth eventually
limits admission.

## 13. Duplicate reservation command event

Root cause: At-least-once delivery. Prevention/recovery: Baseline accepts Try via HTTP; any future command consumer must
reuse operation identity. Overselling/integrity: supported atomic transactions preserve counters and ownership; external
or unsupported protocols require the additional controls described. Customer impact: rejection, delay or durable
terminal result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag
and reconciliation evidence relevant to this cause. Automated evidence: inboxDeduplicatesAndRejectsStaleProjection tests
facts only. Residual risk: ReservationRequested command consumer not implemented.

## 14. Out-of-order events

Root cause: Parallel publication/replay. Prevention/recovery: Apply increasing aggregate versions; projections are
nonauthoritative. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or
unsupported protocols require the additional controls described. Customer impact: rejection, delay or durable terminal
result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and
reconciliation evidence relevant to this cause. Automated evidence: inboxDeduplicatesAndRejectsStaleProjection. Residual
risk: Intermediate projection versions may be skipped.

## 15. Payment pending at expiry

Root cause: External latency exceeds TTL. Prevention/recovery: Expire hold; void authorization/refund; never resurrect.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: staleExpiryCannotReleaseNewHold; simulator checkout test. Residual
risk: External PSP saga not implemented.

## 16. Confirm/Cancel race

Root cause: Competing terminal decisions. Prevention/recovery: Reservation row lock; first valid terminal decision wins.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: confirmCancelRace. Residual risk: Caller must inspect returned
terminal state.

## 17. Double expiration

Root cause: Duplicate scan/delivery. Prevention/recovery: Only RESERVED transitions release once. Overselling/integrity:
supported atomic transactions preserve counters and ownership; external or unsupported protocols require the additional
controls described. Customer impact: rejection, delay or durable terminal result, never an inferred stock grant.
Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation evidence relevant to this
cause. Automated evidence: staleExpiryCannotReleaseNewHold. Residual risk: Worker backlog can delay reclamation.

## 18. Old worker sees newer hold

Root cause: Stale expiry task. Prevention/recovery: Reservation UUID+state+version fence; no counter-only release.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: staleExpiryCannotReleaseNewHold. Residual risk: UUID reuse
prohibited.

## 19. Confirm response lost

Root cause: Commit acknowledgement lost. Prevention/recovery: Repeat returns current CONFIRMED; no new increment.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: reconciliationAndSimulatedCheckout. Residual risk: Network recovery
required for certainty.

## 20. Persistence without publication

Root cause: Crash between commits. Prevention/recovery: Outbox saved in same transaction, publisher resumes.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: Outbox asserted through persisted database; crash injection
pending. Residual risk: At-least-once delivery.

## 21. Publisher sends twice

Root cause: Crash after broker ack before DB mark. Prevention/recovery: Inbox event unique ID and version guard.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: inboxDeduplicatesAndRejectsStaleProjection. Residual risk: External
consumers must implement equivalent dedup.

## 22. Reconciler crash

Root cause: Worker interruption mid-batch. Prevention/recovery: Task transaction rollback or complete; re-audit oldest
pools. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported
protocols require the additional controls described. Customer impact: rejection, delay or durable terminal result, never
an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: reconciliationAndSimulatedCheckout; crash injection pending.
Residual risk: Manual-review resolution required.

## 23. Redis/DB count mismatch

Root cause: Cache loss/staleness. Prevention/recovery: No Redis stock counter exists; audit PG reservations and pools.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: reconciliationAndSimulatedCheckout. Residual risk: Admission counts
intentionally differ from reservations.

## 24. Overlapping token partitions

Root cause: Duplicate ownership ranges. Prevention/recovery: Tokens disabled; future allocation must fence durable
disjoint capacities. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or
unsupported protocols require the additional controls described. Customer impact: rejection, delay or durable terminal
result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and
reconciliation evidence relevant to this cause. Automated evidence: Not applicable to baseline. Residual risk:
Preallocation extension unverified.

## 25. Token owner crash

Root cause: Ambiguous consumed capacity. Prevention/recovery: Tokens disabled; fence owner and recover durable log
before reclaim. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or
unsupported protocols require the additional controls described. Customer impact: rejection, delay or durable terminal
result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and
reconciliation evidence relevant to this cause. Automated evidence: Not applicable to baseline. Residual risk: Unused
capacity may remain unavailable.

## 26. Replenishment during holds

Root cause: Concurrent warehouse adjustment. Prevention/recovery: Atomic stock update; sale capacity changes only via
DRAFT allocation. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or
unsupported protocols require the additional controls described. Customer impact: rejection, delay or durable terminal
result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and
reconciliation evidence relevant to this cause. Automated evidence: adjustmentCannotStealAllocatedStock covers
reduction; replenishment race pending. Residual risk: Active sale capacity does not autoexpand.

## 27. Stock reduction below holds

Root cause: Damage/negative adjustment. Prevention/recovery: Refuse reduction below total encumbered allocation.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: adjustmentCannotStealAllocatedStock. Residual risk: Unclosed
dedicated capacity and unshipped commitments still block reductions; explicit terminal-pool closure returns unused
capacity.

## 28. Pause under load

Root cause: Concurrent metadata transition. Prevention/recovery: Sale SHARE/UPDATE barrier; existing holds honored.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: pauseRejectsAndExistingHoldsCanConfirm. Residual risk: Already
admitted transactions can finish before pause commits.

## 29. Sale ends with holds

Root cause: Sale window closes. Prevention/recovery: No new Try; existing TTL applies; scheduled end completion.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: pauseRejectsAndExistingHoldsCanConfirm covers equivalent policy.
Residual risk: TTL may extend beyond sale end.

## 30. Concurrent per-user excess

Root cause: Many keys or allocations. Prevention/recovery: Atomic customer_usage predicate per sale/customer.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: perCustomerLimit (sequential); concurrent cross-pool extension
pending. Residual risk: Identity farming requires fraud controls.

## 31. Redis hot key

Root cause: One hash slot overloaded. Prevention/recovery: Reject fast; partition by sales; measure script latency.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: redisAdmissionIsBounded. Residual risk: One sale remains one
primary execution lane.

## 32. Kafka hot partition

Root cause: Skewed aggregate key. Prevention/recovery: Fact events key by reservation UUID, not stock command; no stock
ordering reliance. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or
unsupported protocols require the additional controls described. Customer impact: rejection, delay or durable terminal
result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and
reconciliation evidence relevant to this cause. Automated evidence: inboxDeduplicatesAndRejectsStaleProjection. Residual
risk: Large aggregates still need lag monitoring.

## 33. Connection pool exhaustion

Root cause: Too much admitted work. Prevention/recovery: 16 connections, bounded wait, controlled 503.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: databasePoolExhaustionFailsBoundedly. Residual risk: Workers share
pool; reserve headroom.

## 34. Retry storm

Root cause: Synchronized recovery retries. Prevention/recovery: Gateway shedding and bounded exponential jitter with
same key. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported
protocols require the additional controls described. Customer impact: rejection, delay or durable terminal result, never
an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: Load script only; chaos unexecuted. Residual risk: Malicious
clients can ignore backoff.

## 35. Pod termination during Try

Root cause: Process killed during commit. Prevention/recovery: PostgreSQL rollback or durable commit; same-key recovery.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: rollbackLeavesNoPartialIdempotency covers rollback, SIGKILL
pending. Residual risk: Graceful timeout cannot resolve every ambiguous response.

## 36. Database deadlock

Root cause: Conflicting lock order. Prevention/recovery: Abort atomically and 503; bounded same-key retry.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: No deliberate deadlock injection. Residual risk: Operational lock
changes need review.

## 37. Worker network partition

Root cause: Worker loses database reachability. Prevention/recovery: No mutation without primary transaction; stale
expiry is fenced. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or
unsupported protocols require the additional controls described. Customer impact: rejection, delay or durable terminal
result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and
reconciliation evidence relevant to this cause. Automated evidence: staleExpiryCannotReleaseNewHold covers stale task.
Residual risk: Partition/failover fencing is infrastructure responsibility.

## 38. Cross-region competing requests

Root cause: Multiple ownership regions. Prevention/recovery: Route one pool to one home PG primary; fail closed without
owner. Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported
protocols require the additional controls described. Customer impact: rejection, delay or durable terminal result, never
an inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: Not deployed/tested. Residual risk: No active-active writes
supported.

## 39. Mixed schema versions

Root cause: Rolling incompatible writers. Prevention/recovery: Expand/contract schema; schemaVersion gate + DLQ.
Overselling/integrity: supported atomic transactions preserve counters and ownership; external or unsupported protocols
require the additional controls described. Customer impact: rejection, delay or durable terminal result, never an
inferred stock grant. Detection: inspect reservation/pool state, dependency errors, outbox lag and reconciliation
evidence relevant to this cause. Automated evidence: Inbox schema validation in source; mixed-version test pending.
Residual risk: Deployment coordination required.

## 40. Order without authoritative allocation

Root cause: External saga or manual corruption. Prevention/recovery: Freeze fulfillment, compare reservation ID/status,
manual review and compensation. Overselling/integrity: supported atomic transactions preserve counters and ownership;
external or unsupported protocols require the additional controls described. Customer impact: rejection, delay or
durable terminal result, never an inferred stock grant. Detection: inspect reservation/pool state, dependency errors,
outbox lag and reconciliation evidence relevant to this cause. Automated evidence: Not implemented external order scan.
Residual risk: No external Order Service connector.

## Additional implemented stock-lifecycle failures

Duplicate dispatch across instances: reservation lock, unique reservation/shipment identity and a single transaction
prevent a second warehouse decrement. Different payload/reference conflicts return 409. Close racing
confirmation/cancellation/expiry: zero-held requirement and pool serialization either reject close or preserve the new
committed quantity. Dispatch racing close: F and L represent disjoint units and both decrement warehouse encumbrance
once. Negative adjustments racing either operation cannot reduce sellable below protected allocation. Accounting
divergence during close returns 409 instead of releasing stock; audits persist MANUAL_REVIEW. PostgreSQL tests exercise
these outcomes and assert persisted counters/identity/ledger. Carrier ambiguity, partial dispatch and returns still
require distinct external protocols.
