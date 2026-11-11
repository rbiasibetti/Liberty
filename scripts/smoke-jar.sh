#!/usr/bin/env bash
# Fumaça do artefato de produção: sobe o jar executável (o mesmo da imagem) com a pasta worker-lib
# como classpath do trabalhador de script e executa um script de verdade no sandbox.
# Usa a pilha de testes (banco, OIDC e Vault falsos) só como infraestrutura; a API sob teste é o jar.
# Exige .env.local (SQL Server local) e Java 25.
# SMOKE_WORKER_CLASSPATH= (vazio) reproduz o defeito original: sem a pasta, o jar não executa scripts.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
log="$(mktemp -t liberty-smoke-XXXX.log)"
set -a; . "$root/.env.local"; set +a

pids=()
cleanup() {
  touch "$root/backend/liberty-app/target/e2e.stop" 2>/dev/null || true
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
}
trap cleanup EXIT

echo "==> Empacotando (jar executável e worker-lib)"
(cd "$root/backend" && ./mvnw -ntp -q package -DskipTests -Dcheckstyle.skip=true -Djacoco.skip=true -Dspotless.check.skip=true)
jar="$root/backend/liberty-app/target/liberty-app.jar"
lib="$root/backend/liberty-scripts/target/worker-lib"
[ -f "$jar" ] && [ -d "$lib" ] || { echo "Faltam $jar ou $lib" >&2; exit 1; }

echo "==> Subindo a infraestrutura de teste (log em $log)"
rm -f "$root/backend/liberty-app/target/e2e.stop" "$root/backend/liberty-app/target/e2e-info.properties"
(cd "$root/backend" && ./mvnw -ntp -pl liberty-app -am verify -Dliberty.e2e=true -Dtest=E2eStackRunner \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true -Djacoco.skip=true -Dspotless.check.skip=true \
  > "$log" 2>&1) &
pids+=($!)
for _ in $(seq 1 120); do
  curl -sf http://127.0.0.1:18081/__ready >/dev/null 2>&1 && break
  sleep 2
done
curl -sf http://127.0.0.1:18081/__ready >/dev/null || { echo "A infraestrutura não subiu; veja $log" >&2; exit 1; }
core_url="$(sed -n 's/^coreUrl=//p' "$root/backend/liberty-app/target/e2e-info.properties")"
vault_uri="$(sed -n 's/^vaultUri=//p' "$root/backend/liberty-app/target/e2e-info.properties")"

echo "==> Subindo o jar (porta 18090)"
env -i PATH="$PATH" HOME="$HOME" \
  JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
  SERVER_PORT=18090 \
  LIBERTY_CORE_URL="$core_url" LIBERTY_CORE_APP_USER="$LIBERTY_TEST_DB_USER" LIBERTY_CORE_APP_PASSWORD="$LIBERTY_TEST_DB_PASSWORD" \
  LIBERTY_VAULT_URI="$vault_uri" LIBERTY_VAULT_TOKEN=teste \
  LIBERTY_SECURITY_ISSUER=http://jar.smoke LIBERTY_OIDC_ISSUER=http://127.0.0.1:18081/realms/liberty \
  LIBERTY_SECURITY_KEYS_STORE=MEMORY LIBERTY_METRICS_PASSWORD=fumaca \
  LIBERTY_SCRIPTS_WORKER_CLASSPATH="${SMOKE_WORKER_CLASSPATH-$lib/*}" \
  java -jar "$jar" >> "$log" 2>&1 &
pids+=($!)
for _ in $(seq 1 90); do
  curl -sf http://127.0.0.1:18090/actuator/health/readiness >/dev/null 2>&1 && break
  sleep 2
done
curl -sf http://127.0.0.1:18090/actuator/health/readiness >/dev/null || { echo "O jar não ficou pronto; veja $log" >&2; exit 1; }

echo "==> Executando um script no sandbox do jar"
token="$(curl -sf "http://127.0.0.1:18081/__token?sub=integrador" | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')"
api="http://127.0.0.1:18090/api/v1/tenants/acme"
call() { curl -s -H "Authorization: Bearer $token" -H "Content-Type: application/json" "$@"; }
call -X POST "$api/integrations" -d '{"key":"fumaca","name":"Fumaça","method":"POST","route":"/fumaca"}' >/dev/null
call -X PUT "$api/integrations/fumaca/draft" -d '{
  "schemaVersion":1,
  "integration":{"key":"fumaca","name":"Fumaça","method":"POST","route":"/fumaca"},
  "request":{"params":[],"bodySchema":{"type":"object"}},
  "scripts":{
    "beforeExecute":"function beforeExecute(i){ const b = i.body; b.cliente = String(b.cliente).toUpperCase(); return b; }",
    "afterExecute":"function afterExecute(i){ const r = http.fetch(`https://api.exemplo.com/x`); return Object.assign({}, i.result, { fumaca: true, simulado: r.simulated }); }"
  },
  "operation":{"type":"INSERT","write":{"root":{"table":"dbo.tb_pedido","fields":[{"column":"cliente","source":"/cliente"},{"column":"total","source":"/total"}]}}}
}' >/dev/null
admin_token="$(curl -sf "http://127.0.0.1:18081/__token?sub=admin" | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')"
curl -sf -X PUT -H "Authorization: Bearer $admin_token" \
  "http://127.0.0.1:18090/api/v1/platform/tenants/acme/http-hosts/api.exemplo.com" >/dev/null
result="$(call -X POST "$api/integrations/fumaca/versions/1/sandbox" -d '{"body":{"cliente":"ana","total":10}}')"
echo "$result" | head -c 600; echo
echo "$result" | python3 -c '
import json,sys
r=json.load(sys.stdin)
assert r.get("succeeded") is True, "o sandbox não teve sucesso"
assert r["body"].get("fumaca") is True and r["body"].get("simulado") is True, "o afterExecute não rodou como esperado"
assert r.get("rolledBack") is True
print("OK: o jar executou beforeExecute e afterExecute (com http.fetch simulado) no trabalhador de script")
'
