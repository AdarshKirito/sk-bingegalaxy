# Current Problems and Errors — Plain-Language Report

> **This is the "what's wrong" document you asked for.** Audit run AUD-2026-07-25-01, commit `6440f58`, 2026-07-25. Every item was verified against current source (file:line cited) or honestly labeled as unverifiable in this run. Canonical tracking: [ISSUE-REGISTER-CURRENT.md](ISSUE-REGISTER-CURRENT.md).
>
> **Scope honesty:** the original 2026-07-25 pass was static-only. **Update 2026-07-26 (execution phase):** the environment was recovered and everything runnable was executed — 846 backend + 362 frontend tests pass, all images rebuilt from current source, migrations proven (empty-DB + live V20/V16 upgrade), the booking race was exercised live with 4 concurrent users (no oversell), and k6 smoke passed 336/336. "X actually happens at runtime" is now proven for those paths — the exceptions that remain unproven are listed in [EXTERNAL-BLOCKERS-AND-UNVERIFIED-ITEMS.md](EXTERNAL-BLOCKERS-AND-UNVERIFIED-ITEMS.md) (chiefly: real payment-provider traffic).

---

## The two things that genuinely block production

### 1. Live-format admin tokens are sitting in your git repository
[admin_token.txt](../../admin_token.txt) and [stress-tokens.txt](../../stress-tokens.txt) contain real-format JWTs (`eyJhbGciOiJIUzI…`) and are **tracked at HEAD** — and in history. Your `.gitignore` lists them (lines 25–27), but gitignore doesn't untrack files that were already committed. Anyone who ever cloned this repo has admin-shaped tokens.
**What must happen:** delete the files, purge git history (filter-repo/BFG + coordinated force-push), rotate `JWT_SECRET`, revoke all sessions. **⚠️ Read problem #4 first — rotating JWT_SECRET carelessly will lock every MFA user out.**

### 2. No payment has ever been proven end-to-end
The payment code is genuinely good (durable intents, HMAC-verified webhooks, real Razorpay/Stripe refund calls, receipt-first reconciliation, a database index that makes double-refunds structurally impossible). But your own changelog admits **"No end-to-end payment has ever run"** — not even against a test sandbox. The entire revenue path is unproven software. One wrong webhook URL, currency unit, or signature secret and money behaves wrongly in production.
**What must happen:** a full sandbox campaign (pay, fail, refund, partial refund, webhook replay, dispute, reconciliation) with archived evidence.

---

## Serious problems (fix before launch)

### 3. Your best safety net has no test
The V75 database trigger is the last line of defense against double-booking a room — and **no automated test exercises it**. There are zero Testcontainers tests in the whole repo: the 80-migration Flyway chain, the advisory-lock contention path, Kafka outbox relay, and Mongo TTL behavior are all tested only with mocks, or not at all. The one real integration test (`BookingFlowIntegrationTest`) is `@Disabled`. *(2026-07-26: the execution pass exercised the race manually — 4 concurrent users, 3 rooms, exactly 3 bookings created with zero overlap — so the mechanism works today; but that is a one-off manual proof, not a regression test. The gap stands.)*

### 4. A hidden trap in your crypto config
[SecretCipher.java](../../backend/auth-service/src/main/java/com/skbingegalaxy/auth/security/SecretCipher.java) L55-57: if `CRYPTO_SECRET_KEY` isn't set, MFA secrets are encrypted with a key **derived from JWT_SECRET**. Consequence: the JWT rotation that problem #1 requires would make **every user's TOTP secret undecryptable** — a platform-wide MFA lockout. Set `CRYPTO_SECRET_KEY` everywhere first, then rotate.

### 5. Monitoring that watches but never calls for help
Prometheus, Zipkin and Loki are wired — but there is **not a single alert rule** in the repo. If the outbox relay stalls, a DLT fills with poisoned refund events, or reconciliation stops running, nobody gets paged. You'd find out from angry customers.

