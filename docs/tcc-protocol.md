# TCC protocol

Try: `POST /api/v1/reservations/try`, authenticated CUSTOMER, Idempotency-Key and sale/allocation/quantity. Key scope is
`(authenticated subject, sale, logical-operation key)`; fingerprint is allocation UUID + canonical integer quantity.
Operation identity deliberately spans Try and its empty cancellation fence. Database unique constraints serialize pods
and persist original reservation reference; mismatch gives 409.

Confirm/Cancel use the server-generated reservation UUID; status is the durable idempotency decision. Lost responses are
recovered by same-key Try or GET. Ordinary cancellation cannot cancel an unknown server-generated UUID and returns 404.
If cancellation arrives before Try completed, use `POST /api/v1/reservations/cancel-try` with the **same key and
payload**. It persists CANCELLED, or cancels the existing hold under the same idempotency lock. A later Try returns that
tombstone. Matching payload is required; callers must retain it. Keep the sale record/tombstone while late operations
remain possible.

No distributed locks are relied on. Terminal serialization is a PostgreSQL row lock plus version predicate, and each
release updates counters, usage, ledger and event in one transaction. TCC does not atomically include external payment
systems.
