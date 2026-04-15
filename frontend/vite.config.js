import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
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
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    css: true,
  },
});
