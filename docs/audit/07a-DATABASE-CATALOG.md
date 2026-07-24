# 07a — Database Column Catalog (live introspection)

> **Historical live snapshot captured before booking V75–V77/payment V14.** Preserve as dated evidence; use [`../12-DATABASE.md`](../12-DATABASE.md) for current source migration state.

Exhaustive column-level catalog of every first-party table across the four PostgreSQL databases, **introspected from the running stack** (commit `e3edbc1`, 2026-07-12) via `information_schema.columns` + `information_schema.table_constraints` as user `skbg_admin`. This is runtime evidence, not a transcription of migrations. `flyway_schema_history` and `shedlock` (infra bookkeeping) are omitted. Type shorthand: `NN` = NOT NULL; `·` = nullable. **Keys** line lists PRIMARY KEY / UNIQUE (`UQ`) / FOREIGN KEY (`FK`) constraints (column-level; FK targets not shown). Mongo `notification_db` collections are catalogued in [07b-MONGO-CATALOG.md](07b-MONGO-CATALOG.md). Narrative integrity analysis is in [07-DATABASE.md](07-DATABASE.md); issues cross-referenced: DATA-001/002/006/007/008.

Coverage: **97 tables** (auth 13, availability 3, booking 70, payment 11 — incl. flyway/shedlock excluded here) · **1,218 columns** · **172 key constraints**.

---

## auth_db

### `auth_audit_log`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `event_type` | varchar(64) | **NN** |
| `actor_id` | bigint | · |
| `actor_role` | varchar(20) | · |
| `target_id` | bigint | · |
| `target_email` | varchar(150) | · |
| `ip_address` | varchar(64) | · |
| `user_agent` | varchar(512) | · |
| `request_id` | varchar(64) | · |
| `success` | boolean | **NN** |
| `failure_reason` | varchar(255) | · |
| `details` | text | · |
| `created_at` | timestamp | **NN** |

**Keys:** PK(id)

### `authority_grant_scopes`

| Column | Type | Null |
|---|---|---|
| `grant_id` | bigint | **NN** |
| `scope` | varchar(32) | **NN** |

**Keys:** FK(grant_id) · PK(grant_id,scope)

### `authority_grants`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `grantee_user_id` | bigint | **NN** |
| `granted_by` | bigint | **NN** |
| `reason` | varchar(500) | **NN** |
| `granted_at` | timestamp | **NN** |
| `expires_at` | timestamp | **NN** |
| `revoked_at` | timestamp | · |
| `revoked_by` | bigint | · |
| `revoke_reason` | varchar(500) | · |

**Keys:** PK(id)

### `email_verification_token`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `user_id` | bigint | **NN** |
| `token_hash` | varchar(128) | **NN** |
| `otp` | varchar(12) | **NN** |
| `otp_attempts` | integer | **NN** |
| `expires_at` | timestamp | **NN** |
| `used` | boolean | **NN** |
| `created_at` | timestamp | **NN** |
| `pending_email` | varchar(150) | · |

**Keys:** FK(user_id) · PK(id)

### `password_history`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `user_id` | bigint | **NN** |
| `password_hash` | varchar(255) | **NN** |
| `created_at` | timestamp | **NN** |

**Keys:** FK(user_id) · PK(id)

### `password_reset_tokens`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `token` | varchar(128) | **NN** |
| `otp` | varchar(6) | · |
| `user_id` | bigint | **NN** |
| `expires_at` | timestamp | **NN** |
| `used` | boolean | **NN** |
| `created_at` | timestamp | · |
| `otp_attempts` | integer | · |

**Keys:** FK(user_id) · PK(id)

### `resource_locks`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `resource_type` | varchar(64) | **NN** |
| `resource_id` | varchar(128) | **NN** |
| `locked_by` | bigint | **NN** |
| `locked_by_name` | varchar(200) | · |
| `reason` | varchar(500) | **NN** |
| `locked_at` | timestamp | **NN** |

**Keys:** PK(id) · UQ(resource_type,resource_id)

### `revoked_token`

| Column | Type | Null |
|---|---|---|
| `jti` | varchar(64) | **NN** |
| `user_id` | bigint | · |
| `token_type` | varchar(16) | **NN** |
| `expires_at` | timestamp | **NN** |
| `revoked_at` | timestamp | **NN** |

**Keys:** PK(jti)

### `site_content`

| Column | Type | Null |
|---|---|---|
| `slug` | varchar(64) | **NN** |
| `content_json` | text | **NN** |
| `updated_at` | timestamp | **NN** |
| `updated_by` | bigint | · |

**Keys:** PK(slug)

### `user_session`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `user_id` | bigint | **NN** |
| `refresh_jti` | varchar(64) | **NN** |
| `ip_address` | varchar(64) | · |
| `user_agent` | varchar(512) | · |
| `device_label` | varchar(255) | · |
| `created_at` | timestamp | **NN** |
| `last_seen_at` | timestamp | **NN** |
| `expires_at` | timestamp | **NN** |
| `revoked_at` | timestamp | · |
| `revoked_by` | bigint | · |
| `revoke_reason` | varchar(64) | · |

**Keys:** FK(user_id) · PK(id) · UQ(refresh_jti)

### `users`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `first_name` | varchar(100) | **NN** |
| `last_name` | varchar(100) | **NN** |
| `email` | varchar(150) | **NN** |
| `phone` | varchar(20) | · |
| `preferred_experience` | varchar(100) | · |
| `vibe_preference` | varchar(120) | · |
| `reminder_lead_days` | integer | · |
| `birthday_month` | varchar(20) | · |
| `birthday_day` | integer | · |
| `anniversary_month` | varchar(20) | · |
| `anniversary_day` | integer | · |
| `birthday_reminder_sent_year` | integer | · |
| `anniversary_reminder_sent_year` | integer | · |
| `notification_channel` | varchar(20) | · |
| `receives_offers` | boolean | · |
| `weekend_alerts` | boolean | · |
| `concierge_support` | boolean | · |
| `password` | varchar(255) | **NN** |
| `role` | varchar(20) | **NN** |
| `active` | boolean | **NN** |
| `created_at` | timestamp | · |
| `updated_at` | timestamp | · |
| `failed_login_attempts` | integer | · |
| `locked_until` | timestamp | · |
| `email_verified` | boolean | **NN** |
| `email_verified_at` | timestamp | · |
| `mfa_enabled` | boolean | **NN** |
| `mfa_secret` | varchar(64) | · |
| `mfa_enrolled_at` | timestamp | · |
| `mfa_recovery_codes_hash` | text | · |
| `last_password_change_at` | timestamp | · |
| `address_line1` | varchar(200) | · |
| `address_line2` | varchar(200) | · |
| `city` | varchar(100) | · |
| `state` | varchar(100) | · |
| `country` | varchar(2) | · |
| `postal_code` | varchar(20) | · |
| `phone_country_code` | varchar(8) | · |
| `pending_email` | varchar(150) | · |
| `pending_email_otp` | varchar(8) | · |
| `pending_email_token_hash` | varchar(64) | · |
| `pending_email_expires_at` | timestamp | · |
| `personal_phone` | varchar(20) | · |
| `personal_phone_country_code` | varchar(8) | · |
| `otp_failed_attempts` | integer | **NN** |
| `otp_locked_until` | timestamp | · |
| `deleted_at` | timestamp | · |
| `anonymized_at` | timestamp | · |
| `deletion_requested_at` | timestamp | · |
| `consent_given_at` | timestamp | · |
| `consent_marketing` | boolean | **NN** |
| `data_retention_expires_at` | timestamp | · |
| `webauthn_credential_id` | text | · |
| `webauthn_public_key_cose` | text | · |
| `webauthn_enrolled_at` | timestamp | · |
| `webauthn_last_used_at` | timestamp | · |
| `webauthn_aaguid` | varchar(64) | · |
| `must_change_password` | boolean | **NN** |
| `temp_password_logins_remaining` | integer | · |
| `is_guest` | boolean | **NN** |

