#!/usr/bin/env bash
# Sobe o ambiente local completo: infraestrutura (compose), bancos de exemplo, segredos no Vault e usuário no Keycloak.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$root/.env.local"
compose_file="$root/deploy/local/compose.yaml"
tools_dir="$root/.local"

[ -f "$env_file" ] || install -m 600 /dev/null "$env_file"

ensure_var() { # NOME [valor-padrão]: gera um segredo aleatório se a variável ainda não existir
  grep -q "^$1=" "$env_file" || printf '%s=%s\n' "$1" "${2:-$(openssl rand -hex 16)}" >> "$env_file"
}
ensure_var LIBERTY_TEST_DB_HOST 127.0.0.1
ensure_var LIBERTY_TEST_DB_PORT 1433
ensure_var LIBERTY_TEST_DB_USER sa
for v in LIBERTY_REDIS_PASSWORD LIBERTY_VAULT_TOKEN LIBERTY_KEYCLOAK_ADMIN_PASSWORD LIBERTY_GRAFANA_ADMIN_PASSWORD \
         LIBERTY_LOCAL_USER_PASSWORD LIBERTY_CORE_MIGRATOR_PASSWORD LIBERTY_CORE_APP_PASSWORD \
         LIBERTY_TENANT_A_DB_PASSWORD LIBERTY_TENANT_B_DB_PASSWORD; do
  ensure_var "$v"
done
set -a; . "$env_file"; set +a
[ -n "${LIBERTY_TEST_DB_PASSWORD:-}" ] || { echo "Defina LIBERTY_TEST_DB_PASSWORD (senha do 'sa') em .env.local" >&2; exit 1; }

# docker compose, docker-compose ou podman (via socket compatível)
if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then compose=(docker compose)
elif command -v docker-compose >/dev/null; then
  compose=(docker-compose)
  if [ -z "${DOCKER_HOST:-}" ] && ! command -v docker >/dev/null && command -v podman >/dev/null; then
    export DOCKER_HOST="unix://${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
    [ -S "${DOCKER_HOST#unix://}" ] || systemctl --user start podman.socket
  fi
else echo "Instale docker compose ou podman com docker-compose." >&2; exit 1; fi
compose+=(--env-file "$env_file" -f "$compose_file")

mssql_open() { (exec 3<>"/dev/tcp/$LIBERTY_TEST_DB_HOST/$LIBERTY_TEST_DB_PORT") 2>/dev/null; }
if [ "${LIBERTY_MSSQL_EXTERNAL:-}" = "true" ] || mssql_open; then
  echo "==> Usando o SQL Server já disponível em $LIBERTY_TEST_DB_HOST:$LIBERTY_TEST_DB_PORT"
else
  echo "==> Nenhum SQL Server local: subindo o container (perfil mssql)"
  compose+=(--profile mssql)
fi

echo "==> Subindo a infraestrutura"
"${compose[@]}" up -d --wait --wait-timeout 600

"$root/scripts/seed-sql.sh"

echo "==> Gravando as credenciais dos tenants no Vault"
for t in a b; do
  pw_var="LIBERTY_TENANT_${t^^}_DB_PASSWORD"
  curl -fsS -X POST -H "X-Vault-Token: $LIBERTY_VAULT_TOKEN" \
    -d "{\"data\":{\"jdbcUrl\":\"jdbc:sqlserver://$LIBERTY_TEST_DB_HOST:$LIBERTY_TEST_DB_PORT;databaseName=tenant_cliente_$t;encrypt=true;trustServerCertificate=true\",\"username\":\"liberty_app_cliente_$t\",\"password\":\"${!pw_var}\"}}" \
    "http://127.0.0.1:8200/v1/secret/data/liberty/tenants/cliente-$t/hml" >/dev/null
done

echo "==> Criando o usuário 'integrador' no Keycloak"
kc="http://127.0.0.1:8081"
token="$(curl -fsS -d client_id=admin-cli -d username=admin --data-urlencode "password=$LIBERTY_KEYCLOAK_ADMIN_PASSWORD" \
  -d grant_type=password "$kc/realms/master/protocol/openid-connect/token" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')"
auth=(-H "Authorization: Bearer $token")
if [ "$(curl -fsS "${auth[@]}" "$kc/admin/realms/liberty/users?username=integrador&exact=true" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')" = 0 ]; then
  curl -fsS "${auth[@]}" -H 'Content-Type: application/json' -X POST "$kc/admin/realms/liberty/users" \
    -d "{\"username\":\"integrador\",\"email\":\"integrador@liberty.local\",\"emailVerified\":true,\"enabled\":true,\"credentials\":[{\"type\":\"password\",\"value\":\"$LIBERTY_LOCAL_USER_PASSWORD\",\"temporary\":false}]}"
fi

cat <<MSG

Ambiente pronto (senhas em .env.local):
  Keycloak   http://127.0.0.1:8081   realm liberty, usuário integrador
  Vault      http://127.0.0.1:8200
  Redis      127.0.0.1:6379
  Prometheus http://127.0.0.1:9090
  Grafana    http://127.0.0.1:3000   usuário admin
  Jaeger     http://127.0.0.1:16686  (OTLP em 4317/4318)
  SQL Server $LIBERTY_TEST_DB_HOST:$LIBERTY_TEST_DB_PORT  bancos liberty_core, tenant_cliente_a, tenant_cliente_b
MSG
