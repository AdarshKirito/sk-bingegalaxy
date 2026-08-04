-- Assertions for the distribution schema (V1). Each RAISEs on failure, so the run
-- aborts at the first problem. See docs/distribution/05-DISTRIBUTION-CONSOLE-DESIGN-V2.md
\set ON_ERROR_STOP on

-- 1. Google is seeded as feed-only. This is the correction that matters most: the
--    previous design showed a Google reservation in the inbox, and no such object
--    exists — Things to Do is a feed plus a deep link.
DO $$
DECLARE delivers BOOLEAN;
BEGIN
    SELECT delivers_reservations INTO delivers FROM destinations WHERE code = 'GOOGLE_TTD';
    IF delivers IS DISTINCT FROM FALSE THEN
        RAISE EXCEPTION 'FAIL: Google Things to Do must not deliver reservations';
    END IF;
    RAISE NOTICE 'PASS 1: Google TTD seeded as feed-only (delivers_reservations = false)';
END $$;

-- 2. Capabilities fail CLOSED: an unknown capability must read as false, never true.
DO $$
DECLARE found BOOLEAN;
BEGIN
    SELECT enabled INTO found FROM provider_capabilities
     WHERE provider_code = 'VIATOR' AND capability_key = 'someCapabilityNobodyDeclared';
    IF found IS NOT NULL THEN
        RAISE EXCEPTION 'FAIL: an undeclared capability returned a row';
    END IF;
    -- And the specific one the UI would otherwise light up wrongly.
    SELECT enabled INTO found FROM provider_capabilities
     WHERE provider_code = 'VIATOR' AND capability_key = 'supportsCounterOffer';
    IF found IS DISTINCT FROM FALSE THEN
        RAISE EXCEPTION 'FAIL: unverified counter-offer capability is not disabled';
    END IF;
    RAISE NOTICE 'PASS 2: capabilities fail closed; UNVERIFIED counter-offer stays disabled';
END $$;

-- 3. Every provider is seeded INACTIVE except the simulator — nothing is connectable
--    before a super-admin activates it, and the commercial question (will any reseller
--    list private venue hire?) is still open.
DO $$
DECLARE bad INTEGER;
BEGIN
    SELECT COUNT(*) INTO bad FROM providers WHERE active AND code <> 'SIMULATOR';
    IF bad > 0 THEN
        RAISE EXCEPTION 'FAIL: % real provider(s) seeded active', bad;
    END IF;
    RAISE NOTICE 'PASS 3: only the simulator is active; real providers require activation';
END $$;

-- 4. A listing cannot be LIVE while incomplete. Expressed as a CHECK so no service
--    bug can publish a half-ready listing.
INSERT INTO providers (code, display_name, provider_kind, auth_method) VALUES ('T', 'T', 'BOTH', 'API_KEY');
INSERT INTO destinations (code, display_name, operated_by_provider_code) VALUES ('TD', 'TD', 'T');
INSERT INTO connections (id, binge_id, provider_code) VALUES (900, 1, 'T');
INSERT INTO connection_destinations (id, connection_id, destination_code) VALUES (900, 900, 'TD');

DO $$
BEGIN
    BEGIN
        INSERT INTO listing_mappings (connection_destination_id, event_type_id, binge_id,
                                      publish_state, readiness_pct)
        VALUES (900, 1, 1, 'LIVE', 60);
        RAISE EXCEPTION 'FAIL: a 60%% ready listing was published LIVE';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS 4: LIVE requires 100%% readiness';
    END;
    INSERT INTO listing_mappings (connection_destination_id, event_type_id, binge_id,
                                  publish_state, readiness_pct)
    VALUES (900, 2, 1, 'LIVE', 100);
    RAISE NOTICE 'PASS 5: a fully ready listing publishes';
END $$;

