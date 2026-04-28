# Seeds creative event types & add-ons (with Unsplash image URLs) into ZK BINGE (id=2)
# Auth: kirito@gmail.com (admin of binge 2)
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080/api/v1'

Write-Host "==> Logging in..." -ForegroundColor Cyan
$login = Invoke-RestMethod -Uri "$base/auth/admin/login" -Method Post -ContentType 'application/json' `
    -Body (@{ email = 'kirito@gmail.com'; password = 'Kirito@gmail.com1' } | ConvertTo-Json)
$token  = $login.data.token
$userId = $login.data.user.id
$role   = $login.data.user.role
Write-Host "    Logged in as userId=$userId role=$role" -ForegroundColor Green

$headers = @{
    Authorization   = "Bearer $token"
    'X-User-Id'     = "$userId"
    'X-User-Role'   = "$role"
    'Content-Type'  = 'application/json'
    'X-Binge-Id'    = '2'
}

# ─────────────────────────────────────────────────────────────────────
# EVENT TYPES — each with 2 Unsplash image URLs
# ─────────────────────────────────────────────────────────────────────
$eventTypes = @(
    @{
        name          = 'Romantic Movie Date'
        description   = 'Cozy private screening for two with mood lighting and cushions.'
        basePrice     = 1999
        hourlyRate    = 400
        pricePerGuest = 0
        minHours      = 2
        maxHours      = 4
        imageUrls     = @(
            'https://images.unsplash.com/photo-1542204165-65bf26472b9b?w=1200&q=80',
            'https://images.unsplash.com/photo-1516280440614-37939bbacd81?w=1200&q=80'
        )
    },
    @{
        name          = 'Birthday Bash'
        description   = 'Surprise birthday celebration with decoration, cake reveal & cinematic vibes.'
        basePrice     = 2999
        hourlyRate    = 500
        pricePerGuest = 100
        minHours      = 2
        maxHours      = 6
        imageUrls     = @(
            'https://images.unsplash.com/photo-1558636508-e0db3814bd1d?w=1200&q=80',
            'https://images.unsplash.com/photo-1530103862676-de8c9debad1d?w=1200&q=80'
        )
    },
    @{
        name          = 'Anniversary Special'
        description   = 'Celebrate your milestone with rose-petal decor, candles and a private theatre.'
        basePrice     = 3499
        hourlyRate    = 600
        pricePerGuest = 0
        minHours      = 2
        maxHours      = 5
        imageUrls     = @(
            'https://images.unsplash.com/photo-1518621736915-f3b1c41bfd00?w=1200&q=80',
            'https://images.unsplash.com/photo-1519671482749-fd09be7ccebf?w=1200&q=80'
        )
    },
    @{
        name          = 'Surprise Proposal'
        description   = 'Pop the question in a cinematic setup — fairy lights, fog effect & custom slideshow.'
        basePrice     = 4999
        hourlyRate    = 700
        pricePerGuest = 0
        minHours      = 2
        maxHours      = 4
        imageUrls     = @(
            'https://images.unsplash.com/photo-1525772764200-be829a350797?w=1200&q=80',
            'https://images.unsplash.com/photo-1519225421980-715cb0215aed?w=1200&q=80'
        )
    },
    @{
        name          = 'Friends Hangout'
        description   = 'Squad goals night out — your favourite movies on the big screen with snacks galore.'
        basePrice     = 2499
        hourlyRate    = 450
        pricePerGuest = 75
        minHours      = 2
        maxHours      = 6
        imageUrls     = @(
            'https://images.unsplash.com/photo-1543007630-9710e4a00a20?w=1200&q=80',
            'https://images.unsplash.com/photo-1517457373958-b7bdd4587205?w=1200&q=80'
        )
    },
    @{
        name          = 'Cricket / Sports Screening'
        description   = 'Live match screening on a giant screen with premium audio. Cheer with your tribe!'
        basePrice     = 1999
        hourlyRate    = 400
        pricePerGuest = 50
        minHours      = 2
        maxHours      = 6
        imageUrls     = @(
            'https://images.unsplash.com/photo-1531415074968-036ba1b575da?w=1200&q=80',
            'https://images.unsplash.com/photo-1540747913346-19e32dc3e97e?w=1200&q=80'
        )
    },
    @{
        name          = 'Corporate Team Event'
        description   = 'Team off-sites, product launches or training days in a private theatre setup.'
        basePrice     = 3999
        hourlyRate    = 800
        pricePerGuest = 150
        minHours      = 2
        maxHours      = 8
        imageUrls     = @(
            'https://images.unsplash.com/photo-1556761175-5973dc0f32e7?w=1200&q=80',
            'https://images.unsplash.com/photo-1542744173-8e7e53415bb0?w=1200&q=80'
        )
    },
    @{
        name          = 'Baby Shower'
        description   = 'Pastel-themed baby shower setup with photo backdrop & cake reveal.'
        basePrice     = 3499
        hourlyRate    = 500
        pricePerGuest = 80
        minHours      = 2
        maxHours      = 5
        imageUrls     = @(
            'https://images.unsplash.com/photo-1530103862676-de8c9debad1d?w=1200&q=80',
            'https://images.unsplash.com/photo-1464347744102-11db6282f854?w=1200&q=80'
        )
    },
    @{
        name          = 'Gaming Marathon'
        description   = 'Console / PC gaming on a 150-inch screen — perfect for FIFA, COD or RDR2 nights.'
        basePrice     = 2299
        hourlyRate    = 450
        pricePerGuest = 50
        minHours      = 2
        maxHours      = 8
        imageUrls     = @(
            'https://images.unsplash.com/photo-1542751371-adc38448a05e?w=1200&q=80',
            'https://images.unsplash.com/photo-1493711662062-fa541adb3fc8?w=1200&q=80'
        )
    }
)

# ─────────────────────────────────────────────────────────────────────
# ADD-ONS — each with 1 Unsplash image URL
# ─────────────────────────────────────────────────────────────────────
$addOns = @(
    # DECORATION
    @{ name='Basic Balloon Decor';     description='Themed balloon arch and table setup.';                  price=499;  category='DECORATION';  imageUrls=@('https://images.unsplash.com/photo-1530103862676-de8c9debad1d?w=1000&q=80') },
    @{ name='Premium Floral Decor';    description='Fresh flower wall, rose petals and aisle decor.';        price=1499; category='DECORATION';  imageUrls=@('https://images.unsplash.com/photo-1519225421980-715cb0215aed?w=1000&q=80') },
    @{ name='Neon LED Backdrop';       description='Custom neon sign with LED-lit photo backdrop.';          price=1299; category='DECORATION';  imageUrls=@('https://images.unsplash.com/photo-1492684223066-81342ee5ff30?w=1000&q=80') },

    # BEVERAGE
    @{ name='Soft Drinks Pack (4)';    description='Assorted cold drinks — Coke, Pepsi, Sprite, Fanta.';     price=299;  category='BEVERAGE';    imageUrls=@('https://images.unsplash.com/photo-1581636625402-29b2a704ef13?w=1000&q=80') },
    @{ name='Mocktail Mixer';          description='Two pitchers of mocktails — Virgin Mojito & Pina Colada.'; price=799; category='BEVERAGE';   imageUrls=@('https://images.unsplash.com/photo-1551024709-8f23befc6f87?w=1000&q=80') },
    @{ name='Premium Tea & Coffee';    description='Hot beverages on tap — barista-style coffee & masala chai.'; price=499; category='BEVERAGE'; imageUrls=@('https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?w=1000&q=80') },

    # PHOTOGRAPHY
    @{ name='Photo Shoot (30 min)';    description='Professional photographer with edited high-res photos.'; price=1999; category='PHOTOGRAPHY'; imageUrls=@('https://images.unsplash.com/photo-1502920917128-1aa500764cbd?w=1000&q=80') },
    @{ name='Cinematic Video Reel';    description='60-second cinematic edit of your event with music.';     price=2999; category='PHOTOGRAPHY'; imageUrls=@('https://images.unsplash.com/photo-1492691527719-9d1e07e534b4?w=1000&q=80') },

    # EFFECT
    @{ name='Fog Entry Effect';        description='Dramatic fog entry the moment you walk in.';             price=799;  category='EFFECT';      imageUrls=@('https://images.unsplash.com/photo-1518972559570-7cc1309f3229?w=1000&q=80') },
    @{ name='Confetti Blast';          description='Celebration confetti shower at cake-cutting.';            price=499;  category='EFFECT';      imageUrls=@('https://images.unsplash.com/photo-1532635241-17e820acc59f?w=1000&q=80') },
    @{ name='Cold Pyro Sparklers';     description='Indoor-safe cold sparklers for the magical moment.';     price=1199; category='EFFECT';      imageUrls=@('https://images.unsplash.com/photo-1467810563316-b5476525c0f9?w=1000&q=80') },

    # FOOD
    @{ name='Birthday Cake (1 kg)';    description='Choice of chocolate / vanilla / red velvet — eggless option.'; price=799;  category='FOOD'; imageUrls=@('https://images.unsplash.com/photo-1558636508-e0db3814bd1d?w=1000&q=80') },
    @{ name='Premium Cake (2 kg)';     description='Designer 2-kg cake with custom topper.';                  price=1499; category='FOOD';        imageUrls=@('https://images.unsplash.com/photo-1535141192574-5d4897c12636?w=1000&q=80') },
    @{ name='Snacks Platter';          description='Veg & non-veg snack platter (serves 4).';                price=699;  category='FOOD';        imageUrls=@('https://images.unsplash.com/photo-1541544537156-7627a7a4aa1c?w=1000&q=80') },
    @{ name='Pizza Combo';             description='Two large pizzas + garlic bread + dip.';                 price=999;  category='FOOD';        imageUrls=@('https://images.unsplash.com/photo-1513104890138-7c749659a591?w=1000&q=80') },

    # EXPERIENCE
    @{ name='Live Acoustic Guitarist'; description='1-hour live acoustic performance — your playlist.';      price=2999; category='EXPERIENCE';  imageUrls=@('https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=1000&q=80') },
    @{ name='Personal Host / MC';      description='Dedicated event host to manage announcements & games.';   price=1499; category='EXPERIENCE';  imageUrls=@('https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=1000&q=80') }
)

# ─────────────────────────────────────────────────────────────────────
# Helper
# ─────────────────────────────────────────────────────────────────────
function Post-Json($url, $obj) {
    $json = $obj | ConvertTo-Json -Depth 6 -Compress
    return Invoke-RestMethod -Uri $url -Method Post -Headers $headers -Body $json
}

$created = 0; $skipped = 0; $failed = 0

# ─────────────────────────────────────────────────────────────────────
# Pre-fetch existing names so we don't create duplicates
# ─────────────────────────────────────────────────────────────────────
Write-Host "`n==> Fetching existing event types & add-ons..." -ForegroundColor Cyan
$existingEvents = (Invoke-RestMethod -Uri "$base/bookings/admin/event-types" -Headers $headers).data
$existingAddons = (Invoke-RestMethod -Uri "$base/bookings/admin/add-ons"     -Headers $headers).data
$existingEventNames = @($existingEvents | ForEach-Object { $_.name.ToLower() })
$existingAddonNames = @($existingAddons | ForEach-Object { $_.name.ToLower() })
Write-Host "    Existing event types: $($existingEvents.Count) | add-ons: $($existingAddons.Count)" -ForegroundColor DarkGray

