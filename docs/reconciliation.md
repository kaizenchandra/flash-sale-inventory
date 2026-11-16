# Reconciliation

Runs every minute, or ADMIN POST `/api/v1/admin/inventory/reconciliation/run`. It audits up to 100 oldest-audited pools
and, in a **separate transaction**, up to 100 warehouse/SKU rows. Both use SKIP LOCKED and persist
CONSISTENT/MANUAL_REVIEW records; `checked` counts both batches (maximum 200). GET returns the newest 100 audit records.
Pool records have allocation_id; warehouse records instead have sku_id and warehouse_id.

Pool audits compare R/K against RESERVED/CONFIRMED reservation quantities, F against durable fulfillment quantities and
FULFILL movements, and L against POOL_CLOSE movements. They also require one closure movement for each closed pool,
including zero-release closures, and reject fulfillment rows whose quantities/status disagree with their reservation.
Historical CONFIRMED rows remain after dispatch and must not be summed as remaining warehouse demand.

Warehouse audits compare A against sum(C-F-L) and cumulative warehouse fulfilled against sum(F). Locking the warehouse
without child locks is important: a concurrent pool/warehouse mutation cannot commit until it has changed its warehouse
row, so an audit observing the old parent also observes the corresponding old child versions through MVCC. Pool and
warehouse locks are never acquired together by reconciliation; this avoids reversing the pool->warehouse mutation lock
order.

Crashing rolls back the current audit batch; later runs re-audit. No automatic counter reset is performed. Normal expiry
safely repairs overdue active holds. Reconciliation records discrepancies and leaves committed ownership intact for
reviewed repair. It does not query external orders or carriers, rebuild a Redis stock mirror (none exists), or prove
initial warehouse receipts from a complete physical ledger. Those remain separate gaps.

```sql
SELECT i.sku_id,
       i.warehouse_id,
       i.allocated,
       COALESCE(sum(a.capacity - a.fulfilled - a.released), 0) AS encumbered
FROM inventory i
         LEFT JOIN inventory_allocations a USING (sku_id, warehouse_id)
GROUP BY i.sku_id, i.warehouse_id
HAVING i.allocated <> COALESCE(sum(a.capacity - a.fulfilled - a.released), 0);
```

Freeze an affected sale before repair. Lock the affected accounting rows in the documented order; verify reservations,
fulfillment identities, movements and external order/dispatch evidence. Preserve before/after evidence and an idempotent
repair ID. Retention/archival remains deployment work; do not purge terminal decisions, closure movements or shipment
references within the replay horizon.
