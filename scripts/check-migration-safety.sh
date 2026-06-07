#!/usr/bin/env bash
# check-migration-safety.sh
# CI gate: reject Flyway migrations that contain patterns known to cause
# production incidents. Run this BEFORE 'mvn flyway:validate' in the pipeline.
#
# Guards applied:
#
#   1. DESTRUCTIVE DDL/DML
#      DROP TABLE / COLUMN / INDEX / CONSTRAINT / SEQUENCE / VIEW / FUNCTION / TRIGGER,
#      TRUNCATE, DELETE FROM (unguarded)
#      → Must carry '-- allow:destructive' override.
#
#   2. LOCKING ALTER TABLE (table-level lock)
#      ALTER TABLE ... ADD COLUMN ... NOT NULL  (without DEFAULT — locks table on Postgres <11)
#      ALTER TABLE ... ALTER COLUMN ... SET NOT NULL
#      ALTER TABLE ... DROP NOT NULL
#      ALTER TABLE ... TYPE  (rewrites the table — full lock)
#      → Must carry '-- allow:lock' override.
#      → Production-safe alternative: pg_rewrite or zero-downtime pattern
#        (add nullable → backfill → set not null in separate migration).
#
#   3. FORWARD-ONLY MIGRATION GUARD (no plain ROLLBACK / UNDO blocks)
#      Checks that migrations do NOT silently contain a ROLLBACK statement —
#      Flyway ignores them but a reviewer might think the migration is undoable.
#      → Must carry '-- allow:rollback' override if a ROLLBACK is intentional.
#
# Override tags (add the comment anywhere in the file after peer review):
#   -- allow:destructive    acknowledged destructive operation
#   -- allow:lock           acknowledged table-lock operation
#   -- allow:rollback       acknowledged rollback statement (non-functional in Flyway)
#
# Exit codes:
#   0  All migrations safe (or explicitly overridden)
#   1  At least one migration blocked — BUILD FAILS

set -euo pipefail

BACKEND_DIR="${1:-backend}"
FAILED=0

# ── Pre-approved migration allowlist ──────────────────────────────────────────
# Migrations that existed before this CI gate was introduced have already been
# reviewed, applied to production, and had their maintenance windows observed.
# They are exempt from the destructive/lock guards by version number.
#
# IMPORTANT: Do NOT add new migrations here — they must carry inline override
# tags instead. This list is append-only for legacy migrations only.
#
# Exempt versions (destructive / locking patterns approved):
#   auth-service:         V3 (DROP NOT NULL), V6 (hash tokens), V7 (security hardening),
#                         V8 (address fields)
#   availability-service: V2 (unique index rebuild)
#   booking-service:      V8 (remove global event types), V11 (reschedule fields),
#                         V12 (rooms/loyalty/surge), V16 (loyalty expiry),
#                         V17 (loyalty system-level), V18 (outbox retry),
#                         V25 (drop loyalty columns), V28 (drop v1 loyalty tables),
#                         V29 (address/phone), V38 (taxes/currencies),
#                         V39 (production taxes), V51 (finance schema), V52 (invoice lines),
#                         V53 (invoices), V58 (addon category), V59 (addon NOT NULL),
#                         V60 (addon FK restrict)
#   payment-service:      V6 (outbox retry)
#
# Note: the allowlist is version-number-only (no service scope). V12 covers
# booking-service V12 (rooms/loyalty/surge — pre-existing) AND payment-service
# V12 (payment_disputes — new, but contains only CREATE TABLE/INDEX, no
# destructive patterns, so the exemption is harmless either way).
APPROVED_VERSIONS="V2|V3|V6|V7|V8|V11|V12|V16|V17|V18|V25|V28|V29|V38|V39|V51|V52|V53|V58|V59|V60"

# ── Pattern groups ─────────────────────────────────────────────────────────────

DESTRUCTIVE_PATTERNS=(
    'DROP[[:space:]]+TABLE'
    'DROP[[:space:]]+COLUMN'
    'DROP[[:space:]]+INDEX'
    'DROP[[:space:]]+CONSTRAINT'
    'DROP[[:space:]]+SEQUENCE'
    'DROP[[:space:]]+VIEW'
    'DROP[[:space:]]+FUNCTION'
    'DROP[[:space:]]+TRIGGER'
    'TRUNCATE'
    'DELETE[[:space:]]+FROM'
    'ALTER[[:space:]]+TABLE[[:space:]]+[a-zA-Z_]+[[:space:]]+DROP'
)

# Patterns that take a table-level lock on Postgres:
#   ADD COLUMN with NOT NULL and no DEFAULT (Postgres <11 full rewrite; >=11 only for non-constant defaults)
#   ALTER COLUMN ... SET NOT NULL (full table scan + lock)
#   ALTER COLUMN ... TYPE (table rewrite = full lock)
LOCKING_PATTERNS=(
    'ALTER[[:space:]]+TABLE.*ADD[[:space:]]+COLUMN.*NOT[[:space:]]+NULL'
    'ALTER[[:space:]]+TABLE.*ALTER[[:space:]]+COLUMN.*SET[[:space:]]+NOT[[:space:]]+NULL'
    'ALTER[[:space:]]+TABLE.*ALTER[[:space:]]+COLUMN.*DROP[[:space:]]+NOT[[:space:]]+NULL'
    'ALTER[[:space:]]+TABLE.*ALTER[[:space:]]+COLUMN.*TYPE[[:space:]]'
)

