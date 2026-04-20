/**
 * Analytics abstraction with funnel tracking and Web Vitals.
 * Supports Google Analytics 4 (gtag.js) out of the box.
 * Set VITE_GA_MEASUREMENT_ID in your env to enable.
 */

const GA_ID = import.meta.env.VITE_GA_MEASUREMENT_ID;
let initialized = false;

/** Load GA4 gtag.js snippet dynamically */
export function initAnalytics() {
  if (initialized) return;
  if (!GA_ID) {
    if (import.meta.env.DEV) {
      console.warn('[analytics] VITE_GA_MEASUREMENT_ID not set — analytics events will be no-ops in dev');
    }
    // Create a no-op gtag so funnel calls don't need null checks, but events are silently dropped.
    window.gtag = function () {};
    initialized = true;
    return;
  }
  initialized = true;

  const script = document.createElement('script');
  script.async = true;
  script.src = `https://www.googletagmanager.com/gtag/js?id=${encodeURIComponent(GA_ID)}`;
  document.head.appendChild(script);

  window.dataLayer = window.dataLayer || [];
  window.gtag = function () { window.dataLayer.push(arguments); };
  window.gtag('js', new Date());
  window.gtag('config', GA_ID, { send_page_view: false }); // manual page views

  // Report Core Web Vitals to GA4
  reportWebVitals();
}

/** Track a page view */
export function trackPageView(path, title) {
  if (window.gtag) {
    window.gtag('event', 'page_view', {
      page_path: path,
      page_title: title || document.title,
    });
  }
}

/**
 * Track a custom event.
 * @param {string} eventName  e.g. 'booking_created', 'login_success'
 * @param {object} [params]   Additional event parameters
 */
export function trackEvent(eventName, params = {}) {
  if (window.gtag) {
    window.gtag('event', eventName, params);
  }
}

/** Identify a user (set user properties) */
export function identifyUser(userId, traits = {}) {
  if (window.gtag) {
    window.gtag('set', 'user_properties', { user_id: userId, ...traits });
  }
}

/* ── Booking Funnel Events ─────────────────────────────── */

/** Customer started the booking wizard */
export function trackBookingStarted(eventTypeName) {
  trackEvent('booking_started', { event_type: eventTypeName });
}

/** Customer completed a specific wizard step */
export function trackBookingStepCompleted(step, stepName) {
  trackEvent('booking_step_completed', { step_number: step, step_name: stepName });
}

/** Customer submitted the booking */
export function trackBookingCompleted(bookingRef, totalAmount) {
  trackEvent('booking_completed', { booking_ref: bookingRef, value: totalAmount, currency: 'INR' });
}

/** Customer reached the payment page */
export function trackPaymentStarted(bookingRef, amount) {
  trackEvent('payment_started', { booking_ref: bookingRef, value: amount, currency: 'INR' });
}

/** Payment succeeded */
export function trackPaymentCompleted(bookingRef, amount, method) {
  trackEvent('payment_completed', { booking_ref: bookingRef, value: amount, currency: 'INR', payment_method: method });
}

/** Payment failed */
export function trackPaymentFailed(bookingRef, reason) {
  trackEvent('payment_failed', { booking_ref: bookingRef, failure_reason: reason });
}

/** User registered */
export function trackSignUp(method) {
  trackEvent('sign_up', { method: method || 'email' });
}

/** User logged in */
export function trackLogin(method) {
  trackEvent('login', { method: method || 'email' });
}

/* ── Web Vitals ─────────────────────────────────────────── */

function reportWebVitals() {
  // Use the PerformanceObserver API to collect CLS, FID, LCP, FCP, TTFB
  // without requiring the web-vitals library as a dependency.
  if (typeof PerformanceObserver === 'undefined') return;

  // Largest Contentful Paint
  try {
    new PerformanceObserver((list) => {
      const entries = list.getEntries();
      const last = entries[entries.length - 1];
      if (last) sendVital('LCP', last.startTime);
    }).observe({ type: 'largest-contentful-paint', buffered: true });
  } catch { /* unsupported */ }

  // First Input Delay
  try {
    new PerformanceObserver((list) => {
      const entry = list.getEntries()[0];
      if (entry) sendVital('FID', entry.processingStart - entry.startTime);
    }).observe({ type: 'first-input', buffered: true });
  } catch { /* unsupported */ }

  // Cumulative Layout Shift
  try {
    let cls = 0;
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        if (!entry.hadRecentInput) cls += entry.value;
      }
      sendVital('CLS', cls);
    }).observe({ type: 'layout-shift', buffered: true });
  } catch { /* unsupported */ }

  // Navigation timing (TTFB, FCP)
  try {
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        if (entry.name === 'first-contentful-paint') {
          sendVital('FCP', entry.startTime);
        }
      }
    }).observe({ type: 'paint', buffered: true });
  } catch { /* unsupported */ }

  // TTFB from navigation timing
  try {
    const nav = performance.getEntriesByType('navigation')[0];
    if (nav) sendVital('TTFB', nav.responseStart - nav.requestStart);
  } catch { /* unsupported */ }
}

function sendVital(name, value) {
  trackEvent('web_vital', {
    metric_name: name,
    metric_value: Math.round(name === 'CLS' ? value * 1000 : value),
    metric_unit: name === 'CLS' ? 'milliunits' : 'ms',
  });
}
