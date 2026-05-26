-- V55 — Catalog categories (events + add-ons).
--
-- Introduces two new top-level taxonomy tables:
--   * event_categories — admin-defined groupings of EventTypes
--   * addon_categories — admin-defined groupings of AddOns
--
-- Both follow the existing "binge_id NULLABLE" convention used by event_types
-- and add_ons:
--   * binge_id IS NULL → global category (super-admin owned, visible to all
--                                          binges)
--   * binge_id NOT NULL → per-binge category (binge admin owned)
--
-- The legacy `add_ons.category` VARCHAR column is kept for one release cycle
-- (planned drop in V58) — the new `category_id` FK is populated alongside it
-- in dual-write mode. A backfill step at the bottom seeds addon_categories
-- with one row per distinct (binge_id, category) pair so every existing
-- add-on resolves to a real category row.
--
-- All operations are IDEMPOTENT (IF NOT EXISTS / NOT EXISTS guards) so this
-- migration is safe to re-run against partially-migrated environments.

-- ─────────────────────────────────────────────────────────────────────────
-- 1) Event categories
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS event_categories (
    id          BIGSERIAL PRIMARY KEY,
    binge_id    BIGINT,
    name        VARCHAR(80)  NOT NULL,
    description VARCHAR(500),
    image_url   VARCHAR(1000),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP
);

-- Per-binge uniqueness: each binge can only have one category with a given
-- name. Globals (binge_id IS NULL) must also be unique among themselves.
CREATE UNIQUE INDEX IF NOT EXISTS uk_event_categories_binge_name
    ON event_categories (binge_id, name)
    WHERE binge_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_event_categories_global_name
    ON event_categories (name)
    WHERE binge_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_event_categories_binge_id
    ON event_categories (binge_id);

-- ─────────────────────────────────────────────────────────────────────────
-- 2) Add-on categories
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS addon_categories (
    id          BIGSERIAL PRIMARY KEY,
    binge_id    BIGINT,
    name        VARCHAR(80)  NOT NULL,
    description VARCHAR(500),
    image_url   VARCHAR(1000),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_addon_categories_binge_name
    ON addon_categories (binge_id, name)
    WHERE binge_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_addon_categories_global_name
    ON addon_categories (name)
    WHERE binge_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_addon_categories_binge_id
    ON addon_categories (binge_id);

-- ─────────────────────────────────────────────────────────────────────────
-- 3) Wire category_id onto event_types + add_ons (NULLABLE — uncategorized
--    items are allowed and surface under the "All" filter only)
-- ─────────────────────────────────────────────────────────────────────────
ALTER TABLE event_types
    ADD COLUMN IF NOT EXISTS category_id BIGINT REFERENCES event_categories (id) ON DELETE SET NULL;

ALTER TABLE add_ons
    ADD COLUMN IF NOT EXISTS category_id BIGINT REFERENCES addon_categories (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_event_types_category_id ON event_types (category_id);
CREATE INDEX IF NOT EXISTS idx_add_ons_category_id    ON add_ons    (category_id);

-- ─────────────────────────────────────────────────────────────────────────
-- 4) Backfill: for every existing (binge_id, add_ons.category) pair that
--    has no matching addon_categories row, insert one. Then wire add_ons.
--    category_id to point at it.
--
--    Existing customer-visible labels are preserved exactly (no rename).
--    Per-binge categories are scoped by binge_id; legacy NULL-binge add-ons
--    (super-admin globals from V1 seed) become global categories.
-- ─────────────────────────────────────────────────────────────────────────
INSERT INTO addon_categories (binge_id, name, description, sort_order, active)
SELECT DISTINCT
       a.binge_id,
       a.category,
       NULL,
       0,
       TRUE
FROM   add_ons a
WHERE  a.category IS NOT NULL
  AND  NOT EXISTS (
        SELECT 1
        FROM   addon_categories c
        WHERE  c.name = a.category
          AND  (c.binge_id IS NOT DISTINCT FROM a.binge_id)
  );

UPDATE add_ons a
SET    category_id = c.id
FROM   addon_categories c
WHERE  a.category IS NOT NULL
  AND  a.category_id IS NULL
  AND  c.name = a.category
  AND  (c.binge_id IS NOT DISTINCT FROM a.binge_id);
