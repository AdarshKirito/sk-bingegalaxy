# Worst-case live stress test - ASCII only, hardcoded valid IDs
$ErrorActionPreference = 'Continue'
$gw = 'http://localhost:8080'
$bingeId = 1
$eventTypeId = 8
$results = @()

function Add-Result($id, $name, $expected, $actual, $verdict, $note='') {
  $script:results += [pscustomobject]@{Id=$id;Name=$name;Expected=$expected;Actual=$actual;Verdict=$verdict;Note=$note}
  $color = 'Yellow'
  if ($verdict -eq 'PASS') { $color = 'Green' }
  if ($verdict -eq 'FAIL') { $color = 'Red' }
  Write-Host ("[{0,-5}] {1,-12} {2,-40} expected={3,-25} actual={4,-12} {5}" -f $verdict,$id,$name,$expected,$actual,$note) -ForegroundColor $color
}
function Hit($method, $url, $headers, $body=$null, $timeout=15) {
  try {
    $params = @{ Uri=$url; Method=$method; Headers=$headers; UseBasicParsing=$true; TimeoutSec=$timeout; ErrorAction='Stop' }
    if ($null -ne $body) { $params.Body = ($body | ConvertTo-Json -Depth 8 -Compress); $params.ContentType = 'application/json; charset=utf-8' }
    $r = Invoke-WebRequest @params
    return @{ ok=$true; status=$r.StatusCode; body=$r.Content }
  } catch {
    $resp = $_.Exception.Response
    if ($resp) {
      try { $sr = New-Object System.IO.StreamReader($resp.GetResponseStream()); $b = $sr.ReadToEnd() } catch { $b = '' }
      return @{ ok=$false; status=$resp.StatusCode.value__; body=$b }
    }
    return @{ ok=$false; status=-1; body=$_.Exception.Message }
  }
}

Write-Host "`n========== STAGE 0: Setup ==========" -ForegroundColor Cyan
$adminLogin = Hit POST "$gw/api/v1/auth/login" @{} @{ email='admin@skbingegalaxy.com'; password='Admin@123Local' }
if (-not $adminLogin.ok) { Write-Host "FATAL: admin login failed status=$($adminLogin.status)"; exit 1 }
$adminTok = (ConvertFrom-Json $adminLogin.body).data.token
$AH = @{ Authorization = "Bearer $adminTok"; 'X-Binge-Id' = "$bingeId" }
Write-Host "Admin OK"

function NewCustomer($prefix) {
  $em = $prefix + '_' + (Get-Random) + '@example.com'
  $body = @{ firstName=$prefix; lastName='User'; email=$em; password='Test@123Secure'; phone=('9'+(Get-Random -Min 100000000 -Max 999999999).ToString()) }
  $r = Hit POST "$gw/api/v1/auth/register" @{} $body
  if (-not $r.ok) { throw "register failed: $($r.body)" }
  $j = ConvertFrom-Json $r.body
  return @{ id=$j.data.user.id; email=$em; token=$j.data.token }
}
$c1 = NewCustomer 'alice'
$c2 = NewCustomer 'mallory'
$AB = @{ Authorization = "Bearer $($c1.token)"; 'X-Binge-Id' = "$bingeId" }
$MB = @{ Authorization = "Bearer $($c2.token)"; 'X-Binge-Id' = "$bingeId" }
Write-Host "Alice id=$($c1.id) Mallory id=$($c2.id)"

$tomorrow = (Get-Date).AddDays(3).ToString('yyyy-MM-dd')   # 3 days out (clear of cancel cutoff windows)
Write-Host "Test date=$tomorrow eventTypeId=$eventTypeId bingeId=$bingeId"

Write-Host "`n========== STAGE 1: Booking creation worst cases ==========" -ForegroundColor Cyan
# B1 baseline
$bk = @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='14:00'; durationMinutes=120; numberOfGuests=2; addOns=@(); specialNotes='baseline' }
$r = Hit POST "$gw/api/v1/bookings" $AB $bk
$aliceRef = $null
if ($r.ok) {
  $aliceRef = (ConvertFrom-Json $r.body).data.bookingRef
  Add-Result B1 'create-baseline' '200' $r.status 'PASS' "ref=$aliceRef"
} else { Add-Result B1 'create-baseline' '200' $r.status 'FAIL' $r.body.Substring(0,[Math]::Min(160,$r.body.Length)) }

