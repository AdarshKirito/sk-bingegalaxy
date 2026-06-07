# 02 — `discovery-server` + `config-server`

Two tiny infrastructure services. Both are plain Spring Boot apps with a single security
config; their real "content" is in their `application.yml`.

---

## discovery-server (Eureka, :8761)

### `DiscoveryServerApplication.java`
13 lines: `@SpringBootApplication` + **`@EnableEurekaServer`**. The entire service registry is
that annotation; everything else is config.

### `config/SecurityConfig.java`
A `SecurityFilterChain` bean:
- `csrf().ignoringRequestMatchers("/eureka/**")` — CSRF is disabled for the registry endpoints
  (service registration is a non-browser, credential-authenticated POST; CSRF doesn't apply).
- `/actuator/health` is `permitAll()` (so K8s liveness/readiness can probe without creds).
- Everything else `authenticated()` via **HTTP Basic** — the Eureka dashboard and registry are
  protected by the `eureka`/`${EUREKA_PASSWORD}` credentials.

### `application.yml`
- Port 8761, graceful shutdown (30s drain).
- Basic-auth user `eureka` / `${EUREKA_PASSWORD}` (secret-injected).
- **Standalone by default**: `register-with-eureka` and `fetch-registry` are `false` unless
  `EUREKA_PEER_ENABLED` (so a single node doesn't try to peer with itself); `EUREKA_PEER_URLS`
  configures HA peering when enabled.
- **Self-preservation disabled** (`enable-self-preservation: false`) — the inline comment
  explains why: in containers, self-preservation keeps evicting-"down" instances around to
  avoid a registry wipe on a network partition, which causes *stale upstream routing for
  minutes after a pod dies*. K8s liveness/readiness already protects against partitions, so
  prompt eviction is preferred. `eviction-interval-timer-in-ms: 10000` (evict every 10s),
  `renewal-percent-threshold: 0.85`.
- Actuator exposes only `health,info`; health details `when-authorized`.

---

## config-server (Spring Cloud Config, :8888)

### `ConfigServerApplication.java`
13 lines: `@SpringBootApplication` + **`@EnableConfigServer`**.

### `config/SecurityConfig.java`
Same shape as discovery's: CSRF ignored for `/actuator/**`, `/actuator/health` permit-all,
everything else HTTP-Basic authenticated.

### `application.yml`
- Port 8888, graceful shutdown.
- Basic-auth `${CONFIG_SERVER_USER:configuser}` / `${CONFIG_SERVER_PASSWORD}` — services
  authenticate when fetching their config.
- **`profiles.active: native`** with `search-locations: classpath:/configurations` — config is
  served from bundled YAML files (no Git backend), one per service.
- Registers with Eureka.

### `src/main/resources/configurations/*.yml`
The **centralized per-service config** the config server serves. One file per service:
`api-gateway.yml` (holds the gateway route table — see ARCHITECTURE.md §2), `auth-service.yml`,
`availability-service.yml`, `booking-service.yml`, `payment-service.yml`,
`notification-service.yml`. Each defines that service's datasource, Kafka, JWT secret/issuer/
audience, Redis, theater defaults, rate-limit caps, and feature flags — externalized so the
same image runs in dev/staging/prod by swapping config + secrets.
