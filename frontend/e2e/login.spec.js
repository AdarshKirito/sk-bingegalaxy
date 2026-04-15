import { test, expect } from '@playwright/test';

test.describe('Login flow', () => {
  test('shows login page with email and password fields', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.locator('input[type="email"], input[name="email"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
  });

  test('shows validation error on empty submit', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: /sign in|login/i }).click();
    // Should show some validation feedback (toast or inline error)
    await expect(page.locator('.Toastify, [role="alert"], .error, .field-error')).toBeVisible({ timeout: 5000 });
  });

  test('navigates to register page', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('link', { name: /sign up|register|create/i }).click();
    await expect(page).toHaveURL(/register/);
  });

  test('navigates to forgot password page', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('link', { name: /forgot/i }).click();
    await expect(page).toHaveURL(/forgot-password/);
  });

  test('redirects authenticated users away from login', async ({ page }) => {
    // Simulate an active session via localStorage (mock)
    await page.goto('/');
    await page.evaluate(() => {
      localStorage.setItem('user', JSON.stringify({ id: 1, firstName: 'Test', role: 'CUSTOMER', active: true, phone: '9999999999' }));
      localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
    });
    await page.goto('/login');
    // Should redirect away from login (exact URL depends on auth state)
    await page.waitForTimeout(2000);
    const url = page.url();
    expect(url).not.toContain('/login');
  });
});

test.describe('Admin login flow', () => {
  test('shows admin login page', async ({ page }) => {
    await page.goto('/admin/login');
    await expect(page.locator('input[type="email"], input[name="email"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
  });

  test('blocks non-admin access to admin dashboard', async ({ page }) => {
    await page.goto('/admin/dashboard');
    // Should redirect to admin login
    await page.waitForURL(/admin\/login/, { timeout: 5000 });
  });
});
