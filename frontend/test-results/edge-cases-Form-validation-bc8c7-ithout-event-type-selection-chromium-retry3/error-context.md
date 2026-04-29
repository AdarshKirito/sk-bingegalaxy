# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: edge-cases.spec.js >> Form validation edge cases >> booking wizard rejects without event type selection
- Location: e2e\edge-cases.spec.js:85:3

# Error details

```
Test timeout of 30000ms exceeded.
```

```
Error: locator.click: Test timeout of 30000ms exceeded.
Call log:
  - waiting for locator('button:has-text("Next")').first()
    - locator resolved to <button class="btn btn-primary">Next: Choose Date & Time</button>
  - attempting click action
    - waiting for element to be visible, enabled and stable
  - element was detached from the DOM, retrying
    - waiting for" http://localhost:3000/login" navigation to finish...
    - navigated to "http://localhost:3000/login"

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
        - generic [ref=e25]: Member access
        - heading "Welcome back to your private screening journey." [level=1] [ref=e26]
        - paragraph [ref=e27]: Sign in to continue bookings, complete payments, review preferences, and move straight back into the experience without starting over.
        - generic [ref=e28]:
          - generic [ref=e29]: Inside your account
          - list [ref=e30]:
            - listitem [ref=e31]:
              - img [ref=e32]
              - text: Active bookings, payment follow-ups, and booking history in one place.
            - listitem [ref=e35]:
              - img [ref=e36]
              - text: Protected customer access with your existing SK Binge Galaxy account.
            - listitem [ref=e38]:
              - img [ref=e39]
              - text: Faster return to saved plans, dates, and guest details.
        - generic [ref=e42]:
          - article [ref=e43]:
            - strong [ref=e44]: Private
            - generic [ref=e45]: booking flow
          - article [ref=e46]:
            - strong [ref=e47]: Fast
            - generic [ref=e48]: return to your next event
      - generic [ref=e50]:
        - generic [ref=e51]: Sign in
        - heading "Welcome Back" [level=2] [ref=e52]
        - paragraph [ref=e53]: Use your SK Binge Galaxy account to continue where you left off.
        - generic [ref=e54]:
          - generic [ref=e55]:
            - generic [ref=e56]: Email
            - textbox "you@example.com" [active] [ref=e57]
          - generic [ref=e58]:
            - generic [ref=e59]: Password
            - generic [ref=e60]:
              - textbox "••••••••" [ref=e61]
              - button "Show password" [ref=e62] [cursor=pointer]:
                - img [ref=e63]
          - button "Sign In" [ref=e66] [cursor=pointer]
        - generic [ref=e68]: or continue with
        - iframe [ref=e71]:
          - button "Sign in with Google. Opens in new tab" [ref=f1e3] [cursor=pointer]:
            - generic [ref=f1e5]:
              - img [ref=f1e7]
              - generic [ref=f1e14]: Sign in with Google
        - generic [ref=e72]:
          - link "Forgot password?" [ref=e73] [cursor=pointer]:
            - /url: /forgot-password
          - generic [ref=e74]:
            - text: Don't have an account?
            - link "Sign up" [ref=e75] [cursor=pointer]:
              - /url: /register
```

# Test source

