-- ═══════════════════════════════════════════════════════════
--  V17: Move loyalty from per-binge to system level
-- ═══════════════════════════════════════════════════════════

-- Step 1: Merge duplicate accounts (same customer across different binges)
-- Keep the account with the highest balance for each customer and merge totals
CREATE TEMP TABLE loyalty_merged AS
SELECT
    MIN(id) AS id,
    customer_id,
    SUM(total_points_earned) AS total_points_earned,
    SUM(current_balance) AS current_balance,
    MAX(tier_level) AS tier_level,
    0 AS version,
    MIN(created_at) AS created_at,
    MAX(updated_at) AS updated_at
FROM loyalty_accounts
GROUP BY customer_id;

-- Step 2: Re-map transactions from merged-away accounts to the kept account
UPDATE loyalty_transactions lt
SET account_id = m.id
FROM loyalty_merged m
JOIN loyalty_accounts la ON la.customer_id = m.id
WHERE lt.account_id = la.id AND la.id != m.id;

-- Step 3: Delete duplicate accounts (keep only the merged ones)
DELETE FROM loyalty_accounts
WHERE id NOT IN (SELECT id FROM loyalty_merged);

-- Step 4: Update kept accounts with merged totals
UPDATE loyalty_accounts la
SET total_points_earned = m.total_points_earned,
    current_balance = m.current_balance
FROM loyalty_merged m
WHERE la.id = m.id;

DROP TABLE loyalty_merged;

-- Step 5: Drop the old unique constraint and binge_id column
ALTER TABLE loyalty_accounts DROP CONSTRAINT IF EXISTS uq_loyalty_customer_binge;
ALTER TABLE loyalty_accounts DROP COLUMN IF EXISTS binge_id;

-- Step 6: Add new unique constraint on customer_id alone
ALTER TABLE loyalty_accounts ADD CONSTRAINT uq_loyalty_customer UNIQUE (customer_id);

-- Step 7: Recalculate tier levels based on totalPointsEarned
UPDATE loyalty_accounts SET tier_level = CASE
    WHEN total_points_earned >= 50000 THEN 'PLATINUM'
    WHEN total_points_earned >= 20000 THEN 'GOLD'
    WHEN total_points_earned >= 5000 THEN 'SILVER'
    ELSE 'BRONZE'
END;