**Keys:** PK(id)

---

## availability_db

### `blocked_dates`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `blocked_date` | date | **NN** |
| `reason` | varchar(255) | · |
| `blocked_by` | bigint | **NN** |
| `created_at` | timestamp | · |

**Keys:** PK(id)

### `blocked_slots`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `slot_date` | date | **NN** |
| `start_hour` | integer | **NN** |
| `end_hour` | integer | **NN** |
| `reason` | varchar(255) | · |
| `blocked_by` | bigint | **NN** |
| `created_at` | timestamp | · |

**Keys:** PK(id)

---

## booking_db

### `add_on_images`

| Column | Type | Null |
|---|---|---|
| `add_on_id` | bigint | **NN** |
| `image_url` | varchar(1000) | · |

**Keys:** FK(add_on_id)

### `add_ons`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `name` | varchar(100) | **NN** |
| `description` | varchar(300) | · |
| `price` | numeric(10,2) | **NN** |
| `active` | boolean | **NN** |
| `stock_per_day` | integer | · |
| `advance_notice_minutes` | integer | · |
| `category_id` | bigint | **NN** |

**Keys:** FK(binge_id) · FK(category_id) · PK(id)

### `addon_categories`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `name` | varchar(80) | **NN** |
| `description` | varchar(500) | · |
| `image_url` | varchar(1000) | · |
| `sort_order` | integer | **NN** |
| `active` | boolean | **NN** |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | · |

**Keys:** PK(id)

### `admin_notifications`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `recipient_user_id` | bigint | · |
| `recipient_role` | varchar(32) | **NN** |
| `type` | varchar(64) | **NN** |
| `severity` | varchar(16) | **NN** |
| `title` | varchar(200) | **NN** |
| `message` | varchar(1000) | **NN** |
| `related_binge_id` | bigint | · |
| `action_url` | varchar(500) | · |
| `read_at` | timestamp | · |
| `created_at` | timestamp | **NN** |
| `sender_user_id` | bigint | · |
| `sender_role` | varchar(32) | **NN** |
| `sender_name` | varchar(150) | · |
| `recipient_name` | varchar(150) | · |
| `thread_id` | bigint | · |
| `parent_id` | bigint | · |
| `attachment_url` | varchar(500) | · |
| `attachment_type` | varchar(20) | · |
| `attachment_name` | varchar(255) | · |

**Keys:** PK(id)

### `billing_addresses`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `customer_id` | bigint | · |
| `booking_ref` | varchar(20) | · |
| `full_name` | varchar(160) | · |
| `company_name` | varchar(200) | · |
| `tax_id` | varchar(64) | · |
| `address_line1` | varchar(200) | **NN** |
| `address_line2` | varchar(200) | · |
| `city` | varchar(120) | · |
| `state_code` | varchar(16) | · |
| `postal_code` | varchar(20) | · |
| `country_code` | varchar(8) | **NN** |
| `email` | varchar(160) | · |
| `phone` | varchar(40) | · |
| `phone_country_code` | varchar(8) | · |
| `customer_type` | varchar(20) | **NN** |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |

**Keys:** PK(id)

### `binge_change_requests`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | **NN** |
| `request_type` | varchar(40) | **NN** |
| `current_value` | varchar(100) | · |
| `requested_value` | varchar(100) | **NN** |
| `requested_currency` | varchar(3) | · |
| `reason` | varchar(500) | · |
| `requested_by_admin_id` | bigint | **NN** |
| `status` | varchar(20) | **NN** |
| `decided_by_user_id` | bigint | · |
| `decided_at` | timestamp | · |
| `decision_note` | varchar(500) | · |
| `created_at` | timestamp | **NN** |

**Keys:** PK(id)

### `binge_module_permissions`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | **NN** |
| `user_id` | bigint | **NN** |
| `role` | varchar(32) | **NN** |
| `module_key` | varchar(40) | **NN** |
| `action_key` | varchar(20) | **NN** |
| `enabled` | boolean | **NN** |
| `locked_by_super_admin` | boolean | **NN** |
| `granted_by_user_id` | bigint | · |
| `granted_by_role` | varchar(32) | · |
| `remarks` | varchar(500) | · |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |

**Keys:** PK(id) · UQ(binge_id,user_id,module_key,action_key)

### `binge_permission_audit`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | **NN** |
| `target_user_id` | bigint | **NN** |
| `module_key` | varchar(40) | **NN** |
| `action_key` | varchar(20) | **NN** |
| `old_enabled` | boolean | · |
| `new_enabled` | boolean | **NN** |
| `old_locked` | boolean | · |
| `new_locked` | boolean | **NN** |
| `changed_by_user_id` | bigint | **NN** |
| `changed_by_role` | varchar(32) | **NN** |
| `remarks` | varchar(500) | · |
| `created_at` | timestamp | **NN** |

**Keys:** PK(id)

### `binge_site_content`

| Column | Type | Null |
|---|---|---|
| `binge_id` | bigint | **NN** |
| `slug` | varchar(64) | **NN** |
| `content_json` | text | **NN** |
| `updated_at` | timestamp | **NN** |
| `updated_by` | bigint | · |

**Keys:** FK(binge_id) · PK(binge_id,slug)

### `binges`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `name` | varchar(150) | **NN** |
| `address` | varchar(500) | · |
| `admin_id` | bigint | **NN** |
| `active` | boolean | **NN** |
| `operational_date` | date | · |
| `created_at` | timestamp | · |
| `updated_at` | timestamp | · |
| `customer_dashboard_config_json` | text | · |
| `support_email` | varchar(150) | · |
| `support_phone` | varchar(20) | · |
| `support_whatsapp` | varchar(20) | · |
| `customer_cancellation_enabled` | boolean | **NN** |
| `customer_cancellation_cutoff_minutes` | integer | **NN** |
| `max_concurrent_bookings` | integer | · |
| `customer_about_config_json` | text | · |
| `open_time` | time without time zone | · |
| `close_time` | time without time zone | · |
| `address_line1` | varchar(200) | · |
| `address_line2` | varchar(200) | · |
| `city` | varchar(100) | · |
| `state` | varchar(100) | · |
| `country` | varchar(2) | · |
| `postal_code` | varchar(20) | · |
| `support_phone_country_code` | varchar(8) | · |
| `support_whatsapp_country_code` | varchar(8) | · |
| `status` | varchar(32) | **NN** |
| `approval_decided_by` | bigint | · |
| `approval_decided_at` | timestamp | · |
| `approval_rejection_reason` | varchar(500) | · |
| `first_event_created_at` | timestamp | · |
| `grace_warning_sent_at` | timestamp | · |
| `auto_deactivated_at` | timestamp | · |
| `freeze_duration_minutes` | integer | **NN** |
| `max_pending_cancels_before_freeze` | integer | **NN** |
| `max_pending_payment_timeouts_before_freeze` | integer | **NN** |
| `refund_on_successful_payment_cancel` | boolean | **NN** |
| `refund_on_pending_payment_cancel` | boolean | **NN** |
| `freeze_policy_enabled` | boolean | **NN** |
| `max_no_shows_before_freeze` | integer | **NN** |
| `room_selection_required` | boolean | **NN** |
| `timezone` | varchar(64) | **NN** |
| `latitude` | double precision | · |
| `longitude` | double precision | · |
| `opening_hours_json` | text | · |
| `currency` | varchar(3) | **NN** |
| `max_unpaid_bookings_per_customer` | integer | **NN** |
| `taxes_enabled` | boolean | **NN** |
| `access_remarks` | varchar(1000) | · |

