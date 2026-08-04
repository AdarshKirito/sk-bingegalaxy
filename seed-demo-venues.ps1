# Seeds a demo venue-owner admin plus a set of venues (binges) across several
# cities and currencies, then approves them so they are live for customers.
#
# Idempotent - safe to re-run. Existing accounts/venues are detected by email and
# name and left untouched, so this is the script to re-run after `./rebuild.sh`
# wipes the database.
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less scripts
# as ANSI, and a UTF-8 dash/box-drawing character decodes into a smart quote that
# silently breaks string parsing.
#
# Credentials come from .env (gitignored) so nothing secret lands in git:
#   ADMIN_PASSWORD        - the seeded super-admin (admin@skbingegalaxy.com)
#   DEMO_ADMIN_EMAIL      - venue-owner account to create
#   DEMO_ADMIN_PASSWORD   - its password
#
# The venue CATALOG (event types, add-ons, rooms and their photo galleries) is
# not created here - booking-service's DataSeeder tops up every binge on boot,
# so this script restarts that service at the end. Pass -SkipRestart to skip it.
#
# Usage:
#   .\seed-demo-venues.ps1
#   .\seed-demo-venues.ps1 -SkipRestart
param(
    [string]$Base = 'http://localhost:8090/api/v1',
    [string]$Origin = 'http://localhost:3000',
    [switch]$SkipRestart
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# --- .env -------------------------------------------------------------------
function Read-DotEnv([string]$path) {
    $map = @{}
    if (-not (Test-Path $path)) { throw ".env not found at $path" }
    foreach ($line in Get-Content $path) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $map[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
    }
    return $map
}

$envMap     = Read-DotEnv (Join-Path $PSScriptRoot '.env')
$superEmail = 'admin@skbingegalaxy.com'
$superPw    = $envMap['ADMIN_PASSWORD']
$demoEmail  = $envMap['DEMO_ADMIN_EMAIL']
$demoPw     = $envMap['DEMO_ADMIN_PASSWORD']

if ([string]::IsNullOrWhiteSpace($superPw))   { throw "ADMIN_PASSWORD is not set in .env" }
if ([string]::IsNullOrWhiteSpace($demoEmail)) { throw "DEMO_ADMIN_EMAIL is not set in .env" }
if ([string]::IsNullOrWhiteSpace($demoPw))    { throw "DEMO_ADMIN_PASSWORD is not set in .env" }

# --- HTTP helpers -----------------------------------------------------------
# Three gateway rules shape every call below:
#   1. X-User-* headers are stripped and re-derived from the JWT, so only
#      Authorization is needed to act as a given user.
#   2. An allowed Origin is required or POSTs are rejected CSRF_BAD_ORIGIN.
#   3. State-changing calls need the XSRF double-submit pair: the XSRF-TOKEN
#      cookie minted by any GET, echoed back in the X-XSRF-TOKEN header.
# A single WebSession carries the cookie jar. Sharing it across the super-admin
# and venue-owner identities is safe because extractToken() prefers the
# Authorization header over the `token` cookie.
$script:Session   = $null
$script:CsrfToken = $null
$BaseHost = ([Uri]$Base).Host

function Initialize-Session {
    # GET /csrf mints the XSRF-TOKEN cookie and also returns it in the body.
    # The cookie is flagged Secure, and .NET will not replay a Secure cookie over
    # plain http the way browsers do for localhost - so seed the jar by hand from
    # the body value, without the Secure flag, and the double-submit pair matches.
    $resp = Invoke-RestMethod -Uri "$Base/csrf" -Method Get -Headers @{ Origin = $Origin }
    if (-not $resp.token) { throw "GET /csrf returned no token" }
    $script:CsrfToken = $resp.token
    $script:Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $script:Session.Cookies.Add((New-Object System.Net.Cookie('XSRF-TOKEN', $resp.token, '/', $BaseHost)))
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$Token = $null
    )
    $headers = @{ Origin = $Origin }
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    if ($script:CsrfToken) { $headers['X-XSRF-TOKEN'] = $script:CsrfToken }
    $params = @{ Uri = "$Base$Path"; Method = $Method; Headers = $headers; ContentType = 'application/json' }
    if ($script:Session) { $params['WebSession'] = $script:Session }
    if ($null -ne $Body) { $params['Body'] = ($Body | ConvertTo-Json -Depth 6) }
    return Invoke-RestMethod @params
}

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Write-Skip($msg) { Write-Host "    $msg" -ForegroundColor DarkGray }

