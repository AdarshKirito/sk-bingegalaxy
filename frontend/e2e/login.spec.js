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
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await expect(page.locator('.error-message, .Toastify__toast--error').first()).toBeVisible({ timeout: 5000 });
  });

  test('shows error on invalid credentials', async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[type="email"], input[name="email"]', 'wrong@example.com');
    await page.fill('input[type="password"]', 'WrongPassword123!');
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    // Expect inline error-message div or error toast
    await expect(page.locator('.error-message, .Toastify__toast--error').first()).toBeVisible({ timeout: 10000 });
  });

  test('navigates to register page', async ({ page }) => {
    await page.goto('/login');
    const signUpLink = page.locator('.auth-links a[href="/register"]');
    await signUpLink.scrollIntoViewIfNeeded();
    await signUpLink.click();
    await expect(page).toHaveURL(/register/);
  });

  test('navigates to forgot password page', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('link', { name: /forgot/i }).click();
    await expect(page).toHaveURL(/forgot-password/);
  });

  test('redirects authenticated users away from login', async ({ page }) => {
    // Mock auth profile validation so fake localStorage user is accepted
    await page.route('**/api/v1/auth/profile', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, data: { id: 1, firstName: 'Test', role: 'CUSTOMER', active: true, phone: '9999999999' } }),
      });
    });
    await page.goto('/');
    await page.evaluate(() => {
      localStorage.setItem('user', JSON.stringify({ id: 1, firstName: 'Test', role: 'CUSTOMER', active: true, phone: '9999999999' }));
      localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
    });
    await page.goto('/login');
    await expect(page).not.toHaveURL(/\/login$/, { timeout: 8000 });
  });
});

test.describe('Admin login flow', () => {
  test('shows admin login page with admin branding', async ({ page }) => {
    await page.goto('/admin/login');
    await expect(page.locator('input[type="email"], input[name="email"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
  });

  test('blocks non-admin customer from admin dashboard', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => {
      localStorage.setItem('user', JSON.stringify({ id: 1, firstName: 'Customer', role: 'CUSTOMER', active: true, phone: '9999999999' }));
      localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
    });
    await page.goto('/admin/dashboard');
    await expect(page).not.toHaveURL(/admin\/dashboard/, { timeout: 5000 });
  });

  test('unauthenticated user is redirected to admin login', async ({ page }) => {
    await page.goto('/admin/dashboard');
    await page.waitForURL(/admin\/login/, { timeout: 5000 });
  });
});
