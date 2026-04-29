// Playwright UI snapshot test for the redesigned customer-portal toolbars.
// Stubs auth + API responses so the page renders the toolbar deterministically
// without requiring real backend data, then captures screenshots of:
//  - MyBookings toolbar (default + with active filters)
//  - CustomerPayments toolbar (default + with search applied)
import { test, expect } from '@playwright/test';

const SAMPLE_BOOKINGS_CURRENT = [
  {
    bookingRef: 'BK-DEMO-001',
    eventType: { name: 'Cinema' },
    bookingDate: '2026-05-10',
    startTime: '18:00',
    durationMinutes: 90,
    numberOfGuests: 2,
    totalAmount: 1200,
    status: 'CONFIRMED',
    paymentStatus: 'SUCCESS',
    venueRoomName: 'Hall A',
    paymentMethod: 'UPI',
  },
  {
    bookingRef: 'BK-DEMO-002',
    eventType: { name: 'Karaoke' },
    bookingDate: '2026-06-02',
    startTime: '20:00',
    durationMinutes: 120,
    numberOfGuests: 4,
    totalAmount: 2400,
    status: 'PENDING',
    paymentStatus: 'PENDING',
    venueRoomName: 'Hall B',
    paymentMethod: 'CARD',
  },
];

const SAMPLE_PAYMENTS = [
  {
    id: 1,
    transactionId: 'TXN-DEMO-A',
    bookingRef: 'BK-DEMO-001',
    amount: 1200,
    paymentMethod: 'UPI',
    status: 'SUCCESS',
    createdAt: '2026-04-20T10:30:00Z',
  },
  {
    id: 2,
    transactionId: 'TXN-DEMO-B',
    bookingRef: 'BK-DEMO-002',
    amount: 2400,
    paymentMethod: 'CARD',
    status: 'INITIATED',
    createdAt: '2026-04-25T14:00:00Z',
  },
];

async function stubApis(page) {
  await page.route('**/api/v1/auth/profile', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: { id: 1, firstName: 'Demo', lastName: 'User', email: 'demo@example.com', role: 'CUSTOMER', active: true, phone: '9999999999' },
      }),
    }),
  );
  await page.route('**/api/v1/bookings/my/current**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: SAMPLE_BOOKINGS_CURRENT }) }),
  );
  await page.route('**/api/v1/bookings/my/past**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [] }) }),
  );
  await page.route('**/api/v1/payments/my**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: SAMPLE_PAYMENTS }) }),
  );
  // Generic catch-all for anything else the page may probe.
  await page.route('**/api/v1/**', (route) => {
    if (route.request().resourceType() === 'xhr' || route.request().resourceType() === 'fetch') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [] }) });
    }
    return route.continue();
  });
}

async function authenticate(page) {
  await page.goto('/');
  await page.evaluate(() => {
    localStorage.setItem('user', JSON.stringify({ id: 1, firstName: 'Demo', lastName: 'User', email: 'demo@example.com', role: 'CUSTOMER', active: true, phone: '9999999999' }));
    localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
    localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Demo Binge', address: '123 Demo Street', timezone: 'Asia/Kolkata' }));
  });
}

test.describe('Customer portal — redesigned toolbar', () => {
  test('My Bookings toolbar renders with polished search + chips', async ({ page }) => {
    await stubApis(page);
    await authenticate(page);

    await page.goto('/my-bookings');
    const toolbar = page.getByTestId('my-bookings-toolbar');
    await expect(toolbar).toBeVisible({ timeout: 10000 });
    await toolbar.scrollIntoViewIfNeeded();

    // Tabs visible
    await expect(toolbar.getByRole('tab', { name: /Upcoming/i })).toBeVisible();
    // Search visible with kbd hint
    await expect(toolbar.getByPlaceholder(/search by ref/i)).toBeVisible();
    await expect(toolbar.locator('.customer-hub-kbd')).toContainText('/');

    await toolbar.screenshot({ path: 'test-results/toolbar-mybookings-default.png' });

    // Apply a filter to verify chip active state lights up
    await toolbar.getByLabel('Filter by booking status').selectOption('CONFIRMED');
    await expect(toolbar.locator('.customer-hub-select[data-active="true"]').first()).toBeVisible();
    await expect(toolbar.locator('.customer-hub-summary')).toBeVisible();
    await expect(toolbar.locator('.customer-hub-active-pill')).toContainText(/filter/i);

    await toolbar.screenshot({ path: 'test-results/toolbar-mybookings-filtered.png' });
  });

  test('"/" keyboard shortcut focuses the bookings search', async ({ page }) => {
    await stubApis(page);
    await authenticate(page);
    await page.goto('/my-bookings');
    await expect(page.getByTestId('my-bookings-toolbar')).toBeVisible({ timeout: 10000 });

    await page.locator('body').press('/');
    const focused = await page.evaluate(() => document.activeElement?.getAttribute('aria-label'));
    expect(focused).toBe('Search bookings');
  });

  test('Customer Payments toolbar renders with summary footer when searching', async ({ page }) => {
    await stubApis(page);
    await authenticate(page);

    await page.goto('/payments');
    const toolbar = page.getByTestId('customer-payments-toolbar');
    await expect(toolbar).toBeVisible({ timeout: 10000 });
    await toolbar.scrollIntoViewIfNeeded();

    await expect(toolbar.getByPlaceholder(/search booking ref/i)).toBeVisible();
    await toolbar.screenshot({ path: 'test-results/toolbar-payments-default.png' });

    await toolbar.getByPlaceholder(/search booking ref/i).fill('BK-DEMO-001');
    await expect(toolbar.locator('.customer-hub-summary')).toBeVisible({ timeout: 4000 });

    await toolbar.screenshot({ path: 'test-results/toolbar-payments-search.png' });
  });
});
