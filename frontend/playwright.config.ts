import { defineConfig } from '@playwright/test';

/**
 * E2E contra a pilha local: a API roda pelo E2eStackRunner (scripts/e2e.sh) em 18080, com OIDC e
 * Vault falsos em 18081. O navegador é o Chrome instalado: nenhum download de navegador.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:5173',
    channel: 'chrome',
    actionTimeout: 15_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npx vite --strictPort',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    env: {
      LIBERTY_API_PROXY: 'http://127.0.0.1:18080',
      VITE_OIDC_AUTHORITY: 'http://127.0.0.1:18081/realms/liberty',
    },
  },
});
