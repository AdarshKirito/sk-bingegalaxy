$ErrorActionPreference = 'Continue'
$gw = 'http://localhost:8080'

function Post($url, $body, $hdr) {
  try {
    $r = Invoke-WebRequest -Uri $url -Method POST -Body $body -Headers $hdr -ContentType 'application/json' -UseBasicParsing -ErrorAction Stop
    return @{ s = [int]$r.StatusCode; b = $r.Content }
  } catch {
    $rs = $_.Exception.Response
    if ($rs) {
      $sr = New-Object System.IO.StreamReader($rs.GetResponseStream())
      $bb = $sr.ReadToEnd()
      return @{ s = [int]$rs.StatusCode; b = $bb }
    }
    return @{ s = -1; b = $_.Exception.Message }
  }
}

function Put($url, $body, $hdr) {
  try {
    $r = Invoke-WebRequest -Uri $url -Method PUT -Body $body -Headers $hdr -ContentType 'application/json' -UseBasicParsing -ErrorAction Stop
    return @{ s = [int]$r.StatusCode; b = $r.Content }
  } catch {
    $rs = $_.Exception.Response
    if ($rs) {
      $sr = New-Object System.IO.StreamReader($rs.GetResponseStream())
      $bb = $sr.ReadToEnd()
      return @{ s = [int]$rs.StatusCode; b = $bb }
    }
    return @{ s = -1; b = $_.Exception.Message }
  }
}

function Get-Json($url, $hdr) {
  try {
    $r = Invoke-WebRequest -Uri $url -Method GET -Headers $hdr -UseBasicParsing -ErrorAction Stop
    return @{ s = [int]$r.StatusCode; b = $r.Content; data = (ConvertFrom-Json $r.Content) }
  } catch {
    $rs = $_.Exception.Response
    if ($rs) {
      $sr = New-Object System.IO.StreamReader($rs.GetResponseStream())
      $bb = $sr.ReadToEnd()
      return @{ s = [int]$rs.StatusCode; b = $bb }
    }
    return @{ s = -1; b = $_.Exception.Message }
  }
}

$adm = Post "$gw/api/v1/auth/login" (@{email='admin@skbingegalaxy.com';password='Admin@123Local'}|ConvertTo-Json) @{'X-Binge-Id'='1'}
if ($adm.s -ne 200) { Write-Host "[FATAL] admin login: $($adm.s) $($adm.b)"; exit 1 }
$adminTok = (ConvertFrom-Json $adm.b).data.token
$AH = @{ Authorization = "Bearer $adminTok"; 'X-Binge-Id' = '1' }

$em = "ver_$(Get-Random)@x.com"
$ph = '9' + (Get-Random -Min 100000000 -Max 999999999).ToString()
$reg = @{firstName='Verify';lastName='Tester';email=$em;password='Test@123Secure';phone=$ph} | ConvertTo-Json
$cus = Post "$gw/api/v1/auth/register" $reg @{ 'X-Binge-Id' = '1' }
if ($cus.s -ne 200 -and $cus.s -ne 201) { Write-Host "[FATAL] register: $($cus.s) $($cus.b)"; exit 1 }
$tok = (ConvertFrom-Json $cus.b).data.token
$H = @{ Authorization = "Bearer $tok"; 'X-Binge-Id' = '1' }

$d = (Get-Date).AddDays((Get-Random -Min 40 -Max 300)).ToString('yyyy-MM-dd')
Write-Host "Test date: $d"
$pass = 0; $fail = 0
function Check($name, $cond, $detail) {
  if ($cond) { Write-Host "[PASS] $name"; $script:pass++ } else { Write-Host "[FAIL] $name :: $detail"; $script:fail++ }
}

$cur = Get-Json "$gw/api/v1/bookings/binges/1" $AH
$c = $cur.data.data
$base = @{
  name = $c.name; address = $c.address;
  supportEmail = $c.supportEmail; supportPhone = $c.supportPhone; supportWhatsapp = $c.supportWhatsapp;
  customerCancellationEnabled = $c.customerCancellationEnabled;
  customerCancellationCutoffMinutes = $c.customerCancellationCutoffMinutes;
  maxConcurrentBookings = $c.maxConcurrentBookings
}
Write-Host "----- Initial: open=$($c.openTime) close=$($c.closeTime) -----"

$body = $base.Clone(); $body.openTime='22:00'; $body.closeTime='10:00'
$r = Put "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Check "T1 close<open rejected" ($r.s -eq 400) "got $($r.s)"

$body = $base.Clone(); $body.openTime='12:00'; $body.closeTime='12:00'
$r = Put "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Check "T2 close==open rejected" ($r.s -eq 400) "got $($r.s)"

$body = $base.Clone(); $body.openTime='25:99'; $body.closeTime='30:30'
$r = Put "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Check "T3 garbage time rejected" ($r.s -eq 400) "got $($r.s)"

