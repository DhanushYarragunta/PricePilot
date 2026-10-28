import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Vite config.
 *
 * The proxy is the important part. In development the app runs on 5173 and Spring on 8080, and
 * routing /api through the dev server means the browser only ever talks to one origin — so the code
 * can use relative URLs like `/api/products` in development and in production without a single
 * conditional. Production serves the built files from the same nginx that proxies /api, which is the
 * same arrangement by different means.
 *
 * It also means CORS is not exercised in normal development. That is deliberate: CORS bugs found on
 * the day of deployment are expensive, so the app is built to never need it. `pricepilot.cors` in
 * application.yml still allows 5173 for anyone who bypasses this proxy.
 *
 * There is exactly one knob, and it only affects the dev server: where to find the backend. It
 * exists because running Spring in Docker while running Vite on the host is a normal thing to want,
 * and the port differs. Nothing in `src/` reads an environment variable — a build output that
 * behaves differently depending on which .env file was present when it was built is the single
 * most confusing failure mode a frontend can have.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.VITE_API_TARGET || 'http://localhost:8080';

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target,
          changeOrigin: true,
        },
        '/actuator': {
          target,
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
    },
  };
});
