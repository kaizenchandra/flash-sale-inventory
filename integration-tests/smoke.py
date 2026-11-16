#!/usr/bin/env python3
"""Full REST and optional two-instance smoke. Requires local demo profile."""
import base64
import concurrent.futures
import datetime
import json
import os
import sys
import time
import urllib.request
import uuid

base = os.getenv('BASE_URL', 'http://localhost:8080');
other = os.getenv('SECOND_URL', base)


def call(path, body=None, key=None, admin=False, url=base):
    headers = {'Authorization': 'Basic ' + base64.b64encode(
        ('admin:admin-local' if admin else 'customer:customer-local').encode()).decode(),
               'Content-Type': 'application/json'}
    if key: headers['Idempotency-Key'] = key
    req = urllib.request.Request(url + '/api/v1' + path, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req, timeout=10) as res: return json.load(res)


for url in set([base, other]):
    for attempt in range(60):
        try:
            with urllib.request.urlopen(url + '/actuator/health/readiness', timeout=2) as res:
                assert json.load(res)['status'] == 'UP'
            break
        except Exception:
            if attempt == 59: raise
            time.sleep(1)
p = call('/admin/products', {'name': 'Smoke phone'}, admin=True)['id'];
sku = call('/admin/skus', {'productId': p, 'name': '128GB'}, admin=True)['id'];
w = call('/admin/warehouses', {'name': 'Smoke'}, admin=True)['id']
call('/admin/inventory', {'skuId': sku, 'warehouseId': w, 'physical': 10, 'sellable': 10}, admin=True)
now = datetime.datetime.now(datetime.timezone.utc)
sale = call('/flash-sales', {'name': 'Smoke', 'startsAt': (now - datetime.timedelta(seconds=10)).isoformat(),
                             'endsAt': (now + datetime.timedelta(hours=1)).isoformat(), 'customerLimit': 10,
                             'maxQuantity': 1, 'ttlSeconds': int(os.getenv('SMOKE_TTL', '30'))}, admin=True)['id']
pool = call('/flash-sales/' + sale + '/allocations', {'skuId': sku, 'warehouseId': w, 'quantity': 10}, admin=True)[
    'id'];
call('/flash-sales/' + sale + '/activate', {}, admin=True)
body = {'saleId': sale, 'allocationId': pool, 'quantity': 1};
key = str(uuid.uuid4())
with concurrent.futures.ThreadPoolExecutor(2) as executor:
    a = executor.submit(call, '/reservations/try', body, key, False, base);
    b = executor.submit(call, '/reservations/try', body, key, False, other)
    a, b = a.result(), b.result();
    assert a['id'] == b['id'];
    assert a['status'] == 'RESERVED'
confirmed = call('/reservations/' + a['id'] + '/checkout', {}, url=other);
assert confirmed['checkout'] == 'ORDER_CONFIRMED'
time.sleep(1.1)
b = call('/reservations/try', body, str(uuid.uuid4()));
assert call('/reservations/' + b['id'] + '/cancel', {}, url=other)['status'] == 'CANCELLED'
time.sleep(1.1)
c = call('/reservations/try', body, str(uuid.uuid4()))
for attempt in range(int(os.getenv('SMOKE_TTL', '30')) + 15):
    if call('/reservations/' + c['id'])['status'] == 'EXPIRED': break
    time.sleep(1)
else:
    raise AssertionError('Expiration worker failed')
stock = call('/flash-sales/' + sale + '/availability')[0];
assert stock['reserved'] == 0 and stock['committed'] == 1 and stock['available'] == 9
call('/admin/inventory/reconciliation/run', {}, admin=True)
print(json.dumps(
    {'two_instances': other != base, 'same_reservation': a['id'], 'confirmed': 1, 'cancelled': 1, 'expired': 1,
     'saleId': sale, 'allocationId': pool, 'stock': stock}, indent=2))
