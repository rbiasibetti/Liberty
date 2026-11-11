#!/usr/bin/env bash
# Volta para a versão anterior (a que estava ativa antes do último install.sh). Rode como root.
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "Rode como root (sudo)." >&2; exit 1; }
[ -L /opt/liberty/previous ] || { echo "Não há versão anterior guardada." >&2; exit 1; }
current="$(readlink -f /opt/liberty/current)"
previous="$(readlink -f /opt/liberty/previous)"
[ -d "$previous" ] || { echo "A versão anterior não existe mais: $previous" >&2; exit 1; }
ln -sfn "$previous" /opt/liberty/current.new
mv -Tf /opt/liberty/current.new /opt/liberty/current
ln -sfn "$current" /opt/liberty/previous
systemctl restart liberty-backend
echo "Voltei para $(cat "$previous/VERSION"); a versão $(cat "$current/VERSION") ficou como 'previous'."
