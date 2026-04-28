# Worst-case live stress test: booking, cancel, refund, payment, waitlist, IDOR/race/idempotency.
# Runs against http://localhost:8080. Requires docker stack already up.
# Outputs PASS/FAIL/INFO lines for each scenario.

$ErrorActionPreference = 'Continue'
$gw = 'http://localhost:8080'
$results = @()
function Add-Result($id, $name, $expected, $actual, $verdict, $note='') {
  $script:results += [pscustomobject]@{Id=$id;Name=$name;Expected=$expected;Actual=$actual;Verdict=$verdict;Note=$note}
  $color = if ($verdict -eq 'PASS') {'Green'} elseif ($verdict -eq 'FAIL') {'Red'} else {'Yellow'}
  Write-Host ("[{0,-5}] {1} :: {2}  expected={3} actual={4} {5}" -f $verdict,$id,$name,$expected,$actual,$note) -ForegroundColor $color
}
function Hit($method, $url, $headers, $body=$null, $timeout=10) {
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

# --- Admin login ---
$adminLogin = Hit POST "$gw/api/v1/auth/login" @{} @{ email='admin@skbingegalaxy.com'; password='Admin@123Local' }
if (-not $adminLogin.ok) { Write-Host "FATAL: admin login failed status=$($adminLogin.status) body=$($adminLogin.body)" -ForegroundColor Red; exit 1 }
$adminTok = (ConvertFrom-Json $adminLogin.body).data.token
$AH = @{ Authorization = "Bearer $adminTok" }
Write-Host "Admin OK"

# --- Two fresh customers ---
function NewCustomer($prefix) {
  $em = "$prefix" + "_" + (Get-Random) + "@example.com"
  $body = @{ firstName=$prefix; lastName='User'; email=$em; password='Test@123Secure'; phone=('9'+(Get-Random -Min 100000000 -Max 999999999).ToString()) }
  $r = Hit POST "$gw/api/v1/auth/register" @{} $body
  if (-not $r.ok) { throw "register failed: $($r.body)" }
  $j = ConvertFrom-Json $r.body
  return @{ id=$j.data.user.id; email=$em; token=$j.data.token }
}
$c1 = NewCustomer 'alice'
$c2 = NewCustomer 'mallory'
Write-Host "Customer1 id=$($c1.id) email=$($c1.email)"
Write-Host "Customer2 id=$($c2.id) email=$($c2.email)"

$AB = @{ Authorization = "Bearer $($c1.token)" }   # alice
$MB = @{ Authorization = "Bearer $($c2.token)" }   # mallory

# --- Discover binge + event types ---
$bingeId = $null
$listBinges = Hit GET "$gw/api/v1/binges" $AH
if ($listBinges.ok) {
  $bj = ConvertFrom-Json $listBinges.body
  $first = $bj.data | Select-Object -First 1
  if ($first) { $bingeId = $first.id }
}
if (-not $bingeId) {
  $listBinges = Hit GET "$gw/api/v1/binges/admin" $AH
  if ($listBinges.ok) { $bj = ConvertFrom-Json $listBinges.body; $first = $bj.data | Select-Object -First 1; if ($first) { $bingeId = $first.id } }
}
if (-not $bingeId) { Write-Host "Could not discover bingeId — defaulting to 1" -ForegroundColor Yellow; $bingeId = 1 }
Write-Host "Using bingeId=$bingeId"

$AH['X-Binge-Id'] = "$bingeId"
$AB['X-Binge-Id'] = "$bingeId"
$MB['X-Binge-Id'] = "$bingeId"

# Event types
$ev = Hit GET "$gw/api/v1/bookings/event-types" $AB
$eventTypeId = $null
if ($ev.ok) {
  $ej = ConvertFrom-Json $ev.body
  $first = $ej.data | Where-Object { $_.active -ne $false } | Select-Object -First 1
  if ($first) { $eventTypeId = $first.id }
}
if (-not $eventTypeId) { Write-Host "Could not discover eventTypeId — defaulting to 1" -ForegroundColor Yellow; $eventTypeId = 1 }
Write-Host "Using eventTypeId=$eventTypeId"

$tomorrow = (Get-Date).AddDays(2).ToString('yyyy-MM-dd')   # +2 days to satisfy cancel-cutoff window
$slot = '14:00'
Write-Host "Test slot date=$tomorrow time=$slot"

# ============================================================
Write-Host "`n========== STAGE 1: Booking creation worst cases ==========" -ForegroundColor Cyan

# B1: Create normal booking
$bk = @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime=$slot; durationMinutes=60; numberOfGuests=2; addOns=@(); specialNotes='baseline' }
$r = Hit POST "$gw/api/v1/bookings" $AB $bk
if ($r.ok) {
  $j = ConvertFrom-Json $r.body
  $aliceRef = $j.data.bookingRef
  Add-Result B1 'create-baseline' '201/200' $r.status 'PASS' "ref=$aliceRef"
} else {
  Add-Result B1 'create-baseline' '201/200' $r.status 'FAIL' $r.body.Substring(0,[Math]::Min(180,$r.body.Length))
}

