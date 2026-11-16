# Checkout integration

The runnable `/reservations/{id}/checkout` adapter is explicitly a deterministic local simulator. It durably records
PENDING, simulates idempotent authorization in a separate transaction, records AUTHORIZED, confirms inventory, captures
or voids the simulated payment, then records ORDER_CONFIRMED or AUTHORIZATION_VOIDED. Each boundary is a separate
commit. A bounded recovery worker resumes PENDING/AUTHORIZED workflows after a crash; stable reservation/payment keys
and immutable inventory decisions make replay safe. Repeated checkout
is stable. It demonstrates the client flow and expiry compensation without falsely treating a real payment provider as
transactional.

A real Order/PSP integration is a required extension: persist pending order + TRY command in order outbox; persist
reservation reference; send idempotent payment authorization using saga ID; persist authorization response; request
Confirm; then confirm order and capture according to PSP contract. On hold expiry, void authorization; if capture
already happened, refund through a durable compensation record. Ambiguous PSP timeouts require status lookup, never
blind repeat. Payment success after expiry grants no stock. Manual review must detect a completed external order lacking
authoritative CONFIRMED allocation.

Inbox projection accepts inventory facts, deduplicates event IDs and applies only increasing aggregate versions. It is
not an order service or a payment processor. There is no external PSP connector in this baseline.