# B2 past date
$r = Hit POST "$gw/api/v1/bookings" $AB @{ eventTypeId=$eventTypeId; bookingDate=(Get-Date).AddDays(-1).ToString('yyyy-MM-dd'); startTime='14:00'; durationMinutes=120; numberOfGuests=2; addOns=@() }
$v = if ($r.status -eq 400) {'PASS'} else {'FAIL'}; Add-Result B2 'past-date-rejected' '400' $r.status $v

# B3 horizon
$r = Hit POST "$gw/api/v1/bookings" $AB @{ eventTypeId=$eventTypeId; bookingDate='2032-01-01'; startTime='14:00'; durationMinutes=120; numberOfGuests=2; addOns=@() }
$v = if ($r.status -eq 400) {'PASS'} else {'FAIL'}; Add-Result B3 'far-future-rejected' '400' $r.status $v

# B4 negative guests
$r = Hit POST "$gw/api/v1/bookings" $AB @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='15:00'; durationMinutes=120; numberOfGuests=-3; addOns=@() }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}; Add-Result B4 'negative-guests' '4xx' $r.status $v

# B5 zero duration (likely below min)
$r = Hit POST "$gw/api/v1/bookings" $AB @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='16:00'; durationMinutes=0; numberOfGuests=1; addOns=@() }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}; Add-Result B5 'zero-duration' '4xx' $r.status $v

# B6 huge guests
$r = Hit POST "$gw/api/v1/bookings" $AB @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='17:00'; durationMinutes=120; numberOfGuests=999999; addOns=@() }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}; Add-Result B6 'huge-guests' '4xx' $r.status $v

# B7 bogus event type
$r = Hit POST "$gw/api/v1/bookings" $AB @{ eventTypeId=999999; bookingDate=$tomorrow; startTime='18:00'; durationMinutes=120; numberOfGuests=1; addOns=@() }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}; Add-Result B7 'bogus-event-type' '4xx' $r.status $v

# B8 dedupe
$r = Hit POST "$gw/api/v1/bookings" $AB @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='14:00'; durationMinutes=120; numberOfGuests=2; addOns=@() }
$dedupe = ($r.status -eq 400 -and $r.body -match 'pending|already|duplicate')
$v = if ($dedupe -or $r.status -eq 409) {'PASS'} else {'FAIL'}
Add-Result B8 'content-dedupe' '400 pending' $r.status $v $r.body.Substring(0,[Math]::Min(140,$r.body.Length))

# B9 idempotency replay (different time so no dedupe collision)
$idemKey = [guid]::NewGuid().ToString()
$AB_idem = @{ Authorization = "Bearer $($c1.token)"; 'X-Binge-Id' = "$bingeId"; 'Idempotency-Key' = $idemKey }
$bk9 = @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='09:00'; durationMinutes=120; numberOfGuests=1; addOns=@() }
$r1 = Hit POST "$gw/api/v1/bookings" $AB_idem $bk9
$r2 = Hit POST "$gw/api/v1/bookings" $AB_idem $bk9
if ($r1.ok -and $r2.ok) {
  $ref1 = (ConvertFrom-Json $r1.body).data.bookingRef
  $ref2 = (ConvertFrom-Json $r2.body).data.bookingRef
  $v = if ($ref1 -eq $ref2) {'PASS'} else {'FAIL'}
  Add-Result B9 'idempotency-replay' 'same ref' "$ref1=$ref2" $v
} else { Add-Result B9 'idempotency-replay' 'both 200' "$($r1.status)/$($r2.status)" 'FAIL' "r1=$($r1.body.Substring(0,[Math]::Min(80,$r1.body.Length)))" }