# ─────────────────────────────────────────────────────────────────────
# Create Event Types
# ─────────────────────────────────────────────────────────────────────
Write-Host "`n==> Creating event types..." -ForegroundColor Cyan
foreach ($e in $eventTypes) {
    if ($existingEventNames -contains $e.name.ToLower()) {
        Write-Host "    [SKIP] $($e.name) already exists" -ForegroundColor Yellow
        $skipped++; continue
    }
    try {
        $r = Post-Json "$base/bookings/admin/event-types" $e
        Write-Host "    [OK]   $($e.name)  (id=$($r.data.id), images=$($r.data.imageUrls.Count))" -ForegroundColor Green
        $created++
    } catch {
        Write-Host "    [FAIL] $($e.name): $($_.Exception.Message)" -ForegroundColor Red
        if ($_.ErrorDetails) { Write-Host "           $($_.ErrorDetails.Message)" -ForegroundColor DarkRed }
        $failed++
    }
}

# ─────────────────────────────────────────────────────────────────────
# Create Add-Ons
# ─────────────────────────────────────────────────────────────────────
Write-Host "`n==> Creating add-ons..." -ForegroundColor Cyan
foreach ($a in $addOns) {
    if ($existingAddonNames -contains $a.name.ToLower()) {
        Write-Host "    [SKIP] $($a.name) already exists" -ForegroundColor Yellow
        $skipped++; continue
    }
    try {
        $r = Post-Json "$base/bookings/admin/add-ons" $a
        Write-Host "    [OK]   $($a.name) [$($a.category)]  (id=$($r.data.id), images=$($r.data.imageUrls.Count))" -ForegroundColor Green
        $created++
    } catch {
        Write-Host "    [FAIL] $($a.name): $($_.Exception.Message)" -ForegroundColor Red
        if ($_.ErrorDetails) { Write-Host "           $($_.ErrorDetails.Message)" -ForegroundColor DarkRed }
        $failed++
    }
}

Write-Host "`n==================== SUMMARY ====================" -ForegroundColor Cyan
Write-Host " Created: $created" -ForegroundColor Green
Write-Host " Skipped: $skipped" -ForegroundColor Yellow
Write-Host " Failed : $failed"  -ForegroundColor $(if ($failed -gt 0) { 'Red' } else { 'DarkGray' })
Write-Host "==================================================" -ForegroundColor Cyan
