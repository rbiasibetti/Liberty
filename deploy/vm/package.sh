#!/usr/bin/env bash
# Gera o pacote de implantação (um .tar.gz) numa máquina de build: jar executável, worker-lib e a
# interface compilada. Constrói UMA vez; o mesmo pacote vai para HML e PRD, e a configuração
# (endereços, segredos) é só do ambiente (/etc/liberty), nunca do pacote.
# Uso: deploy/vm/package.sh [versão]      Exige Java 25 e Node 22+.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
version="${1:-$(git -C "$root" describe --tags --always --dirty 2>/dev/null || date +%Y%m%d%H%M)}"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
release="liberty-$version"

echo "==> Backend (jar e worker-lib)"
(cd "$root/backend" && ./mvnw -ntp -q package -DskipTests)
mkdir -p "$stage/$release/backend" "$stage/$release/frontend"
cp "$root/backend/liberty-app/target/liberty-app.jar" "$stage/$release/backend/"
cp -r "$root/backend/liberty-scripts/target/worker-lib" "$stage/$release/backend/worker-lib"

echo "==> Frontend (build estático; o endereço da API e do login vem do config.json do deploy)"
(cd "$root/frontend" && npm ci --silent && npm run build --silent)
cp -r "$root/frontend/dist" "$stage/$release/frontend/dist"

cp "$root/deploy/vm/liberty-backend.service" "$root/deploy/vm/nginx-liberty.conf.template" \
   "$root/deploy/vm/backend.env.example" "$root/deploy/vm/install.sh" "$root/deploy/vm/rollback.sh" "$stage/$release/"
echo "$version" > "$stage/$release/VERSION"

out="$root/deploy/vm/$release.tar.gz"
tar -C "$stage" -czf "$out" "$release"
(cd "$root/deploy/vm" && sha256sum "$release.tar.gz" > "$release.tar.gz.sha256")
echo "==> Pacote: $out"
cat "$root/deploy/vm/$release.tar.gz.sha256"
