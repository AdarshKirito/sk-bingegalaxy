# Current Database Catalog — Evidence

> AUD-2026-07-25-01 · commit `6440f58` · static catalog from migration files (no live schema inspected)

## auth_db (Flyway V1–V20)

Core tables: `users` (incl. anonymization columns), `user_sessions`, `mfa_secrets` (encrypted TOTP), `authority_grants` (scoped delegation, TTL), `resource_locks`, `password_reset_tokens`, `email_verification_tokens`, CMS content tables (home/account/terms), privacy/deletion-request tracking.
Recent: V19-V20 authority/privacy hardening.

## availability_db (V1–V2)

`availability_slots`, `room_blocks` projections. Minimal, stable, no PII (verified).

## booking_db (V1–V80) — the giant

| Cluster | Tables (principal) |
|---|---|
| Venue | binges, binge_change_requests, binge_site_content, binge_module_permissions |
| Rooms | venue_rooms, room_blocks |
| Events/add-ons | event_types, event_categories, add_ons, add_on_categories, booking_event_types |
| Pricing | rate_codes (+event/addon pricing, change_log), customer_pricing_profiles (+event/addon), surge_pricing_rules, tax_rules, cancellation_tiers, currency_rates |
| Booking | bookings, booking_add_ons, booking_price_snapshots, booking_notes, booking_event_logs, booking_read_models, booking_reviews |
| Ops | check_in_tokens, booking_transfers, slot_holds, waitlist_entries, booking_risk_flags, customer_binge_freezes |
| Financial docs | invoices, invoice_lines, credit_notes, ledger_entries |
| Loyalty v2 (19) | loyalty_programs, tier_definitions, memberships(+events), points_wallets(+lots), binge_bindings(+earning/redemption rules, perk_overrides, reward_items), country_earn_configs, qualification_events |
| Plumbing | outbox_events, processed_events, saga_states, idempotency_keys, system_settings, admin_notifications, shedlock |

Key integrity milestones: **V75 occupancy trigger backstop**; V21/V22 loyalty v2 schema+backfill; V28 drops loyalty v1 (destructive, one-way); V56 room-selection flag; V71 module scoping; V80 loyalty config lock.

## payment_db (V1–V16)

`payments`, `payment_status_history`, `payment_intents` (durable), `refunds` (**V14 partial UNIQUE on gateway_refund_id**), `payment_disputes`, `payment_connected_accounts` (Stripe), `approval_requests`, `ledger_entries`, `outbox_events`, `processed_events`, `idempotency_keys`.

## MongoDB (notification-service)

`notifications` (TTL 90 d on createdAt), `templates` (versioned), `delivery_attempts`, dedup collection (TTL 1 h), `push_subscriptions`.

## infra/init-databases.sql

Creates 4 DBs + per-service roles (dev passwords — HYG-04) + ShedLock table (L67-74).

## Constraint posture (static)

- FKs within each DB: present on child tables (spot-verified booking_add_ons→bookings, invoice_lines→invoices, wallet lots→wallets)
- No cross-DB FKs (by design)
- NOT NULL + CHECK constraints on money columns (minor units ≥ 0) in sampled migrations
- Partial unique indexes: gateway_refund_id (V14); idempotency keys unique per scope
