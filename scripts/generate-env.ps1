# ============================================================
# generate-env.ps1 — Generates a .env file with cryptographically
# secure random secrets for SK Binge Galaxy.
#
# Usage:  .\scripts\generate-env.ps1
# Output: .env (in the project root)
# ============================================================

$ErrorActionPreference = "Stop"
$envFile = Join-Path $PSScriptRoot "..\\.env"

if (Test-Path $envFile) {
    Write-Host "WARNING: .env already exists. Rename or delete it first." -ForegroundColor Yellow
    exit 1
}

function New-SecurePassword {
    param([int]$Length = 32)
    $bytes = New-Object byte[] $Length
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes) -replace '[/+=]', ''
}

function New-Base64Secret {
    param([int]$ByteLength = 48)
    $bytes = New-Object byte[] $ByteLength
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

$postgresPassword  = New-SecurePassword -Length 24
$mongoPassword     = New-SecurePassword -Length 24
$eurekaPassword    = New-SecurePassword -Length 16
$configPassword    = New-SecurePassword -Length 16
$jwtSecret         = New-Base64Secret -ByteLength 48
$internalApiSecret = New-SecurePassword -Length 32
$redisPassword     = New-SecurePassword -Length 24
$adminPassword     = New-SecurePassword -Length 18

$timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

$content = @"
# ============================================================
# SK Binge Galaxy — Environment Variables (AUTO-GENERATED)
# Generated on: $timestamp
# NEVER commit this file to version control.
# ============================================================

# -- Database --------------------------------------------------
POSTGRES_PASSWORD=$postgresPassword
MONGO_PASSWORD=$mongoPassword
# Per-service DB passwords (only needed if overriding init-databases.sql defaults)
# AUTH_SVC_PASSWORD=
# AVAILABILITY_SVC_PASSWORD=
# BOOKING_SVC_PASSWORD=
# PAYMENT_SVC_PASSWORD=

# -- Service Discovery -----------------------------------------
EUREKA_PASSWORD=$eurekaPassword

# -- Config Server ---------------------------------------------
CONFIG_SERVER_USER=configuser
CONFIG_SERVER_PASSWORD=$configPassword

# -- JWT -------------------------------------------------------
JWT_SECRET=$jwtSecret

# -- Internal Service-to-Service Auth --------------------------
INTERNAL_API_SECRET=$internalApiSecret
REDIS_PASSWORD=$redisPassword

# -- Deploy / Ingress ------------------------------------------
INGRESS_HOST=skbingegalaxy.com
TLS_SECRET_NAME=skbingegalaxy-tls
LETSENCRYPT_EMAIL=admin@skbingegalaxy.com
STORAGE_CLASS_NAME=gp3
MANAGED_POSTGRES_HOST=         # TODO: required for production Jenkins/Kubernetes deploys
BACKUP_S3_BUCKET=             # Optional but recommended for off-cluster backups

# -- Auth Cookies ----------------------------------------------
# IMPORTANT: Default is true for production safety. Set to false ONLY for local HTTP dev.
COOKIE_SECURE=true
COOKIE_DOMAIN=

# -- Admin Seeding ---------------------------------------------
ADMIN_EMAIL=admin@skbingegalaxy.com
ADMIN_PASSWORD=$adminPassword

# -- Google OAuth ----------------------------------------------
GOOGLE_CLIENT_ID=                 # TODO: paste your Google OAuth 2.0 client ID

# -- Payment (Razorpay) ---------------------------------------
RAZORPAY_KEY_ID=                  # TODO: paste your Razorpay key ID
RAZORPAY_KEY_SECRET=              # TODO: paste your Razorpay key secret
PAYMENT_CALLBACK_URL=https://yourdomain.com/api/v1/payments/callback

# -- CORS (comma-separated origins) ---------------------------
# Production-safe default. Add localhost origins only when running over local HTTP.
CORS_ALLOWED_ORIGINS=https://skbingegalaxy.com

# -- Email / SMTP ----------------------------------------------
EMAIL_HOST=smtp.gmail.com
EMAIL_PORT=587
EMAIL_USER=                       # TODO: SMTP username
EMAIL_PASS=                       # TODO: SMTP password / app-specific password
EMAIL_FROM=noreply@skbingegalaxy.com

# -- SMS / WhatsApp --------------------------------------------
SMS_PROVIDER=disabled
SMS_API_KEY=
WHATSAPP_PROVIDER=disabled
WHATSAPP_PHONE_ID=
WHATSAPP_TOKEN=
"@

Set-Content -Path $envFile -Value $content -Encoding UTF8

Write-Host "OK - .env generated with strong random secrets." -ForegroundColor Green
Write-Host "Fill in GOOGLE_CLIENT_ID, RAZORPAY keys, EMAIL credentials, and set MANAGED_POSTGRES_HOST plus STORAGE_CLASS_NAME before deploying to production. Set BACKUP_S3_BUCKET if you want off-cluster backups." -ForegroundColor Cyan
