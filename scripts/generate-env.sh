#!/usr/bin/env bash
# ============================================================
# generate-env.sh — Generates a .env file with cryptographically
# secure random secrets for SK Binge Galaxy.
#
# Usage:  chmod +x scripts/generate-env.sh && ./scripts/generate-env.sh
# Output: .env (in the project root)
# ============================================================
set -euo pipefail

ENV_FILE=".env"

if [ -f "$ENV_FILE" ]; then
  echo "⚠  $ENV_FILE already exists. Rename or delete it first."
  exit 1
fi

# ── Generate random secrets ──────────────────────────────────
POSTGRES_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')
MONGO_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')
EUREKA_PASSWORD=$(openssl rand -base64 16 | tr -d '/+=')
CONFIG_SERVER_PASSWORD=$(openssl rand -base64 16 | tr -d '/+=')
JWT_SECRET=$(openssl rand -base64 48)
INTERNAL_API_SECRET=$(openssl rand -hex 32)
REDIS_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')
ADMIN_PASSWORD=$(openssl rand -base64 18 | tr -d '/+=')

cat > "$ENV_FILE" <<EOF
# ============================================================
# SK Binge Galaxy — Environment Variables (AUTO-GENERATED)
# Generated on: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# NEVER commit this file to version control.
# ============================================================

# ── Database ──────────────────────────────────────────────────
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
MONGO_PASSWORD=${MONGO_PASSWORD}

# ── Service Discovery ────────────────────────────────────────
EUREKA_PASSWORD=${EUREKA_PASSWORD}

# ── Config Server ────────────────────────────────────────────
CONFIG_SERVER_USER=configuser
CONFIG_SERVER_PASSWORD=${CONFIG_SERVER_PASSWORD}

# ── JWT ───────────────────────────────────────────────────────
JWT_SECRET=${JWT_SECRET}

# ── Internal Service-to-Service Auth ─────────────────────────
INTERNAL_API_SECRET=${INTERNAL_API_SECRET}
REDIS_PASSWORD=${REDIS_PASSWORD}

# ── Deploy / Ingress ─────────────────────────────────────────
INGRESS_HOST=skbingegalaxy.com
TLS_SECRET_NAME=skbingegalaxy-tls
LETSENCRYPT_EMAIL=admin@skbingegalaxy.com
STORAGE_CLASS_NAME=gp3
MANAGED_POSTGRES_HOST=         # TODO: required for production Jenkins/Kubernetes deploys
BACKUP_S3_BUCKET=             # Optional but recommended for off-cluster backups

# ── Auth Cookies ──────────────────────────────────────────────
# IMPORTANT: Default is true for production safety. Set to false ONLY for local HTTP dev.
COOKIE_SECURE=true
COOKIE_DOMAIN=

# ── Admin Seeding ─────────────────────────────────────────────
ADMIN_EMAIL=admin@skbingegalaxy.com
ADMIN_PASSWORD=${ADMIN_PASSWORD}

# ── Google OAuth ──────────────────────────────────────────────
GOOGLE_CLIENT_ID=                 # TODO: paste your Google OAuth 2.0 client ID

# ── Payment (Razorpay) ───────────────────────────────────────
RAZORPAY_KEY_ID=                  # TODO: paste your Razorpay key ID
RAZORPAY_KEY_SECRET=              # TODO: paste your Razorpay key secret
PAYMENT_CALLBACK_URL=https://yourdomain.com/api/v1/payments/callback

# ── CORS (comma-separated origins) ───────────────────────────
# Production-safe default. Add localhost origins only when running over local HTTP.
CORS_ALLOWED_ORIGINS=https://skbingegalaxy.com

# ── Email / SMTP ──────────────────────────────────────────────
EMAIL_HOST=smtp.gmail.com
EMAIL_PORT=587
EMAIL_USER=                       # TODO: SMTP username
EMAIL_PASS=                       # TODO: SMTP password / app-specific password
EMAIL_FROM=noreply@skbingegalaxy.com

# ── SMS / WhatsApp ────────────────────────────────────────────
SMS_PROVIDER=disabled
SMS_API_KEY=
WHATSAPP_PROVIDER=disabled
WHATSAPP_PHONE_ID=
WHATSAPP_TOKEN=
EOF

echo "✅  $ENV_FILE generated with strong random secrets."
echo "📝  Fill in GOOGLE_CLIENT_ID, RAZORPAY keys, EMAIL credentials, and set MANAGED_POSTGRES_HOST plus STORAGE_CLASS_NAME before deploying to production. Set BACKUP_S3_BUCKET if you want off-cluster backups."
