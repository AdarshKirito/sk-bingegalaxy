/**
 * Lightweight analytics abstraction.
 * Supports Google Analytics 4 (gtag.js) out of the box.
 * Set VITE_GA_MEASUREMENT_ID in your env to enable.
 *
 * Add other providers (Segment, Mixpanel) by extending the
 * `track` and `page` functions below.
 */

const GA_ID = import.meta.env.VITE_GA_MEASUREMENT_ID;
let initialized = false;

/** Load GA4 gtag.js snippet dynamically */
export function initAnalytics() {
  if (initialized || !GA_ID) return;
  initialized = true;

  const script = document.createElement('script');
  script.async = true;
  script.src = `https://www.googletagmanager.com/gtag/js?id=${encodeURIComponent(GA_ID)}`;
  document.head.appendChild(script);

  window.dataLayer = window.dataLayer || [];
  window.gtag = function () { window.dataLayer.push(arguments); };
  window.gtag('js', new Date());
  window.gtag('config', GA_ID, { send_page_view: false }); // manual page views
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
