import { test, expect } from '@playwright/test';

test.describe('Booking flow (unauthenticated)', () => {
  test('redirects to login when accessing /book without auth', async ({ page }) => {
    await page.goto('/book');
    await page.waitForURL(/login/, { timeout: 5000 });
  });

  test('redirects to login when accessing /my-bookings without auth', async ({ page }) => {
    await page.goto('/my-bookings');
    await page.waitForURL(/login/, { timeout: 5000 });
  });
});

test.describe('Booking page structure (authenticated)', () => {
  test.beforeEach(async ({ page }) => {
    // Seed localStorage with mock auth state so ProtectedRoute allows access
    await page.goto('/');
    await page.evaluate(() => {
      localStorage.setItem('user', JSON.stringify({
        id: 1, firstName: 'Test', role: 'CUSTOMER', active: true, phone: '9999999999',
      }));
      localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
      localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Test Binge' }));
    });
  });

  test('booking page loads with event type selection', async ({ page }) => {
    await page.goto('/book');
    // The BookingWizard should render; at minimum we see the first step
    await expect(page.locator('body')).toContainText(/event|book|select/i, { timeout: 8000 });
  });

  test('my-bookings page loads', async ({ page }) => {
    await page.goto('/my-bookings');
    await expect(page.locator('body')).toContainText(/booking|no booking/i, { timeout: 8000 });
  });
});

test.describe('Home page', () => {
  test('renders home with navigation', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('nav')).toBeVisible();
    // Home page should have a call-to-action or hero
    await expect(page.locator('body')).toContainText(/binge|theater|book|experience/i);
  });

  test('404 page renders for unknown routes', async ({ page }) => {
    await page.goto('/this-route-does-not-exist');
    await expect(page.locator('body')).toContainText(/not found|404/i, { timeout: 5000 });
  });
});
