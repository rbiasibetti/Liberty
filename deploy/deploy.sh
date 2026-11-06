#!/usr/bin/env bash
# Uso: deploy/deploy.sh <ambiente> <imagem-backend@digest> <imagem-frontend@digest>
# Requer kubectl com acesso ao cluster (KUBECONFIG) e kustomize embutido no kubectl.
set -euo pipefail

env_name="${1:?ambiente}"
backend_image="${2:?imagem do backend com digest}"
frontend_image="${3:?imagem do frontend com digest}"

case "$backend_image$frontend_image" in
  *@sha256:*@sha256:*) ;;
  *) echo "As imagens precisam ser referenciadas por digest (@sha256:...)." >&2; exit 1 ;;
esac

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp -r "$(dirname "$0")/k8s" "$work/k8s"
overlay="$work/k8s/overlays/$env_name"
[ -d "$overlay" ] || { echo "Overlay inexistente: $env_name" >&2; exit 1; }

(
  cd "$overlay"
  kubectl kustomize . >/dev/null # valida
  sed -i "s#^\(  - name: ghcr.io/liberty/backend\)\$#\1\n    newName: ${backend_image%@*}\n    digest: ${backend_image#*@}#" kustomization.yaml
  sed -i "s#^\(  - name: ghcr.io/liberty/frontend\)\$#\1\n    newName: ${frontend_image%@*}\n    digest: ${frontend_image#*@}#" kustomization.yaml
)

kubectl apply -k "$overlay"
kubectl -n "liberty-$env_name" rollout status deployment/liberty-backend --timeout=300s
kubectl -n "liberty-$env_name" rollout status deployment/liberty-frontend --timeout=300s
