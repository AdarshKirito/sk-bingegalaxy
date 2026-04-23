-- =============================================================================
-- V22 — Loyalty v2 BACKFILL & legacy-binge binding enrollment
--
--   Converts the V1 loyalty state (loyalty_accounts / loyalty_transactions)
--   into an equivalent V2 state so every existing customer keeps their
--   points the moment V2 goes live.  Also writes a `loyalty_binge_binding`
--   row for every existing `binges.id` so the new per-binge rule layer has
--   a target for each venue on day one.
--
--   Design guarantees
--   -----------------
--   1. **Idempotent** — every INSERT is guarded by NOT EXISTS; safe to
--      re-run against a partially-migrated system.
--   2. **Zero data loss** — V1 tables are untouched; a future V23 will
--      drop them only after the shadow period verifies parity.
--   3. **Synthetic lot** — V1 has no lot concept, so we mint ONE lot per
--      account carrying the whole current balance, dated at account
--      creation, expiring `points_expiry_days` later.  FIFO semantics
--      hold naturally for all future earns.
--   4. **Legacy bindings** — every binge gets a binding with
--      status=ENABLED_LEGACY, legacy_frozen=TRUE; EarnEngine / RedeemEngine
--      skip these (see LoyaltyV2Constants.BINDING_ENABLED_LEGACY).  Admins
--      "thaw" a binge from the super-admin UI to graduate it.
--   5. **Deterministic member numbers** — SK-BF-000123 style so backfill
--      rows are visually distinguishable from organic SK-XXXX-XXXX.
-- =============================================================================

-- ─── 0. Guard: default program must exist (V21 seeded it) ──────────────────
DO $$
DECLARE v_program_id BIGINT;
BEGIN
    SELECT id INTO v_program_id FROM loyalty_program WHERE code = 'SK_MEMBERSHIP' LIMIT 1;
    IF v_program_id IS NULL THEN
        RAISE EXCEPTION 'V22 backfill: default loyalty program (SK_MEMBERSHIP) missing — did V21 run?';
    END IF;
END $$;


-- ─── 1. Memberships ────────────────────────────────────────────────────────
INSERT INTO loyalty_membership (
    tenant_id, program_id, customer_id, member_number,
    enrolled_at, enrollment_source,
    current_tier_code, tier_effective_from, tier_effective_until,
    soft_landing_eligible,
    qualifying_credits_window, lifetime_credits, lifetime_years_at_current_tier,
    active, marketing_opt_in,
    created_at, updated_at, version
)
SELECT
    NULL,
    (SELECT id FROM loyalty_program WHERE code = 'SK_MEMBERSHIP' LIMIT 1),
    la.customer_id,
    'SK-BF-' || LPAD(la.customer_id::text, 6, '0'),
    la.created_at, 'BACKFILL_V2',
    CASE UPPER(COALESCE(la.tier_level, 'BRONZE'))
        WHEN 'PLATINUM' THEN 'PLATINUM'
        WHEN 'GOLD'     THEN 'GOLD'
        WHEN 'SILVER'   THEN 'SILVER'
        ELSE 'BRONZE'
    END,
    la.created_at,
    NULL,                                                                   -- tier permanent until TierEngine runs
    TRUE,
    la.total_points_earned,                                                 -- QC window approximation
    la.total_points_earned,                                                 -- lifetime credits
    0,
    TRUE, FALSE,
    la.created_at, NOW(), 0
FROM loyalty_accounts la
WHERE NOT EXISTS (
    SELECT 1 FROM loyalty_membership lm WHERE lm.customer_id = la.customer_id
);


-- ─── 2. Wallets ────────────────────────────────────────────────────────────
INSERT INTO loyalty_points_wallet (
    tenant_id, membership_id,
    current_balance, lifetime_earned, lifetime_redeemed, lifetime_expired, lifetime_adjusted,
    created_at, updated_at, version
)
SELECT
    NULL, lm.id,
    la.current_balance,
    la.total_points_earned,
    GREATEST(la.total_points_earned - la.current_balance, 0),               -- best-effort historical redeem
    0, 0,
    la.created_at, NOW(), 0
FROM loyalty_accounts la
JOIN loyalty_membership lm ON lm.customer_id = la.customer_id
WHERE NOT EXISTS (
    SELECT 1 FROM loyalty_points_wallet w WHERE w.membership_id = lm.id
);


