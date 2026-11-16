# API examples

The complete method/path/body/status contract is docs/openapi.json. Administrative create operations return
`{ "id": "UUID" }`. Read resource fields use database snake_case; reservation DTO fields use camelCase.

```http
POST /api/v1/reservations/try
Authorization: Bearer <CUSTOMER token>
Idempotency-Key: purchase-00001
Content-Type: application/json

{"saleId":"<sale UUID>","allocationId":"<allocation UUID>","quantity":1}
```

```json
{
  "id": "<reservation UUID>",
  "saleId": "<sale UUID>",
  "allocationId": "<allocation UUID>",
  "customerId": "<authenticated subject>",
  "quantity": 1,
  "status": "RESERVED",
  "reason": null,
  "expiresAt": "2026-11-15T16:00:30Z",
  "version": 0
}
```

Try business rejection also returns 200 with FAILED and reason SALE_INACTIVE, QUANTITY_LIMIT, CUSTOMER_LIMIT or
SOLD_OUT. It is a durable result for that key. Confirm and Cancel return the current Reservation DTO: inspect status.
GET after expiry returns EXPIRED; repeated Confirm does not create another hold. Fingerprint mismatch returns 409
Problem Details. Invalid quantity returns 400; unauthenticated 401; unauthorized admin mutation 403; not-owned
reservation 404; admission 429; dependency uncertainty 503. Security errors may use standard authentication challenges
rather than controller Problem Details.

```http
POST /api/v1/reservations/<reservation UUID>/confirm
Authorization: Bearer <owner token>
```

For empty rollback use cancel-try with original Try body and key. Confirm/Cancel need no separate key because
reservation UUID identifies their durable state transition. Checkout returns
`{ "reservation": {...}, "checkout": "ORDER_CONFIRMED", "payment": "SIMULATED" }` or AUTHORIZATION_VOIDED.

```http
POST /api/v1/inventory/<sku UUID>/adjustments
Authorization: Bearer <ADMIN token>
Idempotency-Key: receipt-20261115-01
Content-Type: application/json

{"warehouseId":"<warehouse UUID>","delta":100,"reason":"supplier receipt"}
```

Adjustment deduplication scope is SKU, warehouse, authenticated administrator and key; mismatched delta/reason
conflicts. Create product/SKU/warehouse/inventory/sale/allocation operations are non-idempotent and must not be blindly
retried after ambiguous responses. Resolve state through reads. The UI create-stocked-sale workflow is a sequence of
admin API calls; partial setup can leave a draft catalog or sale, which is safe but may require cleanup.

Lists /flash-sales and /customers/me/reservations support offset 0..100000, limit fixed at 100. Availability and
inventory reads cap at 100 rows, reconciliation returns newest 100 audits, metrics aggregate reservation states per
selected sale. These capped views are intended for the demo/operator baseline; cursor pagination across very large
warehouse sets is future work. Gateway must throttle every endpoint; implemented Redis quota applies to Try and is
100/s/sale, 3/s/customer, with fixed-window boundary bursts documented in redis-atomicity.md.

Stock lifecycle APIs: see stock-pool-lifecycle.md for full request/response and idempotency policy. ADMIN POST
`/admin/reservations/{id}/fulfill` takes `{"shipmentReference":"shipment-20261115-0001"}` and derives quantity from
confirmed stock. ADMIN POST `/admin/allocations/{id}/close` has no body. Owner/ADMIN GET`/reservations/{id}/fulfillment`
returns the dispatch fact. Both writes are durable/idempotent by business identity, audited, and return 409 for unsafe
state/reference conflicts. Closed allocations report zero sale availability. Audit runs now check up to 100 pools plus
100 warehouse/SKU rows.
