# k6 Load Tests

Performance regression scripts for SK Binge Galaxy's hot paths. Run against a
test environment, never production.

## Profiles

| Script              | Purpose                                    | VUs          | Duration |
|---------------------|--------------------------------------------|--------------|----------|
| `smoke.js`          | Verify all endpoints respond in CI         | 2            | 30 s     |
| `spike.js`          | Sudden surge handling                      | 1 → 100 → 1  | 2 m 30 s |
| `soak-bookings.js`  | Memory / connection-pool leak detection    | 10           | 15 m     |
| `spike-payments.js` | Payment-service resilience under burst     | 1 → 50 → 1   | 2 m      |

## Running

```bash
# Install k6 (https://k6.io/docs/get-started/installation/)
brew install k6                          # macOS
choco install k6                         # Windows
# or: docker run --rm -i grafana/k6 run - < smoke.js

# Run a profile against local docker-compose
k6 run --env BASE_URL=http://localhost:8080 smoke.js

# Run against a deployed test cluster
k6 run --env BASE_URL=https://test.skbingegalaxy.example.com \
       --env AUTH_TOKEN=$TEST_JWT \
       soak-bookings.js
```

## Thresholds

Every script declares `thresholds` so the CI run fails on regression:

- **p95 latency**: varies per endpoint (200 ms to 800 ms)
- **error rate**: `< 1%` for smoke, `< 5%` for spike/soak
- **checks**: `> 99%` passing

Tune the thresholds in each script once you have baseline numbers from the test
environment. The numbers shipped here are conservative starter values drawn
from the existing in-process timings (see `PaymentReconciliationScheduler` 240s
timeout and the `@Transactional(timeout=...)` annotations on booking writes).