**Keys:** PK(id)

### `booking_add_ons`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_id` | bigint | **NN** |
| `add_on_id` | bigint | **NN** |
| `quantity` | integer | **NN** |
| `price` | numeric(10,2) | **NN** |

**Keys:** FK(add_on_id) · FK(booking_id) · PK(id)

### `booking_event_log`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(20) | **NN** |
| `event_type` | varchar(30) | **NN** |
| `previous_status` | varchar(20) | · |
| `new_status` | varchar(20) | **NN** |
| `triggered_by` | bigint | · |
| `triggered_by_role` | varchar(20) | · |
| `description` | varchar(2000) | · |
| `snapshot` | jsonb | · |
| `event_version` | integer | **NN** |
| `created_at` | timestamp | **NN** |
| `triggered_by_name` | varchar(160) | · |
| `reason` | varchar(1000) | · |
| `ip_address` | varchar(45) | · |
| `user_agent` | varchar(500) | · |
| `binge_id` | bigint | · |

**Keys:** PK(id)

### `booking_notes`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(30) | **NN** |
| `binge_id` | bigint | **NN** |
| `author_admin_id` | bigint | **NN** |
| `author_name` | varchar(100) | **NN** |
| `body` | text | **NN** |
| `visibility` | varchar(12) | **NN** |
| `pinned` | boolean | **NN** |
| `edited` | boolean | **NN** |
| `deleted` | boolean | **NN** |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |

**Keys:** PK(id)

### `booking_price_snapshots`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(20) | · |
| `binge_id` | bigint | · |
| `customer_id` | bigint | · |
| `base_currency_code` | varchar(8) | **NN** |
| `display_currency_code` | varchar(8) | **NN** |
| `payment_currency_code` | varchar(8) | **NN** |
| `settlement_currency_code` | varchar(8) | **NN** |
| `subtotal_base` | numeric(14,4) | **NN** |
| `surge_amount_base` | numeric(14,4) | **NN** |
| `loyalty_redemption_base` | numeric(14,4) | **NN** |
| `discount_amount_base` | numeric(14,4) | **NN** |
| `platform_fee_base` | numeric(14,4) | **NN** |
| `tax_amount_base` | numeric(14,4) | **NN** |
| `total_base` | numeric(14,4) | **NN** |
| `display_total` | numeric(14,4) | **NN** |
| `payment_total` | numeric(14,4) | **NN** |
| `settlement_total` | numeric(14,4) | · |
| `fx_rate_display` | numeric(20,10) | **NN** |
| `fx_rate_payment` | numeric(20,10) | **NN** |
| `fx_rate_settlement` | numeric(20,10) | **NN** |
| `fx_source` | varchar(40) | **NN** |
| `fx_locked_at` | timestamp | · |
| `fx_locked_until` | timestamp | · |
| `tax_breakdown_json` | text | · |
| `pricing_breakdown_json` | text | · |
| `billing_country` | varchar(8) | · |
| `billing_state` | varchar(16) | · |
| `billing_postal_code` | varchar(20) | · |
| `customer_type` | varchar(20) | · |
| `calculation_version` | integer | **NN** |
| `created_at` | timestamp | **NN** |
| `created_by` | varchar(120) | · |

**Keys:** PK(id)

### `booking_read_model`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(20) | **NN** |
| `customer_id` | bigint | · |
| `status` | varchar(255) | · |
| `payment_status` | varchar(255) | · |
| `total_amount` | numeric(10,2) | · |
| `collected_amount` | numeric(10,2) | · |
| `booking_date` | date | · |
| `start_time` | time without time zone | · |
| `duration_minutes` | integer | · |
| `number_of_guests` | integer | **NN** |
| `checked_in` | boolean | **NN** |
| `event_type_id` | bigint | · |
| `event_count` | integer | **NN** |
| `last_event_id` | bigint | · |
| `projected_at` | timestamp | · |

**Keys:** PK(id)

### `booking_reviews`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `booking_id` | bigint | **NN** |
| `booking_ref` | varchar(20) | **NN** |
| `customer_id` | bigint | **NN** |
| `admin_id` | bigint | · |
| `reviewer_role` | varchar(20) | **NN** |
| `rating` | integer | · |
| `comment` | varchar(1200) | · |
| `skipped` | boolean | **NN** |
| `visible_to_customer` | boolean | **NN** |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | · |

**Keys:** FK(booking_id) · PK(id)

### `booking_risk_flags`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(30) | **NN** |
| `binge_id` | bigint | **NN** |
| `customer_id` | bigint | **NN** |
| `rule_code` | varchar(40) | **NN** |
| `severity` | varchar(10) | **NN** |
| `source` | varchar(10) | **NN** |
| `reason` | text | · |
| `evidence` | text | · |
| `created_by_admin_id` | bigint | · |
| `acknowledged` | boolean | **NN** |
| `acknowledged_by_admin_id` | bigint | · |
| `acknowledged_at` | timestamp | · |
| `acknowledged_note` | text | · |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |

**Keys:** PK(id)

### `booking_transfers`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(20) | **NN** |
| `binge_id` | bigint | **NN** |
| `from_customer_id` | bigint | **NN** |
| `from_customer_name` | varchar(150) | **NN** |
| `from_customer_email` | varchar(150) | **NN** |
| `to_name` | varchar(150) | **NN** |
| `to_email` | varchar(150) | **NN** |
| `to_phone` | varchar(20) | · |
| `to_phone_country_code` | varchar(8) | · |
| `to_customer_id` | bigint | · |
| `status` | varchar(20) | **NN** |
| `accept_token` | varchar(80) | **NN** |
| `expires_at` | timestamp | **NN** |
| `created_at` | timestamp | **NN** |
| `accepted_at` | timestamp | · |
| `declined_at` | timestamp | · |
| `revoked_at` | timestamp | · |
| `decline_reason` | varchar(500) | · |

**Keys:** PK(id) · UQ(accept_token)

