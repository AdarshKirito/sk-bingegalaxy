import React from 'react';
import ReactDOM from 'react-dom/client';
import { HelmetProvider } from 'react-helmet-async';
import * as Sentry from '@sentry/react';
import App from './App';
import './services/i18n';
import { initAnalytics } from './services/analytics';
import { captureAttribution } from './utils/attribution';
import './index.css';
// Shared styling for the .admin-*/.modal-*/.form-row/.row-actions/.icon-btn vocabulary used
// across admin pages (imported after index.css so it can build on the base tokens/resets).
import './styles/admin-system.css';

// ── Sentry error monitoring ──────────────────────────
if (import.meta.env.VITE_SENTRY_DSN) {
  Sentry.init({
    dsn: import.meta.env.VITE_SENTRY_DSN,
    environment: import.meta.env.MODE,
    integrations: [
      Sentry.browserTracingIntegration(),
      // SEC-012: privacy-safe Replay defaults. Booking/admin screens render
      // customer names, emails, phones and payment amounts — replays must
      // never carry readable PII to a third-party processor. Unmasking, if
      // ever needed, must be an explicit per-element allowlist
      // (sentry-unmask), reviewed by security — never a global opt-out.
      Sentry.replayIntegration({ maskAllText: true, blockAllMedia: true }),
    ],
    tracesSampleRate: import.meta.env.PROD ? 1.0 : 0.1,
    replaysSessionSampleRate: import.meta.env.PROD ? 0.1 : 0,
    replaysOnErrorSampleRate: 1.0,
  });
}

// ── Analytics ────────────────────────────────────────
initAnalytics();

// Marketing attribution (distribution design G-B). Must run BEFORE the router mounts:
// a Google Things to Do deep link carries its parameters only on the landing URL, and
// the first client-side navigation replaces them. Capturing here is the difference
// between the Google channel being measurable and being unprovable.
// Session-scoped and first-party, so no consent banner is required; it never affects
// price, availability or eligibility.
captureAttribution();

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <HelmetProvider>
      <App />
    </HelmetProvider>
  </React.StrictMode>
);
