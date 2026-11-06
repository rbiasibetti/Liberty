#!/usr/bin/env bash
# Cria (idempotente) os bancos, usuários e o schema de exemplo no SQL Server de desenvolvimento.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tools_dir="$root/.local"
set -a; . "$root/.env.local"; set +a
for v in LIBERTY_CORE_MIGRATOR_PASSWORD LIBERTY_CORE_APP_PASSWORD LIBERTY_TENANT_A_DB_PASSWORD LIBERTY_TENANT_B_DB_PASSWORD; do
  [ -n "${!v:-}" ] || { echo "Falta $v em .env.local (rode scripts/local-up.sh uma vez para gerar)" >&2; exit 1; }
done

echo "==> Preparando o driver JDBC para os scripts SQL"
jar="$tools_dir/mssql-jdbc.jar"
if [ ! -f "$jar" ]; then
  mkdir -p "$tools_dir"
  (cd "$root/backend" && ./mvnw -q -ntp dependency:copy \
    -Dartifact=com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11 -DoutputDirectory="$tools_dir" -Dmdep.stripVersion=true \
    && mv "$tools_dir/mssql-jdbc.jar" "$jar" 2>/dev/null || true)
fi
[ -f "$jar" ] || { echo "Falha ao obter o mssql-jdbc" >&2; exit 1; }

run_sql() { # banco arquivo
  SQL_PASSWORD="$LIBERTY_TEST_DB_PASSWORD" java -cp "$jar" "$root/scripts/Sql.java" \
    "$LIBERTY_TEST_DB_HOST" "$LIBERTY_TEST_DB_PORT" "$1" "$LIBERTY_TEST_DB_USER" "$root/deploy/local/sql/$2"
}
echo "==> Criando bancos e usuários (idempotente)"
run_sql master 00-databases.sql
run_sql liberty_core 10-core-users.sql
TENANT_LOGIN=liberty_app_cliente_a run_sql tenant_cliente_a 20-tenant-schema.sql
TENANT_LOGIN=liberty_app_cliente_b run_sql tenant_cliente_b 20-tenant-schema.sql

