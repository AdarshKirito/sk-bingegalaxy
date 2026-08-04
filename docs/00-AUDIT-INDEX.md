# SK Binge Galaxy — Deep Audit (fresh cut)

> **⚠️ Superseded (2026-07-25):** this set audited a **detached HEAD with ~599 uncommitted files** — that tree has since been committed (`3d65090`, merged as `6440f58` on `main`, clean). Its P0-1 and P0-3 are **FIXED**; P0-2 (tokens in git) remains open. The canonical current audit is [audit/00-AUDIT-INDEX.md](audit/00-AUDIT-INDEX.md) with the sole authoritative register at [audit/ISSUE-REGISTER-CURRENT.md](audit/ISSUE-REGISTER-CURRENT.md). This set is retained as historical reference.

**Audit date:** 2026-07-23
**Repository root (real git root):** `D:\sk-binge-galaxy\sk-binge-galaxy` (note: nested one level under the workspace)
**Working state audited:** detached `HEAD` at `e3edbc1` **plus ~599 uncommitted files** — the audit reflects the *working tree*, which is what actually runs, not the last commit.
**Scale:** ~84k lines Java (712 files, 9 Maven modules) · ~50k lines JS/TS + 16k CSS (~200 frontend files, ~70 pages) · 234 Flyway migrations.

> This set was re-authored from a direct read of the boundary/security/money code and a full structural map of every service, controller, entity, page, and the event/dependency graph. It **replaces** the previous thin numbered stubs. The detailed historical evidence in [`audit/`](audit/), the runbooks, the money-scale contract, the changelog, and the byte-preserved `_previous/` archive were **preserved** (they are non-reproducible reference material).

## How to read this set

| # | Document | What it answers |
|---|---|---|
| 00 | This index | Scope, method, verdict, how the pieces connect |
| 01 | [System overview](01-SYSTEM-OVERVIEW.md) | What the product is, personas, runtime shape, tech, scale |
| 02 | [Architecture & dependencies](02-ARCHITECTURE-AND-DEPENDENCIES.md) | The 9 modules, sync/async/data edges, **how a change ripples**, the integrity seams |
| 03 | [Security, RBAC & privacy](03-SECURITY-RBAC-PRIVACY.md) | Gateway boundary, roles, delegation, per-binge module gates, internal-service auth, findings |
| 04 | [Data, domain & money](04-DATA-DOMAIN-MONEY.md) | Aggregates, per-service schema, migrations, the money/rounding contract, cross-DB integrity |
| 05 | [Service deep-dives](05-SERVICE-DEEP-DIVES.md) | Per-service responsibility, key flows, god-classes, risk notes |
| 06 | [Frontend UI/UX](06-FRONTEND-UI-UX.md) | SPA/PWA, state model, the axios client, admin vs customer surfaces, CSS systems, a11y |
| 07 | [Issue register](07-ISSUE-REGISTER.md) | **The problem list** — prioritized P0→P3: what's broken, what's fragile, the correct way, what's missing |
| 08 | [Recommendations & roadmap](08-RECOMMENDATIONS-ROADMAP.md) | Sequenced remediation and "the better way" per area |

## Verdict (headline)

The platform is **architecturally mature and unusually complete** for its stage: a real API-gateway trust boundary, defence-in-depth RBAC re-enforced at every service, a transactional outbox/inbox/DLQ event fabric, server-authoritative BigDecimal money math, durable refund intents, per-binge multi-tenancy, and a large, feature-rich admin + customer PWA.

It is **not** production-ready as it sits, for reasons that are mostly *operational and hygiene*, not architectural:

1. **Nothing is reproducible from a commit.** ~599 files are uncommitted on a detached HEAD. The running/deployed build and the source you read can silently diverge — this is the single biggest risk and the root cause of many "I fixed it but the bug is still there" reports. **(P0)**
2. **Secrets are in git history.** `admin_token.txt` and `stress-tokens.txt` are still in committed `HEAD` (only staged for deletion locally). History purge + credential rotation required before any public exposure. **(P0)**
3. **Maintainability debt concentrates in a few god-objects** — `BookingService.java` (5,189 lines), `PaymentService.java` (2,247), `AdminBookings.jsx` (2,385), `BingeManagement.jsx` (2,029). These are where regressions will keep coming from. **(P1)**
4. **Deliberate fail-open tradeoffs** (Redis-down ⇒ session-revocation and rate-limiting fail open; soft `iss/aud`/MFA defaults) are reasonable for availability but must be consciously signed off for production. **(P1)**

Full detail and the complete prioritized list are in [07-ISSUE-REGISTER.md](07-ISSUE-REGISTER.md).

## Method & confidence

- **Code-verified (high confidence):** gateway filters, all per-service `SecurityConfig` authz seams, `MoneyUtil`, internal-secret + gateway-header filters, Kafka topic contracts, Feign dependency edges, compose/env/secret wiring, config-server layout, `ddl-auto`, migration heads, the frontend axios client, git/secret hygiene, file/line census.
- **Structure-verified (high confidence on shape, not every branch):** every controller, entity, page, provider, scheduler, listener enumerated; responsibilities inferred from names + the code paths read + prior audit + project memory.
- **Not exhaustively line-read:** the two 5k/2k-line service god-classes and the largest page components were sized and sampled, not read top to bottom. Business-rule edge cases inside them are called out as *needs-verification* where relevant.

Where this set and the older `audit/` record disagree, **this set is the current truth** (it reflects the live working tree; the older record predates several of the 599 changes).