### `bookings`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(20) | **NN** |
| `binge_id` | bigint | · |
| `customer_id` | bigint | **NN** |
| `customer_name` | varchar(150) | **NN** |
| `customer_email` | varchar(150) | **NN** |
| `customer_phone` | varchar(20) | **NN** |
| `event_type_id` | bigint | **NN** |
| `booking_date` | date | **NN** |
| `start_time` | time without time zone | **NN** |
| `duration_hours` | integer | **NN** |
| `duration_minutes` | integer | · |
| `special_notes` | varchar(1000) | · |
| `admin_notes` | varchar(1000) | · |
| `base_amount` | numeric(10,2) | **NN** |
| `add_on_amount` | numeric(10,2) | **NN** |
| `guest_amount` | numeric(10,2) | **NN** |
| `total_amount` | numeric(10,2) | **NN** |
| `collected_amount` | numeric(10,2) | · |
| `number_of_guests` | integer | **NN** |
| `status` | varchar(20) | **NN** |
| `payment_status` | varchar(30) | **NN** |
| `payment_method` | varchar(30) | · |
| `checked_in` | boolean | **NN** |
| `actual_checkout_time` | timestamp | · |
| `actual_used_minutes` | integer | · |
| `early_checkout_note` | varchar(500) | · |
| `pricing_source` | varchar(30) | · |
| `rate_code_name` | varchar(100) | · |
| `created_at` | timestamp | · |
| `updated_at` | timestamp | · |
| `version` | bigint | · |
| `reschedule_count` | integer | **NN** |
| `original_booking_ref` | varchar(20) | · |
| `transferred` | boolean | **NN** |
| `original_customer_id` | bigint | · |
| `original_customer_name` | varchar(150) | · |
| `recurring_group_id` | varchar(40) | · |
| `venue_room_id` | bigint | · |
| `venue_room_name` | varchar(100) | · |
| `loyalty_points_earned` | bigint | **NN** |
| `loyalty_points_redeemed` | bigint | **NN** |
| `loyalty_discount_amount` | numeric(10,2) | · |
| `surge_multiplier` | numeric(5,2) | · |
| `surge_label` | varchar(100) | · |
| `actual_check_in_time` | timestamp | · |
| `customer_phone_country_code` | varchar(8) | · |
| `cancellation_actor` | varchar(20) | · |
| `subtotal_amount` | numeric(12,2) | **NN** |
| `tax_amount` | numeric(12,2) | **NN** |
| `tax_breakdown_json` | text | · |
| `currency_code` | varchar(8) | **NN** |
| `display_amount` | numeric(14,2) | · |
| `fx_rate` | numeric(18,8) | **NN** |
| `base_currency_code` | varchar(8) | · |
| `display_currency_code` | varchar(8) | · |
| `payment_currency_code` | varchar(8) | · |
| `settlement_currency_code` | varchar(8) | · |
| `price_snapshot_id` | bigint | · |
| `billing_address_id` | bigint | · |
| `fx_locked_until` | timestamp | · |
| `calculation_version` | integer | **NN** |
| `late_arrival` | boolean | **NN** |
| `cancellation_reason` | varchar(500) | · |
| `escalation_level` | varchar(16) | **NN** |
| `escalation_reason` | varchar(500) | · |
| `goodwill_credit` | numeric(10,2) | · |
| `goodwill_reason` | varchar(500) | · |
| `goodwill_issued_by_admin_id` | bigint | · |
| `goodwill_issued_at` | timestamp | · |
| `venue_room_price` | numeric(10,2) | **NN** |

**Keys:** FK(event_type_id) · FK(billing_address_id) · FK(price_snapshot_id) · FK(venue_room_id) · PK(id)

### `cancellation_tiers`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | **NN** |
| `hours_before_start` | integer | **NN** |
| `refund_percentage` | integer | **NN** |
| `label` | varchar(100) | · |
| `created_at` | timestamp | **NN** |

**Keys:** FK(binge_id) · PK(id)

### `check_in_tokens`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(20) | **NN** |
| `booking_id` | bigint | **NN** |
| `token_type` | varchar(8) | **NN** |
| `token_value` | varchar(128) | **NN** |
| `issued_by` | varchar(150) | · |
| `issued_at` | timestamp | **NN** |
| `expires_at` | timestamp | **NN** |
| `consumed_at` | timestamp | · |
| `consumed_by` | varchar(150) | · |
| `consumed_ip` | varchar(64) | · |
| `failed_attempts` | integer | **NN** |
| `binge_id` | bigint | · |

**Keys:** PK(id)

### `credit_notes`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `credit_note_number` | varchar(40) | **NN** |
| `invoice_id` | bigint | · |
| `booking_ref` | varchar(20) | **NN** |
| `refund_id` | bigint | · |
| `amount` | numeric(14,4) | **NN** |
| `tax_amount` | numeric(14,4) | **NN** |
| `cancellation_fee` | numeric(14,4) | **NN** |
| `currency_code` | varchar(8) | **NN** |
| `reason` | varchar(40) | **NN** |
| `status` | varchar(20) | **NN** |
| `created_at` | timestamp | **NN** |
| `created_by` | varchar(120) | · |
| `metadata_json` | text | · |

**Keys:** FK(invoice_id) · PK(id) · UQ(credit_note_number)

### `currency_rates`

| Column | Type | Null |
|---|---|---|
| `code` | varchar(8) | **NN** |
| `name` | varchar(80) | **NN** |
| `symbol` | varchar(8) | **NN** |
| `rate_to_base` | numeric(18,8) | **NN** |
| `decimal_digits` | integer | **NN** |
| `active` | boolean | **NN** |
| `is_base` | boolean | **NN** |
| `last_updated` | timestamp | **NN** |
| `manual_override` | boolean | **NN** |
| `supports_display` | boolean | **NN** |
| `supports_payment` | boolean | **NN** |
| `supports_settlement` | boolean | **NN** |
| `fx_source` | varchar(40) | **NN** |
| `created_at` | timestamp | **NN** |
| `updated_by` | varchar(120) | · |

**Keys:** PK(code)

### `customer_addon_pricing`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `customer_pricing_profile_id` | bigint | **NN** |
| `add_on_id` | bigint | **NN** |
| `price` | numeric(10,2) | **NN** |

**Keys:** FK(add_on_id) · FK(customer_pricing_profile_id) · PK(id)

### `customer_binge_freezes`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `customer_id` | bigint | **NN** |
| `binge_id` | bigint | **NN** |
| `freeze_until` | timestamp | **NN** |
| `reason` | text | · |
| `status` | varchar(20) | **NN** |
| `trigger_type` | varchar(40) | **NN** |
| `triggered_by_user_id` | bigint | · |
| `lifted_by_user_id` | bigint | · |
| `lifted_at` | timestamp | · |
| `lifted_reason` | text | · |
| `created_at` | timestamp | **NN** |

**Keys:** PK(id)

### `customer_event_pricing`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `customer_pricing_profile_id` | bigint | **NN** |
| `event_type_id` | bigint | **NN** |
| `base_price` | numeric(10,2) | **NN** |
| `hourly_rate` | numeric(10,2) | **NN** |
| `price_per_guest` | numeric(10,2) | **NN** |

**Keys:** FK(customer_pricing_profile_id) · FK(event_type_id) · PK(id)

### `customer_pricing_profiles`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `customer_id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `rate_code_id` | bigint | · |
| `created_at` | timestamp | · |
| `updated_at` | timestamp | · |
| `member_label` | varchar(120) | · |

**Keys:** FK(rate_code_id) · FK(binge_id) · PK(id)

### `event_categories`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `name` | varchar(80) | **NN** |
| `description` | varchar(500) | · |
| `image_url` | varchar(1000) | · |
| `sort_order` | integer | **NN** |
| `active` | boolean | **NN** |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | · |

**Keys:** PK(id)

### `event_type_images`

| Column | Type | Null |
|---|---|---|
| `event_type_id` | bigint | **NN** |
| `image_url` | text | · |

**Keys:** FK(event_type_id)

