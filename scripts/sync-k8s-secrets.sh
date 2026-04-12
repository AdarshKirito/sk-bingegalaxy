#!/usr/bin/env bash
set -euo pipefail

ENV_FILE=${1:?Usage: sync-k8s-secrets.sh <env-file> [namespace]}
NAMESPACE=${2:-sk-binge-galaxy}

if [ ! -f "$ENV_FILE" ]; then
  echo "Environment file not found: $ENV_FILE" >&2
  exit 1
fi

set -a
. "$ENV_FILE"
set +a

required_vars=(
  POSTGRES_PASSWORD
  MONGO_PASSWORD
  REDIS_PASSWORD
  EUREKA_PASSWORD
  CONFIG_SERVER_PASSWORD
  JWT_SECRET
  INTERNAL_API_SECRET
  ADMIN_PASSWORD
  GOOGLE_CLIENT_ID
  RAZORPAY_KEY_ID
  RAZORPAY_KEY_SECRET
  PAYMENT_CALLBACK_URL
  CORS_ALLOWED_ORIGINS
  EMAIL_HOST
  EMAIL_PORT
  EMAIL_USER
  EMAIL_PASS
  EMAIL_FROM
)

missing_vars=()
for var_name in "${required_vars[@]}"; do
  if [ -z "${!var_name:-}" ]; then
    missing_vars+=("$var_name")
  fi
done

