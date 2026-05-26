// Spike test — sudden burst of read-heavy traffic to the booking service.
// Validates that HPA + rate limiters keep p95 under SLO and no requests
// are silently dropped.
//
// Production-grade upgrades:
//   - setup() obtains a JWT so authed reads (/bookings/my) are exercised.
//   - summaryTrendStats includes p(99).
//   - Thresholds aligned with launch-readiness-report.md (booking p95 ≤ 800ms);
//     spike loosens to ≤ 1500 ms because we deliberately overload.
//   - Tags split traffic into public-read / authed-read so failures are
//     bucketable in the JSON export.
//
// Usage:
//   k6 run --env BASE_URL=http://localhost:8080 load-tests/spike.js

import http from 'k6/http';
import { check } from 'k6';
import { ensureTestUser, headers, ok2xx429, SUMMARY_TREND_STATS, DEFAULT_BASE_URL } from './_helpers.js';

const BASE_URL = DEFAULT_BASE_URL;

export const options = {
    stages: [
        { duration: '30s', target: 1 },     // warm-up
        { duration: '30s', target: 100 },   // spike
        { duration: '1m',  target: 100 },   // sustain
        { duration: '30s', target: 1 },     // ramp down
    ],
    summaryTrendStats: SUMMARY_TREND_STATS,
    thresholds: {
        // Under spike we accept 5 % failures (mostly 429s from gateway limiter).
        http_req_failed: ['rate<0.05'],
        // p95 ≤ 1.5 s under spike (vs ≤ 800ms steady-state SLO); p99 ≤ 3 s.
        http_req_duration: ['p(95)<1500', 'p(99)<3000'],
        // Per-bucket: authed reads should still be quick because the gateway
        // limiter buckets per-client.
        'http_req_duration{kind:authed-read}': ['p(95)<1200'],
        checks: ['rate>0.95'],
    },
};

export function setup() {
    return ensureTestUser(BASE_URL);
}

export default function (data) {
    const today = new Date().toISOString().slice(0, 10);
    const h = headers(data.token);

    const slots = http.get(
        `${BASE_URL}/api/v1/bookings/booked-slots?date=${today}`,
        { headers: h, tags: { kind: 'public-read', name: 'GET /booked-slots' } }
    );
    check(slots, { 'booked-slots 2xx/429': ok2xx429 });

    const events = http.get(
        `${BASE_URL}/api/v1/bookings/event-types`,
        { headers: h, tags: { kind: 'public-read', name: 'GET /event-types' } }
    );
    check(events, { 'event-types 2xx/429': ok2xx429 });

    const mine = http.get(
        `${BASE_URL}/api/v1/bookings/my`,
        { headers: h, tags: { kind: 'authed-read', name: 'GET /bookings/my' } }
    );
    check(mine, { 'my bookings 2xx/429': ok2xx429 });

    // No sleep — spike profile.
}