# --- 1. Super-admin login ---------------------------------------------------
Write-Step "Fetching CSRF token"
Initialize-Session
Write-Ok "session established"

Write-Step "Logging in as super-admin ($superEmail)"
$superLogin = Invoke-Api -Method Post -Path '/auth/admin/login' -Body @{ email = $superEmail; password = $superPw }
$superToken = $superLogin.data.token
if (-not $superToken) { throw "Super-admin login returned no token (MFA required? set SUPER_ADMIN_REQUIRE_MFA=false)" }
Write-Ok "super-admin ok"

# --- 2. Create the venue-owner admin ----------------------------------------
Write-Step "Ensuring venue-owner admin exists ($demoEmail)"
$admins = Invoke-Api -Method Get -Path '/auth/admin/admins?size=200' -Token $superToken
$existingAdmin = $admins.data.content | Where-Object { $_.email -eq $demoEmail }

if ($existingAdmin) {
    Write-Skip "already exists (id=$($existingAdmin.id)) - left untouched"
} else {
    $created = Invoke-Api -Method Post -Path '/auth/admin/register' -Token $superToken -Body @{
        firstName        = 'Varanasi'
        lastName         = 'Kirito'
        email            = $demoEmail
        phone            = '9800012345'
        phoneCountryCode = '+91'
        password         = $demoPw
        city             = 'Hyderabad'
        state            = 'Telangana'
        country          = 'IN'
        consentGiven     = $true
        consentMarketing = $false
    }
    Write-Ok "created admin id=$($created.data.user.id)"
}

# --- 3. Log in as that admin ------------------------------------------------
Write-Step "Logging in as venue owner"
$demoLogin = Invoke-Api -Method Post -Path '/auth/admin/login' -Body @{ email = $demoEmail; password = $demoPw }
$demoToken = $demoLogin.data.token
if (-not $demoToken) { throw "Venue-owner login returned no token" }
Write-Ok "venue owner ok (id=$($demoLogin.data.user.id), role=$($demoLogin.data.user.role))"

# --- 4. Venues --------------------------------------------------------------
# Currency, timezone, tax jurisdiction and payment methods are all DERIVED from
# `country` - the IN/US mix below is what exercises the multi-currency path.
#
# NOTE on the US venues: CountryTaxDefaults has no US entry (correct - there is no
# national sales tax), so they are created with NO tax rule and charge 0% until an
# admin adds a state/city rule. The India venues inherit the global 18% GST rule.
$venues = @(
    @{ name = 'SK Binge Galaxy - Bengaluru';  addressLine1 = '12th Main, Indiranagar';    city = 'Bengaluru';  state = 'Karnataka';   country = 'IN'; postalCode = '560038'; latitude = 12.9716; longitude = 77.5946; timezone = 'Asia/Kolkata';    cc = '+91'; phone = '9876543210' }
    @{ name = 'SK Binge Galaxy - Mumbai';     addressLine1 = 'Linking Road, Bandra West'; city = 'Mumbai';     state = 'Maharashtra'; country = 'IN'; postalCode = '400050'; latitude = 19.0760; longitude = 72.8777; timezone = 'Asia/Kolkata';    cc = '+91'; phone = '9876543210' }
    @{ name = 'SK Binge Galaxy - Chicago';    addressLine1 = '233 S Wacker Dr';           city = 'Chicago';    state = 'IL';          country = 'US'; postalCode = '60606';  latitude = 41.8789; longitude = -87.6359; timezone = 'America/Chicago'; cc = '+1';  phone = '3125550142' }
    @{ name = 'SK Binge Galaxy - Schaumburg'; addressLine1 = '1600 E Golf Rd';            city = 'Schaumburg'; state = 'IL';          country = 'US'; postalCode = '60173';  latitude = 42.0334; longitude = -88.0834; timezone = 'America/Chicago'; cc = '+1';  phone = '8475550188' }
    @{ name = 'SK Binge Galaxy - Woodfield';  addressLine1 = '5 Woodfield Mall';          city = 'Schaumburg'; state = 'IL';          country = 'US'; postalCode = '60173';  latitude = 42.0453; longitude = -88.0353; timezone = 'America/Chicago'; cc = '+1';  phone = '8475550199' }
)

