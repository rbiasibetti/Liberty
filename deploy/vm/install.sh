#!/usr/bin/env bash
# Instala (ou atualiza) o Liberty numa VM Linux com systemd e nginx. Rode como root, de dentro do
# pacote extraído (gerado por deploy/vm/package.sh). Idempotente: a configuração em /etc/liberty
# nunca é sobrescrita, e a versão anterior fica guardada para rollback.sh.
#
# Uso: sudo ./install.sh --server-name liberty.exemplo.com \
#        --oidc-authority https://login.exemplo.com/realms/liberty \
#        [--oidc-client-id liberty-frontend] [--api-url ""] [--no-nginx]
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
server_name="" oidc_authority="" oidc_client_id="liberty-frontend" api_url="" use_nginx=1
while [ $# -gt 0 ]; do
  case "$1" in
    --server-name) server_name="$2"; shift 2 ;;
    --oidc-authority) oidc_authority="$2"; shift 2 ;;
    --oidc-client-id) oidc_client_id="$2"; shift 2 ;;
    --api-url) api_url="$2"; shift 2 ;;
    --no-nginx) use_nginx=0; shift ;;
    *) echo "Opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

[ "$(id -u)" -eq 0 ] || { echo "Rode como root (sudo)." >&2; exit 1; }
version="$(cat "$here/VERSION")"
command -v java >/dev/null || { echo "Java 25 não encontrado (instale um JRE 25)." >&2; exit 1; }
java_major="$(java -version 2>&1 | sed -n '1s/[^0-9]*\([0-9]\+\).*/\1/p')"
[ "${java_major:-0}" -ge 25 ] || { echo "É preciso Java 25 ou superior (encontrado: ${java_major:-?})." >&2; exit 1; }
if [ "$use_nginx" -eq 1 ]; then
  command -v nginx >/dev/null || { echo "nginx não encontrado (instale-o ou use --no-nginx)." >&2; exit 1; }
  [ -n "$server_name" ] && [ -n "$oidc_authority" ] || { echo "Informe --server-name e --oidc-authority." >&2; exit 2; }
fi

echo "==> Usuário e pastas"
getent group liberty >/dev/null || groupadd --system liberty
id liberty >/dev/null 2>&1 || useradd --system --gid liberty --home-dir /var/lib/liberty --shell /usr/sbin/nologin liberty
install -d -o root -g root -m 0755 /opt/liberty /opt/liberty/releases
install -d -o liberty -g liberty -m 0750 /var/lib/liberty /var/lib/liberty/exports
install -d -o root -g liberty -m 0750 /etc/liberty

echo "==> Versão $version"
target="/opt/liberty/releases/$version"
if [ -L /opt/liberty/current ] && [ "$(readlink -f /opt/liberty/current)" = "$target" ]; then
  echo "A versão $version já é a ativa; gere o pacote com outra versão (package.sh <versão>)." >&2
  exit 1
fi
rm -rf "$target"
mkdir -p "$target"
cp -r "$here/backend" "$here/frontend" "$here/VERSION" "$target/"
chmod -R go-w "$target"
if [ -L /opt/liberty/current ]; then
  ln -sfn "$(readlink -f /opt/liberty/current)" /opt/liberty/previous
fi
ln -sfn "$target" /opt/liberty/current.new
mv -Tf /opt/liberty/current.new /opt/liberty/current

echo "==> Serviço"
install -m 0644 "$here/liberty-backend.service" /etc/systemd/system/liberty-backend.service
systemctl daemon-reload
systemctl enable liberty-backend >/dev/null

env_file=/etc/liberty/backend.env
if [ ! -f "$env_file" ]; then
  install -o root -g liberty -m 0640 "$here/backend.env.example" "$env_file"
  echo "!! Criei $env_file a partir do modelo: preencha os valores e rode: systemctl restart liberty-backend"
  need_config=1
else
  need_config=0
  for var in LIBERTY_CORE_APP_PASSWORD LIBERTY_VAULT_URI LIBERTY_EXPORT_SIGNING_KEY LIBERTY_SECURITY_ISSUER LIBERTY_OIDC_ISSUER; do
    grep -Eq "^$var=.+" "$env_file" || { echo "!! $var está vazio em $env_file"; need_config=1; }
  done
fi

if [ "$use_nginx" -eq 1 ]; then
  echo "==> Interface e nginx"
  if [ -n "$oidc_authority" ] && { [ ! -f /etc/liberty/config.json ] || [ -n "$server_name" ]; }; then
    python3 - "$api_url" "$oidc_authority" "$oidc_client_id" > /etc/liberty/config.json <<'PY'
import json, sys
print(json.dumps({"apiUrl": sys.argv[1], "oidcAuthority": sys.argv[2], "oidcClientId": sys.argv[3]}, indent=2))
PY
    chmod 0644 /etc/liberty/config.json
  fi
  oidc_origin="$(printf '%s' "$oidc_authority" | sed -E 's|^(https?://[^/]+).*|\1|')"
  sed -e "s|@SERVER_NAME@|$server_name|g" -e "s|@OIDC_ORIGIN@|$oidc_origin|g" \
    "$here/nginx-liberty.conf.template" > /etc/nginx/conf.d/liberty.conf
  nginx -t
  systemctl reload nginx
fi

if [ "$need_config" -eq 0 ]; then
  echo "==> Reiniciando o serviço"
  systemctl restart liberty-backend
  for _ in $(seq 1 60); do
    curl -sf http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1 && { echo "OK: a API está pronta (versão $version)."; break; }
    sleep 2
  done
  curl -sf http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1 \
    || { echo "A API não ficou pronta; veja: journalctl -u liberty-backend -n 100" >&2; exit 1; }
fi

echo "==> Mantendo só as 3 versões mais recentes"
ls -1dt /opt/liberty/releases/*/ | tail -n +4 | xargs -r rm -rf
