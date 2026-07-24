-- BOOK-002: a waitlist OFFER now reserves the slot with a real SlotHold for
-- the offer window, so a direct booking cannot take the slot out from under
-- the offered customer. The hold's token is stored on the entry so offer
-- expiry / cancellation / conversion can release it.
ALTER TABLE waitlist_entries
    ADD COLUMN IF NOT EXISTS offer_hold_token VARCHAR(64);
