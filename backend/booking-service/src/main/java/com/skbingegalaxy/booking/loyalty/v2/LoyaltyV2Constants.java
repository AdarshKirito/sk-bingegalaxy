package com.skbingegalaxy.booking.loyalty.v2;

/**
 * Loyalty v2 platform constants.
 *
 * <p>Centralizes strings that are referenced both in seed data (V21) and
 * in application code so drift cannot happen.  If the default program
 * code ever changes, it changes in one place.
 */
public final class LoyaltyV2Constants {

    private LoyaltyV2Constants() { /* no instances */ }

    /** Seed program code — matches V21__loyalty_v2_schema.sql. */
    public static final String DEFAULT_PROGRAM_CODE = "SK_MEMBERSHIP";

    // ── Tier codes ────────────────────────────────────────────────────────
    public static final String TIER_BRONZE   = "BRONZE";
    public static final String TIER_SILVER   = "SILVER";
    public static final String TIER_GOLD     = "GOLD";
    public static final String TIER_PLATINUM = "PLATINUM";
    public static final String TIER_LIFETIME_PLATINUM = "LIFETIME_PLATINUM";

    // ── Binge-binding statuses ────────────────────────────────────────────
    public static final String BINDING_ENABLED        = "ENABLED";
    public static final String BINDING_DISABLED       = "DISABLED";
    public static final String BINDING_ENABLED_LEGACY = "ENABLED_LEGACY";

    // ── Ledger entry types ────────────────────────────────────────────────
    public static final String LEDGER_EARN                 = "EARN";
    public static final String LEDGER_REDEEM               = "REDEEM";
    public static final String LEDGER_EXPIRE               = "EXPIRE";
    public static final String LEDGER_ADJUST               = "ADJUST";
    public static final String LEDGER_REVERSE_EARN         = "REVERSE_EARN";
    public static final String LEDGER_REVERSE_REDEEM       = "REVERSE_REDEEM";
    public static final String LEDGER_BONUS                = "BONUS";
    public static final String LEDGER_STATUS_MATCH_GRANT   = "STATUS_MATCH_GRANT";
    public static final String LEDGER_TRANSFER_IN          = "TRANSFER_IN";
    public static final String LEDGER_TRANSFER_OUT         = "TRANSFER_OUT";

    // ── Enrollment sources ────────────────────────────────────────────────
    public static final String ENROLL_SILENT_BOOKING  = "SILENT_BOOKING";
    public static final String ENROLL_EXPLICIT_SIGNUP = "EXPLICIT_SIGNUP";
    public static final String ENROLL_SSO_GOOGLE      = "SSO_GOOGLE";
    public static final String ENROLL_ADMIN_IMPORT    = "ADMIN_IMPORT";
    public static final String ENROLL_STATUS_MATCH    = "STATUS_MATCH";
    public static final String ENROLL_BACKFILL_V2     = "BACKFILL_V2";

    // ── Perk handler keys (match seed data) ───────────────────────────────
    public static final String PERK_DISCOUNT_PCT           = "DISCOUNT_PERCENT_OF_BOOKING";
    public static final String PERK_FREE_CANCEL_24H        = "FREE_CANCELLATION_EXTENDED";
    public static final String PERK_BONUS_MULTIPLIER       = "BONUS_POINTS_MULTIPLIER";
    public static final String PERK_PRIORITY_WAITLIST      = "PRIORITY_WAITLIST";
    public static final String PERK_EARLY_ACCESS           = "EARLY_ACCESS_BOOKING_WINDOW";
    public static final String PERK_BIRTHDAY_BONUS         = "BIRTHDAY_BONUS_POINTS";
    public static final String PERK_WELCOME_BONUS          = "WELCOME_BONUS_POINTS";
    public static final String PERK_STATUS_EXTENSION       = "STATUS_EXTENSION_GRANT";
    public static final String PERK_REWARD_CATALOG         = "REWARD_CATALOG_CLAIM";
    public static final String PERK_SURPRISE_DELIGHT       = "SURPRISE_DELIGHT_BUDGET";
}
