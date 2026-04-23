// Smoke test: quick CI sanity check that every customer-facing endpoint responds.
// Not a performance test — just verifies routing, auth, and availability.
//
// Usage:
//   k6 run --env BASE_URL=http://localhost:8080 smoke.js
//
// Optional env:
//   AUTH_TOKEN   JWT for authenticated endpoints. Omit to skip authenticated checks.
//   BINGE_ID     Binge id header (default: 1).

import http from 'k6/http';
import { check, group } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';
const BINGE_ID = __ENV.BINGE_ID || '1';

export const options = {
    vus: 2,
    duration: '30s',
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<800'],
        checks: ['rate>0.99'],
    },
};

function authed(extra = {}) {
    return {
        headers: {
            'Content-Type': 'application/json',
            'X-Binge-Id': BINGE_ID,
            ...(AUTH_TOKEN ? { Authorization: `Bearer ${AUTH_TOKEN}` } : {}),
            ...extra,
        },
    };
}

export default function () {
    group('public - health', () => {
        const r = http.get(`${BASE_URL}/actuator/health`);
        check(r, { 'health 200': (x) => x.status === 200 });
    });

    group('public - event types', () => {
        const r = http.get(`${BASE_URL}/api/v1/bookings/event-types`, authed());
        check(r, { 'event-types 200 or 401': (x) => x.status === 200 || x.status === 401 });
    });

    group('public - add-ons', () => {
        const r = http.get(`${BASE_URL}/api/v1/bookings/add-ons`, authed());
        check(r, { 'add-ons 200 or 401': (x) => x.status === 200 || x.status === 401 });
    });

    group('public - booked slots today', () => {
        const today = new Date().toISOString().slice(0, 10);
        const r = http.get(
            `${BASE_URL}/api/v1/bookings/booked-slots?date=${today}`,
            authed()
        );
        check(r, { 'booked-slots 2xx': (x) => x.status >= 200 && x.status < 300 });
    });

    if (AUTH_TOKEN) {
        group('auth - my bookings', () => {
            const r = http.get(`${BASE_URL}/api/v1/bookings/my`, authed());
            check(r, { 'my bookings 200': (x) => x.status === 200 });
        });

        group('auth - my payments', () => {
            const r = http.get(`${BASE_URL}/api/v1/payments/my`, authed());
            check(r, { 'my payments 200': (x) => x.status === 200 });
        });
    }
}
