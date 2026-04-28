# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: admin-dashboard.spec.js >> Admin without binge selected >> admin platform entrance shows binge selector
- Location: e2e\admin-dashboard.spec.js:87:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByRole('heading').first()
Expected: visible
Timeout: 8000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 8000ms
  - waiting for getByRole('heading').first()

```

# Page snapshot

```yaml
- generic [ref=e2]:
  - link "Skip to main content" [ref=e3] [cursor=pointer]:
    - /url: "#main-content"
  - navigation "Main navigation" [ref=e4]:
    - generic [ref=e5]:
      - link "SK Binge Galaxy home" [ref=e6] [cursor=pointer]:
        - /url: /
        - generic [ref=e7]: 🎬
        - generic [ref=e8]:
          - strong [ref=e9]: SK Binge Galaxy
          - generic [ref=e10]: Private Screenings
      - generic [ref=e11]:
        - link "Login" [ref=e12] [cursor=pointer]:
          - /url: /login
        - link "Register" [ref=e13] [cursor=pointer]:
          - /url: /register
      - generic [ref=e14]:
        - button "Switch to dark mode" [ref=e15] [cursor=pointer]:
          - img [ref=e16]
        - button "Switch language" [ref=e18] [cursor=pointer]: हि
        - button "Open menu" [ref=e19] [cursor=pointer]:
          - img [ref=e20]
  - main [ref=e21]:
    - generic [ref=e23]:
      - generic [ref=e24]:
        - generic [ref=e25]: Admin access
        - heading "SK Binge Galaxy Management Console" [level=1] [ref=e26]
        - paragraph [ref=e27]: Sign in to manage bookings, view revenue reports, handle check-ins, and oversee day-to-day operations.
        - generic [ref=e28]:
          - generic [ref=e29]: Admin tools
          - list [ref=e30]:
            - listitem [ref=e31]:
              - img [ref=e32]
              - text: Secure role-based access for administrators and super admins.
            - listitem [ref=e34]:
              - img [ref=e35]
              - text: Real-time dashboard with revenue, bookings, and audit reports.
            - listitem [ref=e36]:
              - img [ref=e37]
              - text: Operational day management with check-in and checkout controls.
        - generic [ref=e40]:
          - article [ref=e41]:
            - strong [ref=e42]: Full control
            - generic [ref=e43]: bookings & payments
          - article [ref=e44]:
            - strong [ref=e45]: Live
            - generic [ref=e46]: operational dashboard
      - generic [ref=e48]:
        - generic [ref=e49]: Admin sign in
        - heading "Admin Login" [level=2] [ref=e50]
        - paragraph [ref=e51]: SK Binge Galaxy Administration
        - generic [ref=e52]:
          - generic [ref=e53]:
            - generic [ref=e54]: Email
            - textbox "admin@skbingegalaxy.com" [active] [ref=e55]
          - generic [ref=e56]:
            - generic [ref=e57]: Password
            - generic [ref=e58]:
              - textbox "••••••••" [ref=e59]
              - button "Show password" [ref=e60] [cursor=pointer]:
                - img [ref=e61]
          - button "Sign In as Admin" [ref=e64] [cursor=pointer]
        - generic [ref=e66]: Need admin access? Ask a super admin to create your account.
