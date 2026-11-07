#!/usr/bin/env bash
# Teste de ponta a ponta: sobe a API (banco central e de cliente descartáveis, OIDC e Vault falsos),
# roda o Playwright no Chrome instalado e derruba tudo. Exige .env.local (SQL Server de teste), o
# Chrome em /usr/bin/google-chrome e Node 22+.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
log="$(mktemp -t liberty-e2e-XXXX.log)"
set -a; . "$root/.env.local"; set +a

stop_stack() { touch "$root/backend/liberty-app/target/e2e.stop" 2>/dev/null || true; }
trap stop_stack EXIT

echo "==> Subindo a API de teste (log em $log)"
rm -f "$root/backend/liberty-app/target/e2e.stop"
(cd "$root/backend" && ./mvnw -ntp -pl liberty-app -am verify -Dliberty.e2e=true -Dtest=E2eStackRunner \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true -Djacoco.skip=true -Dspotless.check.skip=true \
  > "$log" 2>&1) &
for _ in $(seq 1 90); do
  curl -sf http://127.0.0.1:18081/__ready >/dev/null 2>&1 && break
  sleep 2
done
curl -sf http://127.0.0.1:18081/__ready >/dev/null || { echo "A API de teste não subiu; veja $log" >&2; exit 1; }

echo "==> Rodando o Playwright"
cd "$root/frontend"
npx playwright test "$@"
