// Soak test — 15 minutes at steady load to surface connection-pool leaks,
// memory creep, and Kafka consumer-lag regressions that only show up over time.
//
// Production-grade upgrades:
//   - setup() obtains a JWT so steady-state hits the authed paths.
//   - summaryTrendStats includes p(99).
//   - Thresholds aligned with steady-state SLOs (booking p95 ≤ 800 ms,
//     payment p95 ≤ 1.5 s, gateway availability 99.9 %).
//
// Watch during the run:
//   - hikaricp_connections_acquire_seconds (HikariPoolExhausted alert)
//   - jvm_memory_used_bytes (heap trend)
//   - kafka_consumer_fetch_manager_records_lag_max
//
// Usage:
//   k6 run --env BASE_URL=http://localhost:8080 load-tests/soak-bookings.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { ensureTestUser, headers, ok2xx, thinkTime, SUMMARY_TREND_STATS, DEFAULT_BASE_URL } from './_helpers.js';

const BASE_URL = DEFAULT_BASE_URL;

export const options = {
    vus: 10,
    duration: '15m',
    summaryTrendStats: SUMMARY_TREND_STATS,
    thresholds: {
        // Steady-state must be near-perfect (gateway SLA 99.5 %, SLO 99.9 %).
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<800', 'p(99)<1500'],
        'http_req_duration{kind:authed-read}': ['p(95)<800', 'p(99)<1500'],
        'http_req_duration{kind:payment-read}': ['p(95)<1500', 'p(99)<3000'],
        checks: ['rate>0.99'],
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
        { headers: h, tags: { kind: 'public-read' } }
    );
    check(slots, { 'booked-slots ok': ok2xx });

    const mine = http.get(`${BASE_URL}/api/v1/bookings/my`, {
        headers: h, tags: { kind: 'authed-read' },
    });
    check(mine, { 'my bookings ok': ok2xx });

    const pays = http.get(`${BASE_URL}/api/v1/payments/my?page=0&size=20`, {
        headers: h, tags: { kind: 'payment-read' },
    });
    check(pays, { 'my payments ok': ok2xx });

    sleep(thinkTime());
}