### `event_types`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `name` | varchar(100) | **NN** |
| `description` | varchar(500) | · |
| `base_price` | numeric(10,2) | **NN** |
| `hourly_rate` | numeric(10,2) | **NN** |
| `price_per_guest` | numeric(10,2) | **NN** |
| `min_hours` | integer | **NN** |
| `max_hours` | integer | **NN** |
| `active` | boolean | **NN** |
| `min_guests` | integer | · |
| `max_guests` | integer | · |
| `category_id` | bigint | · |

**Keys:** FK(category_id) · FK(binge_id) · PK(id)

### `fx_rate_locks`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `lock_token` | varchar(64) | **NN** |
| `customer_id` | bigint | · |
| `booking_ref` | varchar(20) | · |
| `from_currency` | varchar(8) | **NN** |
| `to_currency` | varchar(8) | **NN** |
| `fx_rate` | numeric(20,10) | **NN** |
| `fx_source` | varchar(40) | **NN** |
| `base_amount` | numeric(14,4) | · |
| `converted_amount` | numeric(14,4) | · |
| `locked_at` | timestamp | **NN** |
| `locked_until` | timestamp | **NN** |
| `consumed_at` | timestamp | · |
| `status` | varchar(20) | **NN** |

**Keys:** PK(id) · UQ(lock_token)

### `idempotency_key`

| Column | Type | Null |
|---|---|---|
| `idempotency_key` | varchar(128) | **NN** |
| `http_method` | varchar(8) | **NN** |
| `request_path` | varchar(255) | **NN** |
| `user_id` | bigint | **NN** |
| `request_hash` | varchar(64) | **NN** |
| `response_status` | integer | **NN** |
| `response_body` | text | · |
| `created_at` | timestamp | **NN** |
| `expires_at` | timestamp | **NN** |

**Keys:** PK(idempotency_key,http_method,request_path,user_id)

### `invoice_lines`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `invoice_id` | bigint | **NN** |
| `line_no` | integer | · |
| `description` | varchar(400) | **NN** |
| `quantity` | numeric(10,2) | **NN** |
| `unit_amount` | numeric(14,4) | **NN** |
| `amount` | numeric(14,4) | **NN** |
| `tax_amount` | numeric(14,4) | **NN** |
| `tax_rate_bps` | integer | · |
| `tax_type` | varchar(40) | · |
| `line_type` | varchar(30) | **NN** |
| `sort_order` | integer | **NN** |

**Keys:** FK(invoice_id) · PK(id)

### `invoices`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `invoice_number` | varchar(40) | **NN** |
| `booking_ref` | varchar(20) | **NN** |
| `binge_id` | bigint | · |
| `customer_id` | bigint | · |
| `seller_legal_name` | varchar(200) | · |
| `seller_tax_id` | varchar(64) | · |
| `seller_address_line` | varchar(400) | · |
| `buyer_name` | varchar(200) | · |
| `buyer_tax_id` | varchar(64) | · |
| `buyer_address_line` | varchar(400) | · |
| `buyer_country_code` | varchar(8) | · |
| `currency_code` | varchar(8) | **NN** |
| `subtotal` | numeric(14,4) | **NN** |
| `tax_total` | numeric(14,4) | **NN** |
| `grand_total` | numeric(14,4) | **NN** |
| `tax_breakdown_json` | text | · |
| `snapshot_id` | bigint | · |
| `issued_at` | timestamp | **NN** |
| `status` | varchar(30) | **NN** |
| `pdf_url` | varchar(400) | · |
| `created_at` | timestamp | **NN** |
| `created_by` | varchar(120) | · |
| `billing_address_id` | bigint | · |
| `discount_total` | numeric(14,4) | · |
| `due_at` | timestamp | · |
| `metadata_json` | text | · |
| `updated_at` | timestamp | **NN** |

**Keys:** FK(billing_address_id) · PK(id) · UQ(invoice_number)

### `ledger_entries`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `entry_uuid` | varchar(40) | **NN** |
| `booking_ref` | varchar(20) | · |
| `binge_id` | bigint | · |
| `customer_id` | bigint | · |
| `payment_id` | bigint | · |
| `refund_id` | bigint | · |
| `invoice_id` | bigint | · |
| `credit_note_id` | bigint | · |
| `snapshot_id` | bigint | · |
| `entry_type` | varchar(40) | **NN** |
| `direction` | varchar(8) | **NN** |
| `amount` | numeric(14,4) | **NN** |
| `currency_code` | varchar(8) | **NN** |
| `reversal_of` | bigint | · |
| `description` | varchar(400) | · |
| `metadata_json` | text | · |
| `occurred_at` | timestamp | **NN** |
| `created_at` | timestamp | **NN** |
| `recorded_by` | varchar(120) | · |
| `fx_rate_to_base` | numeric(20,10) | · |
| `amount_in_base` | numeric(14,4) | · |

**Keys:** FK(reversal_of) · PK(id) · UQ(entry_uuid)

### `loyalty_binge_binding`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `program_id` | bigint | **NN** |
| `binge_id` | bigint | **NN** |
| `status` | varchar(20) | **NN** |
| `legacy_frozen` | boolean | **NN** |
| `enrolled_at` | timestamp | · |
| `enrolled_by_admin_id` | bigint | · |
| `disabled_at` | timestamp | · |
| `disabled_by_admin_id` | bigint | · |
| `effective_from` | timestamp | **NN** |
| `effective_to` | timestamp | · |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |
| `version` | bigint | **NN** |

**Keys:** FK(program_id) · PK(id) · UQ(program_id,binge_id)

### `loyalty_binge_earning_rule`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `binding_id` | bigint | **NN** |
| `tier_code` | varchar(30) | · |
| `rule_type` | varchar(30) | **NN** |
| `points_numerator` | bigint | **NN** |
| `amount_denominator` | numeric(12,2) | **NN** |
| `tier_multiplier` | numeric(5,2) | **NN** |
| `qc_multiplier` | numeric(5,2) | **NN** |
| `min_booking_amount` | numeric(12,2) | · |
| `cap_per_booking` | bigint | · |
| `daily_velocity_cap` | bigint | · |
| `effective_from` | timestamp | **NN** |
| `effective_to` | timestamp | · |
| `created_at` | timestamp | **NN** |
| `created_by_admin_id` | bigint | · |

**Keys:** FK(binding_id) · PK(id)

### `loyalty_binge_perk_override`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `binding_id` | bigint | **NN** |
| `perk_id` | bigint | **NN** |
| `mode` | varchar(20) | **NN** |
| `override_point_cost` | bigint | · |
| `override_cooldown_hours` | integer | · |
| `override_params_json` | text | · |
| `created_at` | timestamp | **NN** |

**Keys:** FK(binding_id) · FK(perk_id) · PK(id) · UQ(binding_id,perk_id)

### `loyalty_binge_redemption_rule`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `binding_id` | bigint | **NN** |
| `points_per_currency_unit` | bigint | **NN** |
| `min_redemption_points` | bigint | **NN** |
| `max_redemption_percent` | numeric(5,2) | **NN** |
| `tier_bonus_pct_json` | text | · |
| `effective_from` | timestamp | **NN** |
| `effective_to` | timestamp | · |
| `created_at` | timestamp | **NN** |
| `created_by_admin_id` | bigint | · |

**Keys:** FK(binding_id) · PK(id)