Write-Host "`n========== STAGE 2: Concurrent same-slot race ==========" -ForegroundColor Cyan
# B10 concurrent race for slot 11:00
Write-Host "  spawning 6 concurrent bookings for slot 11:00..."
$jobs = @()
for ($i=0; $i -lt 6; $i++) {
  $jobs += Start-Job -ArgumentList $gw, $bingeId, $eventTypeId, $tomorrow -ScriptBlock {
    param($gw, $bid, $eid, $date)
    try {
      $em = "race$([guid]::NewGuid().ToString().Substring(0,8))@example.com"
      $reg = @{ firstName='Race'; lastName='User'; email=$em; password='Test@123Secure'; phone=('9'+(Get-Random -Min 100000000 -Max 999999999).ToString()) } | ConvertTo-Json
      $r = Invoke-RestMethod -Uri "$gw/api/v1/auth/register" -Method POST -Body $reg -ContentType 'application/json'
      $tok = $r.data.token
      $bk = @{ eventTypeId=$eid; bookingDate=$date; startTime='11:00'; durationMinutes=120; numberOfGuests=1; addOns=@() } | ConvertTo-Json -Compress
      $h = @{ Authorization = "Bearer $tok"; 'X-Binge-Id' = "$bid" }
      $resp = Invoke-WebRequest -Uri "$gw/api/v1/bookings" -Method POST -Body $bk -Headers $h -ContentType 'application/json' -UseBasicParsing -TimeoutSec 30
      return "OK $($resp.StatusCode)"
    } catch { $rs=$_.Exception.Response; if ($rs) { return "ERR $($rs.StatusCode.value__)" } else { return "EX $($_.Exception.Message.Substring(0,40))" } }
  }
}
$jobs | Wait-Job -Timeout 90 | Out-Null
$out = $jobs | ForEach-Object { Receive-Job $_ }
$jobs | Remove-Job -Force
$okCount = ($out | Where-Object { $_ -like 'OK*' }).Count
$errCount = ($out | Where-Object { $_ -notlike 'OK*' }).Count
Write-Host "  results: OK=$okCount ERR=$errCount :: $($out -join ' | ')"
# Capacity check via admin
$cap = Hit GET "$gw/api/v1/bookings/slot-capacity?date=$tomorrow&startMinute=660&durationMinutes=120" $AH
$capInfo = if ($cap.ok) { $cap.body } else { 'cap-fail' }
$v = if ($okCount -gt 0 -and $errCount -ge 0) {'PASS'} else {'FAIL'}
Add-Result B10 'concurrent-race' "no overcommit" "OK=$okCount ERR=$errCount" $v $capInfo.Substring(0,[Math]::Min(160,$capInfo.Length))

Write-Host "`n========== STAGE 3: Cross-customer (IDOR) ==========" -ForegroundColor Cyan
if ($aliceRef) {
  $r = Hit GET "$gw/api/v1/bookings/$aliceRef" $MB
  $v = if ($r.status -eq 403 -or $r.status -eq 404) {'PASS'} else {'FAIL'}
  Add-Result I1 'mallory-read-alice' '403/404' $r.status $v

  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/cancel" $MB @{ reason='hijack' }
  $v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
  Add-Result I2 'mallory-cancel-alice' '403' $r.status $v

  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/reschedule" $MB @{ newBookingDate=$tomorrow; newStartTime='20:00' }
  $v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
  Add-Result I3 'mallory-reschedule-alice' '403' $r.status $v

  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/transfer" $MB @{ recipientName='M'; recipientEmail=$c2.email; recipientPhone='9999999999' }
  $v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
  Add-Result I4 'mallory-transfer-alice' '403' $r.status $v

  $r = Hit GET "$gw/api/v1/payments/booking/$aliceRef" $MB
  $v = if ($r.status -eq 403 -or $r.status -eq 404) {'PASS'} else {'FAIL'}
  Add-Result I5 'mallory-read-alice-payment' '403/404' $r.status $v
}

# Customer hits admin endpoints
$r = Hit POST "$gw/api/v1/bookings/admin/create-booking" $AB @{ customerName='hack'; customerEmail='h@x.com'; eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='21:00'; durationMinutes=120; numberOfGuests=1; paymentMethod='CASH' }
$v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}; Add-Result I6 'cust-admin-walkin' '403' $r.status $v

$r = Hit POST "$gw/api/v1/payments/admin/refund" $AB @{ paymentId=1; amount=100; reason='hack' }
$v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}; Add-Result I7 'cust-admin-refund' '403' $r.status $v

$r = Hit POST "$gw/api/v1/payments/admin/record-cash" $AB @{ bookingRef='X'; amount=100; description='h' }
$v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}; Add-Result I8 'cust-admin-cash' '403' $r.status $v

# Forged X-User
$forged = @{ Authorization = "Bearer $($c1.token)"; 'X-Binge-Id'="$bingeId"; 'X-User-Id'='1'; 'X-User-Role'='SUPER_ADMIN' }
$r = Hit GET "$gw/api/v1/bookings/admin/dashboard-stats" $forged
$v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}; Add-Result I9 'forged-x-user-role' '403' $r.status $v

