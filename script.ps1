$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080/api/v1'
$login = Invoke-RestMethod -Uri "$base/auth/admin/login" -Method Post -ContentType 'application/json' -Body (@{ email='kirito@gmail.com'; password='Kirito@gmail.com1' } | ConvertTo-Json)
$token = $login.data.accessToken; if (-not $token) { $token = $login.data.token }
$userId = $login.data.user.id; $role = $login.data.user.role
$headers = @{ Authorization="Bearer $token"; 'X-User-Id'="$userId"; 'X-User-Role'="$role"; 'X-Binge-Id'='2'; 'Content-Type'='application/json' }
Write-Host "=== Existing Event Types ==="
$ev = (Invoke-RestMethod -Uri "$base/bookings/admin/event-types" -Headers $headers).data
$ev | ForEach-Object { "{0,-35} id={1} images={2}" -f $_.name, $_.id, $_.imageUrls.Count } | Write-Host
Write-Host "`n=== Existing Add-Ons ==="
$ad = (Invoke-RestMethod -Uri "$base/bookings/admin/add-ons" -Headers $headers).data
$ad | ForEach-Object { "{0,-35} [{1,-12}] id={2} images={3}" -f $_.name, $_.category, $_.id, $_.imageUrls.Count } | Write-Host
$targetEvents = @('Romantic Movie Date','Birthday Bash','Anniversary Special','Surprise Proposal','Friends Hangout','Cricket / Sports Screening','Corporate Team Event','Baby Shower','Gaming Marathon')
$targetAddons = @('Basic Balloon Decor','Premium Floral Decor','Neon LED Backdrop','Soft Drinks Pack (4)','Mocktail Mixer','Premium Tea & Coffee','Photo Shoot (30 min)','Cinematic Video Reel','Fog Entry Effect','Confetti Blast','Cold Pyro Sparklers','Birthday Cake (1 kg)','Premium Cake (2 kg)','Snacks Platter','Pizza Combo','Live Acoustic Guitarist','Personal Host / MC')
$missingEvents = $targetEvents | Where-Object { $_ -notin $ev.name }
$missingAddons = $targetAddons | Where-Object { $_ -notin $ad.name }
Write-Host "`n=== Missing Events: $($missingEvents -join ', ')"
Write-Host "=== Missing Addons: $($missingAddons -join ', ')"
function TryPost($url, $obj, $label) {
  $json = $obj | ConvertTo-Json -Depth 6 -Compress
  try {
    $r = Invoke-RestMethod -Uri $url -Method Post -Headers $headers -Body $json
    Write-Host "[OK] $label id=$($r.data.id)"
  } catch {
    $msg = $_.Exception.Message; $body = ''; try { $resp = $_.Exception.Response; if ($resp) { $stream = $resp.GetResponseStream(); $reader = New-Object System.IO.StreamReader($stream); $body = $reader.ReadToEnd() } } catch {}
    Write-Host "[FAIL] $label : $msg`n      body=$body"
  }
}
$retryEvents = @(
  @{ name='Gaming Marathon'; description='Console / PC gaming on a 150-inch screen.'; basePrice=2299; hourlyRate=450; pricePerGuest=50; minHours=2; maxHours=8; imageUrls=@('https://images.unsplash.com/photo-1542751371-adc38448a05e?w=1200&q=80','https://images.unsplash.com/photo-1493711662062-fa541adb3fc8?w=1200&q=80') }
)
$retryAddons = @(
  @{ name='Soft Drinks Pack (4)'; description='Assorted cold drinks.'; price=299; category='BEVERAGE'; imageUrls=@('https://images.unsplash.com/photo-1581636625402-29b2a704ef13?w=1000&q=80') },
  @{ name='Photo Shoot (30 min)'; description='Pro photographer with edited photos.'; price=1999; category='PHOTOGRAPHY'; imageUrls=@('https://images.unsplash.com/photo-1502920917128-1aa500764cbd?w=1000&q=80') },
  @{ name='Birthday Cake (1 kg)'; description='Choice of chocolate / vanilla / red velvet.'; price=799; category='FOOD'; imageUrls=@('https://images.unsplash.com/photo-1558636508-e0db3814bd1d?w=1000&q=80') },
  @{ name='Premium Cake (2 kg)'; description='Designer 2-kg cake with custom topper.'; price=1499; category='FOOD'; imageUrls=@('https://images.unsplash.com/photo-1535141192574-5d4897c12636?w=1000&q=80') },
  @{ name='Snacks Platter'; description='Veg & non-veg snack platter (serves 4).'; price=699; category='FOOD'; imageUrls=@('https://images.unsplash.com/photo-1541544537156-7627a7a4aa1c?w=1000&q=80') }
)
Write-Host "`n=== Retrying failed items ==="
foreach ($e in $retryEvents) { if ($e.name -in $missingEvents) { TryPost "$base/bookings/admin/event-types" $e $e.name } }
foreach ($a in $retryAddons) { if ($a.name -in $missingAddons) { TryPost "$base/bookings/admin/add-ons" $a $a.name } }
Write-Host "`n=== Final counts ==="
$ev2 = (Invoke-RestMethod -Uri "$base/bookings/admin/event-types" -Headers $headers).data
$ad2 = (Invoke-RestMethod -Uri "$base/bookings/admin/add-ons" -Headers $headers).data
Write-Host ("Event types: " + $ev2.Count + " | Add-ons: " + $ad2.Count)
Write-Host ("With images (events): " + ($ev2 | Where-Object { $_.imageUrls.Count -gt 0 } | Measure-Object).Count)
Write-Host ("With images (addons): " + ($ad2 | Where-Object { $_.imageUrls.Count -gt 0 } | Measure-Object).Count)
