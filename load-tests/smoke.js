// Smoke test — quick CI sanity check that every key customer-facing
// endpoint responds correctly with a real JWT.
//
// Production-grade upgrades vs the previous version:
//   - setup() registers (or logs in) a test user and obtains a JWT once,
//     so authenticated paths are actually exercised instead of 401-skipped.
//   - summaryTrendStats includes p(99) so the JSON export carries it.
//   - Thresholds aligned with the launch-readiness-report.md SLOs:
//       gateway availability 99.9 %  →  http_req_failed < 0.01
//       booking p95 ≤ 800ms          →  http_req_duration p(95) < 800
//
// Usage:
//   k6 run --env BASE_URL=http://localhost:8080 load-tests/smoke.js
//
// Optional env:
//   AUTH_TOKEN  reuse an existing JWT instead of registering a fresh user
//   BINGE_ID    X-Binge-Id header (default 1)

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { ensureTestUser, headers, ok2xx, SUMMARY_TREND_STATS, DEFAULT_BASE_URL } from './_helpers.js';

const BASE_URL = DEFAULT_BASE_URL;

export const options = {
    vus: 2,
    duration: '30s',
    summaryTrendStats: SUMMARY_TREND_STATS,
    thresholds: {
        // Smoke is the gate — must be near-perfect.
        http_req_failed: ['rate<0.01'],
        // Booking-service SLO: p95 ≤ 800 ms; p99 ≤ 1500 ms is our internal target.
        http_req_duration: ['p(95)<800', 'p(99)<1500'],
        checks: ['rate>0.99'],
    },
};

export function setup() {
    return ensureTestUser(BASE_URL);
}

export default function (data) {
    const h = headers(data.token);

    group('public - health', () => {
        const r = http.get(`${BASE_URL}/actuator/health`);
        check(r, { 'health 200': ok2xx });
    });

    group('public - event types', () => {
        const r = http.get(`${BASE_URL}/api/v1/bookings/event-types`, { headers: h, tags: { kind: 'public-read' } });
        check(r, { 'event-types 200': ok2xx });
    });

    group('public - add-ons', () => {
        const r = http.get(`${BASE_URL}/api/v1/bookings/add-ons`, { headers: h, tags: { kind: 'public-read' } });
        check(r, { 'add-ons 200': ok2xx });
    });

    group('public - booked slots today', () => {
        const today = new Date().toISOString().slice(0, 10);
        const r = http.get(
            `${BASE_URL}/api/v1/bookings/booked-slots?date=${today}`,
            { headers: h, tags: { kind: 'public-read' } }
        );
        check(r, { 'booked-slots 200': ok2xx });
    });

    group('auth - my bookings', () => {
        const r = http.get(`${BASE_URL}/api/v1/bookings/my`, { headers: h, tags: { kind: 'authed-read' } });
        check(r, { 'my bookings 200': ok2xx });
    });

    group('auth - my payments', () => {
        const r = http.get(`${BASE_URL}/api/v1/payments/my?page=0&size=20`, { headers: h, tags: { kind: 'authed-read' } });
        check(r, { 'my payments 200': ok2xx });
    });

    // Smoke is a sanity check, not a load test. 1 s think-time keeps the
    // request rate well below the gateway's per-IP limiter so we measure
    // real endpoint behaviour, not 429 backpressure.
    sleep(1);
}
