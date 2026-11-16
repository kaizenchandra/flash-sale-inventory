#!/usr/bin/env python3
"""Local demo HTTP smoke for protected pool closure, dispatch and safe reallocation."""
import base64
import datetime
import json
import os
import time
import urllib.error
import urllib.request
import uuid

BASE = os.getenv('BASE_URL', 'http://localhost:8080')


def api(path, body=None, customer=False, key=None, expected=200):
    credentials = 'customer:customer-local' if customer else 'admin:admin-local'
    headers = {'Authorization': 'Basic ' + base64.b64encode(credentials.encode()).decode(),
               'Content-Type': 'application/json'}
    if key:
        headers['Idempotency-Key'] = key
    request = urllib.request.Request(BASE + '/api/v1' + path, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            assert response.status == expected
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code != expected:
            raise
        return json.load(error) if error.headers.get_content_type() == 'application/problem+json' else {}


for attempt in range(60):
    try:
        with urllib.request.urlopen(BASE + '/actuator/health/readiness', timeout=2) as response:
            assert json.load(response)['status'] == 'UP'
        break
    except Exception:
        if attempt == 59:
            raise
        time.sleep(1)

product = api('/admin/products', {'name': 'Dispatch smoke'})['id']
sku = api('/admin/skus', {'productId': product, 'name': 'Dispatch SKU'})['id']
warehouse = api('/admin/warehouses', {'name': 'Dispatch warehouse'})['id']
api('/admin/inventory', {'skuId': sku, 'warehouseId': warehouse, 'physical': 10, 'sellable': 10})


def sale(name):
    now = datetime.datetime.now(datetime.timezone.utc)
    return api('/flash-sales', {'name': name, 'startsAt': (now - datetime.timedelta(seconds=10)).isoformat(),
                                'endsAt': (now + datetime.timedelta(hours=1)).isoformat(),
                                'customerLimit': 5, 'maxQuantity': 2, 'ttlSeconds': 300})['id']


sale_id = sale('Pool closure smoke')
pool = api('/flash-sales/' + sale_id + '/allocations', {'skuId': sku, 'warehouseId': warehouse, 'quantity': 5})['id']
api('/flash-sales/' + sale_id + '/activate', {})
key = str(uuid.uuid4())
command = {'saleId': sale_id, 'allocationId': pool, 'quantity': 2}
order = api('/reservations/try', command, customer=True, key=key)
assert order['status'] == 'RESERVED'
api('/admin/allocations/' + pool + '/close', {}, expected=409)  # Still ACTIVE.
assert api('/reservations/' + order['id'] + '/checkout', {}, customer=True)['checkout'] == 'ORDER_CONFIRMED'
hold = api('/reservations/try', dict(command, quantity=1), customer=True, key=str(uuid.uuid4()))
assert hold['status'] == 'RESERVED'
api('/flash-sales/' + sale_id + '/complete', {})
api('/admin/allocations/' + pool + '/close', {}, expected=409)  # Active hold must drain.
api('/reservations/' + hold['id'] + '/cancel', {}, customer=True)
closed = api('/admin/allocations/' + pool + '/close', {})
assert (closed['available'], closed['released'], closed['encumbered']) == (0, 3, 2)
assert api('/admin/allocations/' + pool + '/close', {}) == closed
api('/inventory/' + sku + '/adjustments', {'warehouseId': warehouse, 'delta': -9, 'reason': 'damage'},
    key=str(uuid.uuid4()), expected=409)
# The unshipped confirmed units are still unavailable to another sale.
next_sale = sale('Reused capacity')
next_pool = api('/flash-sales/' + next_sale + '/allocations',
                {'skuId': sku, 'warehouseId': warehouse, 'quantity': 8})['id']
api('/flash-sales/' + sale('Excess capacity') + '/allocations',
    {'skuId': sku, 'warehouseId': warehouse, 'quantity': 1}, expected=409)
shipment = {'shipmentReference': 'shipment-' + str(uuid.uuid4())}
api('/admin/reservations/' + order['id'] + '/fulfill', shipment, customer=True, expected=403)
fulfilled = api('/admin/reservations/' + order['id'] + '/fulfill', shipment)
assert fulfilled['quantity'] == 2
assert api('/admin/reservations/' + order['id'] + '/fulfill', shipment) == fulfilled
api('/admin/reservations/' + order['id'] + '/fulfill',
    {'shipmentReference': 'different-' + str(uuid.uuid4())}, expected=409)
assert api('/reservations/' + order['id'] + '/fulfillment', customer=True) == fulfilled
assert api('/reservations/try', command, customer=True, key=key)['status'] == 'CONFIRMED'
stock = api('/inventory/' + sku)[0]
assert (stock['physical'], stock['sellable'], stock['allocated'], stock['fulfilled']) == (8, 8, 8, 2)
# Explicitly cancel/close the replacement draft pool, returning its unused eight units.
api('/flash-sales/' + next_sale + '/cancel', {})
api('/admin/allocations/' + next_pool + '/close', {})
stock = api('/inventory/' + sku)[0]
assert (stock['physical'], stock['sellable'], stock['allocated'], stock['fulfilled']) == (8, 8, 0, 2)
final_pool = api('/admin/allocations/' + pool)
assert (final_pool['capacity'], final_pool['committed'], final_pool['fulfilled'],
        final_pool['released'], final_pool['encumbered'], final_pool['available']) == (5, 2, 2, 3, 0, 0)
api('/admin/inventory/reconciliation/run', {})
audits = api('/admin/inventory/reconciliation')
assert next(a for a in audits if a['allocation_id'] == pool)['state'] == 'CONSISTENT'
assert next(a for a in audits if a['sku_id'] == sku and a['warehouse_id'] == warehouse)['state'] == 'CONSISTENT'
print(json.dumps({'pool': final_pool, 'warehouse': stock, 'fulfillment': fulfilled,
                  'same_key_after_dispatch': 'CONFIRMED', 'reconciliation': 'CONSISTENT'}, indent=2))