### `loyalty_binge_reward_item`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `binding_id` | bigint | **NN** |
| `sku` | varchar(60) | **NN** |
| `display_name` | varchar(120) | **NN** |
| `description` | varchar(500) | · |
| `point_cost` | bigint | **NN** |
| `min_tier_code` | varchar(30) | · |
| `inventory_remaining` | bigint | · |
| `active` | boolean | **NN** |
| `effective_from` | timestamp | **NN** |
| `effective_to` | timestamp | · |
| `created_at` | timestamp | **NN** |

**Keys:** FK(binding_id) · PK(id) · UQ(binding_id,sku)

### `loyalty_guest_shadow`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `email_hash` | varchar(64) | · |
| `phone_hash` | varchar(64) | · |
| `device_fingerprint_hash` | varchar(64) | · |
| `pending_points` | bigint | **NN** |
| `pending_qualifying_credits` | bigint | **NN** |
| `last_booking_ref` | varchar(20) | · |
| `first_seen_at` | timestamp | **NN** |
| `last_seen_at` | timestamp | **NN** |
| `merged_membership_id` | bigint | · |
| `merged_at` | timestamp | · |
| `expires_at` | timestamp | **NN** |

**Keys:** FK(merged_membership_id) · PK(id)

### `loyalty_ledger_entry`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `wallet_id` | bigint | **NN** |
| `entry_type` | varchar(30) | **NN** |
| `points_delta` | bigint | **NN** |
| `lot_id` | bigint | · |
| `binge_id` | bigint | · |
| `booking_ref` | varchar(20) | · |
| `actor_id` | bigint | · |
| `actor_role` | varchar(20) | · |
| `reason_code` | varchar(60) | · |
| `description` | varchar(500) | · |
| `correlation_id` | varchar(64) | · |
| `idempotency_key` | varchar(128) | · |
| `created_at` | timestamp | **NN** |

**Keys:** FK(lot_id) · FK(wallet_id) · PK(id) · UQ(wallet_id,entry_type,idempotency_key)

### `loyalty_membership`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `program_id` | bigint | **NN** |
| `customer_id` | bigint | **NN** |
| `member_number` | varchar(20) | **NN** |
| `enrolled_at` | timestamp | **NN** |
| `enrollment_source` | varchar(30) | **NN** |
| `current_tier_code` | varchar(30) | **NN** |
| `tier_effective_from` | timestamp | **NN** |
| `tier_effective_until` | timestamp | · |
| `soft_landing_eligible` | boolean | **NN** |
| `qualifying_credits_window` | bigint | **NN** |
| `lifetime_credits` | bigint | **NN** |
| `lifetime_years_at_current_tier` | integer | **NN** |
| `status_match_source` | varchar(120) | · |
| `status_match_expires_at` | timestamp | · |
| `active` | boolean | **NN** |
| `deactivated_at` | timestamp | · |
| `deactivation_reason` | varchar(255) | · |
| `marketing_opt_in` | boolean | **NN** |
| `privacy_flags_json` | text | · |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |
| `version` | bigint | **NN** |

**Keys:** FK(program_id) · PK(id) · UQ(program_id,customer_id) · UQ(member_number)

### `loyalty_membership_event`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `membership_id` | bigint | **NN** |
| `event_type` | varchar(40) | **NN** |
| `from_value_json` | text | · |
| `to_value_json` | text | · |
| `triggered_by` | varchar(20) | **NN** |
| `triggered_by_id` | bigint | · |
| `correlation_id` | varchar(64) | · |
| `created_at` | timestamp | **NN** |

**Keys:** FK(membership_id) · PK(id)

### `loyalty_perk_catalog`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `program_id` | bigint | **NN** |
| `code` | varchar(60) | **NN** |
| `display_name` | varchar(120) | **NN** |
| `description` | varchar(500) | · |
| `category` | varchar(20) | **NN** |
| `fulfillment_type` | varchar(20) | **NN** |
| `delivery_handler_key` | varchar(80) | **NN** |
| `default_point_cost` | bigint | **NN** |
| `cooldown_hours` | integer | **NN** |
| `params_json` | text | · |
| `active` | boolean | **NN** |
| `effective_from` | timestamp | **NN** |
| `effective_to` | timestamp | · |
| `created_at` | timestamp | **NN** |

**Keys:** FK(program_id) · PK(id) · UQ(program_id,code)

### `loyalty_points_lot`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `wallet_id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `source_type` | varchar(40) | **NN** |
| `source_ref` | varchar(64) | · |
| `original_points` | bigint | **NN** |
| `remaining_points` | bigint | **NN** |
| `earned_at` | timestamp | **NN** |
| `expires_at` | timestamp | **NN** |
| `created_at` | timestamp | **NN** |

**Keys:** FK(wallet_id) · PK(id)

### `loyalty_points_wallet`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `membership_id` | bigint | **NN** |
| `current_balance` | bigint | **NN** |
| `lifetime_earned` | bigint | **NN** |
| `lifetime_redeemed` | bigint | **NN** |
| `lifetime_expired` | bigint | **NN** |
| `lifetime_adjusted` | bigint | **NN** |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |
| `version` | bigint | **NN** |

**Keys:** FK(membership_id) · PK(id) · UQ(membership_id)

### `loyalty_program`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `code` | varchar(40) | **NN** |
| `display_name` | varchar(120) | **NN** |
| `description` | varchar(500) | · |
| `active` | boolean | **NN** |
| `silent_enrollment_enabled` | boolean | **NN** |
| `guest_shadow_enabled` | boolean | **NN** |
| `retroactive_credit_days` | integer | **NN** |
| `points_expiry_days` | integer | **NN** |
| `devaluation_notice_days` | integer | **NN** |
| `status_match_enabled` | boolean | **NN** |
| `status_challenge_days` | integer | **NN** |
| `welcome_bonus_points` | bigint | **NN** |
| `birthday_bonus_points` | bigint | **NN** |
| `allow_negative_balance` | boolean | **NN** |
| `gifting_enabled` | boolean | **NN** |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |

**Keys:** PK(id) · UQ(tenant_id,code)

### `loyalty_qualification_event`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `membership_id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `booking_ref` | varchar(20) | · |
| `event_type` | varchar(40) | **NN** |
| `qualification_credits` | bigint | **NN** |
| `event_at` | timestamp | **NN** |
| `expires_from_window_at` | timestamp | **NN** |
| `created_at` | timestamp | **NN** |

**Keys:** FK(membership_id) · PK(id)

### `loyalty_reward_claim`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `membership_id` | bigint | **NN** |
| `perk_id` | bigint | · |
| `binge_reward_item_id` | bigint | · |
| `binge_id` | bigint | · |
| `booking_ref` | varchar(20) | · |
| `points_cost` | bigint | **NN** |
| `status` | varchar(20) | **NN** |
| `fulfillment_code` | varchar(80) | · |
| `fulfillment_payload_json` | text | · |
| `claimed_at` | timestamp | **NN** |
| `fulfilled_at` | timestamp | · |
| `expires_at` | timestamp | · |
| `cancelled_at` | timestamp | · |

**Keys:** FK(binge_reward_item_id) · FK(membership_id) · FK(perk_id) · PK(id)

### `loyalty_status_match_request`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `membership_id` | bigint | **NN** |
| `competitor_program_name` | varchar(120) | **NN** |
| `competitor_tier_name` | varchar(60) | **NN** |
| `proof_url` | varchar(500) | · |
| `proof_payload_json` | text | · |
| `requested_tier_code` | varchar(30) | **NN** |
| `status` | varchar(20) | **NN** |
| `reviewed_by_admin_id` | bigint | · |
| `reviewed_at` | timestamp | · |
| `review_notes` | varchar(500) | · |
| `challenge_expires_at` | timestamp | · |
| `created_at` | timestamp | **NN** |

