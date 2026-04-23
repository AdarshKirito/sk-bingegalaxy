// Soak test: 15 minutes at steady load to surface connection-pool leaks,
// memory creep, and Kafka consumer-lag regressions that only show up over time.
//
// Watch during the run:
//   - hikaricp_connections_acquire_seconds (HikariPoolExhausted alert)
//   - jvm_memory_used_bytes (heap trend)
//   - kafka_consumer_fetch_manager_records_lag_max
//
// Usage:
//   k6 run --env BASE_URL=http://localhost:8080 --env AUTH_TOKEN=... soak-bookings.js

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';
const BINGE_ID = __ENV.BINGE_ID || '1';

export const options = {
    vus: 10,
    duration: '15m',
    thresholds: {
        http_req_failed: ['rate<0.02'],
        http_req_duration: ['p(95)<1000', 'p(99)<2500'],
        checks: ['rate>0.98'],
    },
};

const headers = {
    'Content-Type': 'application/json',
    'X-Binge-Id': BINGE_ID,
    ...(AUTH_TOKEN ? { Authorization: `Bearer ${AUTH_TOKEN}` } : {}),
};

export default function () {
    const today = new Date().toISOString().slice(0, 10);

    const slots = http.get(
        `${BASE_URL}/api/v1/bookings/booked-slots?date=${today}`,
        { headers }
    );
    check(slots, { 'booked-slots ok': (r) => r.status === 200 });

    if (AUTH_TOKEN) {
        const mine = http.get(`${BASE_URL}/api/v1/bookings/my`, { headers });
        check(mine, { 'my bookings ok': (r) => r.status === 200 });

        const pays = http.get(`${BASE_URL}/api/v1/payments/my`, { headers });
        check(pays, { 'my payments ok': (r) => r.status === 200 });
    }

    // Moderate think time — simulate a real user browsing.
    sleep(Math.random() * 2 + 1);
}
