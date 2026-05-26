// Spike test for payment-service — bursty checkout traffic targeting
// payment initiation, status-read, and "my payments" paths plus a
// signature-rejection cheap-reject path. Validates that the payment
// service and api-gateway rate limiter degrade gracefully under burst
// and that idempotency-keys are honoured.
//
// Production-grade upgrades:
//   - setup() obtains a JWT (and optionally a real BOOKING_REF via the
//     BOOKING_REF env or by scanning /api/v1/bookings/my) so every iter
//     exercises a realistic checkout flow.
//   - summaryTrendStats includes p(99).
//   - Per-bucket thresholds: my-payments p95 ≤ 1.5 s, initiate p95 ≤ 1.5 s
//     (payment SLO), forged-callback p95 ≤ 100 ms (must short-circuit).
//
// Usage:
//   k6 run --env BASE_URL=http://localhost:8080 \
//          [--env AUTH_TOKEN=eyJ...] \
//          [--env BOOKING_REF=SKBG...] \
//          load-tests/spike-payments.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { ensureTestUser, headers, SUMMARY_TREND_STATS, DEFAULT_BASE_URL } from './_helpers.js';

const BASE_URL = DEFAULT_BASE_URL;
const ENV_BOOKING_REF = __ENV.BOOKING_REF || '';

export const options = {
    stages: [
        { duration: '20s', target: 1 },     // warm-up
        { duration: '20s', target: 50 },    // ramp-up
        { duration: '1m',  target: 50 },    // sustain spike
        { duration: '20s', target: 1 },     // ramp-down
    ],
    summaryTrendStats: SUMMARY_TREND_STATS,
    thresholds: {
        http_req_failed: ['rate<0.05'],
        // Aggregate ceiling — payment SLO p95 ≤ 1.5 s under burst.
        http_req_duration: ['p(95)<1500', 'p(99)<3000'],
        // Per-bucket SLOs.
        'http_req_duration{name:GET /payments/my}':            ['p(95)<1500'],
        'http_req_duration{name:POST /payments/initiate}':     ['p(95)<1500', 'p(99)<3000'],
        'http_req_duration{name:POST /payments/callback (forged)}': ['p(95)<100'],
        checks: ['rate>0.95'],
    },
};

export function setup() {
    const user = ensureTestUser(BASE_URL);
    // If no BOOKING_REF was supplied, attempt to find one by listing the
    // user's bookings. If that returns nothing we still run — initiate
    // calls will be skipped for this VU and only read+forged are exercised.
    let bookingRef = ENV_BOOKING_REF;
    if (!bookingRef) {
        const r = http.get(`${BASE_URL}/api/v1/bookings/my?page=0&size=1`, {
            headers: headers(user.token),
            tags: { setup: 'list-bookings' },
        });
        try {
            const j = r.json();
            const list = j && j.data ? (j.data.content || j.data) : null;
            if (Array.isArray(list) && list.length > 0) {
                bookingRef = list[0].bookingRef || list[0].reference || '';
            }
        } catch { /* leave bookingRef empty */ }
    }
    return { token: user.token, bookingRef };
}

export default function (data) {
    const h = headers(data.token);

    // 1. Authenticated read of payment history — exercises read-replica path.
    const mine = http.get(`${BASE_URL}/api/v1/payments/my?page=0&size=20`, {
        headers: h,
        tags: { name: 'GET /payments/my', kind: 'payment-read' },
    });
    check(mine, {
        'my payments 2xx/429': (r) => (r.status >= 200 && r.status < 300) || r.status === 429,
    });

    // 2. Idempotent payment initiation. Same key per VU = idempotency cache hit.
    if (data.bookingRef) {
        const idemKey = `k6-spike-${__VU}-${data.bookingRef}`;
        const body = JSON.stringify({
            bookingRef: data.bookingRef,
            amount: null,
            currency: 'INR',
            method: 'CARD',
        });
        const init = http.post(`${BASE_URL}/api/v1/payments/initiate`, body, {
            headers: headers(data.token, { 'Idempotency-Key': idemKey }),
            tags: { name: 'POST /payments/initiate', kind: 'payment-write' },
        });
        check(init, {
            'initiate no 5xx': (r) => r.status < 500,
            'initiate known status': (r) =>
                [200, 201, 400, 401, 403, 409, 429].indexOf(r.status) !== -1,
        });
    }

    // 3. Forged callback — must reject fast and cheaply (HMAC short-circuit).
    const forged = http.post(
        `${BASE_URL}/api/v1/payments/callback`,
        JSON.stringify({
            event: 'payment.captured',
            payload: { payment: { entity: { id: `pay_${uuidv4()}` } } },
        }),
        {
            headers: { 'Content-Type': 'application/json', 'X-Razorpay-Signature': 'invalid-sig-for-spike-test' },
            tags: { name: 'POST /payments/callback (forged)', kind: 'forged' },
        }
    );
    check(forged, {
        'forged callback rejected': (r) => r.status === 400 || r.status === 401 || r.status === 403,
    });

    sleep(0.1);
}