-- 6. Payment responsibility defaults to CHANNEL_COLLECTS, because that is how Viator
--    and GetYourGuide actually work (both are merchant of record). The previous design
--    defaulted to the venue collecting, which would misstate a venue's cash flow.
DO $$
DECLARE pr TEXT;
BEGIN
    SELECT payment_responsibility INTO pr FROM connection_destinations WHERE id = 900;
    IF pr <> 'CHANNEL_COLLECTS' THEN
        RAISE EXCEPTION 'FAIL: default payment responsibility is % (expected CHANNEL_COLLECTS)', pr;
    END IF;
    BEGIN
        UPDATE connection_destinations SET payment_responsibility = 'VENUE_KEEPS_EVERYTHING' WHERE id = 900;
        RAISE EXCEPTION 'FAIL: an invalid payment responsibility was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS 6: payment responsibility defaults to CHANNEL_COLLECTS and rejects unknown values';
    END;
END $$;

-- 7. Inbox de-duplication: the same message delivered twice must not be stored twice.
DO $$
BEGIN
    INSERT INTO reservation_inbox (connection_id, destination_code, external_ref, message_type,
                                   external_sequence, payload_json)
    VALUES (900, 'TD', 'EXT-1', 'CREATE', 1, '{}');
    BEGIN
        INSERT INTO reservation_inbox (connection_id, destination_code, external_ref, message_type,
                                       external_sequence, payload_json)
        VALUES (900, 'TD', 'EXT-1', 'CREATE', 1, '{}');
        RAISE EXCEPTION 'FAIL: a redelivered message was stored twice';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS 7: redelivered inbox message rejected by the unique index';
    END;
    -- A genuine MODIFY for the same reservation is a different message and must land.
    INSERT INTO reservation_inbox (connection_id, destination_code, external_ref, message_type,
                                   external_sequence, payload_json)
    VALUES (900, 'TD', 'EXT-1', 'MODIFY', 2, '{}');
    RAISE NOTICE 'PASS 8: a later MODIFY for the same reservation is accepted';
END $$;

-- 9. Settlement currency is mandatory and ISO-shaped; two currencies are tracked
--    because a destination may remit in one while the venue banks in another.
DO $$
BEGIN
    BEGIN
        -- Deliberately 3 characters but lowercase: a longer value would be rejected by
        -- the VARCHAR(3) length before the CHECK could run, so this proves the CHECK
        -- itself enforces the ISO-4217 shape rather than the column width doing it.
        INSERT INTO settlement_records (booking_ref, binge_id, destination_code,
                                        settlement_currency, collected_by)
        VALUES ('SKBG1', 1, 'TD', 'inr', 'CHANNEL');
        RAISE EXCEPTION 'FAIL: a non-ISO settlement currency was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS 9: settlement currency must be ISO-4217 shaped (uppercase)';
    END;
    INSERT INTO settlement_records (booking_ref, binge_id, destination_code,
                                    settlement_currency, venue_payout_currency, collected_by,
                                    gross_minor, commission_minor, venue_net_minor)
    VALUES ('SKBG1', 1, 'TD', 'EUR', 'INR', 'CHANNEL', 840000, 168000, 672000);
    RAISE NOTICE 'PASS 10: cross-currency settlement recorded (EUR collected, INR payout)';
END $$;

-- 11. Safety inventory and stop-sell exist (restored from the original dossier; the
--     v2 design had dropped them).
DO $$
BEGIN
    UPDATE connection_destinations SET safety_inventory = 2, stop_sell = TRUE WHERE id = 900;
    BEGIN
        UPDATE connection_destinations SET safety_inventory = -1 WHERE id = 900;
        RAISE EXCEPTION 'FAIL: negative safety inventory accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'PASS 11: safety inventory and stop-sell present and range-checked';
    END;
END $$;

-- 12. One connection per venue per provider per environment; sandbox and production
--     coexist, duplicates of either do not.
DO $$
BEGIN
    INSERT INTO connections (binge_id, provider_code, environment) VALUES (1, 'T', 'PRODUCTION');
    RAISE NOTICE 'PASS 12: sandbox and production connections coexist';
    BEGIN
        INSERT INTO connections (binge_id, provider_code, environment) VALUES (1, 'T', 'PRODUCTION');
        RAISE EXCEPTION 'FAIL: duplicate production connection accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'PASS 13: duplicate connection for the same venue/provider/env rejected';
    END;
END $$;

DO $$ BEGIN RAISE NOTICE '=== ALL DISTRIBUTION SCHEMA ASSERTIONS PASSED ==='; END $$;
