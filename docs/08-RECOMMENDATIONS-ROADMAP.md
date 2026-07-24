# 08 — Recommendations & Roadmap

Sequenced so each phase unblocks the next. Effort is rough (S ≤ 1 day, M ≤ 1 week, L > 1 week).

## Phase 0 — Make the truth reproducible (do first, blocks everything)
| # | Action | Sev | Effort |
|---|---|---|---|
| 0.1 | Commit the ~599-file working tree on a branch; get off detached HEAD; push. | P0 | S |
| 0.2 | Purge `admin_token.txt` + `stress-tokens.txt` from all history (`git filter-repo`); rotate the JWT secret + admin creds they held. | P0 | S–M |
| 0.3 | Rebuild + redeploy from the tagged commit; re-triage the open bug list against *that* build (many may already be fixed in-tree). | P0 | S |
| 0.4 | Make the deploy pipeline build **only** from a tagged SHA; add a "no dirty tree" gate. | P0 | S |

Until Phase 0 lands, do not trust "it's fixed" for anything — the running build isn't provably the source.

## Phase 1 — Production safety rails
| # | Action | Sev | Effort |
|---|---|---|---|
| 1.1 | Production profile **fail-fast** on dev defaults: require `SUPER_ADMIN_REQUIRE_MFA=true`, an independent `CRYPTO_SECRET_KEY`, and `PAYMENT_SIMULATION_ENABLED=false`. | P1 | S |
| 1.2 | Provider sandbox proof: register + exercise Razorpay `refund.processed`/`refund.failed` + dispute webhooks; verify the durable-intent / receipt-first recovery path end-to-end. | P0/ops | M |
| 1.3 | CI gate: every entity change must ship a Flyway migration; boot each service against the real migration chain on an ephemeral DB per PR (wire in `scripts/check-migration-safety.sh`). | P1 | M |
| 1.4 | Redis HA + alerting; decide + document the fail-open window; consider fail-closed revocation on admin/super-admin paths. | P1 | M |
| 1.5 | Secrets management + rotation runbook for prod. | P1 | M |

## Phase 2 — Kill the recurring-bug classes
| # | Action | Sev | Effort |
|---|---|---|---|
| 2.1 | **Declarative authz registry** (path → role/scope/module) consumed by gateway + service + frontend; contract test that every `/admin/**` route appears in all layers. Kills the "four-places drift" bug class (#1 real-bug source). | P1 | M–L |
| 2.2 | Golden-master money tests over the pricing/refund matrix (currency × rate code × surge × tax mode × customer profile); document canonical order-of-operations + single final rounding. | P2 | M |
| 2.3 | Replace broad `permitAll` namespaces with explicit allow-lists; add a public-endpoint enumeration test. Flip `iss`/`aud` to hard enforcement once tokens carry them. | P2 | S–M |
| 2.4 | Audit the ~14 empty catches / ~75 `.orElse(null)` seams; log+metric or `orElseThrow`. | P2 | M |
| 2.5 | Runtime/integration suite: concurrency (advisory-lock/occupancy), provider fault injection, webhook crash-replay, SW identity-switch, axe/keyboard. | P1 | L |

## Phase 3 — Decompose the god-objects (highest maintainability leverage)
| # | Action | Sev | Effort |
|---|---|---|---|
| 3.1 | Split `BookingService` (5.2k) into lifecycle / pricing-facade / event-publisher / query collaborators behind existing tests. | P1 | L |
| 3.2 | Split `PaymentService` (2.2k) into order / capture / refund / reconciliation collaborators. | P1 | L |
| 3.3 | Decompose `AdminBookings.jsx` (2.4k), `BingeManagement.jsx` (2k), `AdminLoyaltyCenter.jsx` (1.6k): extract table/modal/form components + data hooks. | P1 | L |

## Phase 4 — Frontend consistency & polish
| # | Action | Sev | Effort |
|---|---|---|---|
| 4.1 | Consolidate state onto Zustand; delete the Context duplicates; axios reads from the store, not raw `localStorage`. | P2 | M |
| 4.2 | Converge admin CSS onto `admin-system.css`; codemod `.adm-*`; delete the dead vocabulary. | P2 | M |
| 4.3 | Add a thin per-resource typed API layer over the axios instance; migrate inline call-sites. | P2 | M–L |
| 4.4 | A11y: keyboard/axe pass on custom drawers/click-only rows; verify every admin page tolerates "no binge selected". | P2/P3 | M |
| 4.5 | Repo-root cleanup: move/delete ad-hoc scripts + large artifacts (`k6.zip`, `spike.out`, `hs_err_*`, stress `.ps1`); tighten `.gitignore`. | P3 | S |

## The "correct way" principles worth institutionalizing
- **One source of truth per fact**: schema (Flyway), identity (gateway), money (`MoneyUtil`), authz mapping (a registry), frontend state (one store). Every duplicated source of truth here is a current or future bug.
- **Deploy only what's committed and tagged.** No dirty-tree builds, ever.
- **Contracts are the integrity boundary** (no cross-DB FKs): change `common-lib` events and internal DTOs *additively*; test the contract.
- **Round money once, at the end**, per the money contract; never in float.
- **Fail-closed by default on security**, fail-open only where you've consciously signed off (and document it).
- **Big files are a smell, not a milestone** — cap service/page size in review.

## What "done and production-ready" looks like
Committed+tagged+CI-built release · secrets purged & rotated · prod fail-fast on dev defaults · provider sandbox-proven money paths · migration-boot + concurrency + provider-fault tests green · Redis HA + fail-open sign-off · authz registry + contract test · god-objects decomposed · one state store + one CSS system · a11y pass. See [07-ISSUE-REGISTER.md](07-ISSUE-REGISTER.md) for the detail behind each.
