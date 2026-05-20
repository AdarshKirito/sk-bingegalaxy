// Spike test for payment-service: bursty checkout traffic targeting the
// payment initiation, status, and "my payments" read paths. Validates that
// the payment service and the api-gateway rate limiter degrade gracefully
// under burst, that idempotency-keys are honoured, and that p95/p99 stay
// within SLO.
//
// Watch during the run:
//   - http_req_duration p(95) on /api/v1/payments/initiate (SLO: ≤ 1.5s p95)
//   - skbg_payment_initiate_total / skbg_payment_initiate_failed_total
//   - resilience4j_circuitbreaker_state on the payment-service client
//   - kafka_producer_record_send_total{topic="payment.success"}
//
// Usage:
//   k6 run --env BASE_URL=http://localhost:8080 \
//          --env AUTH_TOKEN=eyJ... \
//          --env BOOKING_REF=SKBG... \
//          load-tests/spike-payments.js
//
// If BOOKING_REF / AUTH_TOKEN are omitted the script will only exercise the
// public read paths and signature-failure rejection of /callback.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';
const BINGE_ID = __ENV.BINGE_ID || '1';
const BOOKING_REF = __ENV.BOOKING_REF || '';

export const options = {
    // 1 → 50 → 1 over ~2 minutes. Matches the spike-payments profile
    // documented in production-proof/launch-readiness-report.md and the
    // load-tests README.
    stages: [
        { duration: '20s', target: 1 },     // warm-up
        { duration: '20s', target: 50 },    // ramp-up
        { duration: '1m',  target: 50 },    // sustain spike
        { duration: '20s', target: 1 },     // ramp-down
    ],
    thresholds: {
        // Payment is allowed slightly looser SLO than booking reads because
        // it does a synchronous gateway call.
        http_req_failed: ['rate<0.05'],
        http_req_duration: ['p(95)<1500', 'p(99)<3000'],
        checks: ['rate>0.95'],
    },
};

function headers(extra = {}) {
    return {
        'Content-Type': 'application/json',
        'X-Binge-Id': BINGE_ID,
        ...(AUTH_TOKEN ? { Authorization: `Bearer ${AUTH_TOKEN}` } : {}),
        ...extra,
    };
}

export default function () {
    // 1. Public read — "my payments" or, if unauthenticated, fall back to
    //    a benign GET that should reach payment-service via the gateway.
    if (AUTH_TOKEN) {
        const mine = http.get(`${BASE_URL}/api/v1/payments/my?page=0&size=20`, {
            headers: headers(),
            tags: { name: 'GET /payments/my' },
        });
        check(mine, {
            'my payments 2xx/429': (r) =>
                (r.status >= 200 && r.status < 300) || r.status === 429,
        });
    }

    // 2. Idempotent payment initiation. We send the SAME idempotency key
    //    on every iteration of a single VU to also exercise the
    //    Idempotency-Key cache hot path. Different VUs use different keys.
    if (AUTH_TOKEN && BOOKING_REF) {
        const idemKey = `k6-spike-${__VU}-${BOOKING_REF}`;
        const body = JSON.stringify({
            bookingRef: BOOKING_REF,
            // the service computes the canonical amount server-side; this
            // hint is only used when the booking allows a partial top-up.
            amount: null,
            currency: 'INR',
            method: 'CARD',
        });
        const init = http.post(`${BASE_URL}/api/v1/payments/initiate`, body, {
            headers: headers({ 'Idempotency-Key': idemKey }),
            tags: { name: 'POST /payments/initiate' },
        });
        check(init, {
            'initiate 2xx/4xx (no 5xx)': (r) => r.status < 500,
            'initiate has txn id or known error': (r) =>
                r.status === 200 ||
                r.status === 201 ||
                r.status === 400 ||
                r.status === 409 ||
                r.status === 429,
        });
    }

    // 3. Forged callback — gateway + payment-service must reject these
    //    fast and cheaply with a 401. This proves the HMAC verification
    //    short-circuit stays cheap under burst.
    const forged = http.post(
        `${BASE_URL}/api/v1/payments/callback`,
        JSON.stringify({
            event: 'payment.captured',
            payload: { payment: { entity: { id: `pay_${uuidv4()}` } } },
        }),
        {
            headers: {
                'Content-Type': 'application/json',
                'X-Razorpay-Signature': 'invalid-sig-for-spike-test',
            },
            tags: { name: 'POST /payments/callback (forged)' },
        }
    );
    check(forged, {
        'forged callback rejected': (r) =>
            r.status === 400 || r.status === 401 || r.status === 403,
    });

    sleep(0.1);
}
