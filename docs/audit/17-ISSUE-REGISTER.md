# 17 — Canonical Issue Register

One canonical issue per root cause. Cross-references avoid duplication. Status labels: CONFIRMED / HIGH CONFIDENCE / PROBABLE / QUESTION / NOT VERIFIED. Severity per the master-prompt rubric (Critical reserved for cross-Binge access, credential/data/financial exposure, double-booking, irrecoverable inconsistency).

> **Current-state cut (2026-07-17):** the remediation log entry below (2026-07-17) is the authoritative view for the present working tree. The July 16 open-issue table further down is retained as the discovery record for that pass; the July 12 discovery sections remain historical evidence. Inline `Status: CONFIRMED` labels describe the tree when found, not the post-remediation state.

## Current release verdict

**NO-GO pending runtime verification.** Every code-side P0/P1 from the July 16 register (SEC-009/010/011, PAY-006/007/008/009/010, BOOK-004, plus the newly found SEC-013 support-console gap) is now FIXED in source, with unit-test fences. The gate to a GO is now evidence, not code: a green containerized build + suite run on this tree, the Testcontainers concurrency/fault-injection harness (TEST-001), a browser pass (SW identity switch, axe/keyboard), Razorpay sandbox proof of the refund-intent/webhook path, and the standing operator actions (git-history purge + secret rotation, refund-webhook registration).

## Remediation log (2026-07-17) — fourth pass, implemented in working tree

All five Critical P0s, all five High P1s, the support-console gap this pass surfaced, and the P2/P3 backlog were fixed in source. Runtime verification pending (host toolchain unavailable; containerized build attempted — see AUDIT_STATUS).

