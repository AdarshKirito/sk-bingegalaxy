-- Add expiry support for loyalty points
ALTER TABLE loyalty_transactions ADD COLUMN expires_at TIMESTAMP;

-- Widen type column to support EARN_REVERSAL (13 chars)
ALTER TABLE loyalty_transactions ALTER COLUMN type TYPE VARCHAR(20);

-- Index for efficient expiry queries (only EARN transactions with an expiry date)
CREATE INDEX idx_loyalty_txn_expires ON loyalty_transactions (expires_at)
    WHERE expires_at IS NOT NULL AND type = 'EARN';