# B2: Past-date booking
$bk2 = @{ eventTypeId=$eventTypeId; bookingDate=(Get-Date).AddDays(-1).ToString('yyyy-MM-dd'); startTime=$slot; durationMinutes=60; numberOfGuests=2; addOns=@() }
$r = Hit POST "$gw/api/v1/bookings" $AB $bk2
$verdict = if ($r.status -eq 400) {'PASS'} else {'FAIL'}
Add-Result B2 'past-date-rejected' '400' $r.status $verdict

# B3: 5-year-future booking (horizon)
$bk3 = @{ eventTypeId=$eventTypeId; bookingDate='2032-01-01'; startTime=$slot; durationMinutes=60; numberOfGuests=2; addOns=@() }
$r = Hit POST "$gw/api/v1/bookings" $AB $bk3
$verdict = if ($r.status -eq 400) {'PASS'} else {'FAIL'}
Add-Result B3 'far-future-rejected' '400' $r.status $verdict

# B4: Negative guests
$bk4 = @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='15:00'; durationMinutes=60; numberOfGuests=-3; addOns=@() }
$r = Hit POST "$gw/api/v1/bookings" $AB $bk4
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result B4 'negative-guests-rejected' '4xx' $r.status $verdict

# B5: Zero duration
$bk5 = @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='16:00'; durationMinutes=0; numberOfGuests=1; addOns=@() }
$r = Hit POST "$gw/api/v1/bookings" $AB $bk5
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result B5 'zero-duration-rejected' '4xx' $r.status $verdict

# B6: Huge guest count
$bk6 = @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='17:00'; durationMinutes=60; numberOfGuests=999999; addOns=@() }
$r = Hit POST "$gw/api/v1/bookings" $AB $bk6
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result B6 'huge-guests-rejected' '4xx' $r.status $verdict

# B7: Bogus eventTypeId
$bk7 = @{ eventTypeId=999999; bookingDate=$tomorrow; startTime='18:00'; durationMinutes=60; numberOfGuests=1; addOns=@() }
$r = Hit POST "$gw/api/v1/bookings" $AB $bk7
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result B7 'bogus-event-type' '4xx' $r.status $verdict

# B8: Content-based dedupe — second pending booking for same event+date+slot
$bk8 = @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime=$slot; durationMinutes=60; numberOfGuests=2; addOns=@(); specialNotes='dup' }
$r = Hit POST "$gw/api/v1/bookings" $AB $bk8
$verdict = if ($r.status -eq 400 -and $r.body -match 'pending|already|duplicate') {'PASS'} elseif ($r.status -eq 409) {'PASS'} else {'FAIL'}
Add-Result B8 'content-dedupe' '400 (pending duplicate)' $r.status $verdict ($r.body.Substring(0,[Math]::Min(120,$r.body.Length)))

# ============================================================
Write-Host "`n========== STAGE 2: Concurrency & idempotency ==========" -ForegroundColor Cyan

# B9: Idempotency-Key replay — first creates, second returns same ref (cached)
$idemKey = [guid]::NewGuid().ToString()
$AB2 = $AB.Clone(); $AB2['Idempotency-Key'] = $idemKey
$bk9 = @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='10:00'; durationMinutes=60; numberOfGuests=1; addOns=@() }
$r1 = Hit POST "$gw/api/v1/bookings" $AB2 $bk9
$r2 = Hit POST "$gw/api/v1/bookings" $AB2 $bk9
if ($r1.ok -and $r2.ok) {
  $ref1 = (ConvertFrom-Json $r1.body).data.bookingRef
  $ref2 = (ConvertFrom-Json $r2.body).data.bookingRef
  $verdict = if ($ref1 -eq $ref2) {'PASS'} else {'FAIL'}
  Add-Result B9 'idempotency-replay' "same ref" "$ref1 vs $ref2" $verdict
} else {
  Add-Result B9 'idempotency-replay' 'both 200' "$($r1.status)/$($r2.status)" 'FAIL'
}