Write-Step "Creating venues"
$mine = Invoke-Api -Method Get -Path '/bookings/admin/binges' -Token $demoToken
$existingNames = @()
if ($mine.data) { $existingNames = @($mine.data | ForEach-Object { $_.name }) }

foreach ($v in $venues) {
    if ($existingNames -contains $v.name) {
        Write-Skip "$($v.name) - already exists"
        continue
    }
    $body = @{
        name                              = $v.name
        addressLine1                      = $v.addressLine1
        city                              = $v.city
        state                             = $v.state
        country                           = $v.country
        postalCode                        = $v.postalCode
        latitude                          = $v.latitude
        longitude                         = $v.longitude
        timezone                          = $v.timezone
        supportEmail                      = 'support@skbingegalaxy.com'
        supportPhone                      = $v.phone
        supportPhoneCountryCode           = $v.cc
        supportPhoneIsWhatsapp            = $true
        ownerEmail                        = $demoEmail
        ownerPhone                        = '9800012345'
        ownerPhoneCountryCode             = '+91'
        customerCancellationEnabled       = $true
        customerCancellationCutoffMinutes = 180
        maxConcurrentBookings             = 6
        openTime                          = '10:00'
        closeTime                         = '23:00'
    }
    $res = Invoke-Api -Method Post -Path '/bookings/admin/binges' -Token $demoToken -Body $body
    $line = "{0} -> id={1} status={2} currency={3}" -f $v.name, $res.data.id, $res.data.status, $res.data.currency
    Write-Ok $line
}

# --- 5. Approve -------------------------------------------------------------
# ADMIN-created venues land in PENDING_APPROVAL and stay invisible to customers
# until a super-admin approves them, so the demo data is not usable without this.
Write-Step "Approving pending venues as super-admin"
$pending = Invoke-Api -Method Get -Path '/bookings/admin/binges/pending' -Token $superToken
if (-not $pending.data -or @($pending.data).Count -eq 0) {
    Write-Skip "nothing pending"
} else {
    foreach ($p in $pending.data) {
        $ok = Invoke-Api -Method Post -Path "/bookings/admin/binges/$($p.id)/approve" -Token $superToken -Body @{ taxesEnabled = $true }
        $line = "approved {0} (id={1}, active={2})" -f $ok.data.name, $ok.data.id, $ok.data.active
        Write-Ok $line
    }
}

# --- 6. Catalog -------------------------------------------------------------
# DataSeeder runs at booking-service startup and tops up EVERY binge with the
# event types, add-ons, rooms and photo galleries it is missing.
if ($SkipRestart) {
    Write-Step "Skipping restart - run 'docker compose restart booking-service' to seed catalogs"
} else {
    Write-Step "Restarting booking-service to seed catalogs + photos into the new venues"
    docker compose restart booking-service | Out-Null
    Write-Ok "restarted - catalog seeding runs during startup (60-90s to healthy)"
}

Write-Host ""
Write-Host "Done. Sign in at http://localhost:3000 as $demoEmail" -ForegroundColor Yellow