if [ ${#missing_vars[@]} -gt 0 ]; then
  printf 'Missing required environment variables for Kubernetes secrets:\n' >&2
  printf '  - %s\n' "${missing_vars[@]}" >&2
  exit 1
fi

COOKIE_SECURE=${COOKIE_SECURE:-true}
if [ -z "${COOKIE_DOMAIN:-}" ] && [ -n "${INGRESS_HOST:-}" ] && [ "${INGRESS_HOST}" != "localhost" ]; then
  COOKIE_DOMAIN=".${INGRESS_HOST#.}"
else
  COOKIE_DOMAIN=${COOKIE_DOMAIN:-}
fi
SMS_PROVIDER=${SMS_PROVIDER:-disabled}
SMS_API_KEY=${SMS_API_KEY:-}
WHATSAPP_PROVIDER=${WHATSAPP_PROVIDER:-disabled}
WHATSAPP_PHONE_ID=${WHATSAPP_PHONE_ID:-}
WHATSAPP_TOKEN=${WHATSAPP_TOKEN:-}
BACKUP_S3_BUCKET=${BACKUP_S3_BUCKET:-}

# ── Per-service database credentials (least privilege) ────────────────────
# If per-service passwords are not explicitly set, derive them from POSTGRES_PASSWORD + service name.
AUTH_DB_USERNAME=${AUTH_DB_USERNAME:-auth_svc}
AUTH_DB_PASSWORD=${AUTH_DB_PASSWORD:-$(echo -n "${POSTGRES_PASSWORD}_auth" | openssl dgst -sha256 -hex | cut -c1-32)}
AVAILABILITY_DB_USERNAME=${AVAILABILITY_DB_USERNAME:-availability_svc}
AVAILABILITY_DB_PASSWORD=${AVAILABILITY_DB_PASSWORD:-$(echo -n "${POSTGRES_PASSWORD}_availability" | openssl dgst -sha256 -hex | cut -c1-32)}
BOOKING_DB_USERNAME=${BOOKING_DB_USERNAME:-booking_svc}
BOOKING_DB_PASSWORD=${BOOKING_DB_PASSWORD:-$(echo -n "${POSTGRES_PASSWORD}_booking" | openssl dgst -sha256 -hex | cut -c1-32)}
PAYMENT_DB_USERNAME=${PAYMENT_DB_USERNAME:-payment_svc}
PAYMENT_DB_PASSWORD=${PAYMENT_DB_PASSWORD:-$(echo -n "${POSTGRES_PASSWORD}_payment" | openssl dgst -sha256 -hex | cut -c1-32)}

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

kubectl -n "$NAMESPACE" create secret generic db-secrets \
  --from-literal=POSTGRES_USER=skbg_admin \
  --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --from-literal=AUTH_DB_USERNAME="$AUTH_DB_USERNAME" \
  --from-literal=AUTH_DB_PASSWORD="$AUTH_DB_PASSWORD" \
  --from-literal=AVAILABILITY_DB_USERNAME="$AVAILABILITY_DB_USERNAME" \
  --from-literal=AVAILABILITY_DB_PASSWORD="$AVAILABILITY_DB_PASSWORD" \
  --from-literal=BOOKING_DB_USERNAME="$BOOKING_DB_USERNAME" \
  --from-literal=BOOKING_DB_PASSWORD="$BOOKING_DB_PASSWORD" \
  --from-literal=PAYMENT_DB_USERNAME="$PAYMENT_DB_USERNAME" \
  --from-literal=PAYMENT_DB_PASSWORD="$PAYMENT_DB_PASSWORD" \
  --from-literal=MONGO_USER=skbg_admin \
  --from-literal=MONGO_PASSWORD="$MONGO_PASSWORD" \
  --from-literal=SPRING_DATASOURCE_USERNAME=skbg_admin \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD" \
  --from-literal=SPRING_DATA_MONGODB_URI="mongodb://skbg_admin:${MONGO_PASSWORD}@mongodb-0.mongodb:27017,mongodb-1.mongodb:27017,mongodb-2.mongodb:27017/notification_db?authSource=admin&replicaSet=rs0" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$NAMESPACE" create secret generic app-secrets \
  --from-literal=JWT_SECRET="$JWT_SECRET" \
  --from-literal=RAZORPAY_KEY_ID="$RAZORPAY_KEY_ID" \
  --from-literal=RAZORPAY_KEY_SECRET="$RAZORPAY_KEY_SECRET" \
  --from-literal=GOOGLE_CLIENT_ID="$GOOGLE_CLIENT_ID" \
  --from-literal=EUREKA_PASSWORD="$EUREKA_PASSWORD" \
  --from-literal=CONFIG_SERVER_PASSWORD="$CONFIG_SERVER_PASSWORD" \
  --from-literal=SPRING_CLOUD_CONFIG_USERNAME="${CONFIG_SERVER_USER:-configuser}" \
  --from-literal=ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  --from-literal=INTERNAL_API_SECRET="$INTERNAL_API_SECRET" \
  --from-literal=PAYMENT_CALLBACK_URL="$PAYMENT_CALLBACK_URL" \
  --from-literal=CORS_ALLOWED_ORIGINS="$CORS_ALLOWED_ORIGINS" \
  --from-literal=COOKIE_SECURE="$COOKIE_SECURE" \
  --from-literal=COOKIE_DOMAIN="$COOKIE_DOMAIN" \
  --from-literal=EMAIL_HOST="$EMAIL_HOST" \
  --from-literal=EMAIL_PORT="$EMAIL_PORT" \
  --from-literal=EMAIL_USER="$EMAIL_USER" \
  --from-literal=EMAIL_PASS="$EMAIL_PASS" \
  --from-literal=EMAIL_FROM="$EMAIL_FROM" \
  --from-literal=SMS_PROVIDER="$SMS_PROVIDER" \
  --from-literal=SMS_API_KEY="$SMS_API_KEY" \
  --from-literal=WHATSAPP_PROVIDER="$WHATSAPP_PROVIDER" \
  --from-literal=WHATSAPP_PHONE_ID="$WHATSAPP_PHONE_ID" \
  --from-literal=WHATSAPP_TOKEN="$WHATSAPP_TOKEN" \
  --from-literal=BACKUP_S3_BUCKET="$BACKUP_S3_BUCKET" \
  --from-literal=REDIS_PASSWORD="${REDIS_PASSWORD:-}" \
  --from-literal=SPRING_DATA_REDIS_PASSWORD="${REDIS_PASSWORD:-}" \
  --from-literal=SPRING_CLOUD_CONFIG_PASSWORD="${CONFIG_SERVER_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Synchronized Kubernetes secrets in namespace $NAMESPACE"