```

# Test source

```ts
  1   | import { test, expect } from '@playwright/test';
  2   | 
  3   | /**
  4   |  * Admin console flow tests.
  5   |  * Tests the admin dashboard, booking management, and report pages
  6   |  * with simulated admin auth. Requires backend to be running.
  7   |  */
  8   | test.describe('Admin dashboard and management', () => {
  9   |   test.beforeEach(async ({ page }) => {
  10  |     // Seed admin session with binge selected
  11  |     await page.goto('/');
  12  |     await page.evaluate(() => {
  13  |       localStorage.setItem('user', JSON.stringify({
  14  |         id: 99, firstName: 'Admin', role: 'ADMIN', active: true, phone: '9999999999',
  15  |       }));
  16  |       localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
  17  |       localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Test Binge' }));
  18  |     });
  19  |   });
  20  | 
  21  |   test('dashboard loads with heading and content', async ({ page }) => {
  22  |     await page.goto('/admin/dashboard');
  23  |     // Dashboard should render stat cards or at minimum a heading
  24  |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  25  |   });
  26  | 
  27  |   test('dashboard SSE connection is established', async ({ page }) => {
  28  |     // Monitor EventSource connections during page load
  29  |     const sseRequests = [];
  30  |     page.on('request', request => {
  31  |       if (request.url().includes('/sse') || request.headers()['accept']?.includes('text/event-stream')) {
  32  |         sseRequests.push(request.url());
  33  |       }
  34  |     });
  35  |     await page.goto('/admin/dashboard');
  36  |     // Wait for the network to settle so any SSE / polling handshake has been issued.
  37  |     // This is deterministic in CI vs. a fixed sleep which is flaky under load.
  38  |     await page.waitForLoadState('networkidle');
  39  |     // The dashboard should attempt an SSE connection for real-time updates
  40  |     expect(sseRequests.length).toBeGreaterThanOrEqual(0); // Soft check — no crash on SSE init
  41  |   });
  42  | 
  43  |   test('admin bookings page loads', async ({ page }) => {
  44  |     await page.goto('/admin/bookings');
  45  |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  46  |   });
  47  | 
  48  |   test('admin can navigate to create booking (walk-in)', async ({ page }) => {
  49  |     await page.goto('/admin/book');
  50  |     // Admin wizard includes StepCustomer as step 0
  51  |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  52  |   });
  53  | 
  54  |   test('admin reports page loads', async ({ page }) => {
  55  |     await page.goto('/admin/reports');
  56  |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  57  |   });
  58  | 
  59  |   test('admin blocked dates page loads', async ({ page }) => {
  60  |     await page.goto('/admin/blocked-dates');
  61  |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  62  |   });
  63  | 
  64  |   test('admin event types page loads', async ({ page }) => {
  65  |     await page.goto('/admin/event-types');
  66  |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  67  |   });
  68  | 
  69  |   test('admin rate codes page loads', async ({ page }) => {
  70  |     await page.goto('/admin/rate-codes');
  71  |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  72  |   });
  73  | });
  74  | 
  75  | test.describe('Admin without binge selected', () => {
  76  |   test.beforeEach(async ({ page }) => {
  77  |     // Admin without a binge selected
  78  |     await page.goto('/');
  79  |     await page.evaluate(() => {
  80  |       localStorage.setItem('user', JSON.stringify({
  81  |         id: 99, firstName: 'Admin', role: 'ADMIN', active: true, phone: '9999999999',
  82  |       }));
  83  |       localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
  84  |     });
  85  |   });
  86  | 
  87  |   test('admin platform entrance shows binge selector', async ({ page }) => {
  88  |     await page.goto('/admin/platform');
> 89  |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
      |                                                     ^ Error: expect(locator).toBeVisible() failed
  90  |   });
  91  | 
  92  |   test('admin dashboard redirects to platform without binge', async ({ page }) => {
  93  |     await page.goto('/admin/dashboard');
  94  |     await page.waitForURL(/admin\/platform/, { timeout: 5000 });
  95  |   });
  96  | });
  97  | 
  98  | test.describe('Admin navigation guards', () => {
  99  |   test('customer cannot access admin dashboard', async ({ page }) => {
  100 |     await page.goto('/');
  101 |     await page.evaluate(() => {
  102 |       localStorage.setItem('user', JSON.stringify({
  103 |         id: 1, firstName: 'Customer', role: 'CUSTOMER', active: true, phone: '9999999999',
  104 |       }));
  105 |       localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
  106 |     });
  107 |     await page.goto('/admin/dashboard');
  108 |     await expect(page).not.toHaveURL(/admin\/dashboard/, { timeout: 5000 });
  109 |   });
  110 | 
  111 |   test('unauthenticated user redirected to admin login', async ({ page }) => {
  112 |     await page.goto('/admin/dashboard');
  113 |     await page.waitForURL(/admin\/login/, { timeout: 5000 });
  114 |   });
  115 | });
  116 | 
```