**Keys:** FK(membership_id) · PK(id)

### `loyalty_tier_definition`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `program_id` | bigint | **NN** |
| `code` | varchar(30) | **NN** |
| `display_name` | varchar(60) | **NN** |
| `rank_order` | integer | **NN** |
| `qualification_credits_required` | bigint | **NN** |
| `qualification_window_days` | integer | **NN** |
| `lifetime_credits_required` | bigint | · |
| `lifetime_years_held_required` | integer | · |
| `validity_calendar_years_after` | integer | · |
| `soft_landing_tier_code` | varchar(30) | · |
| `color_hex` | varchar(9) | · |
| `icon_key` | varchar(40) | · |
| `effective_from` | timestamp | **NN** |
| `effective_to` | timestamp | · |
| `created_at` | timestamp | **NN** |
| `created_by_admin_id` | bigint | · |

**Keys:** FK(program_id) · PK(id) · UQ(program_id,code,effective_from)

### `loyalty_tier_perk`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `tenant_id` | bigint | · |
| `tier_definition_id` | bigint | **NN** |
| `perk_id` | bigint | **NN** |
| `override_point_cost` | bigint | · |
| `auto_grant` | boolean | **NN** |
| `sort_order` | integer | **NN** |
| `created_at` | timestamp | **NN** |

**Keys:** FK(perk_id) · FK(tier_definition_id) · PK(id) · UQ(tier_definition_id,perk_id)

### `outbox_event`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `topic` | varchar(100) | **NN** |
| `aggregate_key` | varchar(30) | **NN** |
| `payload` | text | **NN** |
| `sent` | boolean | **NN** |
| `created_at` | timestamp | **NN** |
| `sent_at` | timestamp | · |
| `attempts` | integer | **NN** |
| `last_attempt_at` | timestamp | · |
| `last_error` | varchar(1000) | · |
| `failed_permanent` | boolean | **NN** |
| `event_id` | varchar(64) | · |
| `event_type` | varchar(80) | · |
| `event_version` | integer | · |
| `occurred_at` | timestamp | · |
| `correlation_id` | varchar(64) | · |

**Keys:** PK(id)

### `processed_event`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `event_key` | varchar(200) | **NN** |
| `processed_at` | timestamp | · |

**Keys:** PK(id)

### `rate_code_addon_pricing`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `rate_code_id` | bigint | **NN** |
| `add_on_id` | bigint | **NN** |
| `price` | numeric(10,2) | **NN** |

**Keys:** FK(add_on_id) · FK(rate_code_id) · PK(id)

### `rate_code_change_log`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `customer_id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `previous_rate_code_id` | bigint | · |
| `previous_rate_code_name` | varchar(100) | · |
| `new_rate_code_id` | bigint | · |
| `new_rate_code_name` | varchar(100) | · |
| `change_type` | varchar(30) | · |
| `changed_by_admin_id` | bigint | · |
| `changed_at` | timestamp | · |

**Keys:** PK(id)

### `rate_code_event_pricing`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `rate_code_id` | bigint | **NN** |
| `event_type_id` | bigint | **NN** |
| `base_price` | numeric(10,2) | **NN** |
| `hourly_rate` | numeric(10,2) | **NN** |
| `price_per_guest` | numeric(10,2) | **NN** |

**Keys:** FK(event_type_id) · FK(rate_code_id) · PK(id)

### `rate_codes`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `name` | varchar(100) | **NN** |
| `description` | varchar(500) | · |
| `active` | boolean | **NN** |
| `created_at` | timestamp | · |
| `updated_at` | timestamp | · |

**Keys:** FK(binge_id) · PK(id)

### `room_blocks`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `room_id` | bigint | **NN** |
| `start_at` | timestamp | **NN** |
| `end_at` | timestamp | **NN** |
| `reason` | varchar(500) | · |
| `created_by` | bigint | · |
| `created_at` | timestamp | **NN** |

**Keys:** FK(room_id) · PK(id)

### `saga_state`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(20) | **NN** |
| `saga_status` | varchar(30) | **NN** |
| `last_completed_step` | varchar(50) | · |
| `failure_reason` | varchar(500) | · |
| `compensation_attempts` | integer | **NN** |
| `started_at` | timestamp | · |
| `updated_at` | timestamp | · |
| `completed_at` | timestamp | · |
| `version` | bigint | · |

**Keys:** PK(id)

### `slot_holds`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `hold_token` | varchar(64) | **NN** |
| `binge_id` | bigint | **NN** |
| `customer_id` | bigint | **NN** |
| `customer_name` | varchar(150) | · |
| `customer_email` | varchar(150) | · |
| `event_type_id` | bigint | **NN** |
| `booking_date` | date | **NN** |
| `start_time` | time without time zone | **NN** |
| `duration_minutes` | integer | **NN** |
| `number_of_guests` | integer | **NN** |
| `venue_room_id` | bigint | · |
| `status` | varchar(20) | **NN** |
| `expires_at` | timestamp | **NN** |
| `released_at` | timestamp | · |
| `release_reason` | varchar(80) | · |
| `converted_booking_ref` | varchar(32) | · |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |
| `version` | bigint | **NN** |

**Keys:** FK(event_type_id) · PK(id)

### `surge_pricing_rules`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | **NN** |
| `name` | varchar(100) | **NN** |
| `day_of_week` | integer | · |
| `start_minute` | integer | **NN** |
| `end_minute` | integer | **NN** |
| `multiplier` | numeric(5,2) | **NN** |
| `label` | varchar(100) | · |
| `active` | boolean | **NN** |
| `created_at` | timestamp | · |
| `updated_at` | timestamp | · |

**Keys:** PK(id)

### `system_settings`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `operational_date` | date | **NN** |

**Keys:** PK(id)

### `tax_rules`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | · |
| `name` | varchar(120) | **NN** |
| `description` | varchar(500) | · |
| `rate_bps` | integer | **NN** |
| `applies_to` | varchar(20) | **NN** |
| `inclusive` | boolean | **NN** |
| `country_code` | varchar(8) | · |
| `region_code` | varchar(16) | · |
| `priority` | integer | **NN** |
| `active` | boolean | **NN** |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |
| `state_code` | varchar(16) | · |
| `city` | varchar(120) | · |
| `postal_code` | varchar(20) | · |
| `product_type` | varchar(40) | · |
| `customer_type` | varchar(20) | · |
| `tax_type` | varchar(40) | **NN** |
| `effective_from` | timestamp | · |
| `effective_to` | timestamp | · |
| `rule_version` | integer | **NN** |
| `created_by` | varchar(120) | · |
| `updated_by` | varchar(120) | · |

**Keys:** PK(id)

### `venue_room_images`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `room_id` | bigint | **NN** |
| `image_url` | varchar(1000) | **NN** |
| `sort_order` | integer | **NN** |

**Keys:** FK(room_id) · PK(id)

### `venue_rooms`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | **NN** |
| `name` | varchar(100) | **NN** |
| `room_type` | varchar(30) | **NN** |
| `capacity` | integer | **NN** |
| `description` | varchar(500) | · |
| `sort_order` | integer | **NN** |
| `active` | boolean | **NN** |
| `created_at` | timestamp | · |
| `updated_at` | timestamp | · |
| `price_addition` | numeric(10,2) | **NN** |
| `status` | varchar(32) | **NN** |
| `approval_decided_by` | bigint | · |
| `approval_decided_at` | timestamp | · |
| `approval_rejection_reason` | varchar(500) | · |

