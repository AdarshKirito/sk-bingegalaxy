// Shared helpers for the k6 load-test suite.
//
// All scripts use this module so behaviour is consistent across tests:
//   - login() acquires a real JWT against /api/v1/auth/register or /login.
//   - summaryTrendStats includes p(99) so the JSON export carries it.
//   - Standard tags let us bucket failures by category (public vs authed
//     vs forged) without polluting http_req_failed for expected 401s.
//
// k6 is single-process; setup() runs once per test, default() per VU iter.

import http from 'k6/http';
import { check, fail } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const DEFAULT_BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const DEFAULT_BINGE_ID = __ENV.BINGE_ID || '1';

// p(99) is NOT in the default summaryTrendStats list; without this the
// JSON export only has avg/min/med/p(90)/p(95)/max and we cannot report p99.
export const SUMMARY_TREND_STATS = [
    'avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count',
];

/**
 * Register a fresh user against /api/v1/auth/register and return the JWT.
 *
 * Falls back to /login if the user already exists (so suites can be
 * re-run with the same email). The default endpoint is on the api-gateway
 * (8080) — pass `baseUrl` to override.
 *
 * Returns: { token, refreshToken, email, password, userId }
 */
export function ensureTestUser(baseUrl = DEFAULT_BASE_URL, opts = {}) {
    // If the caller already has a token, use it directly.
    if (__ENV.AUTH_TOKEN) {
        return { token: __ENV.AUTH_TOKEN, email: '<from-env>', password: null };
    }

    const email = opts.email || `k6+${uuidv4()}@loadtest.skbingegalaxy.local`;
    const password = opts.password || 'LoadTest!2026#aA1';
    // The api-gateway CsrfProtectionFilter requires Origin/Referer to match
    // the configured allow-list (CORS_ALLOWED_ORIGINS) for any state-changing
    // request — including the bootstrap auth endpoints. Without these
    // headers register/login are rejected with CSRF_BAD_ORIGIN (403).
    // We deliberately use the same Origin the SPA uses in dev.
    const origin = opts.origin || __ENV.LOADTEST_ORIGIN || 'http://localhost:8080';
    const headers = {
        'Content-Type': 'application/json',
        Origin: origin,
        Referer: `${origin}/`,
    };

    // 1) Try to register. 201 → fresh user, 409 → already exists, fall through.
    const regBody = JSON.stringify({
        firstName: 'Load',
        lastName: 'Test',
        email,
        phone: String(2000000000 + Math.floor(Math.random() * 999999999)).slice(0, 10),
        phoneCountryCode: '+91',
        password,
    });
    const reg = http.post(`${baseUrl}/api/v1/auth/register`, regBody, { headers, tags: { setup: 'register' } });
    if (reg.status === 201 || reg.status === 200) {
        const token = extractToken(reg);
        if (token) return { token, email, password };
    }

    // 2) Fall back to login.
    const login = http.post(
        `${baseUrl}/api/v1/auth/login`,
        JSON.stringify({ email, password }),
        { headers, tags: { setup: 'login' } }
    );
    if (login.status === 200) {
        const token = extractToken(login);
        if (token) return { token, email, password };
    }

    fail(
        `[setup] Could not obtain JWT (register=${reg.status}, login=${login.status}). ` +
        `Body register=${truncate(reg.body)} login=${truncate(login.body)}`
    );
}

function extractToken(res) {
    try {
        const j = res.json();
        // ApiResponse<AuthResponse> wraps payload under .data
        const data = j && j.data ? j.data : j;
        return data && data.token ? data.token : null;
    } catch {
        return null;
    }
}

function truncate(s, n = 200) {
    if (!s) return '';
    s = String(s);
    return s.length > n ? s.slice(0, n) + '…' : s;
}

/**
 * Build a standard headers map. Pass `auth` to add Bearer token.
 */
export function headers(auth, extra = {}) {
    const h = { 'Content-Type': 'application/json', 'X-Binge-Id': DEFAULT_BINGE_ID };
    if (auth) h.Authorization = `Bearer ${auth}`;
    return Object.assign(h, extra);
}

/**
 * Standard check: 2xx is "ok", 429 is acceptable backpressure under spike.
 * Use for traffic that should succeed end-to-end.
 */
export const ok2xx429 = (r) => (r.status >= 200 && r.status < 300) || r.status === 429;

/** Standard check: status is 2xx (no tolerance for 429). */
export const ok2xx = (r) => r.status >= 200 && r.status < 300;

/** Reusable random-think-time helper (uniform 0.5..1.5s). */
export function thinkTime() {
    return 0.5 + Math.random();
}
