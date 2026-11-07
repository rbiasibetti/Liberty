/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  build: { sourcemap: false },
  server: {
    host: '127.0.0.1',
    port: 5173,
    // em desenvolvimento a API responde na mesma origem, sem CORS
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/main.tsx',
        'src/api/schema.d.ts',
        'src/test/**',
        // o Monaco só roda num navegador de verdade: nos testes ele é substituído por um campo de texto
        'src/editor/**',
        'src/**/*.test.{ts,tsx}',
      ],
      thresholds: { lines: 80, functions: 80, branches: 80, statements: 80 },
    },
  },
});
