-- =============================================================================
-- V30 — Seed default loyalty v2 binge bindings + earning rules,
--       and backfill EARN ledger entries from historical completed bookings.
--
-- WHY THIS EXISTS
-- ---------------
-- After the M13 v1 retirement (V28), the v2 loyalty system was the sole
-- source of truth.  However, the production data set never had any rows
-- inserted into `loyalty_binge_binding` or `loyalty_binge_earning_rule`,
-- because the original v1 system encoded earn rules in app-level
-- properties (LOYALTY_POINTS_PER_RUPEE) rather than in DB rows.
--
-- Consequence: every BookingCompletedEvent flowed into EarnEngine and
-- silently exited with `skipped("NO_BINDING")`.  Customers who had
-- COMPLETED bookings showed only their welcome bonus in the wallet.
--
-- This migration:
--   1) Seeds an ENABLED binge_binding for every binge under program 1.
--   2) Seeds one universal (tier-agnostic) FLAT_PER_AMOUNT earning rule
--      per binding — 10 points per ₹1, qc_multiplier=1, tier_multiplier=1
--      (matches the legacy v1 LOYALTY_POINTS_PER_RUPEE=10 rate).
--   3) Backfills v2 EARN ledger entries, lots, qualification events,
--      and updates wallet/membership counters for every COMPLETED
--      booking that has `bookings.loyalty_points_earned > 0` and no
--      pre-existing v2 EARN row keyed by booking_ref.
--
-- The backfill is idempotent: it skips any booking that already has an
-- EARN entry.  Re-running this migration (e.g. after restoring a backup)
-- will not double-credit points.
-- =============================================================================

-- ── 1) Seed binge bindings for every binge ───────────────────────────────────
INSERT INTO loyalty_binge_binding
    (program_id, binge_id, status, legacy_frozen, enrolled_at,
     effective_from, created_at, updated_at, version)
SELECT
    1, b.id, 'ENABLED', false, now(),
    now(), now(), now(), 0
FROM binges b
WHERE NOT EXISTS (
    SELECT 1 FROM loyalty_binge_binding lbb
    WHERE lbb.program_id = 1 AND lbb.binge_id = b.id
);

-- ── 2) Seed one universal earning rule per binding (10 pts / ₹1) ─────────────
INSERT INTO loyalty_binge_earning_rule
    (binding_id, tier_code, rule_type, points_numerator, amount_denominator,
     tier_multiplier, qc_multiplier, effective_from, created_at)
SELECT
    lbb.id, NULL, 'FLAT_PER_AMOUNT', 10, 1.00,
    1.00, 1.00, now(), now()
FROM loyalty_binge_binding lbb
WHERE NOT EXISTS (
    SELECT 1 FROM loyalty_binge_earning_rule ler
    WHERE ler.binding_id = lbb.id
      AND ler.tier_code IS NULL
      AND ler.effective_to IS NULL
);

-- ── 3) Retroactive backfill: project bookings.loyalty_points_earned into the
--      v2 wallet/ledger/lot/qualification-event tables. ────────────────────────
DO $$
DECLARE
    r              RECORD;
    v_membership   BIGINT;
    v_tenant       BIGINT;
    v_wallet       BIGINT;
    v_lot          BIGINT;
    v_points       BIGINT;
    v_redeemed     BIGINT;
    v_lot_expiry   TIMESTAMP;
    v_qc_expiry    TIMESTAMP;
