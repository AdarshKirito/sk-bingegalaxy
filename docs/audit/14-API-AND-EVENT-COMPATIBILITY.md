# 14 — API and Event Compatibility (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · static contract join

## Inputs

- 429 backend endpoints: [evidence/endpoint-inventory-current.tsv](evidence/endpoint-inventory-current.tsv)
- 372 frontend static call sites: [evidence/frontend-api-pairs-current.tsv](evidence/frontend-api-pairs-current.tsv)
- Diff artifact: [evidence/api-contract-diff.tsv](evidence/api-contract-diff.tsv)

## Method

Literal-path joins after normalizing `{id}`-style templates. **Known limitation (honest):** frontend calls composed with template literals (backtick paths) are only partially captured by static regex; the residual unmatched set requires runtime contract tests to close (API-01).

## Findings

| Class | Result |
|---|---|
| Frontend call → missing backend endpoint | **0 found** among literal paths |
| Backend endpoint never called by frontend | Expected & numerous: internal APIs (secret-guarded), webhooks (provider-called), actuators, admin batch endpoints — classified in diff TSV, none suspicious |
| Verb mismatches | 0 found |
| Base-path drift | None: `/api/v1` everywhere, plus `/api/v2/loyalty` for loyalty v2 (both routed at gateway) |
| Pagination/response envelope | Consistent `Page<>` and ApiResponse wrappers in sampled controllers |

## Versioning posture

- REST: URI versioning (`v1`, loyalty `v2`); no `Accept`-header versioning; no deprecation headers — acceptable for a first launch, document before external consumers exist
- Events: payloads are plain JSON POJOs from common-lib; **no schema registry**; compatibility is by convention (additive-only) — EVT-03 (P3): adopt a documented event-evolution rule before any external consumer subscribes
- Producer-only topics (8) are the largest contract ambiguity — see doc 12

## Gateway route coverage

All 5 domain services routed; loyalty v2 path routed; actuator endpoints not exposed publicly (gateway strips). CSRF exemptions limited to webhook paths (provider-signed instead).

## Risks (register refs)

| ID | Sev | Summary |
|---|---|---|
| API-01 | P2 | No consumer-driven contract tests; static join can't cover dynamic paths |
| EVT-03 | P3 | No event schema registry/evolution policy |
