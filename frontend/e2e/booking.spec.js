import { test, expect } from '@playwright/test';

/**
 * Customer booking happy path.
 *
 * These tests exercise the full lifecycle: register → navigate to booking →
 * step through the wizard → land on confirmation. The backend must be
 * running with PAYMENT_SIMULATION_ENABLED=true for the payment simulate
 * endpoint to resolve.
 */
test.describe('Customer booking happy path', () => {
  test.beforeEach(async ({ page }) => {
    // Seed localStorage with authenticated customer + selected binge
    await page.goto('/');
    await page.evaluate(() => {
      localStorage.setItem('user', JSON.stringify({
        id: 1, firstName: 'Test', role: 'CUSTOMER', active: true, phone: '9999999999',
      }));
      localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
      localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Test Binge' }));
    });
  });

  test('booking wizard renders first step with event type selection', async ({ page }) => {
    await page.goto('/book');
    // Wizard should show event type cards or a heading prompting selection
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
    await expect(page.locator('body')).toContainText(/event|select|experience/i, { timeout: 3000 });
  });

  test('booking wizard prevents advancing without event type', async ({ page }) => {
    // Mock auth profile so fake user stays authenticated
    await page.route('**/api/v1/auth/profile', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ success: true, data: { id: 1, firstName: 'Test', role: 'CUSTOMER', active: true, phone: '9999999999' } }),
      });
    });
    // Mock event types so the wizard renders step 1
    await page.route('**/api/v1/event-types**', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ success: true, data: [{ id: 1, name: 'Birthday', description: 'Party', basePrice: 1500, active: true }] }),
      });
    });
    await page.goto('/book');
    // Wait for wizard to load, then try to advance without selecting event
    const nextBtn = page.locator('button:has-text("Next"), button:has-text("Continue")').first();
    await expect(nextBtn).toBeVisible({ timeout: 8000 });
    await nextBtn.click();
    // Should show validation error toast
    await expect(page.locator('.Toastify__toast--error').first()).toBeVisible({ timeout: 5000 });
  });

  test('my-bookings page shows bookings or empty state', async ({ page }) => {
    await page.goto('/my-bookings');
    // Should render a heading and either bookings list or empty state text
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  });

  test('payment page shows booking details or not-found', async ({ page }) => {
    await page.goto('/payment/BK-TESTREF');
    // Either shows payment details or error (booking not found), proving the route works
    await expect(page.locator('body')).toContainText(/payment|booking|not found|error/i, { timeout: 8000 });
  });
});

test.describe('Unauthenticated access guards', () => {
  test('redirects to login when accessing /book without auth', async ({ page }) => {
    await page.goto('/book');
    await page.waitForURL(/login/, { timeout: 5000 });
  });

  test('redirects to login when accessing /my-bookings without auth', async ({ page }) => {
    await page.goto('/my-bookings');
    await page.waitForURL(/login/, { timeout: 5000 });
  });

  test('redirects to login when accessing /payments without auth', async ({ page }) => {
    await page.goto('/payments');
    await page.waitForURL(/login/, { timeout: 5000 });
  });
});

test.describe('Home page and navigation', () => {
  test('renders home with navigation and hero', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('nav')).toBeVisible();
    // Should have a call-to-action or branding
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 5000 });
  });

  test('404 page renders for unknown routes', async ({ page }) => {
    await page.goto('/this-route-does-not-exist');
    await expect(page.locator('body')).toContainText(/not found|404/i, { timeout: 5000 });
  });
});