| ID | Status | What shipped |
|---|---|---|
| SEC-009 | **FIXED (code)** | SW caches NO API responses: single `NetworkOnly` rule for `/api/`; `address-data` chunk excluded from precache; legacy `api-cache` deleted on SW activate (`push-sw.js`), on logout (`authStore`) and on binge switch/clear (`bingeStore`) |
| SEC-010 | **FIXED** | Approval surface tenant-scoped end-to-end: binge-scoped repository queries + `list(..., bingeId)`; controller resolves scope via `requireManagedBinge` (SUPER_ADMIN w/o binge = platform view); every action re-validates the TARGET ROW's binge via new `PaymentBingeScopeService.requireBingeOwnership` (fail-closed, null-binge rows are SUPER_ADMIN-only); `executeApprovedRefundRetry` re-checks approval-binge == refund's payment-binge in-transaction. Test: `AdminApprovalControllerScopeTest` |
| SEC-011 | **FIXED** | Internal snapshot now returns `bookingId/customerId/bingeId/totalAmount`; initiation binds customer (CUSTOMER role must own the booking) and stamps the BOOKING's binge; cash/add-payment fail closed on missing snapshot, reject cross-binge bookings, take owner+ceiling from the snapshot (caller-supplied `bookingTotalAmount` ignored) + second fence from local SUCCESS sum; refund timeline filters to the caller's own payments for CUSTOMER; `PaymentEvent` carries `bingeId` and the booking listener refuses mismatched-binge mutations (flag + park). Tests: listener mismatch pair in `PaymentEventListenerTest` |
| PAY-006 | **FIXED** | Durable refund intents: V15 adds `refunds.gateway_receipt` (partial-unique) + status/created index; reserve (REQUIRES_NEW, payment lock, over-refund guard incl. INITIATED) → provider leg OUTSIDE any tx → finalize (REQUIRES_NEW, idempotent). Ambiguous outcomes STAY INITIATED — never FAILED — and reconciliation resolves them receipt-first via new `findRefundByReceipt` (null=ambiguous vs empty=authoritative-absence). Webhook settle gained a receipt fallback for intents whose finalize crashed pre-id. Late-capture and duplicate-capture auto-refunds create the intent in the callback tx and run the provider leg after commit. Retry path supersedes the original only after provider acceptance |
| PAY-007 | **FIXED** | `OrderStatusLookup{status, authoritative}`: 4xx = authoritative not_found, 5xx/timeouts = non-authoritative; reconciliation leaves non-authoritative lookups INITIATED (alert after 24 h), holds young "attempted" orders, and only authoritative not-paid transitions FAILED; daily settlement no longer fires SETTLEMENT_MISMATCH on transport failures and skips synthetic order ids; duplicate-capture guard in the callback records the capture for ledger truth then auto-refunds it when the booking was already fully collected |
| PAY-008 | **FIXED** | `WebhookDedupService.recordNew` propagation REQUIRES_NEW → REQUIRED: marker and business effects commit/roll back atomically; redelivery after rollback re-processes against idempotent, row-locked handlers |
| PAY-009 | **FIXED** | Monotonic dispute fence (terminal states never reopened/overwritten; backwards transitions ignored; conflicting terminal → loud refusal for ops); `under_review` upserts a missing record; LOST/ACCEPTED create a real chargeback `Refund` row (idempotent by dispute id, clamped to remaining refundable) recomputing payment status and publishing `payment.refunded` so booking/ledger update; WON restores from the settled-refund ledger and only from DISPUTED |
| PAY-010 | **FIXED** | Gross = captured-ever (`SUCCESS/PARTIALLY_REFUNDED/REFUNDED/DISPUTED`) via new `sumAmountByStatusIn[AndBingeId]`; refunds subtracted exactly once from the refund ledger. ₹100 charge + ₹30 refund now reports gross 100 / net 70 |
| BOOK-004 | **FIXED** | Customer cancel gate opens to CONFIRMED (policy decides); refund basis = COLLECTED × policy %; booking no longer zeroes collected locally — `booking.cancelled` carries `refundAmount` and payment-service's listener spreads it across captured payments as REAL refund intents (idempotent by reason marker across redeliveries; shortfall alert for disputed/legacy rows); settled `payment.refunded` events reconcile the booking's collected amount. Tests: `BookingCancelledEventListenerTest` |
| SEC-013 (NEW) | **FIXED** | Support console (`/admin/support/**`): every booking-level op (escalations list, by-ref lookup, escalate, goodwill, resend-confirmation) now runs `requireManagedBinge` before the presence-scoped service call — the spoofable `X-Binge-Id` alone no longer selects the tenant |
| SEC-012 | **FIXED (code)** | Replay masking restored (`maskAllText/blockAllMedia: true`); unmasking policy documented as per-element allowlist only. DSN/DPA/retention remain deployment decisions |
| API-002 | **FIXED (removed)** | Singular `POST /{ref}/transfer` deleted; transfers only via the consent (magic-link) flow |
| FE-001 | **FIXED** | `/payments/my/summary` (lifetime spend = captured-ever + counts); CustomerPayments uses server paging + `Pagination`; AccountCenter lifetime spend from the summary; endpoints tests updated |
| NEW-2 | **FIXED (both services)** | Idempotency claim-first: in-progress claim row committed (REQUIRES_NEW) BEFORE work; simultaneous duplicates collide on the composite PK → 409/cached; claim released on failure; crashed claims expire in 5 min; null-body = in-progress marker. Race test added (booking) |
| PERF-001 | **FIXED** | `toDtos`/`toDtoPage` batch mappers: one query each for binges, add-on categories, event categories per page (15 list/page call sites converted); policy evaluation takes the prefetched binge |
| PERF-002 | **FIXED (code)** | `country-state-city` isolated into an `address-data` chunk via `manualChunks`, excluded from precache (`globIgnores`), Workbox size cap back to 3 MiB. Rebuilt byte-size not yet measured |
| REL-002 | **FIXED** | Reconciliation restructured: no batch transactions; provider calls outside any tx; per-row REQUIRES_NEW row-ops (`markStaleInitiatedFailed`, `flagGatewayPaidMismatch`) that re-check state under the row lock; batch bound (200/pass); stranded refund intents recovered receipt-first |
| PAY-005 | **FIXED (upgraded)** | Initiation is intent-first: TX1 reserves the INITIATED row (all guards + binding) → provider order OUTSIDE tx (receipt reuse) → TX2 attaches order id under the advisory lock (first writer wins; loser's order logged orphan). Crash windows converge via guard-2 reuse and reconciliation failing order-less intents |
| A11Y-003 | **PARTIAL → improved** | Password-reveal buttons keyboard-reachable (16 `tabIndex={-1}` removed; drawer's programmatic-focus `tabIndex` kept); shared Modal was already native `<dialog>`. Remaining: hand-built drawers/click-only rows (`RoomDetailModal`, `AdminBookings` tables) |
| A11Y-004 | **FIXED (token-level)** | AA `--primary/success/warning/danger-text` tokens (light+dark); 143 bare text-color usages repointed (background/border fills untouched) |
| A11Y-005 | **FIXED** | Global `prefers-reduced-motion` layer (durations → 1 frame, smooth-scroll off) with `.motion-essential` opt-out; Spinner slows instead of freezing |
| NEW-3 | **FIXED** | Runbook's phantom `advance-saga` route replaced with the real recovery replay + outbox retry paths |
| NEW-4 | **FIXED** | Stale "lock a new rate" checkout copy removed from payment-service messages |
| DOC-001 | **IMPROVED** | `docs/codebase/00-INDEX.md` banner corrected (consumeHold wired, checkout deleted, 87/47 counts); `06c` checkout/transfer sections marked REMOVED |
| SEC-007/DEVOPS-002 residue | **PARTIAL** | Remaining tracked artifacts untracked (`backend/*.txt` logs, extracted test scratch); `.gitignore` covers crash dumps/scratch. STILL OPEN: tree uncommitted; token files live in HEAD/history until commit + `filter-repo` + JWT/VAPID rotation (operator) |
| TEST-001 | **PARTIAL** | Updated suites for the new flows + new fences (approval scope, cancellation listener, idempotency race, tenant-mismatch listener). STILL MISSING: Testcontainers advisory-lock/V75-trigger concurrency, provider fault-injection at send/save/commit boundaries, webhook crash-after-marker replay, browser/axe pass |

Migrations this pass: payment **V15** (`refund_intent_receipt`). Booking next free: V78 (V77 = index drops). Contract changes: `PaymentEvent.bingeId`, `BookingEvent.refundAmount` (both nullable, consumers tolerate old events); internal `/internal/amount/{ref}` response extended (additive).

## Current open-issue details

### [SEC-009] Workbox caches authenticated booking/admin responses across identities and Binges
- **Status:** CONFIRMED · **Severity:** Critical · **Priority:** P0 · **Category:** Security / privacy / tenant isolation
- **Affected —** application: frontend PWA · service/module: Workbox runtime cache · routes: `/api/v1/bookings/**` and other broad cached groups · workflow: customer/admin reads, logout/login/Binge switch · data: booking/customer/support/operational PII · files: `frontend/vite.config.js:74-86`, `frontend/src/services/api.js:50-59`, `frontend/src/stores/authStore.ts:203-210`.
- **Evidence:** one shared `NetworkFirst` `api-cache` keys a broad URL regex; identity/Binge live in cookie/header context, not URL; logout never deletes Cache Storage. Independent current-tree verifier confirmed customer and admin booking calls match the rule.
- **Observed / expected / trigger / root cause:** after five seconds or offline, the service worker can return a prior identity's URL-keyed response; authenticated data must be network-only and identity-safe; trigger is a reused browser profile plus slow/offline network; root is caching by path without an explicit public allowlist or lifecycle purge.
- **Impact —** customer/Binge: cross-user or cross-Binge PII/stale operational data · platform: breach/support incident · financial: booking decisions from another tenant's state · security/privacy: Critical · integrity: cached state can drive incorrect follow-up actions.
- **Target/remediation:** make every authenticated/tenant endpoint `NetworkOnly`; cache only explicitly public URL-keyed resources; version and clear named caches at login/logout/Binge change; remove already-installed unsafe caches.
- **Acceptance/tests/monitoring:** automated service-worker test switches user and Binge, simulates timeout/offline, and never receives the old response; production SW version/cache inventory telemetry; security review of every future runtime-caching rule.
- **Effort/owner/deps/related/limitations:** S · frontend/security · PWA rollout coordination · related SEC-011 · static path confirmed; exploit was not executed with real PII.

### [SEC-010] Maker-checker approvals are globally readable/actionable across Binges
- **Status:** CONFIRMED · **Severity:** Critical · **Priority:** P0 · **Category:** Authorization / tenant isolation / financial control
- **Affected —** payment-service · approval controller/service/repository and approved refund retry · `/payments/admin/approvals/**` · `admin_approval_requests`, refunds · files: `AdminApprovalController.java:40-124`, `AdminApprovalService.java:107-230`, `AdminApprovalRequestRepository.java:16-20`, `PaymentService.java:1015-1039`.
- **Evidence:** controller checks only role; list/get/approve/reject/cancel use global queries; execution resolves global approval/refund IDs without `ensurePaymentInCurrentBinge`.
- **Observed / expected / trigger / root cause:** a Binge admin can inspect or act on another Binge's approval; every row/action must be constrained by managed Binge, with an explicit native-super-admin platform path; trigger is knowledge or discovery of a row; root is a separate controller bypassing the standard scope service.
- **Impact —** customer/Binge: unauthorized refund action and financial metadata exposure · platform: broken tenant promise/audit trail · financial: real provider refund retry · security/privacy/integrity: Critical cross-tenant mutation.
- **Target/remediation:** Binge predicates in repository queries and locks; controller scope enforcement; revalidate approval, refund, payment, and executor Binge in one transaction; explicit audited platform override.
- **Acceptance/tests/monitoring:** two-Binge negative tests for every method and execute path; alert on approval actor/resource Binge mismatch; audit records include authoritative actor scope.
- **Effort/owner/deps/related/limitations:** M · payments/security · scope-policy decision for native super-admin · related SEC-011/PAY-006 · direct static evidence; runtime exploit not performed.

### [SEC-011] Payment writes are not bound to the authoritative booking owner/Binge/balance
- **Status:** CONFIRMED · **Severity:** Critical · **Priority:** P0 · **Category:** Object authorization / tenant isolation / financial integrity
- **Affected —** payment + booking services · initiation, cash/add-payment, refund timeline, payment Kafka consumer · `/payments/initiate`, admin manual payment, `/booking/{ref}/refunds` · payment/refund/booking balances · files: `PaymentService.java:143-270,789-927`, `InternalBookingController.java:69-94`, `PaymentEventListener.java:58-84`, `RefundDto.java:15-30`.
- **Evidence:** booking snapshot omits owner/Binge; payment row trusts caller/context; manual writes never resolve booking ownership/Binge and trust an optional amount ceiling; booking listener mutates by global reference; refund-timeline read lacks customer ID/ownership enforcement.
- **Observed / expected / trigger / root cause:** a customer can pay a known foreign booking; an admin can stamp their Binge onto another booking's payment and publish success; overcollection is possible; same-Binge customers can read another refund timeline. Every payment operation must bind to an authoritative booking snapshot and remaining balance before provider/network action and under a booking lock.
- **Impact —** customer: unauthorized disclosure/charge linkage · Binge: cross-tenant financial/lifecycle mutation · platform: ledger and booking divergence · financial/security/privacy/integrity: Critical.
- **Target/remediation:** internal booking contract returns immutable booking ID, owner, Binge, currency, total, collected/remaining, status/version; compare authenticated actor/scope; lock authoritative booking/payment aggregate; reject mismatches; include/verify Binge on events.
- **Acceptance/tests/monitoring:** two-Binge/two-customer tests for initiation/manual writes/timeline/event; concurrent overcollection test; invariant alerts for payment Binge/booking Binge and collected amount beyond total.
- **Effort/owner/deps/related/limitations:** L · payments+booking+security · versioned internal contract/event schema · related PAY-007/PAY-010 · confirmed static; provider charge not attempted.

### [PAY-006] Provider refund can succeed while its DB/idempotency transaction fails
- **Status:** CONFIRMED · **Severity:** Critical · **Priority:** P0 · **Category:** Distributed transaction / idempotency
- **Affected —** payment-service refund issuance/retry · Razorpay refund API, refunds/outbox/idempotency/audit · `PaymentService.java:565-698`, `IdempotencyService.java:59-122`, `RazorpayGatewayClient.java:153-197`.
- **Evidence:** external refund POST occurs before Refund/outbox/idempotency commit; timeout or later rollback loses the local result; every retry creates a new random receipt and performs no stable-receipt reconciliation.
- **Observed / expected / trigger / root cause:** provider may move money while local state says FAILED/absent, then a retry moves it again; irreversible calls need durable intent and ambiguity recovery; trigger is timeout/crash/DB or before-commit failure; root is remote I/O inside an atomic DB workflow without a stable operation key.
- **Impact —** duplicate refunds, untracked money movement, irreconcilable customer/support state; financial and integrity blast radius per refund/payment.
- **Target/remediation:** persist unique refund intent/receipt before sending; outbox worker; `UNKNOWN` state; query provider by stable key/ID before retry; unique operation constraint.
- **Acceptance/tests/monitoring:** fault injection at each send/save/commit boundary proves at-most-once provider movement and eventual reconciliation; alert on aged UNKNOWN intents and provider/local amount mismatch.
- **Effort/owner/deps/related/limitations:** L · payments/platform · provider lookup semantics · related PAY-008 · failure sequence is code-confirmed, sandbox injection not run.

### [PAY-007] Provider-unreachable becomes FAILED, enabling a second charge
- **Status:** CONFIRMED · **Severity:** Critical · **Priority:** P0 · **Category:** Payment state machine / reconciliation
- **Affected —** order-status client, stale-payment scheduler, initiation and callback · `RazorpayGatewayClient.java:259-280`, `PaymentReconciliationScheduler.java:43-100`, `PaymentService.java:189-211,301-381`.
- **Evidence:** every lookup exception returns `null`; every non-`paid` value including null becomes `FAILED`; FAILED permits another order; a valid delayed callback can still make the first row SUCCESS.
- **Observed / expected / trigger / root cause:** provider outage opens a second payable order while the first can later capture; unknown is not failure; root is collapsed transport/business state and no cross-payment capture guard.
- **Impact —** duplicate customer charge and duplicated booking collection; Binge/support refunds and trust loss; financial Critical.
- **Target/remediation:** `UNKNOWN/RECONCILING`, retry/alert without reopening checkout, authoritative provider reconciliation, booking-scoped callback serialization and duplicate-capture reversal.
- **Acceptance/tests/monitoring:** two-order delayed-callback scenario yields at most one retained capture; transport errors never transition FAILED; alert on multiple provider orders/captures per booking.
- **Effort/owner/deps/related/limitations:** M/L · payments · state/migration/product copy · related PAY-005/PAY-006 · static sequence confirmed; provider timing not driven.

### [SEC-012] Sentry Replay disables privacy masking on PII-rich screens
- **Status:** CONFIRMED, deployment-contingent · **Severity:** High · **Priority:** P1 · **Category:** Observability privacy/configuration
- **Affected —** frontend monitoring · all rendered routes when `VITE_SENTRY_DSN` is enabled · customer/admin PII · `frontend/src/main.jsx:14-24`, representative PII at `AdminBookings.jsx:469-474,1334-1342`.
- **Evidence:** `maskAllText:false`, `blockAllMedia:false`, 10% production-session and 100% error replay sampling. No compensating masking hook found. Current Docker/Compose does not pass a DSN and CSP may block ingest, so actual production transmission is NOT VERIFIED.
- **Observed / expected / trigger / root cause:** enabling the configured DSN/CSP captures readable PII; privacy defaults/allowlisted unmasking are expected; trigger is deployment activation; root is explicit opt-out of safe defaults plus undocumented deployment wiring.
- **Impact —** customer/Binge privacy and third-party data-processing exposure; platform compliance/incident risk; no direct financial mutation.
- **Target/remediation:** restore masking/blocking defaults, explicit safe selectors, scrub user context, retention/DPA review, and tested CSP/DSN secret deployment.
- **Acceptance/tests/monitoring:** privacy fixture replay contains no PII; deployment test proves either monitoring is intentionally off or safely working; audit Replay sampling/egress.
- **Effort/owner/deps/related/limitations:** S/M · frontend/security/privacy · Sentry policy/DPA · related SEC-009 · code confirmed, runtime activation unverified.

### [PAY-008] Webhook dedup marker commits independently of business state
- **Status:** CONFIRMED · **Severity:** High · **Priority:** P1 · **Category:** Reliability / idempotency
- **Affected —** payment/dispute webhooks, dedup table, outbox · `WebhookDedupService.java:58-75`, `PaymentService.java:289-415`, `PaymentKafkaPublisher.java:26-37`, `DisputeWebhookService.java:101-139,281-284`.
- **Evidence:** `recordNew` is `REQUIRES_NEW`; marker commits before the outer business transaction. A later rollback leaves a duplicate marker that suppresses redelivery.
- **Observed/expected/trigger/root:** durable “processed” without durable effect; dedup and result must commit atomically or use recoverable inbox states; trigger is any post-marker rollback; root is split transaction propagation.
- **Impact:** missed capture/refund/dispute state and booking divergence; blast radius per event, potentially financial.
- **Remediation/acceptance:** transactional inbox/outbox lifecycle with leases and replay; crash-after-marker test must recover exactly once; alert on stuck/failed inbox rows.
- **Effort/owner/deps/related/limitations:** M · payments/platform · schema/state transition · related PAY-006/PAY-009 · code confirmed, crash injection not run.

### [PAY-009] Dispute ordering/accounting does not preserve financial truth
- **Status:** CONFIRMED · **Severity:** High · **Priority:** P1 · **Category:** Dispute lifecycle / accounting
- **Affected —** dispute webhook service, payment/refund/booking ledgers · `DisputeWebhookService.java:30-35,47-51,127-231,273-278`.
- **Evidence:** lost/accepted writes payment REFUNDED but creates no promised Refund row/event; terminal-before-created updates no dispute but is deduped; won unconditionally restores SUCCESS.
- **Observed/expected/trigger/root:** out-of-order events can reopen/lose a terminal case and financial/booking totals are not updated; expected monotonic upserted state plus chargeback ledger; root is update-only handlers and presentation status standing in for accounting entries.
- **Impact:** wrong balances, revenue, support state and possible continued access after chargeback; financial/reporting High.
- **Remediation/acceptance:** monotonic provider-state upsert, principal/fee ledger entries, idempotent booking propagation, preserve pre-dispute money state; test all event permutations/duplicates.
- **Effort/owner/deps/related/limitations:** L · payments/finance · provider event semantics · related PAY-008/PAY-010 · static handler trace confirmed.

### [PAY-010] Settled refunds are double-subtracted from dashboard revenue
- **Status:** CONFIRMED · **Severity:** High · **Priority:** P1 · **Category:** Financial reporting
- **Affected —** payment stats/dashboard · `PaymentRepository.java:49-53`, `PaymentService.java:740-748,1164-1181`.
- **Evidence:** gross includes only current SUCCESS rows; refunded parents change status and leave gross; completed refunds are then subtracted. A 100 charge/30 refund reports gross 0, refunded 30, net -30 instead of 70.
- **Observed/expected/trigger/root:** every settled refund understates gross and double-reduces net; captured gross must be immutable across refund presentation states; root is current-status filtering instead of ledger semantics.
- **Impact:** Binge/platform financial decisions and reconciliation reports are wrong; no direct provider movement but high financial-control risk.
- **Remediation/acceptance:** sum all captured states or immutable capture ledger, subtract settled refund/chargeback ledger once; golden tests for partial/full/multiple refunds and disputes; monitor report-vs-provider settlement delta.
- **Effort/owner/deps/related/limitations:** S/M · payments/finance · define reporting semantics · related SEC-011/PAY-009 · deterministic query proof.

### [BOOK-004] Cancellation policy is disconnected from captured-money refunds
- **Status:** CONFIRMED · **Severity:** High · **Priority:** P1 · **Category:** Booking/payment saga correctness
- **Affected —** customer/admin cancellation, cancellation-policy UI, booking/payment/loyalty events · `BookingService.java:1097-1106,2014-2040,4721-4792`, `MyBookings.jsx:908-909`, `BookingCancelledEventListener.java:30-45`, common `BookingEvent.java:18-47`.
- **Evidence:** customer endpoint rejects CONFIRMED while UI/policy advertise it; cancellation subtracts full collected amount locally; event has no refund amount; payment listener only fails INITIATED rows.
- **Observed/expected/trigger/root:** paid cancellation either fails or changes local state without returning captured money; expected durable cancellation/refund saga; root is separate booking policy and provider settlement models with an underspecified event.
- **Impact:** customer money retained despite cancellation representation, wrong loyalty/revenue/balance, Binge support exposure.
- **Remediation/acceptance:** explicit refund quote/intent/correlation/status; provider settlement before final financial state or visible pending-refund state; E2E tests for unpaid/paid/partial/provider failure/delay; monitor cancelled-paid bookings without settled refund.
- **Effort/owner/deps/related/limitations:** L · booking+payments+product · cancellation/refund policy decision and event version · related PAY-006/PAY-010 · static end-to-end trace confirmed.

### [API-002] Public legacy immediate-transfer endpoint bypasses recipient consent
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** API lifecycle / privacy
- **Affected —** booking transfer · singular `/bookings/{ref}/transfer` vs plural consent flow · booking customer PII/ownership · `BookingController.java:168-178`, `BookingService.java:1378-1444`, `frontend/src/services/endpoints.js:133-139`.
- **Evidence:** direct endpoint immediately rewrites recipient details/marks transferred and is absent from SPA; consent flow calls the same mutation only after magic-link acceptance.
- **Impact/target:** a customer can bypass recipient consent and leave ownership on the original ID; delete/publicly disable the singular endpoint and expose an internal acceptance-only operation.
- **Acceptance/tests/monitoring:** singular route 404/410; only valid unexpired consent token can transfer; audit requester/recipient IDs; API deprecation check.
- **Effort/owner/deps/limitations:** S/M · booking/API · compatibility decision · static confirmed.

### [FE-001] Customer history and lifetime totals silently stop at 20 payments
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Frontend/API pagination correctness
- **Affected —** `/payments`, dashboard, account center · `PaymentController.java:118-126`, `endpoints.js:304`, `CustomerPayments.jsx:14-18,109-122`, `AccountCenter.jsx:44-80`, `Dashboard.jsx:47-53`.
- **Evidence:** backend defaults page 0/20; client passes no page/size; history renders only Page.content; dashboard/account derive totals from that page.
- **Impact/target:** older transactions disappear and lifetime spend/counts are false; add pagination/cursor and server aggregate endpoints.
- **Acceptance/tests/monitoring:** fixture with 21+ rows shows all via navigation and correct lifetime totals; contract test asserts page metadata is consumed.
- **Effort/owner/deps/limitations:** S/M · frontend+payments · UX choice · confirmed; customer volume distribution unknown.

### [A11Y-003] Custom dialogs and click-only controls have keyboard/focus gaps
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Accessibility / keyboard
- **Affected —** admin/customer custom modals/drawers/tables/password controls · representative files: `RoomDetailModal.jsx`, `AdminBookings.jsx`, `AdminAllUsers.jsx`, `AdminUsersConfig.jsx`, `MyBookings.jsx`, `CustomerReviewsDrawer.jsx`, login/settings/account pages.
- **Evidence:** hand-built overlays lack full focus trap/initial-return lifecycle; actionable rows/cells are click-only; reveal buttons use `tabIndex={-1}`. Shared Modal's old double-dismiss defect is fixed and is not this issue.
- **Impact/target:** keyboard and some AT users cannot reliably reach/operate/exit controls; consolidate on an accessible dialog/drawer primitive and native interactive elements.
- **Acceptance/tests/monitoring:** Playwright keyboard-only tests cover Tab loop, Escape, return focus, row activation and password reveal; axe has no critical dialog/control findings.
- **Effort/owner/deps/limitations:** M/L · frontend/design system · component migration · no browser/AT run, but code paths confirmed.

### [A11Y-004] Normal-text color tokens fail WCAG AA contrast
- **Status:** CONFIRMED (token-level) · **Severity:** Medium · **Priority:** P2 · **Category:** Accessibility / visual contrast
- **Affected —** light/dark theme badges and normal text · `frontend/src/index.css` tokens and CSS consumers.
- **Evidence:** approximate foreground/white ratios: primary 4.47, success 2.54, warning 2.15, danger 3.76; dark-muted/dark-card 3.97, all below 4.5 for normal text.
- **Impact/target:** low-vision users encounter unreadable normal-sized text; introduce role-specific accessible foreground tokens rather than using decorative/status fills as text colors.
- **Acceptance/tests/monitoring:** automated contrast/axe plus representative light/dark visual review; token lint prevents regression.
- **Effort/owner/deps/limitations:** M · frontend/design · palette approval · token math confirmed; page-level visual conformance not exhaustively rendered.

### [A11Y-005] Reduced-motion preference coverage is incomplete
- **Status:** CONFIRMED (static) · **Severity:** Low · **Priority:** P3 · **Category:** Accessibility quality / motion
- **Affected —** global animations/transitions including skeleton shimmer, live pulse, spinners and drawers · representative files: `Home.css`, `Skeleton.css`, `AdminDashboard.css`, `CustomerReviewsDrawer.css`, `index.css`.
- **Evidence:** `prefers-reduced-motion` was found only in `Home.css`, while the current static survey counted 20 animation and 108 transition declarations. This confirms incomplete preference support, not that every animation independently violates WCAG.
- **Impact/target:** motion-sensitive users may receive avoidable non-essential motion; define a global preference layer and component-specific exceptions only where motion is essential.
- **Acceptance/tests/monitoring:** OS reduced-motion browser test neutralizes non-essential shimmer/pulse/spinner/drawer motion without hiding state; CSS lint/test prevents unguarded infinite animation regressions.
- **Effort/owner/deps/limitations:** S/M · frontend/design system · motion inventory/product decisions · static absence confirmed; symptom severity not measured with users.

### [PERF-001] Booking page DTO enrichment has linear N+1 query paths
- **Status:** HIGH CONFIDENCE · **Severity:** Medium · **Priority:** P2 · **Category:** Database performance
- **Affected —** booking list/page APIs · `BookingService#toDto`, `evaluateCustomerCancellation`, booking repositories/entity graphs.
- **Evidence:** list/page maps every row through DTO enrichment that resolves Binge/policy/category/lazy add-ons per booking; page graphs fetch only part of the data.
- **Impact/target:** query count and latency grow linearly with page size; use projections/batch fetch and one policy lookup per Binge, with detail-only enrichment separated.
- **Acceptance/tests/monitoring:** query-budget integration test for 1/20/100 rows shows bounded query count; observe endpoint p95 and DB statement counts.
- **Effort/owner/deps/limitations:** M · booking/data · Hibernate profiling harness · static call structure strong, exact runtime count not measured.

### [PERF-002] Full address dataset creates an approximately 8.7 MB precached chunk
- **Status:** HIGH CONFIDENCE · **Severity:** Medium · **Priority:** P2 · **Category:** Frontend performance / PWA
- **Affected —** address fields and PWA install/update · `AddressFields.jsx`, `VenueCoordinatesField.jsx`, `vite.config.js:69-72`, historical `frontend/dist/assets/AddressFields-*.js`.
- **Evidence:** source imports full `country-state-city`; Workbox limit raised to 12 MiB specifically for it; checked-in historical chunk is about 8.66 MB and precached.
- **Impact/target:** large mobile download/storage/update cost; split/lazy fetch region data and exclude it from unconditional precache; enforce bundle/precache budgets.
- **Acceptance/tests/monitoring:** clean build meets agreed initial/route/precache byte limits; slow-network Lighthouse/Web Vitals checked.
- **Effort/owner/deps/limitations:** M · frontend · data-source/licensing/UX · source cause current; byte size is historical until rebuilt.

### [REL-002] Reconciliation performs remote I/O in long, unbounded DB transactions
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Reliability / capacity
- **Affected —** stale/daily payment reconciliation · `PaymentReconciliationScheduler.java:43-100,145-205`.
- **Evidence:** 240s and 1100s transactions loop unbounded result lists while making one provider call per row; manual rows have synthetic nonblank gateway IDs and may be sent to Razorpay reconciliation.
- **Impact/target:** DB connection/transaction held for network latency, batch-wide rollback/timeouts, false settlement alerts; claim bounded pages, call provider outside DB tx, commit each result separately, identify source explicitly.
- **Acceptance/tests/monitoring:** slow-provider test does not exhaust pool or roll back other rows; bounded batch/lease recovery; metrics for lag, last success, duration, unknowns, mismatch by payment source.
- **Effort/owner/deps/limitations:** M · payments/SRE · scheduler refactor · confirmed static; no load test.

### [TEST-001] Critical concurrency/provider-boundary regressions lack executable coverage
- **Status:** PARTIAL · **Severity:** Medium · **Priority:** P2 · **Category:** Test assurance
- **Affected —** cross-Binge payments/approvals, refund ambiguity, duplicate capture, dedup crash recovery, DB booking backstop, cancellation saga.
- **Evidence:** July remediation added valuable authz/refund-fail-closed/pricing/waitlist/DST tests, but the newly confirmed P0/P1 sequences and DB-level concurrency/failure boundaries are not covered.
- **Impact/target:** fixes can regress silently at the exact failure boundaries that move money or cross tenants; add Testcontainers plus deterministic provider stub/fault injector and Kafka inbox/outbox crash tests.
- **Acceptance/monitoring:** suites run in CI and gate release; flaky/quarantined critical tests fail the readiness gate; publish pass/failure evidence.
- **Effort/owner/deps/limitations:** L · backend/QA/platform · CI Docker resources/provider stub · related all P0/P1 · historical July 15 suite pass was reported, not rerun in this pass.

### [PAY-005] Order retry mitigation still creates provider orders before the DB lock
- **Status:** MITIGATED / PARTIAL · **Severity:** Low · **Priority:** P3 · **Category:** Provider idempotency
- **Affected —** payment initiation · `PaymentService.java:143-169` and provider receipt lookup.
- **Evidence:** best-effort receipt reuse reduces retry duplicates, but the provider call still occurs before the booking DB lock; concurrent requests can create an orphan second provider order.
- **Impact/target:** usually an unused provider order, with elevated risk when combined with PAY-007; persist a stable initiation intent/receipt before provider creation and serialize initiation at the intent boundary.
- **Acceptance/tests/monitoring:** concurrent identical requests create one provider order; alert on multiple active orders per booking.
- **Effort/owner/deps/limitations:** M · payments · intent design · related PAY-007 · current mitigation confirmed, provider concurrency not run.

### [DOC-001] Legacy `docs/codebase` detail pages remain stale historical references
- **Status:** PARTIAL · **Severity:** Low · **Priority:** P3 · **Category:** Documentation drift
- **Affected —** developer onboarding/reference · `docs/codebase/01-*` through `10-*`.
- **Evidence:** canonical audit/current architecture docs are rebuilt, and `docs/codebase/00-INDEX.md` warns of supersession, but individual pre-overhaul pages still describe removed/changed surfaces.
- **Impact/target:** developers may implement against stale detail; retain them only as explicitly dated historical material or regenerate from current source.
- **Acceptance/tests/monitoring:** every legacy page carries a visible status/date/canonical link or is replaced; link and stale-symbol scan in docs CI.
- **Effort/owner/deps/limitations:** M · docs/engineering · choose archive vs rebuild · current canonical set reduces severity but does not erase stale files.

## Remediation log (2026-07-13/14) — implemented in working tree

Fixes applied per `18-REMEDIATION-ROADMAP.md`; not yet runtime-verified in a full rebuild. Regression tests: `PricingMathTest`, `PaymentServiceRefundFailClosedTest`, `AdminRecoveryQueueScopeTest`, plus updates to `PaymentServiceTest` / `WaitlistServicePromotionRaceTest`.

**Gap-closure pass (2026-07-15)** — third pass; closed every remaining code-side item against a **verified-green full-reactor baseline** (`BUILD SUCCESS`, all 9 modules, 15:27, zero failures) and re-verified after: backend `BUILD SUCCESS / exit 0` (incl. new `DstTransitionTests` 2/2 and `LoyaltyClawbackReachability` 3/3) and frontend `32/32` passing.
- *BOOK-003 → FIXED:* DST spring-forward guard in the availability grid + a defence-in-depth reject in `validateWithinOperatingHours`; no-op for non-DST zones.
- *A11Y-002 → FIXED:* shared `FormField` now emits `aria-describedby`→error-`id` + `role="alert"` (every consumer inherits it); `AdminRegister` + `AdminLogin` associated.
- *LOYALTY-001 → RESOLVED by design + locked:* traced the register's untraced exposure window — earn fires only at COMPLETED and COMPLETED→CANCELLED is unreachable, so the edge cannot occur. Deliberately did **not** build the maturation-hold feature (would degrade UX and mutate a verified-robust subsystem to defend a non-reachable edge); pinned the invariant with tests instead.
- *DATA-007 → FIXED:* V77 drops three indexes proven **structurally** prefix-redundant (no prod `pg_stat` pull needed).
- *DATA-006 → FIXED:* rounding contract documented. *DOC-002 → FIXED:* proprietary LICENSE + NOTICE.
- Remaining code-side gap: TEST-001's Testcontainers DB-concurrency harness. Remaining operator actions: git-history purge, prod secret rotation, Razorpay webhook registration.

**Self-audit pass (2026-07-15)** — second review of the remediation itself; all deltas applied and the FULL unit-test suite of every module now passes in a containerized Maven build (only the infra-bound `BingeRepositoryGeoTest` excluded; `BookingFlowIntegrationTest` is `@Disabled` upstream):
- *Verified clean:* `user.anonymized` deserializes on all three consumers (header-driven JsonDeserializer + trusted package; auth's producer emits type headers); Postgres 16 everywhere so the V75 trigger's `hashtextextended` is safe; the admin refund UI's try/catch surfaces server error messages unchanged.
- *Gap fixed — hold enforcement bypass:* reschedule and admin-edit date/time changes could steal a slot a customer was holding (BOOK-001 only covered create). Both paths now count foreign live holds exactly like `createBooking`.
- *Gap fixed — refund failure masked as success:* `POST /admin/refund` returned 200 "Refund initiated" even when the gateway refund FAILED. Controller now returns 502 with the failure reason (row stays in the failed-refund queue) and labels PROCESSING acceptances distinctly.
- *Gap fixed — missing DLT topics:* `user.anonymized-dlt` is now pre-created by booking + payment `KafkaConfig` so a poison erasure event parks instead of relying on broker auto-create defaults.
- *Test fix:* `PaymentControllerAuthzTest` (@WebMvcTest slice) needed a mock for the controller's new `RefundWebhookService` dependency; one incorrect arithmetic expectation in the new `PricingMathTest` corrected.

| ID | Status | What shipped |
|---|---|---|
| SEC-001 | **FIXED** | Recovery reads scoped via `requireManagedBinge` → owned `bingeId` predicate in the 4 queries; recovery ACTIONS also ownership-checked; SUPER_ADMIN w/o binge = platform view |
| SEC-002 | **FIXED** | Invoice list uses `requireManagedBinge` (was presence-only) |
| SEC-003 | **FIXED** | `spring.profiles.group.kubernetes: production` in all 6 services + explicit `kubernetes,production` in k8s ConfigMap |
| SEC-004 | **FIXED** | VAPID defaults removed (fail-fast); fresh dev keypair in `.env`; compose passthrough; keys added to k8s secret sync |
| SEC-005 | **FIXED** | `AdminOpsController` now SUPER_ADMIN-only; funnel ownership-checked; `ModulePermissionInterceptor` fails CLOSED on missing binge/user context |
| SEC-006 | **FIXED** | Transfer preview masks both emails (`LogSanitizer.maskEmail`) |
| SEC-007 | **FIXED** | `admin_token.txt`/`stress-tokens.txt` untracked; `.gitignore` extended; local `JWT_SECRET` rotated. History purge + prod-secret rotation remain operator tasks |
| SEC-008 | **FIXED** | reCAPTCHA weak default removed — prod boot fails without the key; dev stub unaffected |
| PAY-002 | **FIXED** | Real `RazorpayGatewayClient.createRefund` wired into all 3 refund paths, fail-closed; PROCESSING state settles via new `refund.processed/failed` webhook route + reconciliation poller; cash/simulated payments settle locally (documented) |
| PAY-003 | **FIXED** | Signature verification hoisted above the late-capture branch (all state-changing branches now behind HMAC) |
| PAY-004 | **FIXED** | Provider `verifyCallback` does real HMAC; `refund()` delegates to the gateway client |
| PAY-005 | **MITIGATED / PARTIAL** | Best-effort receipt reuse reduces retry duplicates, but provider order creation still precedes the booking DB lock; see the current PAY-005 detail |
| DATA-001 | **FIXED** | V75: `booking_occupancy_backstop` trigger — mirrors capacity/overlap semantics, own advisory-lock keyspace, catches lock-skipping writers |
| DATA-002 | **FIXED** | V14 (payment): `CHECK amount>0` + partial unique on `gateway_refund_id`; app guard now counts in-flight (PROCESSING) refunds |
| DATA-003 | **FIXED** | `auto-index-creation: true` + `MongoIndexBootstrap` (deterministic ensure + reminder dedup + startup verification/alert) |
| DATA-004 | **FIXED** | `user.anonymized` event from auth → booking (bookings/waitlist/holds/transfers), payment (payments), notification (Mongo deletes) redaction listeners; topic added to compose/k8s init |
| DATA-005 | **FIXED** | Duplicate/unpaid guards re-checked under the advisory lock |
| DATA-008 | **RESOLVED (stale finding)** | Current code stores blocks in minutes and ceil-rounds to 30-min indices in grid + `isSlotAvailable` — the hour-granularity gap predates the July overhaul |
| BOOK-001 | **FIXED (wired)** | `holdToken` on `CreateBookingRequest`; `consumeHold` in-transaction; foreign live holds block direct bookings (room-less, per-room pinned, total capacity); wizard now creates the hold, shows countdown, passes token, releases on exit |
| BOOK-002 | **FIXED** | OFFER creates a real SlotHold for the offer window (stored as `offer_hold_token`, V76); released on expiry/cancel; `BookingCreatedEvent` (AFTER_COMMIT) closes OFFERED→BOOKED and releases the hold; capacity check now counts live holds |
| PRICE-001 | **FIXED** | Canonical `PricingService.computeBaseAmount/computeGuestAmount/applySurge`; all 5 paths delegate; golden tests pin rounding |
| PRICE-002 | **FIXED (removed)** | Whole dormant surface deleted: `/checkout/preview`, `/checkout/lock-fx`, `FxLockService`, `FxRateLock`+repo, DTOs, `fxLockToken`, frontend `checkoutService`. `fx_rate_locks` table left in place (empty, harmless) |
| REL-001 | **FIXED** | Serializer/class-cast carve-out removed — every failure class parks at MAX_ATTEMPTS; replay via `/admin/ops/outbox/retry-failed` |
| A11Y-001 | **FIXED** | Single Escape/backdrop closes; `confirmClose` opt-in keeps the guard with an `aria-live` hint |
| A11Y-002 | **FIXED** | Register, Login, shared `PhoneField` associate errors; the shared `FormField` now emits `aria-describedby` → error `id` + `role="alert"` so every consumer inherits it; `AdminRegister` (all raw fields) + `AdminLogin` (labels + MFA hint) associated |
| API-001 | **FIXED** | Orphaned checkout client removed; `BookingPage` analytics reads the server's `totalAmount`. Admin loyalty-redeem remains a product decision (UI stays gated) |
| DEVOPS-002/005 | **FIXED** | k6/log/test artifacts + `.vite/` untracked; ignore rules extended; `backend;C` deleted |
| DEVOPS-004 | **FIXED** | `.env.example` reconciled; `PAYMENT_SIMULATION_ENABLED` + WEBPUSH/RAZORPAY_WEBHOOK_SECRET documented |
| DOC-003 | **RESOLVED by BOOK-001** | The documented hold guarantee is now actually enforced |
| TEST-001 | **PARTIAL** | Authz scope fence, refund fail-closed suite, pricing golden tests, waitlist offer-hold check added. DB-level concurrency tests (advisory-lock race, trigger) still need a Testcontainers harness |
| BOOK-003 | **FIXED** | DST spring-forward guard: availability slot generation skips wall-clock times that do not exist in the venue zone (`slotWallClockExists`), and `validateWithinOperatingHours` rejects a directly-submitted phantom start time (defence-in-depth). No-op for non-DST zones (`getTransition` → null), so IST is byte-for-byte unchanged. Tests: `AvailabilityServiceTest$DstTransitionTests` (America/Chicago 2026-03-08 gap skipped; Asia/Kolkata emits all 12) |
| LOYALTY-001 | **RESOLVED by design + locked** | Traced the untraced exposure window: earn fires **only** on `BookingCompletedEvent` (`LoyaltyV2BookingListener#onBookingCompleted`), so "redeemability deferred to COMPLETED" is already the behaviour. The earn→spend→cancel edge additionally requires COMPLETED→CANCELLED, which is **unreachable**: `OVERRIDE_TARGETS` allows COMPLETED→{CHECKED_IN} only, CHECKED_IN is not an override *source*, the normal table is CHECKED_IN→{COMPLETED}, and `undoCheckIn` refuses once checked-out. A maturation-hold feature was deliberately NOT built (would degrade every customer's UX and mutate a verified-robust subsystem to defend a non-reachable edge). Invariant pinned by `BookingStateMachineTest$LoyaltyClawbackReachability` (3 tests) so a future table edit cannot silently open it. Balance-aware clawback stays as defence-in-depth |
| DATA-006 | **FIXED** | `docs/MONEY_SCALE_AND_ROUNDING_CONTRACT.md` — the three boundaries (computation / charge / ledger), round-once-`HALF_UP`-at-charge to currency minor units, `NUMERIC(14,4)` for ledger rows, ≥8 dp for FX rates, minor-unit reconciliation tolerance, and a checklist for new finance code |
| DATA-007 | **FIXED** | V77 drops `idx_booking_date`, `idx_booking_customer`, `idx_booking_binge_date`. Each is a **strict leading-column prefix** of an existing composite (`idx_bookings_date_status`, `idx_booking_customer_status`, `idx_bookings_binge_date_status`), so redundancy is structural — no query loses an access path and no prod `pg_stat` pull was needed to prove it. Reversible; `idx_booking_status` and all partial indexes left intact |
| DOC-002 | **FIXED** | Proprietary `LICENSE` (All Rights Reserved) + `NOTICE` at repo root; NOTICE points at the build manifests as the authoritative third-party list rather than a drift-prone hand-maintained copy |
| SEC-007/DEVOPS-002 follow-ups | OPEN (operator) | Git-history purge (filter-repo) and prod JWT/VAPID rotation are coordinated operator actions — deliberately not executed from here |
| PAY-002 follow-up | OPEN (operator) | Register the `refund.processed` / `refund.failed` webhook events in the Razorpay dashboard and supply refund-API credentials; the code path is implemented and tested |
| TEST-001 | **PARTIAL** | Authz scope fence, refund fail-closed suite, pricing golden tests, waitlist offer-hold check, DST-transition tests, and the loyalty reachability lock added. DB-level concurrency tests (advisory-lock race, V75 trigger) still need a Testcontainers harness — the only remaining code-side gap |
| DOC-001 | OPEN | Codebase-doc rebuild (low, cosmetic) |

Evidence lives in `docs/audit/evidence/specialist-0[1-9]-*.md` and `21-RUNTIME-VERIFICATION-LOG.md`. Specialists 01–05 preserve the earlier audit/remediation history; 06–09 are the 2026-07-16 current-tree supplements. Line numbers are from the working tree at commit `e3edbc1` (uncommitted July overhaul included).

**Template (2026-07-12):** every issue below carries the full master-prompt field set — Status/Severity/Priority/Category, the six *Affected* axes, Evidence, Observed/Expected/Trigger/Root cause, the four *Impact* fields (Direct/Future/Blast radius/Dependencies), the six *impact-by-axis* fields (Customer/Binge/Platform/Financial/Security-privacy/Data-integrity), Correct-target/Recommended/Smallest-safe remediation, Alternative/Tradeoffs, Acceptance/Required-tests/Required-monitoring, and Effort/Owner/Blocking-deps/Related/Confidence-limitations. Fields that genuinely do not apply are marked `n/a` with a reason rather than omitted.

## Historical discovery summary (superseded for current status)

> The following table and detailed sections preserve the July 12 discovery state. They are **not an active-issue count**. The current table at the top of this file and the remediation log are authoritative.

| ID | Title | Status | Severity | Priority |
|---|---|---|---|---|
| SEC-001 | Cross-binge customer PII leak in recovery-queue reads | CONFIRMED | Critical | P0 |
| PAY-002 | Refunds never call Razorpay — book-keeping only (silent financial mismatch) | CONFIRMED | Critical | P0 |
| SEC-002 | Cross-binge invoice/financial list via missing ownership check | CONFIRMED | High | P0 |
| SEC-003 | Production Spring profile never activated → captcha stub + payment guards inert | CONFIRMED | High | P0 |
| SEC-007 | Live-looking JWTs committed and tracked in git | CONFIRMED | High | P1 |
| DATA-001 | No DB backstop for double-booking (advisory lock is sole guard; guard CONFIRMED working at runtime) | CONFIRMED | High | P1 |
| DATA-003 | Mongo TTL + unique indexes inert → unbounded PII, possible double-send (runtime-CONFIRMED absent) | CONFIRMED | High | P1 |
| BOOK-001 | Slot-hold → booking hand-off is dead code (hold guarantee illusory) | CONFIRMED | High | P1 |
| DATA-004 | Cross-service PII survives auth anonymization | HIGH CONFIDENCE | High | P1 |
| PAY-003 | Late-capture auto-refund acts before signature verification | CONFIRMED | Medium (High once refunds real) | P1 |
| PRICE-001 | Pricing orchestration formula duplicated across 5 paths (display-vs-charge drift risk) | CONFIRMED | Medium | P2 |
| PRICE-002 | FX-lock / multi-currency feature is a dead end — lock created but never consumed; native per-binge pricing only (corrects a prior positive-control claim) | CONFIRMED (static+runtime) | Medium | P2 |
| A11Y-001 | Modal requires double-Escape / double-backdrop-tap to close (non-standard dialog contract) | CONFIRMED | Medium | P2 |
| A11Y-002 | Form validation errors not programmatically associated with inputs (aria-describedby=0, aria-invalid=6) | HIGH CONFIDENCE | Medium | P2 |
| DATA-002 | Over/duplicate refund not enforced at DB (app guard confirmed present) | CONFIRMED | Medium | P2 |
| SEC-005 | Platform control-plane + funnel aggregates reachable by any binge admin | PROBABLE | Medium | P2 |
| SEC-004 | Committed VAPID Web Push private key as silent default | CONFIRMED | Medium | P2 |
| DATA-005 | Multi-room same-customer duplicate PENDING booking (pre-lock TOCTOU) | HIGH CONFIDENCE | Medium | P2 |
| DATA-008 | Availability blocked-slot hour vs booking 30-min granularity mismatch | QUESTION | Medium | P2 |
| REL-001 | Poison-message (serializer bug) retries indefinitely, not DLQ'd | CONFIRMED | Medium | P2 |
| BOOK-002 | Waitlist OFFER does not reserve slot; converter caller unverified | HIGH CONFIDENCE | Medium | P2 |
| DEVOPS-002 | Repo hygiene: tracked build/k6/log artifacts, stray dirs | CONFIRMED | Medium | P2 |
| DOC-001 | Codebase/architecture docs stale (pre-July overhaul) | CONFIRMED | Medium | P2 |
| DOC-003 | Docs/UI describe slot-hold guarantee that does not exist | CONFIRMED | Medium | P2 |
| TEST-001 | No regression tests for the confirmed isolation/refund/duplicate gaps | CONFIRMED | Medium | P2 |
| API-001 | Minor frontend↔backend field-drift + orphaned checkout client (method-level diff findings) | CONFIRMED | Low | P3 |
| DEVOPS-003 | Compose services rely on Dockerfile healthchecks (present in all 9) | CONFIRMED (resolved) | Low | P3 |
| PAY-004 | PaymentProvider abstraction is an incomplete stub | CONFIRMED | Low | P3 |
| PAY-005 | @Retry on order creation can create duplicate Razorpay orders | CONFIRMED | Low | P3 |
| SEC-006 | Booking-transfer public preview leaks both parties' emails | CONFIRMED | Low | P3 |
| SEC-008 | reCAPTCHA secret has weak dev default | CONFIRMED | Low | P3 |
| DATA-006 | Money scale inconsistent across finance tables | QUESTION | Low | P3 |
| DATA-007 | Redundant single-column indexes on hot bookings table | PROBABLE | Low | P3 |
| BOOK-003 | DST slot skew for non-IST venue timezones | PROBABLE | Low | P3 |
| DEVOPS-004 | `.env` ↔ `.env.example` drift; simulation flag undocumented | CONFIRMED | Low | P3 |
| DOC-002 | No LICENSE/NOTICE file in repo | CONFIRMED | Low | P3 |
| LOYALTY-001 | Earn→spend→cancel may leave a small points gain (clawback can't recover spent points) | QUESTION | Low | P3 |
| DEVOPS-005 | Stray empty `backend;C` directory | CONFIRMED | Informational | P4 |
| PAY-001 | Payment area deep pass (was NOT VERIFIED — now RESOLVED) | RESOLVED | — | — |

Historical discovery set: **38 findings** (+ PAY-001 bookkeeping row). Current status/counts are at the top of this file.

---

## Historical Critical & High findings (at discovery)

### [SEC-001] Cross-binge customer PII leak in recovery-queue reads
- **Status:** CONFIRMED · **Severity:** Critical · **Priority:** P0 · **Category:** Authorization / tenant isolation
- **Affected —** application: booking admin SPA + booking-service · service: booking-service · module: Failed Refunds / Recovery queues · page/route: `/admin/recovery/**`, `/admin/failed-refunds` · workflow: operator recovery · database/event: `bookings`, `slot_holds`
- **Evidence:** `AdminRecoveryQueueController.java:79,98,122,147,164`; queries `BookingRepository.findStuckPending/findPaidButNotConfirmed/findNoShowBookings` (`:280-297`) and `SlotHoldRepository.findExpiredNotReleased` (`:42-46`) have **no `bingeId` predicate**; rows expose `customerId`, `customerEmail`, `amount` (`:82-88`). No class-level `@ModelAttribute` ownership guard. Contradicts `CrossBingeIsolationTest.java:44-47`.
- **Observed:** Any authenticated ADMIN (any binge) receives other binges' customers' PII and amounts via the recovery-queue endpoints.
- **Expected:** Every admin read is scoped to a binge the caller owns (`requireManagedBinge`) with a `bingeId` predicate at the repository.
- **Trigger:** Binge-A admin calls a recovery endpoint with `X-Binge-Id: B` (or with no scoping at all — the queries are unscoped regardless of header).
- **Root cause:** Isolation is enforced per-endpoint by developer discipline; this controller bypasses `AdminBingeScopeService` and queries repositories directly. `X-Binge-Id` is client-controlled and never validated at the gateway (`api-gateway/.../JwtAuthenticationFilter.java:136-153` strips user/authority headers but not binge id).
- **Impact —** Direct: cross-tenant PII + amount disclosure · Future: DPDP/GDPR breach, competitor scraping if a hostile admin is onboarded · Blast radius: **every binge on the platform** · Dependencies affected: shares the root cause with SEC-002/SEC-005.
- **Impact by axis —** Customer: PII (email) exposed to another tenant · Binge: competitive/financial data leak · Platform: legal + trust exposure · Financial: amounts leaked (not moved) · Security/privacy: **high — confidentiality breach** · Data-integrity: none (read-only).
- **Correct target state:** class-level `@ModelAttribute requireManagedBinge` + a `bingeId` parameter added to the four repository queries and filtered.
- **Recommended remediation:** add the `bingeId` predicate to the four queries; resolve `bingeId` from an ownership-checked context, not the raw header.
- **Smallest safe remediation:** wrap each of the four handlers with `adminBingeScopeService.requireManagedBinge(...)` and pass its result as the query's `bingeId`; reject null binge for non-super-admin.
- **Alternative considered:** gateway-level binge validation (rejected as smallest-fix — needs a binge-membership lookup at the edge; better as defence-in-depth later).
- **Tradeoffs:** super-admin cross-binge recovery must be explicitly allowed (branch on role).
- **Acceptance:** binge-A admin with `X-Binge-Id: B` → 403 or only binge-A rows; regression test asserts no cross-binge rows.
- **Required tests:** authz test per recovery endpoint with spoofed `X-Binge-Id`. · **Required monitoring:** alert when result rows' bingeId ≠ context bingeId.
- **Effort:** S · **Owner:** backend/security · **Blocking deps:** none · **Related:** SEC-002, SEC-005 · **Confidence limitations:** static + repo-boundary test; not runtime-exercised with two real admin tokens (harness CSRF limit).

### [PAY-002] Refunds never call Razorpay — book-keeping only (silent financial mismatch)
- **Status:** CONFIRMED · **Severity:** Critical · **Priority:** P0 · **Category:** Financial correctness
- **Affected —** application: payment-service + admin Failed-Refunds UI · service: payment-service · module: refund / failed-refund queue · page/route: `/admin/failed-refunds`, cancellation refund · workflow: cancellation & refund · database/event: `refunds`, `payment.refunded`, customer refund email
- **Evidence:** All three refund paths generate a local fake gateway id `"RFD-"+UUID` and mark SUCCEEDED without any gateway call — `initiateRefund` (`PaymentService.java:605-645`), late-capture auto-refund (`:317-332`), `retryFailedRefund`/`executeApprovedRefundRetry` (`:958-995`). `RazorpayGatewayClient` has **no** refund method (only `createOrder`/`fetchOrderStatus`); `RazorpayPaymentProvider.refund()` is `NOT_IMPLEMENTED` (`RazorpayPaymentProvider.java:74-82`). Not gated by `paymentSimulationEnabled`. Refund events are published (`publishRefundEvent`), triggering customer "refunded" emails.
- **Observed:** Refunds show SUCCEEDED (admin UI, DB `REFUNDED`, customer email) but **no money is returned**.
- **Expected:** `initiateRefund` calls Razorpay's refund API, records the real gateway refund id, and only marks SUCCEEDED on gateway confirmation (ideally async via a refund webhook).
- **Trigger:** any refund — cancellation refund, admin failed-refund retry, or late-capture auto-refund.
- **Root cause:** the gateway refund integration was never implemented; the local book-keeping was left as the whole path with no guard/TODO.
- **Impact —** Direct: customer told "refunded", receives nothing · Future: chargebacks, disputes, regulatory complaints · Blast radius: **every refund** · Dependencies affected: PAY-003 (unauthorized-refund vector becomes real), DATA-002 (DB guard matters once gateway rows exist).
- **Impact by axis —** Customer: money not returned despite confirmation · Binge: chargeback fees + reputation · Platform: systemic financial mismatch · Financial: **direct loss/mismatch on every refund** · Security/privacy: n/a · Data-integrity: ledger says REFUNDED while gateway shows captured.
- **Correct target state:** implement `RazorpayGatewayClient.createRefund(paymentId, amount)`, wire `RazorpayPaymentProvider.refund`, drive status from the gateway response + refund webhook; keep the existing over-refund lock/guard.
- **Recommended remediation:** as target; add a `PENDING_GATEWAY` refund state and only settle on webhook.
- **Smallest safe remediation:** gate all three paths behind a check that fails closed unless a real gateway refund id is returned (prevents "fake SUCCEEDED" even before full async wiring).
- **Alternative considered:** manual refunds via Razorpay dashboard + reconciliation (viable stop-gap; still needs the fake-SUCCEEDED removed).
- **Tradeoffs:** async refund webhook adds a state to the machine and a reconciliation job.
- **Acceptance:** a refund reflects a real Razorpay refund id; a gateway failure leaves the refund FAILED, not SUCCEEDED.
- **Required tests:** refund end-to-end against Razorpay sandbox; partial/failed refund; refund-webhook dedup. · **Required monitoring:** alert on refunds SUCCEEDED without a gateway refund id; daily gateway-vs-ledger reconciliation.
- **Effort:** M · **Owner:** payments · **Blocking deps:** Razorpay refund API creds/config · **Related:** PAY-003, DATA-002, SEC-003 · **Confidence limitations:** may be an intended pre-launch/simulation stage, but there is no guard/TODO and it is not simulation-gated — treated as a defect.

### [SEC-002] Cross-binge invoice/financial list via missing ownership check
- **Status:** CONFIRMED · **Severity:** High · **Priority:** P0 · **Category:** Authorization / tenant isolation
- **Affected —** application: admin SPA + booking-service · service: booking-service · module: invoices/reports · page/route: `GET /api/v1/bookings/admin/invoices` · workflow: admin invoice listing · database/event: `invoices`
- **Evidence:** `InvoiceController.java:74-80` calls only `adminBingeScopeService.requireSelectedBinge(...)` (presence, no ownership) then `invoiceService.listForBinge(headerBingeId)`; sibling `downloadInvoice`/`resendInvoice` correctly use `requireBingeOwnership` (`:55,92`) — this endpoint is the outlier. Summary exposes customerId, invoiceNumber, subtotal/tax/grand totals (`:103-116`). Verifier-confirmed 2026-07-12.
- **Observed:** Binge-A admin sending `X-Binge-Id: B` receives binge B's invoice list + financial totals.
- **Expected:** change `requireSelectedBinge` → `requireManagedBinge`.
- **Trigger:** Binge-A admin sets `X-Binge-Id: B` and calls the list endpoint.
- **Root cause:** wrong scope helper on one endpoint (presence vs ownership) — same discipline gap as SEC-001.
- **Impact —** Direct: cross-tenant invoice/financial disclosure · Future: same DPDP/competitor exposure · Blast radius: every binge · Dependencies affected: SEC-001 (same root).
- **Impact by axis —** Customer: invoice PII/amounts leaked · Binge: revenue figures leaked · Platform: legal/trust · Financial: figures disclosed (not moved) · Security/privacy: high · Data-integrity: none.
- **Correct target state / Recommended / Smallest-safe:** one-line change `requireSelectedBinge` → `requireManagedBinge` on `listInvoicesForBinge`.
- **Alternative considered:** none needed (XS fix). · **Tradeoffs:** none.
- **Acceptance:** binge-A admin with `X-Binge-Id: B` → 403. · **Required tests:** authz test on the list endpoint. · **Required monitoring:** covered by the SEC-001 cross-binge-row alert.
- **Effort:** XS · **Owner:** backend/security · **Blocking deps:** none · **Related:** SEC-001 · **Confidence limitations:** static (endpoint compared against its own siblings).

### [SEC-003] Production Spring profile never activated → security stubs load in real deployment
- **Status:** CONFIRMED · **Severity:** High · **Priority:** P0 · **Category:** Deployment / security config
- **Affected —** application: auth-service, payment-service (all `@Profile` beans) · service: auth + payment · module: captcha + payment config guards · page/route: n/a (boot config) · workflow: login bot-gate, payment safety guards · database/event: n/a
- **Evidence:** k8s sets `SPRING_PROFILES_ACTIVE: "kubernetes"` (`k8s/namespace.yml:14`); compose sets none; no `spring.profiles.group` maps `kubernetes`→`production`. `StubCaptchaValidationService` (`@Profile("!production")`, `auth/.../impl/StubCaptchaValidationService.java:13`) returns true for any non-blank token; `RecaptchaValidationService` (`@Profile("production")`) never registers. `PaymentService.validateConfig` computes `isProduction` from active profiles (`payment/.../PaymentService.java:86-119`) → always false → FATAL "simulation off / live key `rzp_live_`" guards inert. Prometheus alert (`k8s/monitoring.yml:673-683`) assumes `production` active.
- **Observed:** In any real deployment, captcha is not validated and the payment safety guards never fire.
- **Expected:** activate `production` in real deployments.
- **Trigger:** any real deploy using the shipped profile config.
- **Root cause:** the `production` profile exists but no environment activates it (missing `spring.profiles.group` mapping).
- **Impact —** Direct: bot-gate + payment guards disabled in prod · Future: catastrophic if `PAYMENT_SIMULATION_ENABLED=true` reaches prod (guard that would block it is inert) · Blast radius: platform-wide · Dependencies affected: SEC-008, DEVOPS-004, PAY-001.
- **Impact by axis —** Customer: weaker abuse protection · Binge: n/a · Platform: defeated defence-in-depth · Financial: sim-mode-in-prod risk · Security/privacy: high (bot gate defeated; account-lockout + per-IP 30/min still apply) · Data-integrity: n/a.
- **Correct target state:** `SPRING_PROFILES_ACTIVE: "kubernetes,production"` or `spring.profiles.group.kubernetes: production`.
- **Recommended / Smallest-safe:** add the one-line `spring.profiles.group` mapping so `production` always rides with `kubernetes`.
- **Alternative considered:** set the env var per-deployment (rejected — easy to forget; the group mapping is centralized).
- **Tradeoffs:** must ensure all `@Profile("production")` beans are actually production-safe (reCAPTCHA keys present).
- **Acceptance:** in a prod-profile boot, `RecaptchaValidationService` is the active bean and `validateConfig` enforces the live-key guard.
- **Required tests:** context test asserting the production captcha bean loads under the prod profile. · **Required monitoring:** boot-time log/metric of active profiles; the existing Prometheus alert then becomes valid.
- **Effort:** XS · **Owner:** devops/security · **Blocking deps:** reCAPTCHA prod keys · **Related:** SEC-008, DEVOPS-004, PAY-001 · **Confidence limitations:** static config analysis; not booted under a prod profile on host.

### [SEC-007] Live-looking JWTs committed and tracked in git
- **Status:** CONFIRMED · **Severity:** High · **Priority:** P1 · **Category:** Secret hygiene
- **Affected —** application: repo · service: n/a · module: repo root · page/route: n/a · workflow: n/a · database/event: n/a · files: `admin_token.txt` (1 JWT), `stress-tokens.txt` (2 JWTs)
- **Evidence:** both files contain `eyJ`-prefixed tokens and are tracked by git despite matching `.gitignore` (grandfathered; `git ls-files` lists them). Values not printed (Rule 7).
- **Observed:** Bearer/access tokens present in version control.
- **Expected:** no credentials in VCS.
- **Trigger:** any clone/fork exposes the tokens.
- **Root cause:** test artifacts committed before the ignore rules existed.
- **Impact —** Direct: credential exposure in VCS · Future: account takeover if tokens ever valid against a real env · Blast radius: whoever the token authorizes · Dependencies affected: DEVOPS-002 (same artifact-hygiene root).
- **Impact by axis —** Customer: n/a · Binge: n/a · Platform: auth compromise if reused · Financial: indirect · Security/privacy: high (credential) · Data-integrity: n/a.
- **Correct target state:** tokens absent from working tree and history; `JWT_SECRET` rotated if they were ever real.
- **Recommended remediation:** `git rm --cached` both; purge from history (filter-repo) if they were valid against any real environment; rotate `JWT_SECRET`.
- **Smallest safe remediation:** `git rm --cached` both and confirm ignore rules; rotate the signing secret.
- **Alternative considered:** leave (rejected — credentials in VCS).
- **Tradeoffs:** history rewrite requires coordinated force-push.
- **Acceptance:** `git ls-files` no longer lists the two files; secret scanner clean.
- **Required tests:** CI secret-scan gate. · **Required monitoring:** pre-commit/secret-scan hook.
- **Effort:** S · **Owner:** security · **Blocking deps:** confirm whether tokens are/were valid · **Related:** DEVOPS-002 · **Confidence limitations:** token validity against a live env not tested (would require using the token — out of scope).

### [DATA-001] No database backstop for double-booking
- **Status:** CONFIRMED (static + live DB) · **Severity:** High · **Priority:** P1 · **Category:** Data integrity / concurrency
- **Affected —** application: booking-service · service: booking-service · module: availability/booking write path · page/route: `POST /api/v1/bookings` · workflow: customer booking · database/event: `bookings`, `slot_holds`
- **Evidence:** Live DB (`21-...LOG.md` R2): `bookings` unique indexes = pkey + `idx_booking_ref`; `slot_holds` = pkey + `idx_slot_holds_token`. No UNIQUE/EXCLUSION on any slot tuple `(binge, date, start_time, room)`. Double-booking is prevented solely by a Postgres transaction-scoped advisory lock `pg_advisory_xact_lock` (`BookingRepository.java:419-420`, key `slotLockKey:4317-4321`) + post-lock conflict checks.
- **Observed:** Physical double-booking is currently prevented, but no schema safety net exists.
- **Expected:** an `EXCLUDE`/partial-unique constraint as belt-and-suspenders behind the advisory lock.
- **Trigger:** a future write path that forgets `acquireSlotLock`, or a multi-primary Postgres topology.
- **Root cause:** concurrency safety lives entirely in application code, not the schema.
- **Impact —** Direct: none today (guard works) · Future: latent irrecoverable double-booking if a path skips the lock · Blast radius: per-binge inventory integrity · Dependencies affected: DATA-005, BOOK-001.
- **Impact by axis —** Customer: two bookings for one slot (future) · Binge: overbooked inventory · Platform: support burden · Financial: refund/goodwill for the loser · Security/privacy: n/a · Data-integrity: **the core risk (latent)**.
- **Correct target state:** Postgres `EXCLUDE`/partial-unique on active-booking room+date+time-window (capacity-1) behind the advisory lock.
- **Recommended remediation:** add the constraint with a careful migration + backfill dedup.
- **Smallest safe remediation:** partial unique index `(binge_id, venue_room_id, booking_date, start_time) WHERE status IN ('PENDING','CONFIRMED',...)` for capacity-1 rooms.
- **Alternative considered:** rely on the advisory lock alone (rejected — no defence-in-depth).
- **Tradeoffs:** multi-capacity rooms need an EXCLUDE with a counter or range type, not a simple unique.
- **Acceptance:** concurrent inserts for the same capacity-1 slot → exactly one succeeds even with the advisory lock removed in test.
- **Required tests:** concurrency test with the constraint; migration backfill dedup test. · **Required monitoring:** alert on constraint-violation rate (signals a lock-skipping path).
- **Effort:** M · **Owner:** backend/db · **Blocking deps:** none · **Related:** DATA-005, BOOK-001
- **Runtime note (R7.1): the guard WORKS** — two customers racing a capacity-1 slot → exactly one booking (authoritative `psql` count = 1). This is a **defence-in-depth** gap, not a live bug.

### [DATA-003] Mongo TTL and unique indexes are inert (auto-index-creation disabled)
- **Status:** CONFIRMED (runtime, 2026-07-12) · **Severity:** High · **Priority:** P1 · **Category:** Privacy/retention + correctness
- **Affected —** application: notification-service · service: notification-service · module: notifications + reminders · page/route: n/a (async) · workflow: notification delivery + reminders · database/event: MongoDB `notification_db` (`notifications`, `booking_reminders`)
- **Evidence:** `notification-service.yml` does not set `spring.data.mongodb.auto-index-creation`; Spring Boot 3 default is `false`, so `@Indexed(expireAfter="P90D")` on `Notification` (`Notification.java:60-62`) and `@CompoundIndex(unique)` on `BookingReminder` (`:18`) are never created. **Runtime (R7.2):** `getIndexes()` on live `notification_db` (73 notifications, 79 reminders) shows **only `_id_` on every collection**.
- **Observed:** Notifications (with `recipientEmail`/`recipientPhone`) never auto-expire → unbounded PII; reminder uniqueness unenforced → possible duplicate reminders.
- **Expected:** TTL + unique compound indexes actually exist in each environment.
- **Trigger:** time (PII accrues) and concurrent reminder inserts (duplicates).
- **Root cause:** auto-index-creation is off (Spring Boot 3 default) and no explicit index bootstrap replaces it.
- **Impact —** Direct: PII retained indefinitely; possible double-send · Future: retention breach grows unbounded · Blast radius: all notification recipients · Dependencies affected: DATA-004 (Mongo PII erasure).
- **Impact by axis —** Customer: PII over-retained; possible duplicate messages · Binge: n/a · Platform: retention-policy breach · Financial: minor (duplicate SMS/WhatsApp cost) · Security/privacy: **high (retention)** · Data-integrity: reminder dedup unenforced.
- **Correct target state:** `spring.data.mongodb.auto-index-creation: true` OR explicit `IndexOperations` bootstrap; verify TTL per environment.
- **Recommended remediation:** explicit index-ensuring `@PostConstruct`/migration (deterministic regardless of the global flag).
- **Smallest safe remediation:** set `auto-index-creation: true` and redeploy; confirm `expireAfterSeconds` + unique compound appear.
- **Alternative considered:** a scheduled purge job (rejected — TTL index is native and cheaper).
- **Tradeoffs:** creating a unique index on existing dup reminders may fail — dedup first.
- **Acceptance:** `getIndexes()` shows `expireAfterSeconds` on `notifications` and the unique compound on `booking_reminders`.
- **Required tests:** integration test asserting indexes exist post-boot. · **Required monitoring:** startup check that expected indexes are present; alert if missing.
- **Effort:** S · **Owner:** notification/backend · **Blocking deps:** dedup existing reminders before unique index · **Related:** DATA-004 · **Confidence limitations:** none — runtime-confirmed absent.

### [BOOK-001] Slot-hold → booking hand-off is dead code; the "hold guarantee" is illusory
- **Status:** CONFIRMED · **Severity:** High · **Priority:** P1 · **Category:** Domain correctness / misleading feature
- **Affected —** application: booking-service + customer booking countdown + admin Slot Holds · service: booking-service · module: SlotHold · page/route: booking wizard hold timer, `/admin/slot-holds` · workflow: availability/holds · database/event: `slot_holds`
- **Evidence:** `SlotHoldService.consumeHold` (`:206-246`) and `releaseQuietly` (`:255-270`) have **zero callers** (verifier-confirmed 2026-07-12: `consumeHold` appears only at its own definition). `CreateBookingRequest` has no `holdToken` field. `BookingService.createBooking` never references a hold; conflict/capacity checks consider only bookings. `SlotHold` Javadoc ("guaranteed against concurrent bookings", `:14-17`) is not upheld.
- **Observed:** A held slot can be taken by a direct booking; the hold-holder is then rejected at their own checkout. Holds only serialize hold-vs-hold and always expire by TTL (never CONVERTED).
- **Expected:** either a hold genuinely reserves the slot, or the feature/claim is removed.
- **Trigger:** any customer who holds a slot while another customer books it directly.
- **Root cause:** the hold-consume hand-off into `createBooking` was never wired.
- **Impact —** Direct: broken "reserved" promise · Future: customer distrust as volume grows · Blast radius: any contended slot · Dependencies affected: DOC-003 (docs claim it), DATA-001.
- **Impact by axis —** Customer: loses a slot they were told was held · Binge: support complaints · Platform: misleading feature · Financial: minor · Security/privacy: n/a · Data-integrity: not a physical double-booking (advisory lock still protects).
- **Correct target state (product decision):** wire `consumeHold` into `createBooking` (add `holdToken`, consume in-transaction, count foreign live holds in capacity) OR remove the hold machinery + docs/UI claims.
- **Recommended remediation:** wire the hold if "reserve my slot" is a wanted UX; else delete it.
- **Smallest safe remediation:** if keeping, add `holdToken` to `CreateBookingRequest` and call `consumeHold` inside the booking transaction.
- **Alternative considered:** keep holds as advisory-only but correct the UI copy (cheaper; loses the guarantee).
- **Tradeoffs:** true holds require counting live holds in capacity — more contention logic.
- **Acceptance:** a held slot cannot be booked by a different customer until the hold expires/releases.
- **Required tests:** hold-then-direct-book concurrency test. · **Required monitoring:** metric on holds CONVERTED vs EXPIRED (currently 0% converted).
- **Effort:** M · **Owner:** backend/product · **Blocking deps:** product decision · **Related:** DOC-003, DATA-001 · **Confidence limitations:** none — dead code confirmed by call-graph.

### [DATA-004] Cross-service PII survives auth anonymization
- **Status:** HIGH CONFIDENCE · **Severity:** High · **Priority:** P1 · **Category:** Privacy / retention
- **Affected —** application: booking + payment + notification · service: booking-service, payment-service, notification-service · module: right-to-erasure · page/route: `DELETE /auth/privacy/me` · workflow: account deletion/anonymization · database/event: `bookings`, `payments`, Mongo `notifications`
- **Evidence:** `auth_db.users` has full soft-delete/anonymize columns (`V14`), but `bookings` (`customer_name/email/phone`, `V1:73-75`) and `payments` (`Payment.java:66-70`) snapshot PII with no soft-delete/retention column and no link to auth's anonymization. No propagation mechanism (no consumer redacts on an anonymization event).
- **Observed:** When auth anonymizes a user, PII copies in booking/payment/Mongo persist indefinitely → incomplete erasure.
- **Expected:** anonymization propagates to every service holding a PII copy.
- **Trigger:** a customer exercises right-to-erasure.
- **Root cause:** PII is snapshotted per service (denormalized) with no erasure fan-out.
- **Impact —** Direct: erasure incomplete cross-service · Future: DPDP/GDPR non-compliance under audit · Blast radius: every deleted user's historical records · Dependencies affected: DATA-003 (Mongo retention).
- **Impact by axis —** Customer: data not fully erased · Binge: n/a · Platform: regulatory exposure · Financial: potential penalty · Security/privacy: **high** · Data-integrity: n/a.
- **Correct target state:** emit a `user.anonymized` event consumed by booking/payment/notification to redact/tombstone PII; add retention columns.
- **Recommended remediation:** as target (event-driven erasure fan-out).
- **Smallest safe remediation:** a scheduled cross-service redaction job keyed by anonymized user ids (less timely but simpler than eventing).
- **Alternative considered:** store only user-id references, not PII snapshots (large refactor — rejected as smallest fix).
- **Tradeoffs:** redacting historical financial rows must preserve audit/tax obligations (tombstone, don't hard-delete).
- **Acceptance:** after anonymization, no plaintext PII remains for that user in booking/payment/Mongo.
- **Required tests:** erasure-propagation integration test across services. · **Required monitoring:** reconciliation of anonymized-user ids vs residual PII.
- **Effort:** M · **Owner:** privacy/backend · **Blocking deps:** legal retention rules for financial rows · **Related:** DATA-003 · **Confidence limitations:** HIGH not CONFIRMED — no propagation code found, but the full anonymization path was not runtime-exercised.

### [PAY-003] Late-capture auto-refund acts before signature verification
- **Status:** CONFIRMED · **Severity:** Medium (High once refunds move real money) · **Priority:** P1 · **Category:** Payment auth / integrity
- **Affected —** application: payment-service · service: payment-service · module: payment callback · page/route: `POST /api/v1/payments/callback` · workflow: payment result · database/event: `payments`, `refunds`, `processed_webhook_event`
- **Evidence:** In `handleCallback`, the normal path rejects unsigned callbacks and HMAC-verifies (`PaymentService.java:359-372`), but the late-capture branch (`:299-347`) transitions FAILED→SUCCESS→REFUNDED, writes an auto-refund, calls `recordWebhookProcessed`, and returns **before** that check, trusting `request.getStatus()`.
- **Observed:** the late-capture branch state-changes without verifying the signature.
- **Expected:** verify the signature at the top of `handleCallback`, before any state-changing branch.
- **Trigger:** an attacker who knows a cancelled booking's `gatewayOrderId` forges a "success" callback.
- **Root cause:** signature verification sits inside the normal branch instead of the method entry.
- **Impact —** Direct: forged callback can drive FAILED→SUCCESS→REFUNDED + a refund record · Future: a **real unauthorized-refund vector once PAY-002 is fixed** · Blast radius: any known order id · Dependencies affected: PAY-002.
- **Impact by axis —** Customer: n/a directly · Binge: n/a · Platform: payment-integrity hole · Financial: unauthorized refund once refunds move money · Security/privacy: auth bypass on a state-changing endpoint · Data-integrity: forged state transitions.
- **Correct target state / Recommended:** hoist signature verification to the method entry, before all branches.
- **Smallest safe remediation:** move the `verifySignature` guard above the late-capture branch.
- **Alternative considered:** verify inside the late-capture branch too (works, but entry-level is cleaner/complete).
- **Tradeoffs:** none material.
- **Acceptance:** an unsigned/badly-signed callback is rejected regardless of branch.
- **Required tests:** forged-callback test hitting the late-capture path → 403. · **Required monitoring:** count callbacks rejected for bad signature.
- **Effort:** S · **Owner:** payments · **Blocking deps:** none · **Related:** PAY-002 · **Confidence limitations:** static; limited today because refunds are fake (PAY-002).

---

## Historical Medium findings (at discovery)

### [PRICE-001] Pricing orchestration formula duplicated across 5 code paths
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Correctness / maintainability (money)
- **Affected —** application: booking-service · service: booking-service · module: pricing/checkout · page/route: checkout preview + booking create/update/reschedule/recurring · workflow: pricing · database/event: `bookings`, `booking_price_snapshots`
- **Evidence:** the same assembly — `baseAmount = basePrice + hourlyRate × (durationMinutes/60)` with `setScale(2, HALF_UP)`, `guestAmount = pricePerGuest × max(guests-1,0)`, surge multiply, then tax — is inline in **five** places: `CheckoutQuoteService.java:75-142`, `BookingService.java:290-386` (create), `:851-960` (update), `:1212-1250` (reschedule), `:1467-1527` (recurring). Primitives (`resolveEventPrice`, `resolveSurge`, `taxService.compute`) ARE shared.
- **Observed:** preview claims (by comment) to use "the EXACT formula createBooking applies" — parity is by convention, not a shared method.
- **Expected:** one shared `compute()` used everywhere.
- **Trigger:** any future edit to one path (surge-on-add-ons, rounding order) diverges the others.
- **Root cause:** copy-paste orchestration around shared primitives.
- **Impact —** Direct: none today (paths agree) · Future: display-vs-charge or create-vs-modify divergence · Blast radius: every priced booking · Dependencies affected: DATA-006 (rounding scale).
- **Impact by axis —** Customer: could be quoted ≠ charged (future) · Binge: revenue mis-price · Platform: maintainability · Financial: latent mis-charge · Security/privacy: n/a · Data-integrity: snapshot preserves the charged breakdown (mitigates).
- **Correct target state / Recommended:** extract a single `PriceQuote compute(...)` used by preview + all four booking paths.
- **Smallest safe remediation:** extract the assembly into one method; keep behaviour identical (characterization tests first).
- **Alternative considered:** leave with a shared characterization test guarding parity (weaker).
- **Tradeoffs:** refactor risk on money code — needs golden tests.
- **Acceptance:** all five entry points delegate to one method; golden tests pin outputs.
- **Required tests:** pricing golden tests across all paths (rate code, surge, add-ons, tax). · **Required monitoring:** alert on preview-vs-charged delta.
- **Effort:** M · **Owner:** backend · **Blocking deps:** none · **Related:** DATA-006, PRICE-002 · **Confidence limitations:** none.

### [PRICE-002] FX-lock / multi-currency feature is a dead end (corrects a prior positive-control claim)
- **Status:** CONFIRMED (static + runtime, 2026-07-12) · **Severity:** Medium · **Priority:** P2 · **Category:** Dead/misleading code (money) + audit self-correction
- **Affected —** application: booking-service + frontend · service: booking-service · module: FX lock / multi-currency checkout · page/route: `POST /checkout/lock-fx`, `POST /checkout/preview` · workflow: pricing (currency) · database/event: `fx_rate_locks`, `bookings.fx_rate/fx_locked_until`
- **Evidence (static):** `CheckoutController.java:48` creates locks via `fxLockService.lockFx(...)`, but **`FxLockService.consume()` has ZERO callers** (only `fxLockService.lockFx` is ever invoked; `.consume(` appears nowhere). `BookingService` injects `FxLockService` (`:85`) but never calls it; it ignores `CreateBookingRequest.fxLockToken` and hard-codes native per-binge pricing — `paymentCurrencyCode = bingeCurrency`, `lockedFxRate = ONE`, `fxLockedUntil = null` (`BookingService.java:399-444`). Frontend imports `checkoutService` (`BookingWizard.jsx:3`) but **never calls** `.preview`/`.lockFx`; no payload sets `fxLockToken`.
- **Evidence (runtime, R8):** `fx_rate_locks` = **0 rows**; **all 22 bookings** `fx_rate=1.0`, `fx_locked_until=NULL` (incl. 4 USD = native US-binge pricing).
- **Observed:** the FX-lock machinery (endpoint + service method + DTO field + table + client) is fully dormant. **Corrects** the earlier "FX-lock expiry rejection" positive-control claim.
- **Expected (product decision):** either remove the dead surface, or wire it end-to-end.
- **Trigger:** n/a (never engaged).
- **Root cause:** the in-flight July overhaul migrated pricing to native per-binge currency and left the FX-lock path stranded.
- **Impact —** Direct: none functional (no FX conversion happens) · Future: confusion/trap for a future dev; false sense of protection · Blast radius: pricing/currency code · Dependencies affected: API-001 (orphaned client), DOC-001.
- **Impact by axis —** Customer: none (native currency; card converts at bank rate) · Binge: none · Platform: maintainability/clarity · Financial: none today · Security/privacy: n/a · Data-integrity: n/a.
- **Correct target state:** (a) remove `/checkout/lock-fx`, `/checkout/preview`, `FxLockService`, `fxLockToken`, unused `checkoutService`; document native per-binge pricing; OR (b) wire frontend→`lockFx`→`consume` in `createBooking` if multi-currency charging is a roadmap item.
- **Recommended remediation:** (a) remove, unless multi-currency is imminent.
- **Smallest safe remediation:** delete the unused `checkoutService` client + mark the endpoints deprecated.
- **Alternative considered:** leave dormant (rejected — it caused a false positive-control claim).
- **Tradeoffs:** removing forecloses the half-built multi-currency path.
- **Acceptance:** no dormant FX-lock surface remains, or the lock is consumed at booking time and `fx_rate_locks` shows CONSUMED rows.
- **Required tests:** if wiring, an FX-lock expiry test; if removing, ensure booking still prices in native currency. · **Required monitoring:** if wiring, alert on expired-lock rejections.
- **Effort:** S (remove) / M (wire) · **Owner:** backend/product · **Blocking deps:** product decision on multi-currency · **Related:** API-001, PRICE-001, DOC-001 · **Confidence limitations:** none — static call-graph + runtime rows agree.

### [A11Y-001] Modal requires double-Escape / double-backdrop-tap to close
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Accessibility
- **Affected —** application: frontend · service: n/a · module: shared `Modal` · page/route: every modal · workflow: any dialog interaction · database/event: n/a
- **Evidence:** the shared Modal (native `<dialog>` + `showModal()` — good focus trap/restore/`aria-modal`) requires **two** Escape presses (`Modal.jsx:22-32`) and **two** backdrop taps (`:40-52`), with no announcement of the "press again" state.
- **Observed:** keyboard/SR users press Escape, nothing happens, no `aria-live` hint; the only reliable close is the × button.
- **Expected:** single Escape closes a non-destructive dialog (WAI-ARIA APG).
- **Trigger:** any keyboard/SR user pressing Escape once.
- **Root cause:** an anti-accidental-close double-press applied to all modals, not just unsaved-data ones.
- **Impact —** Direct: dialog appears stuck for keyboard/SR users · Future: WCAG complaints · Blast radius: every modal · Dependencies affected: none.
- **Impact by axis —** Customer: keyboard/SR friction · Binge: admin keyboard friction · Platform: a11y conformance · Financial: n/a · Security/privacy: n/a · Data-integrity: n/a.
- **Correct target state:** single Escape for non-destructive modals; keep double-press only for unsaved-data modals with an `aria-live` hint.
- **Recommended / Smallest-safe:** make single-Escape the default; opt into double-press per-modal.
- **Alternative considered:** add an `aria-live` "press Escape again" hint only (keeps the non-standard contract — weaker).
- **Tradeoffs:** unsaved-data modals need the guarded variant.
- **Acceptance:** a single Escape closes a standard modal; SR announces close.
- **Required tests:** keyboard test (jsdom) for single-Escape close. · **Required monitoring:** n/a.
- **Effort:** S · **Owner:** frontend · **Blocking deps:** none · **Related:** A11Y-002 · **Confidence limitations:** static read; not exercised with a real screen reader.
- **Note:** `ConfirmProvider` + native-dialog focus management are sound positive controls; this is the one modal-a11y defect.

### [A11Y-002] Form validation errors are not programmatically associated with their inputs
- **Status:** HIGH CONFIDENCE (static; no AT/browser run) · **Severity:** Medium · **Priority:** P2 · **Category:** Accessibility (WCAG 2.1 SC 3.3.1)
- **Affected —** application: frontend · service: n/a · module: all `field-error` forms (register, login, admin config, booking wizard) · page/route: most forms · workflow: form validation · database/event: n/a
- **Evidence:** whole-frontend survey (2026-07-12): `aria-describedby`=**0**, `aria-invalid`=**6**, `aria-required`=**0** across ~40+ forms. Pattern is visual-only — `Register.jsx:180-188` renders `<input>` + `<span class="field-error">` with no `aria-describedby`/`aria-invalid`. `htmlFor`=13 (wrapping-label forms OK; many sibling-label admin forms not associated).
- **Observed:** SR users get no field↔error association and no invalid state.
- **Expected:** each error has an `id`, referenced via `aria-describedby`; invalid inputs carry `aria-invalid`.
- **Trigger:** any SR user submitting an invalid form.
- **Root cause:** the error component was built for sighted users only.
- **Impact —** Direct: SR users can't locate/understand errors · Future: WCAG 3.3.1 fail across the app · Blast radius: every form · Dependencies affected: A11Y-001.
- **Impact by axis —** Customer: SR customers blocked on forms · Binge: SR admins blocked · Platform: a11y conformance · Financial: n/a · Security/privacy: n/a · Data-integrity: n/a.
- **Correct target state:** per-field `id` + `aria-describedby` + `aria-invalid`; `htmlFor`/`id` on sibling-label forms; `role="alert"` on the summary region.
- **Recommended remediation:** systematic form pass, ideally via a shared `<Field>` wrapper.
- **Smallest safe remediation:** fix the shared error-span component once so all consumers inherit association.
- **Alternative considered:** per-form manual fixes (more work, inconsistent).
- **Tradeoffs:** touches many files (or one shared component).
- **Acceptance:** axe reports 0 "form field has no label / no error association" on key forms.
- **Required tests:** axe/RTL assertions on register + a representative admin form. · **Required monitoring:** axe in CI.
- **Effort:** M · **Owner:** frontend · **Blocking deps:** none · **Related:** A11Y-001 · **Confidence limitations:** counts are static; full conformance needs a screen-reader/axe pass (no browser automation on host).

### [DATA-002] Over/duplicate refund not enforced at the database (app guard confirmed present)
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Financial integrity (defence-in-depth)
- **Affected —** application: payment-service · service: payment-service · module: refunds · page/route: refund paths · workflow: refund · database/event: `refunds`, `payments`
- **Evidence:** Live DB: `refunds` has only `refunds_pkey` (no unique on `gateway_refund_id`; `Refund.java:15` `@Index` non-unique). No CHECK `amount > 0`, no `SUM(refunds) ≤ payments.amount`. **App DOES guard** under a pessimistic lock: `initiateRefund` uses `findByIdForUpdate` + `sumCompletedRefundsByPaymentId` and rejects `amount > remaining` (`PaymentService.java:571-603`).
- **Observed:** over-refund is prevented in app code; the DB has no backstop.
- **Expected:** DB constraints behind the app guard.
- **Trigger:** a future refund path that bypasses `initiateRefund` (e.g. a refund webhook inserting rows).
- **Root cause:** integrity enforced in app only.
- **Impact —** Direct: none (app guard works) · Future: duplicate/over refund if a path bypasses the guard · Blast radius: refunded payments · Dependencies affected: PAY-002 (introduces gateway rows).
- **Impact by axis —** Customer: n/a · Binge: n/a · Platform: financial integrity · Financial: over-refund (future) · Security/privacy: n/a · Data-integrity: latent.
- **Correct target state:** unique on `refunds.gateway_refund_id` (real id, once PAY-002) + CHECK `amount > 0`; keep the app lock.
- **Recommended / Smallest-safe:** add the CHECK now; add the unique when real gateway ids exist.
- **Alternative considered:** app-guard-only (current — no defence-in-depth).
- **Tradeoffs:** unique on a currently-fake id is meaningless until PAY-002.
- **Acceptance:** duplicate gateway-refund-id insert fails at the DB.
- **Required tests:** DB-constraint test post-PAY-002; over-refund concurrency test. · **Required monitoring:** alert on `SUM(refunds) > payment.amount`.
- **Effort:** S · **Owner:** payments · **Blocking deps:** PAY-002 (for the unique) · **Related:** PAY-002 · **Confidence limitations:** none.

### [SEC-005] Platform control-plane + funnel aggregates reachable by any binge admin
- **Status:** PROBABLE · **Severity:** Medium · **Priority:** P2 · **Category:** Authorization
- **Affected —** application: booking-service · service: booking-service · module: Admin Ops + recovery funnel · page/route: `/api/v1/bookings/admin/ops/**`, `/admin/recovery/funnel` · workflow: ops tooling / analytics · database/event: DLT, outbox, `bookings` aggregates
- **Evidence:** `AdminOpsController` (`:50-54`) exposes DLT replay, outbox retry, pipeline health with only ROLE_ADMIN/SUPER_ADMIN, no binge scoping; `AdminRecoveryQueueController.funnel` uses `requireSelectedBinge` only (`:278`) then trusts header bingeId. Unmapped admin paths aren't module-gated (`ModulePermissionInterceptor` fail-opens on null bingeId, `:54`).
- **Observed:** a single-binge admin can run platform-wide event tooling and read other binges' conversion aggregates (counts, not PII).
- **Expected:** ops tooling restricted to super-admin; funnel scoped to owned binge.
- **Trigger:** a binge admin calls the ops or funnel endpoints.
- **Root cause:** control-plane endpoints not role-restricted to super-admin; interceptor fail-opens on unmapped paths.
- **Impact —** Direct: cross-binge aggregate read + platform tooling access · Future: privilege-escalation surface · Blast radius: platform ops + all binges' aggregates · Dependencies affected: SEC-001.
- **Impact by axis —** Customer: none (aggregates, not PII) · Binge: competitive counts leaked · Platform: control-plane misuse · Financial: indirect · Security/privacy: authorization gap · Data-integrity: DLT replay/outbox retry could be abused.
- **Correct target state:** `@PreAuthorize` SUPER_ADMIN on ops; scope funnel to `requireManagedBinge`; interceptor fail-closed on null bingeId for non-super-admin.
- **Recommended / Smallest-safe:** add SUPER_ADMIN checks to `AdminOpsController`; switch funnel to `requireManagedBinge`.
- **Alternative considered:** module-gate the paths (needs the interceptor map extended).
- **Tradeoffs:** none material.
- **Acceptance:** binge admin → 403 on ops; funnel returns only owned-binge aggregates.
- **Required tests:** authz tests on ops + funnel. · **Required monitoring:** audit-log ops actions by role.
- **Effort:** S · **Owner:** backend/security · **Blocking deps:** none · **Related:** SEC-001 · **Confidence limitations:** PROBABLE — not runtime-exercised with a binge-admin token.

### [SEC-004] Committed VAPID Web Push private key as silent config default
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Secret hygiene
- **Affected —** application: notification-service · service: notification-service · module: Web Push · page/route: n/a · workflow: push subscribe/send · database/event: n/a
- **Evidence:** `config-server/.../notification-service.yml:99` `private-key: ${WEBPUSH_PRIVATE_KEY:<committed-key>}` — silent fallback to a repo-committed private key if the env var is unset, unlike `JWT_SECRET`/`INTERNAL_API_SECRET` (no default, fail-fast).
- **Observed:** a committed VAPID private key is the default.
- **Expected:** no secret default; boot fails without the env var.
- **Trigger:** deploy without `WEBPUSH_PRIVATE_KEY` set → uses the committed key.
- **Root cause:** convenience default on a secret.
- **Impact —** Direct: private key in VCS · Future: push spoofing if reused in prod · Blast radius: push subscribers · Dependencies affected: SEC-003 (prod-config discipline).
- **Impact by axis —** Customer: could receive spoofed push · Binge: n/a · Platform: secret exposure · Financial: n/a · Security/privacy: key exposure · Data-integrity: n/a.
- **Correct target state / Recommended / Smallest-safe:** remove the default (fail-fast); rotate the exposed key.
- **Alternative considered:** keep default for dev only (rejected — silent prod fallback).
- **Tradeoffs:** dev must set the var (or use a documented dev key).
- **Acceptance:** boot fails without `WEBPUSH_PRIVATE_KEY`.
- **Required tests:** context test asserting boot fails without the var. · **Required monitoring:** secret-scan.
- **Effort:** XS · **Owner:** security/backend · **Blocking deps:** key rotation · **Related:** SEC-003 · **Confidence limitations:** none.

### [DATA-005] Multi-room same-customer duplicate PENDING booking (pre-lock TOCTOU)
- **Status:** HIGH CONFIDENCE · **Severity:** Medium · **Priority:** P2 · **Category:** Data integrity / concurrency
- **Affected —** application: booking-service · service: booking-service · module: booking create guards · page/route: `POST /api/v1/bookings` · workflow: customer booking · database/event: `bookings`
- **Evidence:** `existsPendingDuplicate` (`:221-226`) and the unpaid-count guard (`:169-176`) run **before** `acquireSlotLock` and are not re-checked under it. For a multi-room venue, two concurrent same-customer submits without `Idempotency-Key` can both pass and be assigned different rooms.
- **Observed:** duplicate PENDING bookings possible in the multi-room race.
- **Expected:** duplicate/unpaid guards hold under concurrency.
- **Trigger:** double-submit without an idempotency key on a multi-room venue.
- **Root cause:** TOCTOU — guards evaluated before the lock.
- **Impact —** Direct: duplicate PENDING for one customer · Future: support cleanup, unpaid clutter · Blast radius: multi-room venues · Dependencies affected: DATA-001.
- **Impact by axis —** Customer: two holds/bookings · Binge: inventory noise · Platform: minor · Financial: minor · Security/privacy: n/a · Data-integrity: duplicate rows.
- **Correct target state:** re-check duplicate/unpaid guards after the advisory lock, or add a partial unique index `(customer_id, event_type_id, booking_date, start_time) WHERE status='PENDING'`.
- **Recommended / Smallest-safe:** move the guard checks inside the locked section.
- **Alternative considered:** rely on `Idempotency-Key` (only helps when the client sends one).
- **Tradeoffs:** the partial unique blocks legitimate distinct-room bookings at the same time — scope carefully.
- **Acceptance:** concurrent same-customer double-submit → one PENDING.
- **Required tests:** concurrency test, multi-room, no idempotency key. · **Required monitoring:** alert on multiple PENDING per (customer, slot).
- **Effort:** S · **Owner:** backend · **Blocking deps:** none · **Related:** DATA-001 · **Confidence limitations:** HIGH — not runtime-reproduced (needs two racing sessions on a multi-room venue).

### [DATA-008] Availability blocked-slot hour vs booking 30-min granularity mismatch
- **Status:** QUESTION · **Severity:** Medium · **Priority:** P2 · **Category:** Data integrity / product
- **Affected —** application: availability-service + booking-service · service: availability + booking · module: blocked slots · page/route: `/admin/blocked-slots`, availability query · workflow: availability · database/event: `availability_db.blocked_slots`
- **Evidence:** `blocked_slots` blocks by whole hour (`start_hour/end_hour INTEGER`) while bookings/holds work at 30-min granularity. A half-hour booking could slip inside a partially-blocked hour.
- **Observed:** granularity mismatch between blocking and booking.
- **Expected:** consistent granularity (product decision).
- **Trigger:** a 30-min booking overlapping a partially-blocked hour.
- **Root cause:** two subsystems chose different time granularities.
- **Impact —** Direct: possibly bookable time inside a blocked hour · Future: operational surprise · Blast radius: venues that block partial hours · Dependencies affected: none.
- **Impact by axis —** Customer: could book a "blocked" time · Binge: operational conflict · Platform: minor · Financial: minor · Security/privacy: n/a · Data-integrity: availability-vs-block inconsistency.
- **Correct target state:** align both to 30-min (or confirm hour-blocking is intended and reject sub-hour bookings in blocked hours).
- **Recommended remediation:** move `blocked_slots` to 30-min minute-of-day, matching bookings.
- **Smallest safe remediation:** in the availability filter, treat any overlap with a blocked hour as blocked for sub-hour slots.
- **Alternative considered:** keep hour granularity + validate at booking (partial).
- **Tradeoffs:** migration of existing hour-based blocks.
- **Acceptance:** a 30-min slot inside a blocked hour is not bookable.
- **Required tests:** availability test for sub-hour slot in a blocked hour. · **Required monitoring:** n/a.
- **Effort:** M · **Owner:** backend/product · **Blocking deps:** product decision on granularity · **Related:** BOOK-001 · **Confidence limitations:** QUESTION — intended behaviour undetermined.

### [REL-001] Poison message retries indefinitely instead of DLQ
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Reliability
- **Affected —** application: booking + payment (outbox) · service: booking-service, payment-service · module: transactional outbox · page/route: n/a · workflow: event publish · database/event: `outbox_event`, Kafka
- **Evidence:** `OutboxPublisher` marks `failedPermanent` after `MAX_ATTEMPTS=10` "except serializer/class-cast code bugs which keep retrying rather than being dropped" (`OutboxPublisher.java:98-154`). A malformed payload can loop forever.
- **Observed:** serializer/class-cast failures retry without bound.
- **Expected:** all failure classes cap and route to a DLQ/parking table with alerting.
- **Trigger:** a malformed/incompatible event payload.
- **Root cause:** a failure-class carve-out that never gives up.
- **Impact —** Direct: CPU/log churn on a stuck event · Future: publisher backlog / masked outages · Blast radius: the outbox drain loop · Dependencies affected: events pipeline.
- **Impact by axis —** Customer: delayed notifications if the loop stalls the drain · Binge: n/a · Platform: reliability · Financial: n/a · Security/privacy: n/a · Data-integrity: event may never publish.
- **Correct target state:** cap every failure class; route exhausted events to a parking table + alert.
- **Recommended / Smallest-safe:** remove the serializer/class-cast carve-out so those also hit `failedPermanent` after MAX_ATTEMPTS.
- **Alternative considered:** raise the retry cap (rejected — still unbounded root behaviour).
- **Tradeoffs:** a genuinely transient serializer issue would give up after 10 — acceptable with alerting.
- **Acceptance:** a poison event stops retrying after MAX_ATTEMPTS and is parked + alerted.
- **Required tests:** poison-event test asserting `failedPermanent`. · **Required monitoring:** alert on `failedPermanent` count and parked events.
- **Effort:** S · **Owner:** backend/reliability · **Blocking deps:** none · **Related:** DATA-003 · **Confidence limitations:** none.

### [BOOK-002] Waitlist OFFER does not reserve the slot; converter caller unverified
- **Status:** HIGH CONFIDENCE / QUESTION · **Severity:** Medium · **Priority:** P2 · **Category:** Domain correctness
- **Affected —** application: booking-service · service: booking-service · module: Waitlist · page/route: `/admin/waitlist`, `waitlist.promoted` · workflow: waitlist promotion · database/event: `waitlist_entries`, `waitlist.promoted`
- **Evidence:** promotion sets entry OFFERED + notifies but creates no hold/PENDING; a direct booking can take the slot before the offered customer converts. `markEntryConverted` (`WaitlistService.java:327-338`) caller not observed in the create path.
- **Observed:** an offered slot isn't reserved for the offered customer.
- **Expected:** promotion reserves the slot; the converter is invoked from booking.
- **Trigger:** a direct booking between OFFER and conversion.
- **Root cause:** offer notifies without reserving; conversion hook may be unwired.
- **Impact —** Direct: offered customer can lose the slot · Future: waitlist distrust · Blast radius: waitlisted slots · Dependencies affected: BOOK-001 (holds).
- **Impact by axis —** Customer: offered-then-denied · Binge: support friction · Platform: minor · Financial: minor · Security/privacy: n/a · Data-integrity: waitlist state may not reflect conversion.
- **Correct target state:** create a real hold/PENDING on promotion; confirm the converter runs on booking.
- **Recommended / Smallest-safe:** reserve via a hold on OFFER (depends on BOOK-001 holds being real).
- **Alternative considered:** short exclusive window enforced at booking time.
- **Tradeoffs:** reserving reduces open availability during the offer window.
- **Acceptance:** an offered slot can't be booked by others during the offer TTL; conversion marks the entry CONVERTED.
- **Required tests:** offer-then-direct-book test; converter-invocation test. · **Required monitoring:** OFFER→CONVERTED rate.
- **Effort:** M · **Owner:** backend · **Blocking deps:** BOOK-001 decision · **Related:** BOOK-001 · **Confidence limitations:** converter caller not traced end-to-end.

### [DEVOPS-002] Repository hygiene: tracked build/k6/log artifacts and stray dirs
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Repo hygiene
- **Affected —** application: repo · service: n/a · module: repo root · page/route: n/a · workflow: n/a · database/event: n/a
- **Evidence:** git tracks `k6.zip` (30 MB), `smoke.out`, 11 root `k6-*.json`, stress outputs, `test_all.txt`, `logs_tail.txt`, etc.; `.gitignore` covers them going forward but they were grandfathered. `spike.out` (202 MB) correctly untracked.
- **Observed:** large/ephemeral artifacts tracked in VCS.
- **Expected:** artifacts untracked; repo lean.
- **Trigger:** clone size / noise; potential secret leakage in logs.
- **Root cause:** artifacts committed before ignore rules.
- **Impact —** Direct: bloated repo, noisy diffs · Future: possible secret in logs · Blast radius: repo · Dependencies affected: SEC-007, DEVOPS-005.
- **Impact by axis —** Customer: n/a · Binge: n/a · Platform: dev-experience/hygiene · Financial: n/a · Security/privacy: log files may contain tokens · Data-integrity: n/a.
- **Correct target state / Recommended / Smallest-safe:** `git rm --cached` the artifacts; keep ignore rules; scan removed logs for secrets.
- **Alternative considered:** history purge for the large binaries (optional).
- **Tradeoffs:** history rewrite if size matters.
- **Acceptance:** `git ls-files` lists none of the artifacts.
- **Required tests:** CI size/secret gate. · **Required monitoring:** repo-size + secret-scan in CI.
- **Effort:** S · **Owner:** devops · **Blocking deps:** none · **Related:** SEC-007, DEVOPS-005 · **Confidence limitations:** none.

### [DOC-001] Codebase/architecture docs are stale (pre-July overhaul)
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Documentation drift
- **Affected —** application: docs · service: n/a · module: `docs/codebase/*`, `ARCHITECTURE.md` · page/route: n/a · workflow: onboarding · database/event: n/a
- **Evidence:** `docs/codebase/*` (2026-06-06/07) and `ARCHITECTURE.md` (2026-06-20) predate migrations V72–V74 and the sessions/tax/surge/FX/loyalty overhaul (uncommitted). Archived to `docs/_previous/2026-07-11T19-01-30Z/`; this audit rebuilds the top-level ones.
- **Observed:** docs describe a pre-overhaul system.
- **Expected:** docs match the current build.
- **Trigger:** onboarding reads stale docs.
- **Root cause:** docs not updated alongside the overhaul.
- **Impact —** Direct: misleads new engineers · Future: wrong mental model → bugs · Blast radius: onboarding/ops · Dependencies affected: DOC-003.
- **Impact by axis —** Customer: n/a · Binge: n/a · Platform: knowledge integrity · Financial: n/a · Security/privacy: n/a · Data-integrity: n/a.
- **Correct target state:** rebuild `docs/codebase/01-10` from verified reality (README/ARCHITECTURE already rebuilt).
- **Recommended / Smallest-safe:** finish rebuilding the codebase set; the `docs/audit/*` set already reflects reality.
- **Alternative considered:** point onboarding at `docs/audit/*` instead (interim).
- **Tradeoffs:** rebuild effort.
- **Acceptance:** codebase docs cite current migrations/features.
- **Required tests:** n/a. · **Required monitoring:** doc-freshness review in PRs.
- **Effort:** M · **Owner:** tech-writing/backend · **Blocking deps:** none · **Related:** DOC-003 · **Confidence limitations:** none.

### [DOC-003] Docs/UI describe a slot-hold guarantee that does not exist
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Documentation drift / misleading UX
- **Affected —** application: docs + booking UI · service: booking-service · module: SlotHold docs/UI · page/route: booking wizard hold copy · workflow: holds · database/event: n/a
- **Evidence:** `docs/codebase/06b-booking-services.md:101-102` and `SlotHold.java:14-17` describe `consumeHold` and a hold "guaranteed against concurrent bookings" that are not implemented (see BOOK-001).
- **Observed:** docs/UI promise a guarantee the code doesn't provide.
- **Expected:** copy matches the chosen BOOK-001 resolution.
- **Trigger:** reading the docs / UI hold copy.
- **Root cause:** documents an intended-but-unwired feature.
- **Impact —** Direct: misleading claim · Future: same as BOOK-001 · Blast radius: docs + hold UX · Dependencies affected: BOOK-001.
- **Impact by axis —** Customer: false "reserved" expectation · Binge: n/a · Platform: knowledge/UX integrity · Financial: n/a · Security/privacy: n/a · Data-integrity: n/a.
- **Correct target state:** align docs/UI with BOOK-001's resolution (wire or remove).
- **Recommended / Smallest-safe:** update copy to match actual behaviour now; revise again if BOOK-001 wires holds.
- **Alternative considered:** none.
- **Tradeoffs:** none.
- **Acceptance:** no doc/UI claims a hold guarantee that isn't enforced.
- **Required tests:** n/a. · **Required monitoring:** n/a.
- **Effort:** S · **Owner:** tech-writing/frontend · **Blocking deps:** BOOK-001 decision · **Related:** BOOK-001 · **Confidence limitations:** none.

### [TEST-001] No regression tests for the confirmed isolation/refund/duplicate gaps
- **Status:** CONFIRMED · **Severity:** Medium · **Priority:** P2 · **Category:** Test coverage
- **Affected —** application: backend tests · service: booking + payment · module: authz/refund/concurrency tests · page/route: n/a · workflow: CI · database/event: n/a
- **Evidence:** `CrossBingeIsolationTest` asserts the repository-boundary invariant but no test covers the recovery-queue/invoice-list endpoints (SEC-001/002); no test for over-refund (DATA-002) or multi-room duplicate (DATA-005).
- **Observed:** the confirmed gaps have no guarding tests.
- **Expected:** each confirmed gap has a regression test.
- **Trigger:** a fix regresses later with nothing to catch it.
- **Root cause:** tests assert invariants at the repo layer, not the endpoint/concurrency layer.
- **Impact —** Direct: fixes could silently regress · Future: recurrence of P0/P1 issues · Blast radius: the fixed issues · Dependencies affected: SEC-001/002, DATA-002/005.
- **Impact by axis —** Customer: indirect · Binge: indirect · Platform: quality assurance · Financial: indirect · Security/privacy: no guard on isolation fixes · Data-integrity: no guard on concurrency fixes.
- **Correct target state:** authz tests per recovery/invoice endpoint; over-refund + multi-room concurrency tests.
- **Recommended / Smallest-safe:** add the endpoint authz tests alongside the SEC-001/002 fixes.
- **Alternative considered:** manual QA (rejected — not repeatable).
- **Tradeoffs:** test-writing effort.
- **Acceptance:** CI fails if any of the four gaps regress.
- **Required tests:** the tests themselves are the deliverable. · **Required monitoring:** CI gate.
- **Effort:** M · **Owner:** QE/backend · **Blocking deps:** paired with each fix · **Related:** SEC-001/002, DATA-002/005 · **Confidence limitations:** none.

---

## Historical Low findings (at discovery)

### [API-001] Method-level frontend↔backend field-drift + orphaned checkout client
- **Status:** CONFIRMED · **Severity:** Low · **Priority:** P3 · **Category:** API contract hygiene
- **Affected —** application: frontend + booking-service · service: booking-service · module: booking/checkout API · page/route: booking wizard, admin create-booking · workflow: booking · database/event: n/a
- **Evidence:** method-level diff (2026-07-12). **Positive headline:** every *strict* DTO (`ignoreUnknown=false`: register/booking-create/payment/callback/refund/reschedule/waitlist) matches field-for-field. Residual drift: (1) `checkoutService.preview`/`.lockFx` imported (`BookingWizard.jsx:3`) but never called → `/checkout/preview` + `/checkout/lock-fx` have no consumer (→ PRICE-002); (2) admin payload keeps `redeemLoyaltyPoints` (`:634`) which lenient `AdminCreateBookingRequest` drops (always `null` — admin loyalty UI gated off, `:321,478`); (3) `BookingPage.jsx:32` reads nonexistent `payload.totalAmount` → analytics `undefined`; (4) `redeemLoyaltyPoints` `Long` vs `Integer`; `CheckoutPreviewRequest`/`Response` javadocs disagree POST vs GET.
- **Observed:** minor silent drift; no 400-inducing drift on strict endpoints.
- **Expected:** no dead client; correct analytics field; consistent types.
- **Trigger:** (2) admin trying to redeem loyalty (silently ignored); (3) every booking (analytics undefined).
- **Root cause:** overhaul left an orphaned client + a stale analytics reference.
- **Impact —** Direct: analytics logs undefined; admin can't redeem loyalty · Future: confusion · Blast radius: analytics + admin booking · Dependencies affected: PRICE-002.
- **Impact by axis —** Customer: none · Binge: admin loyalty-redeem gap (product) · Platform: minor · Financial: none · Security/privacy: none · Data-integrity: none.
- **Correct target state:** delete the orphaned checkout client (with PRICE-002); fix the analytics ref; decide admin loyalty redemption.
- **Recommended / Smallest-safe:** fix `BookingPage.jsx:32` to read the real amount; remove the unused client.
- **Alternative considered:** leave (rejected — dead code/misleading).
- **Tradeoffs:** none.
- **Acceptance:** analytics logs a real amount; no unused checkout client.
- **Required tests:** an endpoints contract test (already present: `endpoints.test.js`). · **Required monitoring:** n/a.
- **Effort:** XS–S · **Owner:** frontend/backend · **Blocking deps:** PRICE-002 · **Related:** PRICE-002 · **Confidence limitations:** request-side diff done for money/booking flows; full response-side diff across all 369 calls is spot-level.

### [DEVOPS-003] Compose services rely on Dockerfile healthchecks — CONFIRMED present (resolved)
- **Status:** CONFIRMED (resolved) · **Severity:** Low · **Priority:** P3 · **Category:** Reliability
- **Affected —** compose/deploy · **Evidence:** all 9 Dockerfiles contain a `HEALTHCHECK`; `condition: service_healthy` resolves correctly. Downgraded from PROBABLE Medium.
- **Observed/Expected:** healthchecks present as required. · **Trigger/Root cause:** n/a (resolved).
- **Impact (all axes):** none — resolved. **Blast radius:** n/a. **Dependencies:** n/a.
- **Correct target state:** optional explicit compose-level healthchecks for clarity/override.
- **Recommended/Smallest-safe:** no action required; optionally add compose healthchecks.
- **Alternative/Tradeoffs:** n/a. **Acceptance:** n/a (resolved). **Required tests/monitoring:** existing compose health gating.
- **Effort:** XS (optional) · **Owner:** devops · **Blocking deps:** none · **Related:** REL-001 · **Confidence limitations:** confirmed by a partial specialist pass before rate-limit.

### [PAY-004] PaymentProvider abstraction is an incomplete stub
- **Status:** CONFIRMED · **Severity:** Low · **Priority:** P3 · **Category:** Dead/incomplete code
- **Affected —** payment-service · module: `RazorpayPaymentProvider` · **Evidence:** `verifyCallback` checks only field presence, not signature (`:57-72`); `refund` is `NOT_IMPLEMENTED` (`:74-82`). The real path is `PaymentService`, not this provider.
- **Observed:** an incomplete abstraction sits unused. · **Expected:** finish or remove it. · **Trigger:** a future caller wires to the provider and gets no signature check / no refund. · **Root cause:** abandoned abstraction.
- **Impact —** Direct: none (unused) · Future: trap for a future caller · Blast radius: payment provider layer · Dependencies affected: PAY-002/003.
- **Impact by axis —** Customer: none · Binge: none · Platform: maintainability · Financial: latent (if used) · Security/privacy: latent auth gap (if used) · Data-integrity: none.
- **Correct target state:** either implement (signature verify + real refund) or delete.
- **Recommended/Smallest-safe:** delete the unused provider, or mark `@Deprecated` + throw on use.
- **Alternative/Tradeoffs:** implement fully (couples to PAY-002 work). **Acceptance:** no half-implemented provider reachable.
- **Required tests:** n/a if removed. · **Required monitoring:** n/a.
- **Effort:** XS–S · **Owner:** payments · **Blocking deps:** PAY-002 (if implementing) · **Related:** PAY-002, PAY-003 · **Confidence limitations:** none.

### [PAY-005] `@Retry` on order creation can create duplicate Razorpay orders
- **Status:** CONFIRMED · **Severity:** Low · **Priority:** P3 · **Category:** Idempotency
- **Affected —** payment-service · module: `RazorpayGatewayClient.createOrder` · **Evidence:** `createOrder` is `@Retry`-annotated (`:63`); Razorpay doesn't dedup by `receipt` by default, so a retry after a succeeded-but-timed-out call can create two orders.
- **Observed:** duplicate orders possible on retry. · **Expected:** idempotent order creation. · **Trigger:** network timeout after Razorpay created the order. · **Root cause:** retry without idempotency key/reconciliation.
- **Impact —** Direct: duplicate orders (not captures) · Future: reconciliation noise · Blast radius: order creation · Dependencies affected: none.
- **Impact by axis —** Customer: could see two orders (one paid) · Binge: none · Platform: minor · Financial: low (orders, not captures) · Security/privacy: none · Data-integrity: order dup.
- **Correct target state:** idempotent order creation (Razorpay idempotency key) or receipt-based reconciliation before retry.
- **Recommended/Smallest-safe:** pass an idempotency key on `createOrder`.
- **Alternative/Tradeoffs:** reconcile by receipt before retrying. **Acceptance:** a retried createOrder returns the same order.
- **Required tests:** retry-after-timeout test. · **Required monitoring:** alert on multiple orders per booking.
- **Effort:** S · **Owner:** payments · **Blocking deps:** none · **Related:** PAY-002 · **Confidence limitations:** none.

### [SEC-006] Booking-transfer public preview leaks both parties' emails
- **Status:** CONFIRMED · **Severity:** Low · **Priority:** P3 · **Category:** Privacy
- **Affected —** booking-service · module: booking transfer · page/route: `GET /api/v1/booking-transfers/by-token/{token}` (public) · **Evidence:** returns `fromCustomerEmail`+`toEmail` (`BookingTransferController.java:94-110`) despite a comment claiming names-only. Gated by a 256-bit SecureRandom token in the URL.
- **Observed:** the public preview exposes both emails. · **Expected:** names-only, as the comment claims. · **Trigger:** anyone with the magic-link token. · **Root cause:** DTO exposes more than intended.
- **Impact —** Direct: two emails exposed to a token holder · Future: minor phishing surface · Blast radius: transfer participants · Dependencies affected: none.
- **Impact by axis —** Customer: email exposed to the other party/token holder · Binge: none · Platform: minor privacy · Financial: none · Security/privacy: low (token-gated) · Data-integrity: none.
- **Correct target state/Recommended/Smallest-safe:** mask/remove emails from the preview DTO.
- **Alternative/Tradeoffs:** show masked email (`a***@x.com`). **Acceptance:** preview shows names/masked only.
- **Required tests:** DTO field test. · **Required monitoring:** n/a.
- **Effort:** XS · **Owner:** backend · **Blocking deps:** none · **Related:** SEC-001 · **Confidence limitations:** none.

### [SEC-008] reCAPTCHA secret has weak dev default
- **Status:** CONFIRMED · **Severity:** Low · **Priority:** P3 · **Category:** Secret hygiene / config
- **Affected —** auth-service · **Evidence:** `auth-service.yml:127` `${RECAPTCHA_SECRET_KEY:changeme-dev-only-stub-is-used}` — a placeholder default.
- **Observed:** weak default present. · **Expected:** fail-fast without the env var in prod. · **Trigger:** prod deploy without the key (compounds SEC-003). · **Root cause:** convenience default.
- **Impact —** Direct: none (stub used in dev) · Future: silent weak-captcha if prod misconfigured · Blast radius: login bot-gate · Dependencies affected: SEC-003.
- **Impact by axis —** Customer: weaker abuse protection (future) · Binge: none · Platform: config discipline · Financial: none · Security/privacy: low · Data-integrity: none.
- **Correct target state/Recommended/Smallest-safe:** remove the default (fail-fast).
- **Alternative/Tradeoffs:** dev-only profile default. **Acceptance:** boot fails without the key under prod profile.
- **Required tests:** context test. · **Required monitoring:** covered by SEC-003 profile check.
- **Effort:** XS · **Owner:** security · **Blocking deps:** SEC-003 · **Related:** SEC-003 · **Confidence limitations:** none.

### [DATA-006] Money scale inconsistent across finance tables
- **Status:** QUESTION · **Severity:** Low · **Priority:** P3 · **Category:** Financial precision
- **Affected —** booking + payment · **Evidence:** bookings `NUMERIC(10,2)/(12,2)` vs snapshots/invoices/ledgers `NUMERIC(14,4)`, FX `(18,8)` vs `(20,10)`.
- **Observed:** mixed scales across finance tables. · **Expected:** a documented rounding/scale contract. · **Trigger:** cross-table reconciliation. · **Root cause:** tables authored at different times.
- **Impact —** Direct: possible sub-cent deltas · Future: reconciliation noise · Blast radius: finance reporting · Dependencies affected: PRICE-001.
- **Impact by axis —** Customer: negligible · Binge: reporting deltas · Platform: minor · Financial: sub-cent · Security/privacy: none · Data-integrity: rounding consistency.
- **Correct target state:** standardize scale or document the rounding contract per boundary.
- **Recommended/Smallest-safe:** document the intended scale + rounding at each boundary.
- **Alternative/Tradeoffs:** migrate to a uniform scale (heavier). **Acceptance:** a documented, tested rounding contract.
- **Required tests:** rounding-boundary tests. · **Required monitoring:** reconciliation delta alert.
- **Effort:** S–M · **Owner:** backend/finance · **Blocking deps:** none · **Related:** PRICE-001 · **Confidence limitations:** QUESTION — whether deltas actually occur not proven.

### [DATA-007] Redundant single-column indexes on hot bookings table
- **Status:** PROBABLE · **Severity:** Low · **Priority:** P3 · **Category:** Performance
- **Affected —** booking-service · **Evidence:** `idx_booking_date`, `idx_booking_customer`, `idx_booking_binge_date` are prefixes of composite indexes → write amplification.
- **Observed:** redundant indexes on a hot table. · **Expected:** no prefix-redundant indexes. · **Trigger:** every write to `bookings`. · **Root cause:** indexes added incrementally.
- **Impact —** Direct: minor write amplification · Future: scaling cost · Blast radius: bookings writes · Dependencies affected: none.
- **Impact by axis —** Customer: negligible latency · Binge: none · Platform: minor perf/cost · Financial: minor · Security/privacy: none · Data-integrity: none.
- **Correct target state/Recommended/Smallest-safe:** drop the three redundant indexes after confirming composites cover the queries.
- **Alternative/Tradeoffs:** keep if a query relies on the standalone (verify via `pg_stat`). **Acceptance:** no prefix-redundant index; query plans unchanged.
- **Required tests:** EXPLAIN checks on hot queries. · **Required monitoring:** index-usage stats.
- **Effort:** S · **Owner:** backend/db · **Blocking deps:** verify index usage · **Related:** DATA-001 · **Confidence limitations:** PROBABLE — usage stats not pulled.

### [BOOK-003] DST slot skew for non-IST venue timezones
- **Status:** PROBABLE · **Severity:** Low · **Priority:** P3 · **Category:** Timezone correctness
- **Affected —** booking + availability · **Evidence:** slot generation is minute-of-day arithmetic; DST spring-forward emits a non-existent wall-clock slot, fall-back repeats an hour. No issue for IST; binges 2 & 4 use `America/Chicago`.
- **Observed:** minute-of-day slotting ignores DST. · **Expected:** zoned-instant validation on DST venues. · **Trigger:** a booking on a DST transition day at a DST venue. · **Root cause:** wall-clock arithmetic without zone rules.
- **Impact —** Direct: 1h skew / phantom slot on 2 days/yr for DST venues · Future: mis-timed bookings · Blast radius: `America/Chicago` binges · Dependencies affected: none.
- **Impact by axis —** Customer: wrong slot time on DST day · Binge: operational confusion · Platform: minor · Financial: minor · Security/privacy: none · Data-integrity: time correctness.
- **Correct target state/Recommended/Smallest-safe:** validate zoned instants (skip non-existent, disambiguate repeated) on DST-observing venues.
- **Alternative/Tradeoffs:** store all slots in UTC with zone conversion. **Acceptance:** no phantom/duplicate slot on DST-transition days.
- **Required tests:** DST-transition slot test for `America/Chicago`. · **Required monitoring:** n/a.
- **Effort:** M · **Owner:** backend · **Blocking deps:** none · **Related:** DATA-008 · **Confidence limitations:** PROBABLE — not reproduced on a transition date.

### [DEVOPS-004] `.env` ↔ `.env.example` drift; simulation flag undocumented
- **Status:** CONFIRMED · **Severity:** Low · **Priority:** P3 · **Category:** Config hygiene
- **Affected —** repo config · **Evidence:** 11 vars in example not in `.env` (SMS/WhatsApp/ingress/TLS); `PAYMENT_SIMULATION_ENABLED` in `.env` not in example.
- **Observed:** env template drift; sim flag undocumented. · **Expected:** reconciled + documented. · **Trigger:** onboarding/deploy from the template. · **Root cause:** template not kept in sync.
- **Impact —** Direct: setup confusion · Future: sim-flag misconfig (ties to SEC-003) · Blast radius: env setup · Dependencies affected: SEC-003.
- **Impact by axis —** Customer: none · Binge: none · Platform: config discipline · Financial: sim-in-prod risk · Security/privacy: none · Data-integrity: none.
- **Correct target state/Recommended/Smallest-safe:** reconcile `.env.example`; document `PAYMENT_SIMULATION_ENABLED` prominently.
- **Alternative/Tradeoffs:** none. **Acceptance:** example matches `.env`; sim flag documented.
- **Required tests:** n/a. · **Required monitoring:** n/a.
- **Effort:** XS · **Owner:** devops · **Blocking deps:** none · **Related:** SEC-003 · **Confidence limitations:** none.

### [DOC-002] No LICENSE/NOTICE file in repo
- **Status:** CONFIRMED · **Severity:** Low · **Priority:** P3 · **Category:** Legal/foundational
- **Affected —** repo · **Evidence:** no LICENSE/NOTICE anywhere.
- **Observed:** no license file. · **Expected:** a LICENSE (and NOTICE if third-party attribution needed). · **Trigger:** legal review / open-sourcing. · **Root cause:** never added.
- **Impact —** Direct: unclear usage rights · Future: legal ambiguity · Blast radius: repo · Dependencies affected: none.
- **Impact by axis —** Customer: none · Binge: none · Platform: legal · Financial: none · Security/privacy: none · Data-integrity: none.
- **Correct target state/Recommended/Smallest-safe:** add a LICENSE (choose the intended license) + NOTICE if needed.
- **Alternative/Tradeoffs:** none. **Acceptance:** LICENSE present at repo root.
- **Required tests:** n/a. · **Required monitoring:** n/a.
- **Effort:** XS · **Owner:** owner/legal · **Blocking deps:** license choice · **Related:** none · **Confidence limitations:** none.

### [LOYALTY-001] Earn→spend→cancel may leave a small points gain
- **Status:** QUESTION · **Severity:** Low · **Priority:** P3 · **Category:** Loyalty integrity
- **Affected —** booking-service loyalty v2 · module: cancellation clawback · **Evidence:** on cancellation the earn-clawback (`LoyaltyV2BookingListener.java:171-181`) is deliberately **balance-aware** — if the member already spent the earned points, it won't force a negative balance; it logs a warning and recalculates tier. So book(earn N)→spend N→cancel can retain value the clawback can't recover.
- **Observed:** an edge sequence retains unrecoverable earned value. · **Expected:** product decision on earn-timing. · **Trigger:** earn→spend→cancel. · **Root cause:** balance-aware clawback (intentional, to avoid negative balances).
- **Impact —** Direct: small unrecoverable points gain in an edge case · Future: minor abuse if systematic · Blast radius: loyalty members · Dependencies affected: none.
- **Impact by axis —** Customer: small retained value · Binge: minor loyalty cost · Platform: minor · Financial: minor · Security/privacy: none · Data-integrity: ledger stays consistent (immutable).
- **Correct target state (product decision):** confirm earn-timing; if earn is granted before completion, defer redeemability of fresh points until the booking completes.
- **Recommended/Smallest-safe:** defer redeemability of freshly-earned points until COMPLETED.
- **Alternative/Tradeoffs:** accept the edge (low value). **Acceptance:** earn→spend→cancel nets zero retained value (if that's the decision).
- **Required tests:** earn→spend→cancel ledger test. · **Required monitoring:** anomaly on repeated earn→spend→cancel per user.
- **Effort:** S–M · **Owner:** backend/product · **Blocking deps:** earn-timing decision · **Related:** none · **Confidence limitations:** exposure window (PENDING/CONFIRMED vs COMPLETED earn) not fully traced.
- **Note:** loyalty is otherwise robust (no double-earn, no negative balance, immutable ledger) — an edge product question, not a defect.

---

## Historical Informational findings (at discovery)

### [DEVOPS-005] Stray empty `backend;C` directory
- **Status:** CONFIRMED · **Severity:** Informational · **Priority:** P4 · **Category:** Repo hygiene
- **Affected —** repo root · **Evidence:** empty dir from a botched shell redirect. · **Observed/Expected:** stray dir present / should be deleted. · **Trigger/Root cause:** shell redirect typo.
- **Impact (all axes):** none (cosmetic). **Blast radius:** none. **Dependencies:** DEVOPS-002.
- **Correct target state/Recommended/Smallest-safe:** delete the directory.
- **Alternative/Tradeoffs:** none. **Acceptance:** directory absent. **Required tests/monitoring:** n/a.
- **Effort:** XS · **Owner:** devops · **Blocking deps:** none · **Related:** DEVOPS-002 · **Confidence limitations:** none.

---

## Historical resolved / superseded bookkeeping

### [PAY-001] Payment area deep pass — COMPLETED (2026-07-12)
- **Status:** RESOLVED (superseded by direct lead inspection) · was NOT VERIFIED
- **Outcome:** the focused 2026-07-12 pass is preserved in `evidence/specialist-05-payment-refund.md`. The 2026-07-16 current-tree verification in `evidence/specialist-06-current-payment-security.md` confirms that PAY-002/PAY-003/PAY-004 were remediated, rejects webhook dedup/dispute handling as current positive controls, and records PAY-006 through PAY-010 plus SEC-010/011.

---

## Historical positive-control notes (current exceptions called out)

> This list records controls verified during the earlier tree review. **Do not treat webhook dedup, cross-Binge maker-checker authorization, or dispute lifecycle/accounting as sound in the current tree**; SEC-010 and PAY-008/PAY-009 supersede those portions. Four-eyes self-approval prevention and webhook HMAC verification themselves remain valid controls.

Confirmed sound during the deep passes, subject to the exceptions above: gateway JWT strip/inject + session revocation; CSRF double-submit + Origin pinning (runtime); backend role enforcement (runtime 403); internal `/internal/**` shared-secret (runtime); internal Binge-ownership checks on the paths that use them; V71 module matrix; transactional booking outbox + idempotent consumers (runtime pipeline confirmed); advisory-lock double-booking prevention (runtime, exactly one booking); unpaid-limit + per-customer duplicate guards; super-admin MFA-at-login; payment callback HMAC; pessimistic-lock refund amount guard; maker-checker no-self-approval; dispute webhook fail-closed HMAC; loyalty no-double-earn + balance-aware reversal + immutable ledger; tax single choke-point + per-Binge switch; pricing negative-total guard; money as `NUMERIC`; notification retry/backoff/operator retry; all nine Dockerfiles carry HEALTHCHECK; currency minor-unit conversion; and strict request DTOs on the previously compared high-risk calls.

> **Correction (2026-07-12):** an earlier revision of this section listed "FX-lock expiry rejection" as a verified positive control. The deep pass (PRICE-002) found `FxLockService.consume` is **never called** and the feature is dormant (0 lock rows, all bookings `fx_rate=1`), so that control is **not engaged**. It has been removed here and reclassified as issue **PRICE-002**. The actual (adequate) stale-rate protection is that native per-binge pricing performs no FX conversion at all.
