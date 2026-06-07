import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

const projectRoot = path.dirname(fileURLToPath(import.meta.url));
const localTempDir = path.join(projectRoot, '.tmp');

fs.mkdirSync(localTempDir, { recursive: true });

for (const key of ['TMPDIR', 'TMP', 'TEMP']) {
  process.env[key] = localTempDir;
}

export default defineConfig(async () => {
  const [{ default: react }, { sentryVitePlugin }, { VitePWA }] = await Promise.all([
    import('@vitejs/plugin-react'),
    import('@sentry/vite-plugin'),
    import('vite-plugin-pwa'),
  ]);

  return {
    root: projectRoot,
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
          // The country-state-city dataset bundled into the address picker pushes a
          // single chunk past the default 2 MiB Workbox limit. Bumping to 12 MiB
          // keeps offline support intact without splitting hot user-facing code.
          maximumFileSizeToCacheInBytes: 12 * 1024 * 1024,
          // Runtime caching strategies
          runtimeCaching: [
            {
              // Read-only API data: network-first with short cache fallback.
              // NetworkFirst is safer than StaleWhileRevalidate for bookings - users see
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
            // Google Fonts are intentionally NOT cached by the service worker.
            // When Workbox intercepts a fonts.googleapis.com or fonts.gstatic.com
            // request and calls fetch() internally, the browser classifies that as
            // connect-src (a JS fetch), NOT style-src / font-src. This causes a CSP
            // violation on any policy that allows fonts under style-src / font-src
            // but not under connect-src (which is the correct posture — connect-src
            // should not grant broad external access just to satisfy a caching layer).
            // Google Fonts ship with max-age=31536000 / immutable cache-control
            // headers — the browser's native HTTP cache handles them without SW help.
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
        // Mirror production nginx CSP exactly so font/script violations surface
        // in development rather than only after deployment.
        // connect-src adds ws://localhost:* for Vite HMR WebSocket.
        'Content-Security-Policy': [
          "default-src 'self'",
          "script-src 'self' https://accounts.google.com https://apis.google.com",
          "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://accounts.google.com",
          "font-src 'self' https://fonts.gstatic.com",
          "img-src 'self' data: https:",
          "frame-src https://accounts.google.com",
          // font CDN excluded from connect-src — Google Fonts are loaded via
          // <link> / font-src, not JS fetch(); see workbox runtimeCaching comment.
          "connect-src 'self' ws://localhost:* wss://localhost:* https://accounts.google.com",
          "frame-ancestors 'self'",
        ].join('; '),
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
  };
});
