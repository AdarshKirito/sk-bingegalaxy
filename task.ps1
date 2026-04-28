$ErrorActionPreference = "Continue"
$base = "http://localhost:8080/api/v1"
try {
  $login = Invoke-RestMethod -Uri "$base/auth/admin/login" -Method Post -ContentType "application/json" -Body (@{ email="kirito@gmail.com"; password="Kirito@gmail.com1" } | ConvertTo-Json)
  $token = $login.data.accessToken; if (-not $token) { $token = $login.data.token }
  $userId = $login.data.user.id; $role = $login.data.user.role
  $headers = @{ Authorization="Bearer $token"; "X-User-Id"="$userId"; "X-User-Role"="$role"; "X-Binge-Id"="2"; "Content-Type"="application/json" }

  $items = @(
    @{ name="Mocktail Mixer"; description="Two pitchers of mocktails - Virgin Mojito & Pina Colada."; price=799; category="BEVERAGE"; imageUrls=@("https://images.unsplash.com/photo-1551024709-8f23befc6f87?w=1000&q=80") },
    @{ name="Premium Tea & Coffee"; description="Hot beverages on tap - barista coffee & masala chai."; price=499; category="BEVERAGE"; imageUrls=@("https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?w=1000&q=80") },
    @{ name="Live Acoustic Guitarist"; description="1-hour live acoustic performance - your playlist."; price=2999; category="EXPERIENCE"; imageUrls=@("https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=1000&q=80") }
  )

  foreach ($a in $items) {
    try {
      $json = $a | ConvertTo-Json -Depth 6 -Compress
      $r = Invoke-RestMethod -Uri "$base/bookings/admin/add-ons" -Method Post -Headers $headers -Body $json
      Write-Host "[OK] $($a.name) id=$($r.data.id) images=$($r.data.imageUrls.Count)"
    } catch {
      $body=""
      try { $resp=$_.Exception.Response; if ($resp) { $s=$resp.GetResponseStream(); $rd=New-Object System.IO.StreamReader($s); $body=$rd.ReadToEnd() } } catch {}
      Write-Host "[FAIL] $($a.name): $($_.Exception.Message)`n  body=$body"
    }
  }

  $evResp = Invoke-RestMethod -Uri "$base/bookings/admin/event-types" -Headers $headers
  $ev = $evResp.data
  $adResp = Invoke-RestMethod -Uri "$base/bookings/admin/add-ons" -Headers $headers
  $ad = $adResp.data
  
  Write-Host ""
  Write-Host "--- Events with images ($(($ev | ? {$_.imageUrls.Count -gt 0}).Count) of $($ev.Count)) ---"
  $ev | Where-Object { $_.imageUrls.Count -gt 0 } | ForEach-Object { Write-Host ("  {0,-32} id={1}" -f $_.name, $_.id) }
  Write-Host "--- Addons with images ($(($ad | ? {$_.imageUrls.Count -gt 0}).Count) of $($ad.Count)) ---"
  $ad | Where-Object { $_.imageUrls.Count -gt 0 } | ForEach-Object { Write-Host ("  {0,-32} [{1,-12}] id={2}" -f $_.name, $_.category, $_.id) }
} catch {
  Write-Host "FATAL: $($_.Exception.Message)"
}