# B10: Concurrent same-slot race — 8 customers, same event/date/time. Only one PENDING per slot.
Write-Host "B10: spawning 8 concurrent customers racing for slot 11:00..."
$jobs = @()
for ($i=0; $i -lt 8; $i++) {
  $jobs += Start-Job -ScriptBlock {
    param($gw, $bingeId, $eventTypeId, $tomorrow)
    try {
      $em = "race_${PID}_${using:i}_$(Get-Random)@example.com"
      $reg = @{ firstName='Race'; lastName='User'; email=$em; password='Test@123Secure'; phone=('9'+(Get-Random -Min 100000000 -Max 999999999).ToString()) } | ConvertTo-Json
      $r = Invoke-RestMethod -Uri "$gw/api/v1/auth/register" -Method POST -Body $reg -ContentType 'application/json'
      $tok = $r.data.token
      $bk = @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='11:00'; durationMinutes=60; numberOfGuests=1; addOns=@() } | ConvertTo-Json -Compress
      $headers = @{ Authorization = "Bearer $tok"; 'X-Binge-Id' = "$bingeId" }
      $resp = Invoke-WebRequest -Uri "$gw/api/v1/bookings" -Method POST -Body $bk -Headers $headers -ContentType 'application/json' -UseBasicParsing -TimeoutSec 30
      return "OK $($resp.StatusCode)"
    } catch {
      $r = $_.Exception.Response
      if ($r) { return "ERR $($r.StatusCode.value__)" }
      return "EX $($_.Exception.Message)"
    }
  } -ArgumentList $gw, $bingeId, $eventTypeId, $tomorrow
}
$jobs | Wait-Job -Timeout 60 | Out-Null
$out = $jobs | ForEach-Object { Receive-Job $_ }
$jobs | Remove-Job -Force
$okCount = ($out | Where-Object { $_ -like 'OK*' }).Count
$errCount = ($out | Where-Object { $_ -notlike 'OK*' }).Count
Write-Host "  Race results: $okCount OK, $errCount ERR :: $($out -join ', ')"
# Check live state — count PENDING bookings for this slot via admin
$slotInfo = Hit GET "$gw/api/v1/bookings/slot-capacity?date=$tomorrow&startMinute=660&durationMinutes=60" $AH
$verdict = 'INFO'
$note = "okCount=$okCount  -- check capacity output"
if ($slotInfo.ok) { $note += " :: " + $slotInfo.body.Substring(0,[Math]::Min(160,$slotInfo.body.Length)) }
# Note: capacity may allow >1 if maxConcurrentBookings>1; our concern is no DB-corruption, no overcommit.
Add-Result B10 'concurrent-race-no-overcommit' 'okCount<=maxConcurrent' "okCount=$okCount" $verdict $note

# ============================================================
Write-Host "`n========== STAGE 3: Cross-customer (IDOR) ==========" -ForegroundColor Cyan

if ($aliceRef) {
  # I1: Mallory tries to GET Alice's booking
  $r = Hit GET "$gw/api/v1/bookings/$aliceRef" $MB
  $verdict = if ($r.status -eq 403 -or $r.status -eq 404) {'PASS'} else {'FAIL'}
  Add-Result I1 'mallory-read-alice-booking' '403 or 404' $r.status $verdict

  # I2: Mallory tries to cancel Alice's booking
  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/cancel" $MB @{ reason='hijack' }
  $verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
  Add-Result I2 'mallory-cancel-alice' '403' $r.status $verdict ($r.body.Substring(0,[Math]::Min(120,$r.body.Length)))

  # I3: Mallory tries to reschedule Alice's booking
  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/reschedule" $MB @{ newBookingDate=$tomorrow; newStartTime='20:00' }
  $verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
  Add-Result I3 'mallory-reschedule-alice' '403' $r.status $verdict ($r.body.Substring(0,[Math]::Min(120,$r.body.Length)))

  # I4: Mallory transfers Alice's booking to herself
  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/transfer" $MB @{ recipientName='Mallory'; recipientEmail=$c2.email; recipientPhone='9999999999' }
  $verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
  Add-Result I4 'mallory-transfer-alice' '403' $r.status $verdict ($r.body.Substring(0,[Math]::Min(120,$r.body.Length)))

  # I5: Mallory tries to view Alice's payments (by booking ref)
  $r = Hit GET "$gw/api/v1/payments/booking/$aliceRef" $MB
  $verdict = if ($r.status -eq 403 -or $r.status -eq 404) {'PASS'} else {'FAIL'}
  Add-Result I5 'mallory-read-alice-payment' '403 or 404' $r.status $verdict
}

