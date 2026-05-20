-- V41: Waitlist priority for VIP / loyalty boost.
-- Higher priority is offered first; ties broken by position (FIFO).
-- 0 = normal customer (default), 10 = silver, 20 = gold, 30 = platinum, 100 = ops override.
ALTER TABLE waitlist_entries
    ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 0;

-- Composite index that mirrors the new ordering used by the promotion query
-- (priority DESC, position ASC) so admin and scheduler reads stay sargable.
CREATE INDEX IF NOT EXISTS idx_waitlist_priority_pos
    ON waitlist_entries (binge_id, preferred_date, status, priority DESC, position ASC);
