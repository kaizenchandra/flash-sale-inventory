import http from 'k6/http';
import {Counter, Rate} from 'k6/metrics';

const success = new Counter('reserved');
const admitted = new Counter('authoritative_results');
const errors = new Rate('unexpected_errors');
const rejected = new Counter('rejected_429');
const unavailable = new Counter('unavailable_503');
const network = new Counter('network_errors');
const failed = new Counter('business_failed');
const rate = Number(__ENV.RATE || 1000);
export const options = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
    scenarios: {
        burst: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1s',
            duration: __ENV.DURATION || '30s',
            preAllocatedVUs: Number(__ENV.VUS || 100),
            maxVUs: Number(__ENV.MAX_VUS || 1000)
        }
    },
    thresholds: {unexpected_errors: ['rate<0.01']}
};
export default function () {
    const key = `load-${__VU}-${__ITER}-${Date.now()}`;
    const r = http.post(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/reservations/try`, JSON.stringify({
        saleId: __ENV.SALE_ID,
        allocationId: __ENV.ALLOCATION_ID,
        quantity: 1
    }), {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': (__ENV.AUTH_HEADER || `Bearer ${__ENV.TOKEN}`),
            'Idempotency-Key': key
        }, timeout: '5s'
    });
    if (r.status === 429) rejected.add(1);
    if (r.status === 503) unavailable.add(1);
    if (r.status === 0) network.add(1);
    errors.add(![200, 429, 503].includes(r.status));
    if (r.status === 200) {
        admitted.add(1);
        if (r.json('status') === 'RESERVED') success.add(1); else failed.add(1);
    }
}