-- ─── 3. Synthetic FIFO lots (only when balance > 0) ────────────────────────
INSERT INTO loyalty_points_lot (
    tenant_id, wallet_id, binge_id,
    source_type, source_ref,
    original_points, remaining_points,
    earned_at, expires_at, created_at
)
SELECT
    NULL, w.id, NULL,
    'BACKFILL_V2', 'V22:account=' || la.id,
    la.current_balance, la.current_balance,
    la.created_at,
    la.created_at + ((SELECT points_expiry_days FROM loyalty_program WHERE code = 'SK_MEMBERSHIP' LIMIT 1) || ' days')::INTERVAL,
    NOW()
FROM loyalty_accounts la
JOIN loyalty_membership    lm ON lm.customer_id   = la.customer_id
JOIN loyalty_points_wallet w  ON w.membership_id  = lm.id
WHERE la.current_balance > 0
  AND NOT EXISTS (
      SELECT 1 FROM loyalty_points_lot l
       WHERE l.wallet_id = w.id AND l.source_type = 'BACKFILL_V2'
  );


-- ─── 4. Seed ledger row so audit trail is non-empty ────────────────────────
INSERT INTO loyalty_ledger_entry (
    tenant_id, wallet_id, entry_type, points_delta,
    binge_id, booking_ref, actor_id, actor_role,
    reason_code, description, correlation_id, idempotency_key,
    created_at
)
SELECT
    NULL, w.id, 'ADJUST', la.current_balance,
    NULL, NULL, NULL, 'SYSTEM',
    'BACKFILL_V2', 'V1→V2 migration — opening balance',
    'backfill-v2',
    'backfill-v2:account=' || la.id,
    NOW()
FROM loyalty_accounts la
JOIN loyalty_membership    lm ON lm.customer_id   = la.customer_id
JOIN loyalty_points_wallet w  ON w.membership_id  = lm.id
WHERE la.current_balance > 0
  AND NOT EXISTS (
      SELECT 1 FROM loyalty_ledger_entry e
       WHERE e.wallet_id = w.id AND e.idempotency_key = 'backfill-v2:account=' || la.id
  );


-- ─── 5. Legacy binge bindings ──────────────────────────────────────────────
INSERT INTO loyalty_binge_binding (
    tenant_id, program_id, binge_id,
    status, legacy_frozen,
    enrolled_at,
    effective_from, effective_to,
    created_at, updated_at, version
)
SELECT
    NULL,
    (SELECT id FROM loyalty_program WHERE code = 'SK_MEMBERSHIP' LIMIT 1),
    b.id,
    'ENABLED_LEGACY', TRUE,
    COALESCE(b.created_at, NOW()),
    COALESCE(b.created_at, NOW()), NULL,
    NOW(), NOW(), 0
FROM binges b
WHERE NOT EXISTS (
    SELECT 1 FROM loyalty_binge_binding lb WHERE lb.binge_id = b.id
);


-- ─── 6. Membership-event trail (auditor-friendly) ──────────────────────────
INSERT INTO loyalty_membership_event (
    tenant_id, membership_id,
    event_type, from_value_json, to_value_json,
    triggered_by, triggered_by_id, correlation_id,
    created_at
)
SELECT
    NULL, lm.id,
    'ENROLLED', NULL,
    '{"source":"BACKFILL_V2","lifetimeCredits":' || lm.lifetime_credits || '}',
    'SYSTEM', NULL, 'backfill-v2',
    NOW()
FROM loyalty_membership lm
WHERE lm.enrollment_source = 'BACKFILL_V2'
  AND NOT EXISTS (
      SELECT 1 FROM loyalty_membership_event e
       WHERE e.membership_id = lm.id AND e.event_type = 'ENROLLED'
  );


-- ─── 7. Ops counters surfaced in migration output ──────────────────────────
DO $$
DECLARE v_mem INT; v_wal INT; v_lot INT; v_led INT; v_bind INT;
BEGIN
    SELECT COUNT(*) INTO v_mem  FROM loyalty_membership     WHERE enrollment_source = 'BACKFILL_V2';
    SELECT COUNT(*) INTO v_wal  FROM loyalty_points_wallet;
    SELECT COUNT(*) INTO v_lot  FROM loyalty_points_lot     WHERE source_type = 'BACKFILL_V2';
    SELECT COUNT(*) INTO v_led  FROM loyalty_ledger_entry   WHERE reason_code = 'BACKFILL_V2';
    SELECT COUNT(*) INTO v_bind FROM loyalty_binge_binding  WHERE legacy_frozen = TRUE;
    RAISE NOTICE 'V22 backfill complete — memberships:% wallets:% lots:% ledger:% bindings:%',
                 v_mem, v_wal, v_lot, v_led, v_bind;
END $$;