### 6. HTTPS is a plan, not a manifest
The Jenkinsfile mentions cert-manager, but there's no Ingress or Certificate manifest in k8s/. As committed, there is no TLS entry point. Related: nobody has ever demonstrated a running environment with the production profile active, simulation refused, secure cookies on, and super-admin MFA enforced (the code defaults are right — AuthService.java L464-480 defaults MFA to "true"; it's the runtime proof that's missing).

### 7. Schema drift protection is assumed, not proven
`ddl-auto: validate` could not be found pinned in any service config. Spring Boot's default with Flyway is probably safe (`none`), but "probably" is not a schema-integrity strategy across 4 databases and 118 migrations.

---

## Real but not blocking

8. **Backups have never been restored.** Scripts and a daily CronJob exist; a restore has never been rehearsed. An untested backup is a hope, not a backup. (DB-04)
9. **GDPR erasure can silently fail.** The anonymization pipeline is real and well-built (UserAnonymizationService + `user.anonymized` fan-out to booking/payment/notification) — but auth publishes without an outbox, so a Kafka outage during the nightly sweep could leave PII unredacted in downstream services with no reconciliation to catch it. (EVT-02/PRIV-02)
10. **28.67 MB of k6.zip and Playwright test artifacts are committed**, bloating every clone forever (until the history purge in #1, which is the moment to drop these too). (HYG-01/03)
11. **Load-test evidence is stale.** All k6/stress results predate 566 changed files. Current commit has never been load-tested. (PERF-01)
12. **SMS/WhatsApp are mocks** but appear as channels in the UI. Integrate or hide. (INT-01)
13. **No CI runs on pull requests** — Jenkins only. A broken PR merges silently until Jenkins notices. No secret scanning anywhere (which is exactly how #1 happened). (CI-01, SEC-OP-04)
14. **8 Kafka topics are published and consumed by nobody** (booking.confirmed, room.approved, user.registered, …). Dead code or undocumented contracts. (EVT-01)
15. **79 `orElse(null)` call sites** are latent NPE seams. (QUAL-02)
16. **AdminBookings.jsx is ~1,800 lines** — the next feature there will hurt. (FE-02)
17. **Accessibility gaps:** no `prefers-reduced-motion`, color contrast never verified. (A11Y-01)
18. **Product half-wires:** approval queue executes only REFUND_RETRY; authority locks have no management UI; dispute filing is ops-only. Decisions needed, not necessarily code. (PG-01..03)

---

## Documentation errors found (and fixed by this audit)

- README/ARCHITECTURE claimed Flyway V19/V77/V14 → actually **V20/V80/V16**; "70 routes/421 mappings" → **71/477**; NO-GO reasons cited an already-fixed PWA caching bug → banners added, details in [22-DOCUMENTATION-CONTRADICTION-REGISTER.md](22-DOCUMENTATION-CONTRADICTION-REGISTER.md)
- docs/07-ISSUE-REGISTER still listed "599 uncommitted files" as P0 → the tree is clean; fixed
- docs/audit/README.md linked to two documents that don't exist
- Three internal specialist claims this run were **disproved and corrected** (.env is NOT tracked; k6_bin is NOT tracked; GDPR code DOES exist) — recorded transparently as DOC-CR-10/11/12

## What was verified as genuinely good (so you don't fix what isn't broken)

- Three-layer oversell defense (holds → advisory lock → V75 trigger) — design is right, it's the *testing* that's missing
- Refund integrity machinery (durable intents, receipt-first reconciliation, V14 unique index)
- Tenant isolation incl. the fixed recovery-queue leak, with a real regression test
- Token handling in the PWA (httpOnly, single-flight refresh, NetworkOnly API, DOMPurify)
- Code hygiene: **zero** TODO/FIXME, zero printStackTrace, zero empty catches across 712 Java files
- The 26-Apr CRITICAL (customers writing loyalty config) is properly fixed with class-level `@PreAuthorize`
