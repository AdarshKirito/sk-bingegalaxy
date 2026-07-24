import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

const projectRoot = path.dirname(fileURLToPath(import.meta.url));

function prepareWritableTempDir(primaryDir, fallbackDir) {
  for (const dir of [primaryDir, fallbackDir]) {
    try {
      fs.mkdirSync(dir, { recursive: true });
      const probeDir = fs.mkdtempSync(path.join(dir, 'probe-'));
      fs.rmSync(probeDir, { recursive: true, force: true });
      return dir;
    } catch (error) {
      if (dir === fallbackDir) throw error;
    }
  }
}

const localTempDir = prepareWritableTempDir(
  path.join(projectRoot, '.tmp'),
  path.join(projectRoot, '.vite-tmp')
);

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
      // registerType: 'autoUpdate' — a freshly deployed build is picked up and activated
      // automatically on the next load (skipWaiting + clientsClaim), so users are never
      // stranded on a stale cached bundle after a release. The previous 'prompt' mode
      // required clicking an easily-missed banner, which repeatedly left clients running
      // outdated code. PWAUpdatePrompt still applies the update the moment it is detected.
      VitePWA({
        registerType: 'autoUpdate',
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
          // Layer our Web Push (push + notificationclick) handlers into the generated
          // service worker. importScripts keeps Workbox in charge of the SW lifecycle
          // while adding push display/click behaviour from a small standalone file.
          // push-sw.js also deletes the legacy 'api-cache' on activate (SEC-009).
          importScripts: ['push-sw.js'],
          // Precache all built assets
          globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
          // The country-state-city dataset is split into its own 'address-data'
          // chunk (see build.rollupOptions) and EXCLUDED from precache — an
          // ~8.7 MB download/storage tax on every install/update is not
          // acceptable for a dataset only the address editor needs (PERF-002).
          // It loads on demand and rides the normal HTTP cache.
          globIgnores: ['**/address-data-*.js'],
          maximumFileSizeToCacheInBytes: 3 * 1024 * 1024,
          // ── SEC-009: NO service-worker caching for ANY API response ─────
          // Identity and tenant (X-Binge-Id) live in headers/cookies, but the
          // Workbox cache is keyed by URL alone — a cached response can be
          // replayed across a user or binge switch on a shared device. The old
          // broad NetworkFirst 'api-cache' rule did exactly that for bookings/
          // admin reads. Until an explicit allowlist of truly public, identity-
          // free endpoints is security-reviewed, every /api request bypasses
          // Cache Storage entirely. Offline UX degrades gracefully: the app
          // shell (precache) still loads and pages show their offline states.
          runtimeCaching: [
            {
              urlPattern: /\/api\//,
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
      rollupOptions: {
        output: {
          // Isolate the huge country-state-city dataset into a stable-named
          // chunk so Workbox can exclude it from precache (PERF-002).
          manualChunks(id) {
            if (id.includes('country-state-city')) return 'address-data';
            return undefined;
          },
        },
      },
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
