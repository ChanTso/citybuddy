import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'CITYBUDDY_');

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/auth': env.CITYBUDDY_AUTH_TARGET ?? 'http://127.0.0.1:8081',
        '^/api/(products|seckill|reservations)':
          env.CITYBUDDY_COMMERCE_TARGET ?? 'http://127.0.0.1:8082',
        '^/api/(sessions|chat)':
          env.CITYBUDDY_AGENT_TARGET ?? 'http://127.0.0.1:8000',
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
    },
  };
});