BEGIN
    FOR r IN
        SELECT b.id          AS booking_id,
               b.booking_ref,
               b.customer_id,
               b.binge_id,
               b.total_amount,
               b.collected_amount,
               b.loyalty_points_earned,
               b.loyalty_points_redeemed,
               b.created_at  AS booking_at
        FROM bookings b
        WHERE b.status = 'COMPLETED'
          AND b.loyalty_points_earned IS NOT NULL
          AND b.loyalty_points_earned > 0
        ORDER BY b.created_at
    LOOP
        -- Locate the customer's membership + wallet under default program.
        SELECT m.id, m.tenant_id INTO v_membership, v_tenant
        FROM   loyalty_membership m
        WHERE  m.customer_id = r.customer_id
          AND  m.program_id  = 1
        LIMIT 1;

        IF v_membership IS NULL THEN
            CONTINUE;                       -- customer never enrolled; skip
        END IF;

        SELECT w.id INTO v_wallet
        FROM   loyalty_points_wallet w
        WHERE  w.membership_id = v_membership
        LIMIT 1;

        IF v_wallet IS NULL THEN
            CONTINUE;                       -- enrollment never created wallet; skip
        END IF;

        -- Idempotency: skip if EARN already recorded for this booking.
        IF EXISTS (
            SELECT 1 FROM loyalty_ledger_entry
            WHERE  wallet_id   = v_wallet
              AND  entry_type  = 'EARN'
              AND  booking_ref = r.booking_ref
        ) THEN
            CONTINUE;
        END IF;

        v_points     := r.loyalty_points_earned;
        v_redeemed   := COALESCE(r.loyalty_points_redeemed, 0);
        v_lot_expiry := r.booking_at + INTERVAL '540 days';   -- matches loyalty_program.points_expiry_days
        v_qc_expiry  := r.booking_at + INTERVAL '365 days';   -- 12-month rolling tier window

        -- Create FIFO lot.
        INSERT INTO loyalty_points_lot
            (tenant_id, wallet_id, binge_id, source_type, source_ref,
             original_points, remaining_points, earned_at, expires_at, created_at)
        VALUES
            (v_tenant, v_wallet, r.binge_id, 'EARN_BOOKING', r.booking_ref,
             v_points, v_points, r.booking_at, v_lot_expiry, now())
        RETURNING id INTO v_lot;

        -- Ledger EARN entry.
        INSERT INTO loyalty_ledger_entry
            (tenant_id, wallet_id, entry_type, points_delta, lot_id,
             binge_id, booking_ref, actor_role, reason_code, description,
             idempotency_key, created_at)
        VALUES
            (v_tenant, v_wallet, 'EARN', v_points, v_lot,
             r.binge_id, r.booking_ref, 'SYSTEM', 'BOOKING_COMPLETED',
             'V30 retroactive earn backfill for booking ' || r.booking_ref
                 || ' (amount=' || r.total_amount::text || ')',
             'earn:booking=' || r.booking_ref, now());

        -- Qualification event for tier engine.
        INSERT INTO loyalty_qualification_event
            (tenant_id, membership_id, binge_id, booking_ref,
             event_type, qualification_credits, event_at,
             expires_from_window_at, created_at)
        VALUES
            (v_tenant, v_membership, r.binge_id, r.booking_ref,
             'BOOKING_COMPLETED', v_points, r.booking_at,
             v_qc_expiry, now());

        -- Sync wallet counters.
        UPDATE loyalty_points_wallet
        SET    current_balance = current_balance + v_points,
               lifetime_earned = lifetime_earned + v_points,
               updated_at      = now(),
               version         = version + 1
        WHERE  id = v_wallet;

        -- Sync membership lifetime credits.
        UPDATE loyalty_membership
        SET    lifetime_credits = lifetime_credits + v_points,
               updated_at       = now()
        WHERE  id = v_membership;
    END LOOP;
END $$;

-- ── 4) Tier sync: bump current_tier_code based on backfilled lifetime_credits.
--      Skip CUSTOMER_PWN (admin-only) and LIFETIME_PLATINUM (granted manually).
UPDATE loyalty_membership m
SET    current_tier_code = sub.target_tier,
       updated_at        = now()
FROM (
    SELECT mm.id AS membership_id,
           (SELECT t.code
            FROM   loyalty_tier_definition t
            WHERE  t.qualification_credits_required <= mm.lifetime_credits
              AND  t.code NOT IN ('CUSTOMER_PWN', 'LIFETIME_PLATINUM')
            ORDER BY t.rank_order DESC
            LIMIT 1) AS target_tier
    FROM   loyalty_membership mm
    WHERE  mm.current_tier_code NOT IN ('CUSTOMER_PWN', 'LIFETIME_PLATINUM')
) sub
WHERE  m.id = sub.membership_id
  AND  sub.target_tier IS NOT NULL
  AND  m.current_tier_code <> sub.target_tier;
