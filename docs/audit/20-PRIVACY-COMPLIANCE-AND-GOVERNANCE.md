# 20 — Privacy, Compliance and Governance (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · VERIFIED-STATIC; end-to-end erasure NOT runtime-verified

## Right to erasure (GDPR Art. 17) — pipeline EXISTS

A prior specialist pass claimed "no GDPR/anonymization code" — **disproved**:

| Stage | Implementation | Evidence |
|---|---|---|
| Request | `requestDeletion(userId)` | [UserAnonymizationService.java](../../backend/auth-service/src/main/java/com/skbingegalaxy/auth/service/UserAnonymizationService.java) L55-56, exposed via UserPrivacyController |
| Execute | `anonymizeUser(userId)` — PII overwrite in auth_db | L83-84 |
| Sweep | Nightly cron 02:30 (`app.privacy.anonymization-cron`), per-user commit isolation (one failure doesn't halt the batch) | L97-101 |
| Propagate | Publishes `user.anonymized` | L159 |
| Fan-out | `UserAnonymizedEventListener` in booking, payment, notification redacts local PII | each service's listener |

Gaps:
- **EVT-02 (P2):** auth publishes directly (no outbox) — a Kafka outage at anonymization time risks unpropagated erasure; add outbox or reconciliation sweep
- **PRIV-02 (P2):** no automated end-to-end erasure verification (e.g., scheduled job proving no PII remains across DBs for anonymized users) — currently trust-based
- **PRIV-03 (P3):** availability-service holds no PII (verified — slots/blocks only), Mongo notifications TTL-expire at 90 d, but historical notification payloads within the window still contain PII after anonymization unless the listener rewrites them — listener does redact (verified) ✅; recheck delivery-provider logs at runtime

## Retention

| Store | Policy |
|---|---|
| MongoDB notifications | TTL 90 d (`createdAt` index) |
| Booking/payment rows | Retained (financial records) with PII redacted on anonymization — correct pattern |
| Sessions | Redis TTL + revocation denylist |
| Logs | Compose caps (10m×3); Loki retention not configured in repo (GOV-02, P3) |

## Consent & terms

Terms page exists; registration references terms acceptance. No versioned-consent ledger (who accepted which T&C version when) — GOV-01 (P3) if operating in strict jurisdictions.

## Governance

- LICENSE/NOTICE proprietary; CODEOWNERS absent; no PR template/branch-protection evidence in repo (GitHub-side config unverifiable from here) — GOV-03 (P3)
- Audit trails: BookingEventLog, RateCodeChangeLog, LoyaltyMembershipEvent, AdminNotification — strong domain-level auditability ✅
- Data-processing inventory (RoPA) absent — GOV-04 (P3, needed before EU launch)

## Risks (register refs)

EVT-02 (P2) · PRIV-02 (P2) · GOV-01/02/03/04 (P3)