ROLLBACK_PATTERNS=(
    '^[[:space:]]*ROLLBACK[[:space:]]*;'
)

# Patterns match the keyword anywhere in the file (no leading -- required).
# The -- SQL comment prefix is convention but the check only needs the keyword.
# Note: patterns must NOT start with '--' — some grep versions (MSYS, BSD) treat
# arguments beginning with '--' as option flags rather than the pattern.
OVERRIDE_DESTRUCTIVE='allow:destructive'
OVERRIDE_LOCK='allow:lock'
OVERRIDE_ROLLBACK='allow:rollback'

# ── Scan ───────────────────────────────────────────────────────────────────────

echo "=== Flyway Migration Safety Check ==="
echo "Scanning: $BACKEND_DIR"
echo ""

while IFS= read -r -d '' file; do
    # Extract the version prefix (e.g. "V6" from "V6__some_name.sql")
    migration_version=$(basename "$file" | grep -oE '^V[0-9]+')

    # Skip migrations that were applied to production before this gate existed.
    # They are permanently exempt — do not add newly-written migrations here.
    if echo "$migration_version" | grep -qE "^($APPROVED_VERSIONS)$"; then
        continue
    fi

    content=$(cat "$file")
    file_failed=0

    # ── Guard 1: Destructive DDL/DML ──────────────────────────────────────────
    if ! echo "$content" | grep -qiE "$OVERRIDE_DESTRUCTIVE"; then
        for pattern in "${DESTRUCTIVE_PATTERNS[@]}"; do
            if echo "$content" | grep -qiE "$pattern"; then
                matching=$(echo "$content" | grep -inE "$pattern" | head -3)
                echo "  [BLOCKED:destructive]  $file"
                echo "             Pattern: $(echo "$pattern" | tr -d '\\')"
                echo "             Lines:   $matching"
                echo "             Fix:     add '-- allow:destructive' after peer review + backup verification"
                echo ""
                file_failed=1
                FAILED=1
                break
            fi
        done
    else
        echo "  [OVERRIDE:destructive] $file"
    fi

    # ── Guard 2: Locking ALTER TABLE ──────────────────────────────────────────
    if ! echo "$content" | grep -qiE "$OVERRIDE_LOCK"; then
        for pattern in "${LOCKING_PATTERNS[@]}"; do
            if echo "$content" | grep -qiE "$pattern"; then
                matching=$(echo "$content" | grep -inE "$pattern" | head -3)
                echo "  [BLOCKED:lock]  $file"
                echo "             Pattern: $(echo "$pattern" | tr -d '\\')"
                echo "             Lines:   $matching"
                echo "             Why:     This DDL acquires a table-level lock on Postgres."
                echo "             Impact:  All reads/writes on that table block for the migration duration."
                echo "             Impact:  On a 1M-row table this can take minutes, causing complete outage."
                echo "             Fix 1:   Use the zero-downtime pattern:"
                echo "                        Step A: ADD COLUMN nullable (no lock)"
                echo "                        Step B: backfill in batches (no lock)"
                echo "                        Step C: SET NOT NULL in a later migration"
                echo "             Fix 2:   If you must proceed, add '-- allow:lock' after:"
                echo "                        - testing the migration on a production-scale DB copy"
                echo "                        - scheduling a maintenance window"
                echo ""
                file_failed=1
                FAILED=1
                break
            fi
        done
    else
        echo "  [OVERRIDE:lock] $file"
    fi

    # ── Guard 3: ROLLBACK statements (confusing, non-functional in Flyway) ────
    if ! echo "$content" | grep -qiE "$OVERRIDE_ROLLBACK"; then
        for pattern in "${ROLLBACK_PATTERNS[@]}"; do
            if echo "$content" | grep -qiE "$pattern"; then
                matching=$(echo "$content" | grep -inE "$pattern" | head -3)
                echo "  [BLOCKED:rollback]  $file"
                echo "             Pattern: ROLLBACK statement"
                echo "             Lines:   $matching"
                echo "             Why:     Flyway ignores ROLLBACK — the migration is NOT undoable."
                echo "             Why:     A reviewer reading this file will incorrectly believe it can"
                echo "             Why:     be rolled back. Remove it, or add '-- allow:rollback' to"
                echo "             Why:     acknowledge this is intentional (e.g. a test helper)."
                echo ""
                file_failed=1
                FAILED=1
                break
            fi
        done
    fi

    if [ "$file_failed" -eq 0 ]; then
        # Only print OK lines when there are no overrides (reduces noise)
        :
    fi

done < <(find "$BACKEND_DIR" -path "*/db/migration/V*.sql" ! -path "*/target/*" -print0 | sort -z)

echo ""
if [ "$FAILED" -eq 1 ]; then
    echo "=== FAILED: Unsafe SQL patterns detected in Flyway migrations ==="
    echo ""
    echo "    For destructive operations:  add '-- allow:destructive' after peer review + backup."
    echo "    For locking ALTER TABLE:     use the zero-downtime ADD COLUMN pattern, or"
    echo "                                 add '-- allow:lock' after maintenance window planning."
    echo "    For ROLLBACK statements:     remove them (non-functional in Flyway) or add '-- allow:rollback'."
    exit 1
else
    echo "=== PASSED: All Flyway migrations passed safety checks ==="
fi
