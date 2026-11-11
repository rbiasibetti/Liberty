#!/usr/bin/env bash
# MVP local para testar à mão, sem Docker: API real + interface, com OIDC e Vault falsos, um banco
# central e um banco de cliente descartáveis (somem ao parar) com dados de exemplo.
# Exige .env.local (SQL Server local), Node 22+ no PATH e Java 25.
# Uso: scripts/demo.sh [minutos de vida, padrão 120]   (Ctrl+C para parar)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
minutes="${1:-120}"
log="$(mktemp -t liberty-demo-XXXX.log)"
set -a; . "$root/.env.local"; set +a

pids=()
cleanup() {
  touch "$root/backend/liberty-app/target/e2e.stop" 2>/dev/null || true
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

rm -f "$root/backend/liberty-app/target/e2e.stop"
echo "==> Subindo a API (log em $log)"
(cd "$root/backend" && MAVEN_OPTS="" ./mvnw -ntp -pl liberty-app -am verify -Dliberty.e2e=true \
  -Dliberty.e2e.minutes="$minutes" -Dliberty.e2e.sample-data=true -Dtest=E2eStackRunner \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true -Djacoco.skip=true \
  -Dspotless.check.skip=true > "$log" 2>&1) &
pids+=($!)
for _ in $(seq 1 120); do
  curl -sf http://127.0.0.1:18081/__ready >/dev/null 2>&1 && break
  sleep 2
done
curl -sf http://127.0.0.1:18081/__ready >/dev/null || { echo "A API não subiu; veja $log" >&2; exit 1; }

echo "==> Subindo a interface"
(cd "$root/frontend" && LIBERTY_API_PROXY=http://127.0.0.1:18080 \
  VITE_OIDC_AUTHORITY=http://127.0.0.1:18081/realms/liberty npx vite --strictPort) >> "$log" 2>&1 &
pids+=($!)
sleep 4

cat <<TXT

Pronto. Abra http://127.0.0.1:5173 e clique em Entrar.

Quem é você é escolhido ANTES de clicar em Entrar, abrindo uma destas URLs:
  integrador  http://127.0.0.1:18081/__user?sub=integrador   cria, mapeia, testa no sandbox, envia para revisão
  aprovador   http://127.0.0.1:18081/__user?sub=aprovador    revisa, publica em HML/PRD, faz rollback
  admin       http://127.0.0.1:18081/__user?sub=admin        administra o tenant (conexões, clientes, usuários)
Para trocar de usuário: abra a URL do outro e use "Sair" e "Entrar" de novo.

Tenant "acme", conexões HML e PRD no mesmo banco de exemplo:
  dbo.tb_pedido (id, cliente, total)  e  dbo.tb_pedido_item (id, pedido_id, sku, quantidade), com dados.
API de execução: obtenha um cliente em Administração > Clientes e chame http://127.0.0.1:18080/acme/api/...

Vale por $minutes minutos; os dados somem ao parar (Ctrl+C).
TXT
wait "${pids[0]}"