```ts
  3   | /**
  4   |  * Edge-case & worst-case scenario tests.
  5   |  * Validates real-world robustness: XSS, overflow, auth guards,
  6   |  * rapid interactions, empty states, and error recovery.
  7   |  */
  8   | 
  9   | // ─── Auth & Security ───────────────────────────────────────
  10  | 
  11  | test.describe('Auth edge cases', () => {
  12  |   test('login rejects XSS in email field gracefully', async ({ page }) => {
  13  |     await page.goto('/login');
  14  |     await page.fill('input[type="email"], input[name="email"]', '<script>alert(1)</script>');
  15  |     await page.fill('input[type="password"]', 'SomePassword123!');
  16  |     await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  17  |     // JS validation catches invalid email — shows error-message div and/or toast
  18  |     await expect(page.locator('.error-message, .Toastify__toast--error').first()).toBeVisible({ timeout: 5000 });
  19  |     // Verify no alert dialog was triggered (XSS didn't execute)
  20  |   });
  21  | 
  22  |   test('register rejects weak passwords with helpful message', async ({ page }) => {
  23  |     await page.goto('/register');
  24  |     const pwInput = page.locator('input[placeholder="Min 10 characters"]');
  25  |     await expect(pwInput).toBeVisible({ timeout: 5000 });
  26  |     await pwInput.fill('short');
  27  |     // Trigger blur by clicking elsewhere to show inline field error
  28  |     await pwInput.blur();
  29  |     await expect(page.locator('.field-error').first()).toBeVisible({ timeout: 5000 });
  30  |   });
  31  | 
  32  |   test('forgot password trims whitespace in email', async ({ page }) => {
  33  |     await page.goto('/forgot-password');
  34  |     await page.fill('input[type="email"], input[name="email"]', '  test@example.com  ');
  35  |     await page.getByRole('button', { name: /send|reset|submit/i }).click();
  36  |     // Should not crash — should show success or error from backend
  37  |     await expect(page.locator('.Toastify__toast, .success, .error').first()).toBeVisible({ timeout: 8000 });
  38  |   });
  39  | 
  40  |   test('expired token does not leave UI in broken state', async ({ page }) => {
  41  |     await page.goto('/');
  42  |     await page.evaluate(() => {
  43  |       localStorage.setItem('user', JSON.stringify({ id: 1, firstName: 'Test', role: 'CUSTOMER', active: true, phone: '9999999999' }));
  44  |       localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) - 3600)); // expired 1h ago
  45  |     });
  46  |     await page.goto('/dashboard');
  47  |     // Should redirect to login since token is expired
  48  |     await expect(page).toHaveURL(/login/, { timeout: 8000 });
  49  |   });
  50  | 
  51  |   test('manipulated role in localStorage cannot access admin', async ({ page }) => {
  52  |     await page.goto('/');
  53  |     await page.evaluate(() => {
  54  |       localStorage.setItem('user', JSON.stringify({ id: 1, firstName: 'Hacker', role: 'ADMIN', active: true, phone: '9999999999' }));
  55  |       localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
  56  |     });
  57  |     await page.goto('/admin/dashboard');
  58  |     // Server-validated auth should reject — either redirect or show error
  59  |     // Wait for either an error toast OR a redirect away from /admin/dashboard.
  60  |     const errorLocator = page.locator('.Toastify__toast--error').first();
  61  |     await Promise.race([
  62  |       errorLocator.waitFor({ state: 'visible', timeout: 8000 }).catch(() => null),
  63  |       page.waitForURL((url) => !url.pathname.includes('/admin/dashboard'), { timeout: 8000 }).catch(() => null),
  64  |     ]);
  65  |     const hasError = await page.locator('.Toastify__toast--error').count();
  66  |     const redirectedAway = !(await page.url()).includes('/admin/dashboard');
  67  |     expect(hasError > 0 || redirectedAway).toBeTruthy();
  68  |   });
  69  | });
  70  | 
  71  | // ─── Form Validation Edge Cases ─────────────────────────────
  72  | 
  73  | test.describe('Form validation edge cases', () => {
  74  |   test.beforeEach(async ({ page }) => {
  75  |     await page.goto('/');
  76  |     await page.evaluate(() => {
  77  |       localStorage.setItem('user', JSON.stringify({
  78  |         id: 1, firstName: 'Test', role: 'CUSTOMER', active: true, phone: '9999999999',
  79  |       }));
  80  |       localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
  81  |       localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Test Binge' }));
  82  |     });
  83  |   });
  84  | 
  85  |   test('booking wizard rejects without event type selection', async ({ page }) => {
  86  |     // Mock auth profile so fake user stays authenticated
  87  |     await page.route('**/api/v1/auth/profile', async (route) => {
  88  |       await route.fulfill({
  89  |         status: 200, contentType: 'application/json',
  90  |         body: JSON.stringify({ success: true, data: { id: 1, firstName: 'Test', role: 'CUSTOMER', active: true, phone: '9999999999' } }),
  91  |       });
  92  |     });
  93  |     // Mock event types so the wizard renders step 1
  94  |     await page.route('**/api/v1/event-types**', async (route) => {
  95  |       await route.fulfill({
  96  |         status: 200, contentType: 'application/json',
  97  |         body: JSON.stringify({ success: true, data: [{ id: 1, name: 'Birthday', description: 'Party', basePrice: 1500, active: true }] }),
  98  |       });
  99  |     });
  100 |     await page.goto('/book');
  101 |     const nextBtn = page.locator('button:has-text("Next")').first();
  102 |     await expect(nextBtn).toBeVisible({ timeout: 8000 });
> 103 |     await nextBtn.click();
      |                   ^ Error: locator.click: Test timeout of 30000ms exceeded.
  104 |     await expect(page.locator('.Toastify__toast--error').first()).toBeVisible({ timeout: 5000 });
  105 |   });
  106 | 
  107 |   test('account center handles empty form submission gracefully', async ({ page }) => {
  108 |     await page.goto('/account');
  109 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  110 |     // Page should load without crashing even if API calls fail
  111 |   });
  112 | });
  113 | 
  114 | // ─── Empty State Handling ───────────────────────────────────
  115 | 
  116 | test.describe('Empty states and zero-data scenarios', () => {
  117 |   test.beforeEach(async ({ page }) => {
  118 |     await page.goto('/');
  119 |     await page.evaluate(() => {
  120 |       localStorage.setItem('user', JSON.stringify({
  121 |         id: 999, firstName: 'New', role: 'CUSTOMER', active: true, phone: '9999999999',
  122 |       }));
  123 |       localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
  124 |       localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Test Binge' }));
  125 |     });
  126 |   });
  127 | 
  128 |   test('my-bookings shows meaningful empty state', async ({ page }) => {
  129 |     await page.goto('/my-bookings');
  130 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  131 |     // Should contain empty state messaging or booking list
  132 |     const bodyText = await page.locator('body').textContent();
  133 |     expect(bodyText).toBeTruthy();
  134 |   });
  135 | 
  136 |   test('payments page shows meaningful content or empty state', async ({ page }) => {
  137 |     await page.goto('/payments');
  138 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  139 |   });
  140 | 
  141 |   test('dashboard renders without crashing for new user', async ({ page }) => {
  142 |     await page.goto('/dashboard');
  143 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  144 |     // Should not have any unhandled JS errors
  145 |   });
  146 | });
  147 | 
  148 | // ─── Admin Empty States ─────────────────────────────────────
  149 | 
  150 | test.describe('Admin empty states', () => {
  151 |   test.beforeEach(async ({ page }) => {
  152 |     await page.goto('/');
  153 |     await page.evaluate(() => {
  154 |       localStorage.setItem('user', JSON.stringify({
  155 |         id: 99, firstName: 'Admin', role: 'ADMIN', active: true, phone: '9999999999',
  156 |       }));
  157 |       localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
  158 |       localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Test Binge' }));
  159 |     });
  160 |   });
  161 | 
  162 |   test('admin event types page shows empty state or data', async ({ page }) => {
  163 |     await page.goto('/admin/event-types');
  164 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  165 |   });
  166 | 
  167 |   test('admin blocked dates page renders both tabs', async ({ page }) => {
  168 |     await page.goto('/admin/blocked-dates');
  169 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  170 |     // Switch to slots tab
  171 |     const slotsTab = page.locator('button:has-text("Slot"), button:has-text("slot")').first();
  172 |     if (await slotsTab.isVisible()) {
  173 |       await slotsTab.click();
  174 |       // Wait for the tab's heading/panel to be attached instead of a fixed sleep.
  175 |       await page.waitForLoadState('networkidle');
  176 |       // Should not crash
  177 |       await expect(page.getByRole('heading').first()).toBeVisible();
  178 |     }
  179 |   });
  180 | 
  181 |   test('admin surge rules page loads cleanly', async ({ page }) => {
  182 |     await page.goto('/admin/surge-rules');
  183 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  184 |   });
  185 | 
  186 |   test('admin venue rooms page loads cleanly', async ({ page }) => {
  187 |     await page.goto('/admin/venue-rooms');
  188 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  189 |   });
  190 | 
  191 |   test('admin waitlist page loads cleanly', async ({ page }) => {
  192 |     await page.goto('/admin/waitlist');
  193 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  194 |   });
  195 | 
  196 |   test('admin users config page loads', async ({ page }) => {
  197 |     await page.goto('/admin/users-config');
  198 |     await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 8000 });
  199 |   });
  200 | });
  201 | 
  202 | // ─── Navigation Robustness ───────────────────────────────────
  203 | 
```