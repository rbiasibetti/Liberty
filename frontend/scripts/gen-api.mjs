// Gera os tipos do cliente a partir da especificação OpenAPI da API de administração.
import { execFileSync } from 'node:child_process';

execFileSync(
  process.execPath,
  [
    './node_modules/openapi-typescript/bin/cli.js',
    '../docs/openapi/admin-api.yaml',
    '-o',
    'src/api/schema.d.ts',
  ],
  { stdio: 'inherit' },
);
