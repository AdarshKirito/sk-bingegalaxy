ALTER TABLE customer_pricing_profiles
ADD COLUMN IF NOT EXISTS member_label VARCHAR(120);