Write-Host "`n========== STAGE 4: Cancel & refund ==========" -ForegroundColor Cyan
if ($aliceRef) {
  # C1 cancel own
  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/cancel" $AB @{ reason='change of plans' }
  $v = if ($r.ok) {'PASS'} else {'FAIL'}
  Add-Result C1 'cancel-own' '200' $r.status $v $r.body.Substring(0,[Math]::Min(120,$r.body.Length))

  # C2 cancel again
  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/cancel" $AB @{ reason='retry' }
  $v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
  Add-Result C2 'cancel-twice' '4xx' $r.status $v
}
$r = Hit POST "$gw/api/v1/bookings/BOGUS-XYZ/cancel" $AB @{ reason='nope' }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result C3 'cancel-bogus-ref' '4xx' $r.status $v

$r = Hit POST "$gw/api/v1/payments/admin/refund" $AH @{ paymentId=1; amount=-1000; reason='neg' }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result C5 'admin-refund-neg' '4xx' $r.status $v

$r = Hit POST "$gw/api/v1/payments/admin/refund" $AH @{ paymentId=1; amount=99999999; reason='excess' }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result C6 'admin-refund-excess' '4xx' $r.status $v

Write-Host "`n========== STAGE 5: Payment surface ==========" -ForegroundColor Cyan
$r = Hit POST "$gw/api/v1/payments/initiate" $AB @{ bookingRef='DOES-NOT-EXIST'; amount=100; paymentMethod='CARD' }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result P1 'initiate-bogus' '4xx' $r.status $v

$r = Hit POST "$gw/api/v1/payments/initiate" $AB @{ bookingRef='X'; amount=-500; paymentMethod='CARD' }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result P2 'initiate-negative' '4xx' $r.status $v

$r = Hit POST "$gw/api/v1/payments/initiate" $AB @{ bookingRef='X'; amount=999999999999; paymentMethod='CARD' }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result P3 'initiate-huge' '4xx' $r.status $v

$r = Hit POST "$gw/api/v1/payments/callback" @{} @{ transactionId='HACK'; status='SUCCESS'; amount=50000 }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result P4 'webhook-tamper' '4xx' $r.status $v

$r = Hit GET "$gw/api/v1/payments/my?page=0&size=10" $AB
$v = if ($r.ok) {'PASS'} else {'FAIL'}
Add-Result P5 'list-my-payments' '200' $r.status $v

$r = Hit POST "$gw/api/v1/payments/cancel/NONEXISTENT-TX" $AB $null
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result P6 'cancel-bogus-tx' '4xx' $r.status $v

Write-Host "`n========== STAGE 6: Reschedule guards ==========" -ForegroundColor Cyan
$bkR = @{ eventTypeId=$eventTypeId; bookingDate=(Get-Date).AddDays(7).ToString('yyyy-MM-dd'); startTime='09:00'; durationMinutes=120; numberOfGuests=1; addOns=@() }
$r = Hit POST "$gw/api/v1/bookings" $AB $bkR
if ($r.ok) {
  $rRef = (ConvertFrom-Json $r.body).data.bookingRef
  Write-Host "  Reschedule test booking ref=$rRef"

  # R1 empty body
  $r = Hit POST "$gw/api/v1/bookings/$rRef/reschedule" $AB @{}
  $v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
  Add-Result R1 'resched-empty' '4xx' $r.status $v

  # R2 to past
  $r = Hit POST "$gw/api/v1/bookings/$rRef/reschedule" $AB @{ newBookingDate=(Get-Date).AddDays(-2).ToString('yyyy-MM-dd'); newStartTime='09:00' }
  $v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
  Add-Result R2 'resched-past' '4xx' $r.status $v

  # R3 garbage time
  $r = Hit POST "$gw/api/v1/bookings/$rRef/reschedule" $AB @{ newBookingDate=(Get-Date).AddDays(8).ToString('yyyy-MM-dd'); newStartTime='99:99' }
  $v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
  Add-Result R3 'resched-garbage' '4xx' $r.status $v
} else { Write-Host "  reschedule setup failed: $($r.body.Substring(0,160))" }