**Keys:** PK(id)

### `waitlist_entries`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `binge_id` | bigint | **NN** |
| `customer_id` | bigint | **NN** |
| `customer_name` | varchar(150) | **NN** |
| `customer_email` | varchar(150) | **NN** |
| `customer_phone` | varchar(20) | · |
| `event_type_id` | bigint | **NN** |
| `preferred_date` | date | **NN** |
| `preferred_start_time` | time without time zone | **NN** |
| `duration_minutes` | integer | **NN** |
| `number_of_guests` | integer | **NN** |
| `status` | varchar(20) | **NN** |
| `position` | integer | **NN** |
| `offer_expires_at` | timestamp | · |
| `notified_at` | timestamp | · |
| `converted_booking_ref` | varchar(20) | · |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | · |
| `customer_phone_country_code` | varchar(8) | · |
| `priority` | integer | **NN** |

**Keys:** FK(event_type_id) · PK(id)

---

## payment_db

### `admin_approval_request`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `action_type` | varchar(60) | **NN** |
| `resource_type` | varchar(60) | **NN** |
| `resource_id` | varchar(120) | **NN** |
| `payload` | jsonb | · |
| `amount` | numeric(15,2) | · |
| `currency` | varchar(8) | · |
| `binge_id` | bigint | · |
| `status` | varchar(20) | **NN** |
| `requested_by` | varchar(160) | **NN** |
| `requested_by_id` | bigint | · |
| `requested_at` | timestamp | **NN** |
| `request_reason` | varchar(1000) | · |
| `reviewed_by` | varchar(160) | · |
| `reviewed_by_id` | bigint | · |
| `reviewed_at` | timestamp | · |
| `review_reason` | varchar(1000) | · |
| `executed_at` | timestamp | · |
| `executed_result` | varchar(2000) | · |
| `expires_at` | timestamp | **NN** |
| `version` | bigint | **NN** |

**Keys:** PK(id)

### `audit_log`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `actor` | varchar(128) | **NN** |
| `action` | varchar(64) | **NN** |
| `resource_type` | varchar(64) | **NN** |
| `resource_id` | varchar(64) | **NN** |
| `amount` | numeric(12,2) | · |
| `currency` | varchar(8) | · |
| `binge_id` | bigint | · |
| `metadata` | text | · |
| `created_at` | timestamp | **NN** |

**Keys:** PK(id)

### `idempotency_key`

| Column | Type | Null |
|---|---|---|
| `idempotency_key` | varchar(128) | **NN** |
| `http_method` | varchar(8) | **NN** |
| `request_path` | varchar(255) | **NN** |
| `user_id` | bigint | **NN** |
| `request_hash` | varchar(64) | **NN** |
| `response_status` | integer | **NN** |
| `response_body` | text | · |
| `created_at` | timestamp | **NN** |
| `expires_at` | timestamp | **NN** |

**Keys:** PK(idempotency_key,http_method,request_path,user_id)

### `outbox_event`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `topic` | varchar(100) | **NN** |
| `aggregate_key` | varchar(30) | **NN** |
| `payload` | text | **NN** |
| `sent` | boolean | **NN** |
| `created_at` | timestamp | **NN** |
| `sent_at` | timestamp | · |
| `attempts` | integer | **NN** |
| `last_attempt_at` | timestamp | · |
| `last_error` | varchar(1000) | · |
| `failed_permanent` | boolean | **NN** |

**Keys:** PK(id)

### `payment_disputes`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `payment_id` | bigint | **NN** |
| `gateway_dispute_id` | varchar(120) | **NN** |
| `binge_id` | bigint | · |
| `booking_ref` | varchar(60) | **NN** |
| `amount` | numeric(10,2) | **NN** |
| `currency` | varchar(8) | **NN** |
| `status` | varchar(20) | **NN** |
| `reason_code` | varchar(80) | · |
| `reason_description` | varchar(500) | · |
| `respond_by` | timestamp | · |
| `gateway_created_at` | timestamp | · |
| `raw_payload` | text | · |
| `ops_notes` | text | · |
| `created_at` | timestamp | **NN** |
| `updated_at` | timestamp | **NN** |

**Keys:** FK(payment_id) · PK(id) · UQ(gateway_dispute_id)

### `payment_status_history`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `payment_id` | bigint | **NN** |
| `booking_ref` | varchar(30) | **NN** |
| `from_status` | varchar(30) | · |
| `to_status` | varchar(30) | **NN** |
| `reason` | varchar(500) | · |
| `created_at` | timestamp | **NN** |

**Keys:** PK(id)

### `payments`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `booking_ref` | varchar(255) | **NN** |
| `customer_id` | bigint | **NN** |
| `transaction_id` | varchar(255) | **NN** |
| `gateway_order_id` | varchar(255) | · |
| `gateway_payment_id` | varchar(255) | · |
| `amount` | numeric(10,2) | **NN** |
| `gateway_fee` | numeric(10,2) | · |
| `tax` | numeric(10,2) | · |
| `payment_method` | varchar(255) | **NN** |
| `status` | varchar(255) | **NN** |
| `currency` | varchar(255) | · |
| `gateway_response` | varchar(255) | · |
| `failure_reason` | varchar(255) | · |
| `paid_at` | timestamp | · |
| `created_at` | timestamp | · |
| `updated_at` | timestamp | · |
| `version` | bigint | · |
| `customer_email` | varchar(255) | · |
| `customer_name` | varchar(255) | · |
| `customer_phone` | varchar(20) | · |
| `binge_id` | bigint | · |
| `customer_phone_country_code` | varchar(8) | · |
| `payment_currency_code` | varchar(8) | · |
| `display_currency_code` | varchar(8) | · |
| `settlement_currency_code` | varchar(8) | · |
| `fx_rate_at_payment` | numeric(20,10) | · |
| `fx_locked_until` | timestamp | · |
| `fx_lock_id` | varchar(64) | · |
| `provider_name` | varchar(40) | · |
| `tax_amount` | numeric(14,4) | · |
| `amount_in_settlement` | numeric(14,4) | · |

**Keys:** PK(id)

### `processed_webhook_event`

| Column | Type | Null |
|---|---|---|
| `event_id` | varchar(128) | **NN** |
| `provider` | varchar(32) | **NN** |
| `payload_hash` | varchar(64) | · |
| `received_at` | timestamp | **NN** |

**Keys:** PK(event_id,provider)

### `refunds`

| Column | Type | Null |
|---|---|---|
| `id` | bigint | **NN** |
| `payment_id` | bigint | **NN** |
| `amount` | numeric(10,2) | **NN** |
| `reason` | varchar(255) | · |
| `gateway_refund_id` | varchar(255) | · |
| `status` | varchar(255) | **NN** |
| `gateway_response` | varchar(255) | · |
| `failure_reason` | varchar(255) | · |
| `initiated_by` | varchar(255) | · |
| `refunded_at` | timestamp | · |
| `created_at` | timestamp | · |
| `updated_at` | timestamp | · |
| `refund_status` | varchar(32) | **NN** |
| `retry_of_id` | bigint | · |
| `retry_count` | integer | **NN** |

**Keys:** FK(retry_of_id) · FK(payment_id) · PK(id)
