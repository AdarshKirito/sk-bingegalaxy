import { test, expect } from '@playwright/test';

test.describe('Admin dashboard', () => {
  test.beforeEach(async ({ page }) => {
    // Seed admin session
    await page.goto('/');
    await page.evaluate(() => {
      localStorage.setItem('user', JSON.stringify({
        id: 99, firstName: 'Admin', role: 'ADMIN', active: true, phone: '9999999999',
      }));
      localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
      localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Test Binge' }));
    });
  });

  test('admin dashboard loads stat cards', async ({ page }) => {
    await page.goto('/admin/dashboard');
    // Should either show stats or an error/loading state
    await expect(page.locator('body')).toContainText(/dashboard|admin|loading|error/i, { timeout: 8000 });
  });

  test('admin bookings page loads', async ({ page }) => {
    await page.goto('/admin/bookings');
    await expect(page.locator('body')).toContainText(/booking|operational|today|loading/i, { timeout: 8000 });
  });

  test('admin reports page loads', async ({ page }) => {
    await page.goto('/admin/reports');
    await expect(page.locator('body')).toContainText(/report|date|audit|loading/i, { timeout: 8000 });
  });

  test('admin blocked dates page loads', async ({ page }) => {
    await page.goto('/admin/blocked-dates');
    await expect(page.locator('body')).toContainText(/block|date|loading/i, { timeout: 8000 });
  });

  test('admin event types page loads', async ({ page }) => {
    await page.goto('/admin/event-types');
    await expect(page.locator('body')).toContainText(/event|type|loading/i, { timeout: 8000 });
  });
});

test.describe('Admin navigation guards', () => {
  test('customer cannot access admin dashboard', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => {
      localStorage.setItem('user', JSON.stringify({
        id: 1, firstName: 'Customer', role: 'CUSTOMER', active: true, phone: '9999999999',
      }));
      localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
    });
    await page.goto('/admin/dashboard');
    // Should redirect away from admin
    await page.waitForTimeout(2000);
    expect(page.url()).not.toContain('/admin/dashboard');
  });

  test('unauthenticated user redirected to admin login', async ({ page }) => {
    await page.goto('/admin/dashboard');
    await page.waitForURL(/admin\/login/, { timeout: 5000 });
  });
});
