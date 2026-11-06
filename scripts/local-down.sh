#!/usr/bin/env bash
# Derruba a infraestrutura local (os dados do Vault, Redis e Keycloak são descartados; bancos SQL permanecem).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
set -a; [ -f "$root/.env.local" ] && . "$root/.env.local"; set +a
if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then compose=(docker compose); else compose=(docker-compose); fi
"${compose[@]}" -f "$root/deploy/local/compose.yaml" --profile mssql down --remove-orphans
