-- V17: Seed editable legal/terms content into the site_content CMS.
--
-- Registration (customer + admin) requires explicit consent (DPDP/GDPR). The
-- forms must show the terms the user is consenting to, and a super-admin must be
-- able to paste/replace that text without a code change. These two slugs back:
--   * terms-of-service       — public Terms shown at customer signup + /terms page
--   * admin-onboarding-terms — shown in the "Add Admin" form for the new admin
--
-- Both are edited via the existing site-content CMS
-- (PUT /api/v1/site-content/admin/{slug}). ON CONFLICT DO NOTHING so a super
-- admin's edits are never clobbered on redeploy.

INSERT INTO site_content (slug, content_json, updated_at)
VALUES
  ('terms-of-service',
   '{"title":"Terms of Service & Privacy","body":"Welcome to SK Binge Galaxy. By creating an account you agree to use the platform lawfully, to provide accurate information, and to our processing of your personal data to operate bookings, payments and notifications. A super-admin can replace this placeholder with your full Terms of Service and Privacy Policy."}',
   NOW()),
  ('admin-onboarding-terms',
   '{"title":"Administrator Terms","body":"As an administrator you agree to handle customer and venue data responsibly, keep your credentials secure, act only within your assigned role and scopes, and comply with all applicable data-protection laws. A super-admin can replace this placeholder with your organisation administrator agreement."}',
   NOW())
ON CONFLICT (slug) DO NOTHING;
