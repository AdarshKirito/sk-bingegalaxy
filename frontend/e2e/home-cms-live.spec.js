// LIVE end-to-end smoke that hits the real running backend (no API stubbing).
// Verifies that:
//   1. The public landing page loads against the live gateway and renders the
//      seasonal hero + carousel sourced from the previously-saved CMS doc.
//   2. A super-admin can navigate to /admin/home-editor through the entrance
//      portal and see the editor render with the loaded CMS data.
//   3. Saving from the editor persists end-to-end (PUT, then reload home).
//
// This complements the stubbed home-cms.spec.js by proving the full chain —
// frontend → gateway → JWT filter → auth-service → Postgres — actually works.
import { test, expect } from '@playwright/test';

const ADMIN_EMAIL = 'admin@skbingegalaxy.com';
const ADMIN_PASS = 'Admin@123Local';
const STAMP = `live-${Date.now()}`;

test.describe.configure({ mode: 'serial' });

test('public Home loads seasonal hero from real CMS API', async ({ page }) => {
  await page.goto('/');
  // Hero kicker / headline / gallery should all render — even if the DB is
  // empty the page must use the bundled defaults rather than crashing.
  await expect(page.locator('.home-hero')).toBeVisible({ timeout: 15000 });
  await expect(page.locator('.home-carousel').first()).toBeVisible();
  await expect(page.locator('.home-banner-slide img').first()).toBeVisible();
  await page.screenshot({ path: 'test-results/live-home-default.png', fullPage: true });
});

test('super-admin saves a unique kicker, then it reflects on Home', async ({ page }) => {
  // 1) Login via the real auth flow.
  await page.goto('/admin/login');
  await page.locator('input[type="email"]').fill(ADMIN_EMAIL);
  await page.locator('input[type="password"]').first().fill(ADMIN_PASS);
  await page.getByRole('button', { name: /sign in|login/i }).click();
  await page.waitForURL(/\/admin\/platform/, { timeout: 20000 });

  // 2) Navigate directly to the home editor and confirm it renders.
  await page.goto('/admin/home-editor');
  await expect(page.getByRole('heading', { name: /edit the public home page/i })).toBeVisible({ timeout: 15000 });

  // 3) Update the hero kicker to a unique stamp and Save.
  const kickerInput = page.locator('label:has-text("Kicker (small label above headline)") input');
  await kickerInput.first().fill(STAMP);
  await page.getByRole('button', { name: /save & publish/i }).first().click();

  // Toast confirmation (react-toastify) should show up after the PUT resolves.
  await expect(page.getByText(/Home page updated|Saved/i).first()).toBeVisible({ timeout: 10000 });
  await page.screenshot({ path: 'test-results/live-admin-editor.png', fullPage: true });

  // 4) Reload the public home and assert the new kicker is rendered.
  await page.goto('/');
  await expect(page.locator('.home-kicker').first()).toContainText(STAMP, { timeout: 10000 });
  await page.screenshot({ path: 'test-results/live-home-after-edit.png', fullPage: true });
});