# I6: Customer hits admin walk-in
$r = Hit POST "$gw/api/v1/bookings/admin/create-booking" $AB @{ customerName='hack'; customerEmail='h@x.com'; eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='21:00'; durationMinutes=60; numberOfGuests=1; paymentMethod='CASH' }
$verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
Add-Result I6 'customer-hits-admin-walkin' '403' $r.status $verdict

# I7: Customer tries admin payment refund
$r = Hit POST "$gw/api/v1/payments/admin/refund" $AB @{ paymentId=1; amount=100; reason='hack' }
$verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
Add-Result I7 'customer-hits-admin-refund' '403' $r.status $verdict

# I8: Customer tries admin record-cash
$r = Hit POST "$gw/api/v1/payments/admin/record-cash" $AB @{ bookingRef='X'; amount=100; description='hack' }
$verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
Add-Result I8 'customer-hits-admin-record-cash' '403' $r.status $verdict

# I9: Forged X-User headers (gateway must strip + log)
$forged = $AB.Clone(); $forged['X-User-Id']='1'; $forged['X-User-Role']='SUPER_ADMIN'
$r = Hit GET "$gw/api/v1/bookings/admin/dashboard-stats" $forged
$verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
Add-Result I9 'forged-X-User-Role' '403 (gateway strips)' $r.status $verdict

# ============================================================
Write-Host "`n========== STAGE 4: Cancel & refund flow ==========" -ForegroundColor Cyan

# C1: Alice cancels her own booking
if ($aliceRef) {
  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/cancel" $AB @{ reason='change of plans' }
  $verdict = if ($r.ok) {'PASS'} else {'FAIL'}
  Add-Result C1 'cancel-own-booking' '200' $r.status $verdict ($r.body.Substring(0,[Math]::Min(120,$r.body.Length)))

  # C2: Cancel again (already cancelled)
  $r = Hit POST "$gw/api/v1/bookings/$aliceRef/cancel" $AB @{ reason='retry' }
  $verdict = if ($r.status -eq 400 -or $r.status -eq 409) {'PASS'} else {'FAIL'}
  Add-Result C2 'cancel-already-cancelled' '4xx' $r.status $verdict
}

# C3: Cancel non-existent booking
$r = Hit POST "$gw/api/v1/bookings/BOGUS-REF-XYZ/cancel" $AB @{ reason='nope' }
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result C3 'cancel-bogus-ref' '4xx' $r.status $verdict

# C4: Customer triggers refund directly (must be admin only)
$r = Hit POST "$gw/api/v1/payments/admin/refund" $MB @{ paymentId=999999; amount=999999; reason='free money' }
$verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
Add-Result C4 'customer-refund-rejected' '403' $r.status $verdict

# C5: Admin refund with negative amount
$r = Hit POST "$gw/api/v1/payments/admin/refund" $AH @{ paymentId=1; amount=-1000; reason='neg' }
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result C5 'admin-refund-negative' '4xx' $r.status $verdict

# C6: Admin refund > original (relies on existing payment id 1; if not present, skip)
$r = Hit POST "$gw/api/v1/payments/admin/refund" $AH @{ paymentId=1; amount=99999999; reason='excess' }
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'INFO'}
Add-Result C6 'admin-refund-overamount' '4xx' $r.status $verdict

# ============================================================
Write-Host "`n========== STAGE 5: Payment surface ==========" -ForegroundColor Cyan

# P1: Initiate payment for nonexistent booking
$r = Hit POST "$gw/api/v1/payments/initiate" $AB @{ bookingRef='DOES-NOT-EXIST'; amount=100; paymentMethod='CARD' }
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result P1 'initiate-bogus-booking' '4xx' $r.status $verdict

