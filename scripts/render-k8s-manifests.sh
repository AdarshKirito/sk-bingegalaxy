#!/usr/bin/env bash
set -euo pipefail

ENV_FILE=${1:?Usage: render-k8s-manifests.sh <env-file> [output-dir] <image-tag>}
OUT_DIR=${2:-.rendered-k8s}
IMAGE_TAG=${3:?IMAGE_TAG is required and should be an immutable release tag}

if [ ! -f "$ENV_FILE" ]; then
  echo "Environment file not found: $ENV_FILE" >&2
  exit 1
fi

set -a
. "$ENV_FILE"
set +a

: "${INGRESS_HOST:?INGRESS_HOST is required}"
: "${LETSENCRYPT_EMAIL:?LETSENCRYPT_EMAIL is required}"
: "${STORAGE_CLASS_NAME:?STORAGE_CLASS_NAME is required for stateful production workloads}"

TLS_SECRET_NAME=${TLS_SECRET_NAME:-skbingegalaxy-tls}

escape_sed_replacement() {
  printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'
}

image_tag_escaped=$(escape_sed_replacement "$IMAGE_TAG")
ingress_host_escaped=$(escape_sed_replacement "$INGRESS_HOST")
tls_secret_name_escaped=$(escape_sed_replacement "$TLS_SECRET_NAME")
letsencrypt_email_escaped=$(escape_sed_replacement "$LETSENCRYPT_EMAIL")
managed_postgres_host_escaped=$(escape_sed_replacement "${MANAGED_POSTGRES_HOST:-}")

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
cp k8s/*.yml "$OUT_DIR"/

for file in "$OUT_DIR"/*.yml; do
  sed \
    -e "s|IMAGE_TAG|${image_tag_escaped}|g" \
    -e "s|__INGRESS_HOST__|${ingress_host_escaped}|g" \
    -e "s|__TLS_SECRET_NAME__|${tls_secret_name_escaped}|g" \
    -e "s|__LETSENCRYPT_EMAIL__|${letsencrypt_email_escaped}|g" \
    -e "s|__MANAGED_PG_HOST__|${managed_postgres_host_escaped}|g" \
    "$file" > "$file.tmp"
  mv "$file.tmp" "$file"

  storage_class_escaped=$(escape_sed_replacement "$STORAGE_CLASS_NAME")
  sed "s|storageClassName: __STORAGE_CLASS_NAME__|storageClassName: ${storage_class_escaped}|g" "$file" > "$file.tmp"
  mv "$file.tmp" "$file"
done

echo "Rendered manifests into $OUT_DIR"