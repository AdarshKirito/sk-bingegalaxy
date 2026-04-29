// Playwright validation for the dynamic Home CMS + payment search-by-event fix.
// - Stubs the public /site-content/public/home endpoint with a sample CMS doc
//   and verifies the hero carousel & gallery render on the public Home page.
// - Stubs bookings + payments and verifies typing an event name (e.g. "Surprise
//   Proposal") into the payments search surfaces matching bookings even when
//   no payment row matches by transactionId/bookingRef.
import { test, expect } from '@playwright/test';

const HOME_DOC = {
  hero: {
    kicker: 'Spring 2026 collection',
    headline: 'Cinematic ',
    headlineHighlight: 'celebrations',
    headlineSuffix: ' worth remembering',
    description: 'Curated rooms, hand-picked add-ons, and concierge support — all in one booking.',
    primaryCtaLabel: 'Browse rooms',
    primaryCtaHref: '/binges',
    secondaryCtaLabel: 'Talk to concierge',
    secondaryCtaHref: '#contact',
  },
  proofStrip: [
    { value: '500+', label: 'Celebrations hosted' },
    { value: '4.9★', label: 'Avg. guest rating' },
  ],
  marquee: ['Birthday Magic', 'Surprise Proposal', 'Anniversary'],
  gallery: [
    { url: 'https://images.unsplash.com/photo-1517457373958-b7bdd4587205?auto=format&w=900', caption: 'Premier theatre' },
    { url: 'https://images.unsplash.com/photo-1542204165-65bf26472b9b?auto=format&w=900', caption: 'Karaoke lounge' },
    { url: 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&w=900', caption: 'Anniversary suite' },
    { url: 'https://images.unsplash.com/photo-1492684223066-81342ee5ff30?auto=format&w=900', caption: 'Birthday hall' },
  ],
  features: { kicker: 'Why us', title: 'Built for moments', description: 'Premium experiences, end-to-end.', items: [
    { title: 'Cinematic rooms', description: 'Floor-to-ceiling screens.', icon: 'film' },
    { title: 'Trusted teams', description: 'Background-verified hosts.', icon: 'shield' },
  ]},
  signature: { kicker: 'Signature', title: 'Moments we craft', items: [
    { eyebrow: 'Cinema', title: 'Surprise Proposal', description: 'A private theatre, your story.', accent: 'Most loved' },
  ]},
  process: { kicker: 'Process', title: 'How booking works', items: [
    { number: '01', title: 'Pick a room', description: 'Browse availability.' },
    { number: '02', title: 'Customise', description: 'Add cake, decor, more.' },
    { number: '03', title: 'Celebrate', description: 'We handle the rest.' },
  ]},
  packages: { kicker: 'Packages', title: 'Indicative pricing', description: 'Mix and match.', items: [
    { name: 'Cinema for two', price: '₹1,499', note: '60-min slot', icon: 'film' },
  ]},
  finalCta: { kicker: 'Ready?', title: 'Book your moment', description: 'Concierge available 9am–11pm.' },
};

async function stubHome(page) {
  await page.route('**/api/v1/site-content/public/home', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: { slug: 'home', contentJson: JSON.stringify(HOME_DOC), updatedAt: new Date().toISOString() },
      }),
    }),
  );
}

test.describe('Public Home — dynamic CMS', () => {
  test('renders hero carousel + gallery from CMS document', async ({ page }) => {
    await stubHome(page);
    await page.goto('/');

    await expect(page.locator('.home-carousel').first()).toBeVisible({ timeout: 10000 });
    // At least 2 slides are present (rendered side-by-side, only one visible at a time).
    const slides = page.locator('.home-carousel-slide');
    await expect(slides.first()).toBeVisible();
    expect(await slides.count()).toBeGreaterThanOrEqual(2);

    // Gallery section renders all CMS images.
    const galleryImgs = page.locator('.home-banner-slide img');
    await expect(galleryImgs.first()).toBeVisible({ timeout: 8000 });
    expect(await galleryImgs.count()).toBeGreaterThanOrEqual(1);

    // Headline reflects CMS content.
    await expect(page.locator('h1, .home-headline').first()).toContainText(/celebrations/i);

    await page.screenshot({ path: 'test-results/home-cms-default.png', fullPage: true });
  });
});

const BOOKINGS_WITH_EVENT = [
  {
    bookingRef: 'BK-EVT-001',
    eventType: { name: 'Surprise Proposal', description: 'Elegant proposal setup with premium decorations' },
    bookingDate: '2026-05-12',
    startTime: '19:00',
    durationMinutes: 90,
    numberOfGuests: 2,
    totalAmount: 2999,
    status: 'CONFIRMED',
    paymentStatus: 'PENDING',
    venueRoomName: 'Aurora',
    paymentMethod: 'UPI',
  },
];

test.describe('Customer Payments — search by event name', () => {
  test('typing an event name surfaces matching bookings', async ({ page }) => {
    // Register catch-all FIRST so it has the lowest priority — Playwright
    // resolves routes in reverse registration order (last registered wins).
    await page.route('**/api/v1/**', (route) => {
      if (route.request().resourceType() === 'xhr' || route.request().resourceType() === 'fetch') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [] }) });
      }
      return route.continue();
    });
    await page.route('**/api/v1/auth/profile', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        success: true, data: { id: 1, firstName: 'Demo', lastName: 'User', email: 'demo@example.com', role: 'CUSTOMER', active: true, phone: '9999999999' },
      })}),
    );
    await page.route('**/api/v1/bookings/my/current**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: BOOKINGS_WITH_EVENT }) }),
    );
    await page.route('**/api/v1/bookings/my/past**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [] }) }),
    );
    await page.route('**/api/v1/payments/my**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [] }) }),
    );

    await page.goto('/');
    await page.evaluate(() => {
      localStorage.setItem('user', JSON.stringify({ id: 1, firstName: 'Demo', lastName: 'User', email: 'demo@example.com', role: 'CUSTOMER', active: true, phone: '9999999999' }));
      localStorage.setItem('token_exp', String(Math.floor(Date.now() / 1000) + 3600));
      localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Demo Binge', address: '123 Demo Street', timezone: 'Asia/Kolkata' }));
    });

    await page.goto('/payments');
    const toolbar = page.getByTestId('customer-payments-toolbar');
    await expect(toolbar).toBeVisible({ timeout: 10000 });
    await toolbar.getByPlaceholder(/search booking ref/i).fill('Surprise Proposal');

    // The page should now show the matched booking with a Pay Now CTA
    // (paymentStatus is PENDING) instead of a flat "no results" message.
    await expect(page.getByText(/Surprise Proposal/i).first()).toBeVisible({ timeout: 6000 });
    await expect(page.getByRole('button', { name: /pay now/i }).or(page.getByRole('link', { name: /pay now/i })).first()).toBeVisible({ timeout: 4000 });

    await page.screenshot({ path: 'test-results/payments-search-by-event.png', fullPage: true });
  });
});