# P2: Negative amount payment
$r = Hit POST "$gw/api/v1/payments/initiate" $AB @{ bookingRef='X'; amount=-500; paymentMethod='CARD' }
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result P2 'initiate-negative-amount' '4xx' $r.status $verdict

# P3: Massive amount
$r = Hit POST "$gw/api/v1/payments/initiate" $AB @{ bookingRef='X'; amount=999999999999; paymentMethod='CARD' }
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result P3 'initiate-huge-amount' '4xx' $r.status $verdict

# P4: Public webhook tampering (no signature)
$r = Hit POST "$gw/api/v1/payments/callback" @{} @{ transactionId='HACK'; status='SUCCESS'; amount=50000 }
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'INFO'}
Add-Result P4 'webhook-no-signature' '4xx' $r.status $verdict ($r.body.Substring(0,[Math]::Min(120,$r.body.Length)))

# P5: List my payments — must return only mine
$r = Hit GET "$gw/api/v1/payments/my?page=0&size=10" $AB
$verdict = if ($r.ok) {'PASS'} else {'INFO'}
Add-Result P5 'list-my-payments' '200' $r.status $verdict

# P6: Cancel non-existent transaction
$r = Hit POST "$gw/api/v1/payments/cancel/NONEXISTENT-TX" $AB $null
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
Add-Result P6 'cancel-bogus-tx' '4xx' $r.status $verdict

# ============================================================
Write-Host "`n========== STAGE 6: Reschedule guards ==========" -ForegroundColor Cyan

# R1: Create booking, then reschedule too close to start (cutoff)
$rd = (Get-Date).AddHours(2).ToString('yyyy-MM-dd')
$rt = (Get-Date).AddHours(2).ToString('HH:00')
# Actually cutoff applies to the EXISTING booking's start. So: create booking in 2h, try to reschedule -> should be blocked.
# But we can't book < operational_date. So create booking 3 days out, then try reschedule to past.
$bkR = @{ eventTypeId=$eventTypeId; bookingDate=(Get-Date).AddDays(5).ToString('yyyy-MM-dd'); startTime='09:00'; durationMinutes=60; numberOfGuests=1; addOns=@() }
$r = Hit POST "$gw/api/v1/bookings" $AB $bkR
if ($r.ok) {
  $rRef = (ConvertFrom-Json $r.body).data.bookingRef

  # R1a: reschedule with empty body
  $r = Hit POST "$gw/api/v1/bookings/$rRef/reschedule" $AB @{}
  $verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
  Add-Result R1 'reschedule-empty-body' '4xx' $r.status $verdict

  # R2: reschedule to past
  $r = Hit POST "$gw/api/v1/bookings/$rRef/reschedule" $AB @{ newBookingDate=(Get-Date).AddDays(-2).ToString('yyyy-MM-dd'); newStartTime='09:00' }
  $verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
  Add-Result R2 'reschedule-to-past' '4xx' $r.status $verdict

  # R3: reschedule with garbage time
  $r = Hit POST "$gw/api/v1/bookings/$rRef/reschedule" $AB @{ newBookingDate=(Get-Date).AddDays(7).ToString('yyyy-MM-dd'); newStartTime='99:99' }
  $verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'FAIL'}
  Add-Result R3 'reschedule-garbage-time' '4xx' $r.status $verdict
}

# ============================================================
Write-Host "`n========== STAGE 7: Waitlist ==========" -ForegroundColor Cyan

# W1: Join waitlist
$r = Hit POST "$gw/api/v1/bookings/waitlist" $AB @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='13:00'; durationMinutes=60; numberOfGuests=1 }
$wlEntry = $null
if ($r.ok) {
  $wlEntry = (ConvertFrom-Json $r.body).data.id
  Add-Result W1 'join-waitlist' '200' $r.status 'PASS' "entry=$wlEntry"
} else {
  Add-Result W1 'join-waitlist' '200' $r.status 'INFO' ($r.body.Substring(0,[Math]::Min(140,$r.body.Length)))
}