Write-Host "`n========== STAGE 7: Waitlist ==========" -ForegroundColor Cyan
$r = Hit POST "$gw/api/v1/bookings/waitlist" $AB @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='13:00'; durationMinutes=120; numberOfGuests=1 }
$wlEntry = $null
if ($r.ok) {
  $wj = ConvertFrom-Json $r.body
  $wlEntry = $wj.data.id
  Add-Result W1 'join-waitlist' '200' $r.status 'PASS' "entry=$wlEntry"
} else { Add-Result W1 'join-waitlist' '200' $r.status 'INFO' $r.body.Substring(0,[Math]::Min(140,$r.body.Length)) }

$r = Hit POST "$gw/api/v1/bookings/waitlist" $AB @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='13:00'; durationMinutes=120; numberOfGuests=1 }
$v = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'INFO'}
Add-Result W2 'waitlist-dup' '4xx' $r.status $v

if ($wlEntry) {
  $r = Hit DELETE "$gw/api/v1/bookings/waitlist/$wlEntry" $MB $null
  $v = if ($r.status -eq 403 -or $r.status -eq 404) {'PASS'} else {'FAIL'}
  Add-Result W3 'mallory-leave-alice-wl' '403/404' $r.status $v

  $r = Hit DELETE "$gw/api/v1/bookings/waitlist/$wlEntry" $AB $null
  $v = if ($r.ok -or $r.status -eq 204) {'PASS'} else {'FAIL'}
  Add-Result W4 'alice-leave-wl' '200/204' $r.status $v
}

$r = Hit GET "$gw/api/v1/bookings/waitlist/my" $AB
$v = if ($r.ok) {'PASS'} else {'FAIL'}
Add-Result W5 'list-my-wl' '200' $r.status $v $r.body.Substring(0,[Math]::Min(120,$r.body.Length))

$r = Hit GET "$gw/api/v1/bookings/waitlist/admin?date=$tomorrow" $AB
$v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
Add-Result W6 'cust-admin-wl' '403' $r.status $v

Write-Host "`n========== STAGE 8: Loyalty ==========" -ForegroundColor Cyan
$r = Hit GET "$gw/api/v1/bookings/loyalty" $AB
$v = if ($r.ok) {'PASS'} else {'FAIL'}
Add-Result L1 'loyalty-self' '200' $r.status $v

$r = Hit POST "$gw/api/v1/bookings/admin/loyalty/$($c1.id)/adjust" $MB @{ pointsDelta=999999; reason='hack' }
$v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
Add-Result L2 'cust-loyalty-adjust' '403' $r.status $v

foreach ($ep in @('/api/v2/loyalty/super-admin/program','/api/v2/loyalty/super-admin/tiers','/api/v2/loyalty/super-admin/perks','/api/v2/loyalty/admin/bindings/1')) {
  $r = Hit GET "$gw$ep" $AB $null
  $v = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
  $tag = 'L3-' + ($ep -replace '/','_').Substring(1,[Math]::Min(40,$ep.Length-1))
  Add-Result $tag 'v2-cust-blocked' '403' $r.status $v
}

Write-Host "`n========== STAGE 9: Rate limit ==========" -ForegroundColor Cyan
$rlh = 0; $rlc = 0
for ($i=0; $i -lt 35; $i++) {
  $r = Hit POST "$gw/api/v1/auth/login" @{} @{ email='nx_'+(Get-Random)+'@x.com'; password='bad' } 5
  if ($r.status -eq 429) { $rlh++ } else { $rlc++ }
}
$v = if ($rlh -gt 0) {'PASS'} else {'INFO'}
Add-Result RL1 'auth-rate-limit' '>=1 429' "got $rlh of 35" $v

Write-Host "`n========== Summary ==========" -ForegroundColor Cyan
$results | Format-Table -AutoSize
$pass = ($results | Where-Object Verdict -eq 'PASS').Count
$fail = ($results | Where-Object Verdict -eq 'FAIL').Count
$info = ($results | Where-Object Verdict -eq 'INFO').Count
$total = $results.Count
$totColor = 'Green'
if ($fail -gt 0) { $totColor = 'Red' }
Write-Host "TOTAL=$total PASS=$pass FAIL=$fail INFO=$info" -ForegroundColor $totColor
$results | ConvertTo-Json -Depth 4 | Out-File 'stress-worstcase-results.json' -Encoding utf8
