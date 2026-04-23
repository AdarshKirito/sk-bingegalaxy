// Spike test: sudden burst of read-heavy traffic to the booking service.
// Validates that HPA + rate limiters keep 95th-percentile response times under
// control and no requests get silently dropped.
//
// Usage:
//   k6 run --env BASE_URL=http://localhost:8080 --env AUTH_TOKEN=... spike.js

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';
const BINGE_ID = __ENV.BINGE_ID || '1';

export const options = {
    stages: [
        { duration: '30s', target: 1 },     // warm-up
        { duration: '30s', target: 100 },   // spike
        { duration: '1m',  target: 100 },   // sustain
        { duration: '30s', target: 1 },     // ramp down
    ],
    thresholds: {
        http_req_failed: ['rate<0.05'],
        http_req_duration: ['p(95)<1500', 'p(99)<3000'],
        checks: ['rate>0.95'],
    },
};

const headers = {
    'Content-Type': 'application/json',
    'X-Binge-Id': BINGE_ID,
    ...(AUTH_TOKEN ? { Authorization: `Bearer ${AUTH_TOKEN}` } : {}),
};

export default function () {
    const today = new Date().toISOString().slice(0, 10);

    const res1 = http.get(
        `${BASE_URL}/api/v1/bookings/booked-slots?date=${today}`,
        { headers }
    );
    check(res1, {
        'booked-slots 2xx/429': (r) => (r.status >= 200 && r.status < 300) || r.status === 429,
    });

    const res2 = http.get(`${BASE_URL}/api/v1/bookings/event-types`, { headers });
    check(res2, {
        'event-types 2xx/429': (r) => (r.status >= 200 && r.status < 300) || r.status === 429,
    });

    // Keep the arrival rate high — no sleep.
}
