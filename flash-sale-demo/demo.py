#!/usr/bin/env python3
"""Creates real catalog, warehouse inventory, allocation and active sale via REST."""
import base64
import datetime
import json
import os
import urllib.request
import uuid

BASE = os.getenv('BASE_URL', 'http://localhost:8080') + '/api/v1'


def call(path, body=None, customer=False, key=None):
    auth = base64.b64encode(('customer:customer-local' if customer else 'admin:admin-local').encode()).decode()
    headers = {'Authorization': 'Basic ' + auth, 'Content-Type': 'application/json'}
    if key: headers['Idempotency-Key'] = key
    req = urllib.request.Request(BASE + path, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req, timeout=10) as response: return json.load(response)


p = call('/admin/products', {'name': 'Flash phone'})['id']
s = call('/admin/skus', {'productId': p, 'name': '128GB'})['id']
w = call('/admin/warehouses', {'name': 'Local'})['id']
call('/admin/inventory', {'skuId': s, 'warehouseId': w, 'physical': 10000, 'sellable': 10000})
now = datetime.datetime.now(datetime.timezone.utc)
sale = call('/flash-sales', {'name': 'Phone launch', 'startsAt': (now - datetime.timedelta(seconds=30)).isoformat(),
                             'endsAt': (now + datetime.timedelta(hours=2)).isoformat(), 'customerLimit': 3,
                             'maxQuantity': 1, 'ttlSeconds': 30})['id']
a = call('/flash-sales/' + sale + '/allocations', {'skuId': s, 'warehouseId': w, 'quantity': 10000})['id']
call('/flash-sales/' + sale + '/activate', {})
r = call('/reservations/try', {'saleId': sale, 'allocationId': a, 'quantity': 1}, True, str(uuid.uuid4()))
print(json.dumps({'saleId': sale, 'allocationId': a, 'reservation': r}, indent=2))
print('Open ' + BASE.removesuffix('/api/v1') + ' in your browser. Hold expires in 30 seconds.')