$body = $base.Clone(); $body.openTime='10:00'; $body.closeTime='21:45'
$r = Put "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Check "T4a admin set 10:00-21:45" ($r.s -eq 200) "got $($r.s)"
Start-Sleep 35
$sl = Get-Json "$gw/api/v1/availability/slots?date=$d" $H
$slots = $sl.data.data.availableSlots
Check "T4b grid first=10:00-10:30" ($slots[0].label -eq '10:00 - 10:30') "got $($slots[0].label)"
Check "T4c grid last=21:00-21:30 (truncated)" ($slots[-1].label -eq '21:00 - 21:30') "got $($slots[-1].label)"
Check "T4d grid count=23" ($slots.Count -eq 23) "got $($slots.Count)"

$bk = @{eventTypeId=8;bookingDate=$d;startTime='21:15';durationMinutes=30;numberOfGuests=1;addOns=@()} | ConvertTo-Json
$r = Post "$gw/api/v1/bookings" $bk $H
Check "T5 booking end==close (21:15+30=21:45) accepted" (($r.s -eq 200) -or ($r.s -eq 201)) "got $($r.s) :: $($r.b.Substring(0,[Math]::Min(120,$r.b.Length)))"

$bk = @{eventTypeId=8;bookingDate=$d;startTime='21:00';durationMinutes=46;numberOfGuests=1;addOns=@()} | ConvertTo-Json
$r = Post "$gw/api/v1/bookings" $bk $H
Check "T6 booking end>close rejected" ($r.s -eq 400) "got $($r.s)"

$bk = @{eventTypeId=8;bookingDate=$d;startTime='12:00';durationMinutes=0;numberOfGuests=1;addOns=@()} | ConvertTo-Json
$r = Post "$gw/api/v1/bookings" $bk $H
Check "T7 duration=0 rejected" ($r.s -eq 400) "got $($r.s)"

$bk = @{eventTypeId=8;bookingDate=$d;startTime='12:00';durationMinutes=-30;numberOfGuests=1;addOns=@()} | ConvertTo-Json
$r = Post "$gw/api/v1/bookings" $bk $H
Check "T8 duration<0 rejected" ($r.s -eq 400) "got $($r.s)"

$body = $base.Clone(); $body.openTime='10:00'; $body.closeTime='23:00'
$r = Put "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $H
Check "T9 non-admin PUT forbidden" (($r.s -eq 401) -or ($r.s -eq 403)) "got $($r.s)"

$body = $base.Clone(); $body.openTime='09:00:00'; $body.closeTime='22:30:00'
$r = Put "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Check "T10 HH:mm:ss accepted" ($r.s -eq 200) "got $($r.s)"

Start-Sleep 35
$t0 = Get-Date
for ($i=0; $i -lt 10; $i++) { $null = Get-Json "$gw/api/v1/availability/slots?date=$d" $H }
$ms = [int](New-TimeSpan -Start $t0 -End (Get-Date)).TotalMilliseconds
Check "T11 cache perf <8000ms for 10 calls" ($ms -lt 8000) "got $ms ms"
Write-Host "    (perf $ms ms total, avg $([int]($ms/10)) ms/call)"

$body = $base.Clone(); $body.openTime='08:00'; $body.closeTime='20:00'
$null = Put "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Start-Sleep 32
$sl = Get-Json "$gw/api/v1/availability/slots?date=$d" $H
$slots = $sl.data.data.availableSlots
Check "T12a after-TTL grid first=08:00" ($slots[0].label -eq '08:00 - 08:30') "got $($slots[0].label)"
Check "T12b after-TTL grid last=19:30-20:00" ($slots[-1].label -eq '19:30 - 20:00') "got $($slots[-1].label)"

$bk = @{eventTypeId=8;bookingDate=$d;startTime='07:00';durationMinutes=60;numberOfGuests=1;addOns=@()} | ConvertTo-Json
$r = Post "$gw/api/v1/bookings" $bk $H
Check "T13 booking before new openTime rejected" ($r.s -eq 400) "got $($r.s)"

$bk = @{eventTypeId=8;bookingDate=$d;startTime='14:00';durationMinutes=60;numberOfGuests=1;addOns=@()} | ConvertTo-Json
$r = Post "$gw/api/v1/bookings" $bk $H
Check "T14 booking inside new window accepted" (($r.s -eq 200) -or ($r.s -eq 201)) "got $($r.s) :: $($r.b.Substring(0,[Math]::Min(200,$r.b.Length)))"

$body = $base.Clone()
$body.name = "X'; DROP TABLE binges;--"
$body.openTime='10:00'; $body.closeTime='23:00'
$null = Put "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
$verify = Get-Json "$gw/api/v1/bookings/binges/1" $AH
Check "T15 SQLi harmless (table still exists)" ($verify.s -eq 200) "got $($verify.s)"

$body = $base.Clone(); $body.openTime='10:00'; $body.closeTime='23:00'
$null = Put "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
$final = Get-Json "$gw/api/v1/bookings/binges/1" $AH
$fc = $final.data.data
Write-Host "----- Restored: name=$($fc.name) open=$($fc.openTime) close=$($fc.closeTime) -----"

Write-Host ""
Write-Host "================================="
Write-Host "RESULTS:  PASS=$pass  FAIL=$fail"
Write-Host "================================="
if ($fail -gt 0) { exit 1 }
