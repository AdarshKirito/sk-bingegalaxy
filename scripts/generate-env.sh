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
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5173

# ── Email / SMTP ──────────────────────────────────────────────
EMAIL_HOST=smtp.gmail.com
EMAIL_PORT=587
EMAIL_USER=                       # TODO: SMTP username
EMAIL_PASS=                       # TODO: SMTP password / app-specific password
EMAIL_FROM=noreply@skbingegalaxy.com

# ── SMS / WhatsApp ────────────────────────────────────────────
SMS_PROVIDER=mock
SMS_API_KEY=
WHATSAPP_PROVIDER=mock
WHATSAPP_PHONE_ID=
WHATSAPP_TOKEN=
EOF

echo "✅  $ENV_FILE generated with strong random secrets."
echo "📝  Fill in GOOGLE_CLIENT_ID, RAZORPAY keys, and EMAIL credentials before starting."
