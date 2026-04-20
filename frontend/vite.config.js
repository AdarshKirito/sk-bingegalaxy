import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { sentryVitePlugin } from '@sentry/vite-plugin';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    // PWA with Workbox: generateSW mode auto-creates a service worker.
    // registerType: 'prompt' shows a user-facing notification when a new version is available,
    // instead of silently updating which could break a user mid-booking.
    VitePWA({
      registerType: 'prompt',
      includeAssets: ['favicon.ico', 'offline.html'],
      manifest: {
        name: 'SK Binge Galaxy',
        short_name: 'Binge Galaxy',
        description: 'Private theater booking and management platform',
        theme_color: '#6366f1',
        background_color: '#111827',
        display: 'standalone',
        start_url: '/',
        icons: [
          { src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' },
        ],
      },
      workbox: {
        // Precache all built assets
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
        // Runtime caching strategies
        runtimeCaching: [
          {
            // Read-only API data: network-first with short cache fallback.
            // NetworkFirst is safer than StaleWhileRevalidate for bookings — users see
            // the latest data when online, and cached data only when offline.
            urlPattern: /\/api\/v1\/(bookings|availability|event-types|add-ons|binges)/,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'api-cache',
              networkTimeoutSeconds: 5,
              expiration: { maxEntries: 100, maxAgeSeconds: 2 * 60 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
          {
            // Auth and mutation endpoints: always network-only
            urlPattern: /\/api\/v1\/(auth|payments)/,
            handler: 'NetworkOnly',
          },
          {
            // Google Fonts stylesheets
            urlPattern: /^https:\/\/fonts\.googleapis\.com/,
            handler: 'StaleWhileRevalidate',
            options: { cacheName: 'google-fonts-stylesheets' },
          },
          {
            // Google Fonts webfonts — immutable assets
            urlPattern: /^https:\/\/fonts\.gstatic\.com/,
            handler: 'CacheFirst',
            options: {
              cacheName: 'google-fonts-webfonts',
              expiration: { maxEntries: 20, maxAgeSeconds: 365 * 24 * 60 * 60 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
        // Offline fallback for navigations
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api/],
      },
    }),
    // Upload source maps to Sentry on production builds.
    // Requires SENTRY_AUTH_TOKEN, SENTRY_ORG, SENTRY_PROJECT env vars.
    process.env.SENTRY_AUTH_TOKEN
      ? sentryVitePlugin({
          org: process.env.SENTRY_ORG,
          project: process.env.SENTRY_PROJECT,
          authToken: process.env.SENTRY_AUTH_TOKEN,
          sourcemaps: { filesToDeleteAfterUpload: ['./dist/**/*.map'] },
        })
      : null,
  ].filter(Boolean),
  resolve: {
    dedupe: ['react', 'react-dom', 'react-router-dom'],
  },
  build: {
    sourcemap: 'hidden', // Sourcemaps for Sentry but not served publicly
  },
  server: {
    port: 3000,
    headers: {
      // Match nginx dev/prod behavior so Google popup auth can use postMessage
      // without the browser warning emitted by stricter COOP values.
      'Cross-Origin-Opener-Policy': 'unsafe-none',
    },
    proxy: {
      '/api/v1': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    css: true,
    maxWorkers: 2,
    minWorkers: 1,
    exclude: ['e2e/**', 'node_modules/**'],
  },
});