# W2: Duplicate join
$r = Hit POST "$gw/api/v1/bookings/waitlist" $AB @{ eventTypeId=$eventTypeId; bookingDate=$tomorrow; startTime='13:00'; durationMinutes=60; numberOfGuests=1 }
$verdict = if ($r.status -ge 400 -and $r.status -lt 500) {'PASS'} else {'INFO'}
Add-Result W2 'waitlist-duplicate-rejected' '4xx' $r.status $verdict

# W3: Mallory tries to leave Alice's waitlist entry (IDOR)
if ($wlEntry) {
  $r = Hit DELETE "$gw/api/v1/bookings/waitlist/$wlEntry" $MB $null
  $verdict = if ($r.status -eq 403 -or $r.status -eq 404) {'PASS'} else {'FAIL'}
  Add-Result W3 'mallory-leave-alice-waitlist' '403/404' $r.status $verdict
}

# W4: Alice leaves her own
if ($wlEntry) {
  $r = Hit DELETE "$gw/api/v1/bookings/waitlist/$wlEntry" $AB $null
  $verdict = if ($r.ok -or $r.status -eq 204) {'PASS'} else {'FAIL'}
  Add-Result W4 'alice-leaves-waitlist' '200/204' $r.status $verdict
}

# W5: My waitlist
$r = Hit GET "$gw/api/v1/bookings/waitlist/my" $AB
$verdict = if ($r.ok) {'PASS'} else {'FAIL'}
Add-Result W5 'list-my-waitlist' '200' $r.status $verdict

# W6: Customer hits admin waitlist
$r = Hit GET "$gw/api/v1/bookings/waitlist/admin?date=$tomorrow" $AB
$verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
Add-Result W6 'customer-hits-admin-waitlist' '403' $r.status $verdict

# ============================================================
Write-Host "`n========== STAGE 8: Loyalty surface ==========" -ForegroundColor Cyan

# L1: Read own loyalty
$r = Hit GET "$gw/api/v1/bookings/loyalty" $AB
$verdict = if ($r.ok) {'PASS'} else {'FAIL'}
Add-Result L1 'loyalty-self-read' '200' $r.status $verdict

# L2: Customer hits admin adjust (mallory adjusts alice's points)
$r = Hit POST "$gw/api/v1/bookings/admin/loyalty/$($c1.id)/adjust" $MB @{ pointsDelta=999999; reason='hack' }
$verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
Add-Result L2 'customer-hits-loyalty-adjust' '403' $r.status $verdict

# L3: v2 super-admin endpoints (reverify hardening)
foreach ($ep in @('/api/v2/loyalty/super-admin/program','/api/v2/loyalty/super-admin/tiers','/api/v2/loyalty/super-admin/perks','/api/v2/loyalty/admin/bindings/1')) {
  $r = Hit GET "$gw$ep" $AB $null
  $verdict = if ($r.status -eq 403) {'PASS'} else {'FAIL'}
  Add-Result ("L3:" + $ep.Split('/')[-1]) 'v2-super-admin-customer' '403' $r.status $verdict
}

# ============================================================
Write-Host "`n========== STAGE 9: Rate limit & header tampering ==========" -ForegroundColor Cyan

# Rate limit auth (30/min per IP). Hammer 35 in quick succession.
$rlc = 0; $rlh = 0
for ($i=0; $i -lt 35; $i++) {
  $r = Hit POST "$gw/api/v1/auth/login" @{} @{ email='nonexistent_'+(Get-Random)+'@x.com'; password='bad' } 5
  if ($r.status -eq 429) { $rlh++ } else { $rlc++ }
}
$verdict = if ($rlh -gt 0) {'PASS'} else {'INFO'}
Add-Result RL1 'auth-rate-limit-fires' '>=1 429' "got $rlh of 35" $verdict

# ============================================================
Write-Host "`n========== Summary ==========" -ForegroundColor Cyan
$results | Format-Table -AutoSize
$pass = ($results | Where-Object Verdict -eq 'PASS').Count
$fail = ($results | Where-Object Verdict -eq 'FAIL').Count
$info = ($results | Where-Object Verdict -eq 'INFO').Count
Write-Host "`nTOTAL: PASS=$pass FAIL=$fail INFO=$info" -ForegroundColor (if ($fail -eq 0) {'Green'} else {'Red'})

# Write results to file for follow-up
$results | ConvertTo-Json -Depth 4 | Out-File 'stress-worstcase-results.json' -Encoding utf8
Write-Host "Results saved to stress-worstcase-results.json"
