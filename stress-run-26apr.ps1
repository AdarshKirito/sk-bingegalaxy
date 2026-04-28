# Stress Test Script - April 25, 2026
$ErrorActionPreference = 'Continue'
$gw = 'http://localhost:8080'
$report = @()

function Hit {
    param($method, $url, $body, $headers)
    $h = @{}
    if ($headers) { foreach ($k in $headers.Keys) { $h[$k] = $headers[$k] } }
    $params = @{ Uri = $url; Method = $method; ContentType = 'application/json'; ErrorAction = 'Stop'; UseBasicParsing = $true; Headers = $h; TimeoutSec = 30 }
    if ($body -ne $null) { $params.Body = ($body | ConvertTo-Json -Depth 10 -Compress) }
    try {
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        $sc = 0; $bs = $_.Exception.Message
        if ($resp -and $resp.GetResponseStream) {
            $sc = [int]$resp.StatusCode
            try {
                $stream = $resp.GetResponseStream()
                $reader = New-Object IO.StreamReader($stream)
                $bs = $reader.ReadToEnd()
            } catch {}
        }
        return @{ status = $sc; body = $bs }
    }
}

# Load tokens
Get-Content stress-tokens.txt | ForEach-Object {
    $kv = $_ -split '=', 2
    if ($kv.Length -eq 2) { Set-Variable -Name $kv[0] -Value $kv[1] }
}
$adminTok = (Get-Variable ADMIN -ValueOnly)
$custTok  = (Get-Variable CUST  -ValueOnly)
$custId   = (Get-Variable ID    -ValueOnly)
$custEmail= (Get-Variable EMAIL -ValueOnly)
Write-Host "USING: ADMIN len=$($adminTok.Length) CUST=$custId"

$AH = @{ Authorization = "Bearer $adminTok"; 'X-Binge-Id' = '1' }
$CH = @{ Authorization = "Bearer $custTok";  'X-Binge-Id' = '1' }
$AH_NoBinge = @{ Authorization = "Bearer $adminTok" }
$CH_NoBinge = @{ Authorization = "Bearer $custTok" }

function Log($scenario, $status, $resp, $sev='') {
    $excerpt = $resp; if ($excerpt.Length -gt 250) { $excerpt = $excerpt.Substring(0,250) + '...' }
    $line = "[$sev] $scenario => HTTP $status :: $excerpt"
    Write-Host $line
    Add-Content -Path stress-results.txt -Value $line
}

"== STRESS TEST RUN $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==" | Out-File stress-results.txt -Encoding ascii

# === Discovery ===
$evt = Hit 'GET' "$gw/api/v1/bookings/event-types" $null $CH
Log 'DISCOVER /event-types' $evt.status $evt.body

$addons = Hit 'GET' "$gw/api/v1/bookings/add-ons" $null $CH
Log 'DISCOVER /add-ons' $addons.status $addons.body

# Capture an event type id
try {
    $evtData = ($evt.body | ConvertFrom-Json).data
    $eventTypeId = $evtData[0].id
    Write-Host "Using eventTypeId=$eventTypeId"
} catch { $eventTypeId = 8 }

# Helper: build a booking body
function MakeBooking($date, $time, $guests=2, $duration=120) {
    return @{
        eventTypeId = $eventTypeId
        bookingDate = $date
        startTime = $time
        durationMinutes = $duration
        numberOfGuests = $guests
        addOns = @()
        specialNotes = "Stress test"
    }
}

# === A. Booking creation edge cases ===

# A2: past date
$past = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking '2024-01-01' '18:00') $CH
Log 'A2 Past-date booking (2024-01-01)' $past.status $past.body 'CRITICAL?'

# A3: far future (5 years)
$far = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking '2031-04-25' '18:00') $CH
Log 'A3 Far-future booking (2031)' $far.status $far.body

# A4a: zero guests
$zero = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking '2026-06-01' '18:00' 0) $CH
Log 'A4a Zero guests' $zero.status $zero.body

# A4b: negative guests
$neg = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking '2026-06-01' '18:00' -5) $CH
Log 'A4b Negative guests' $neg.status $neg.body

# A4c: 200 guests (>cap of 100)
$big = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking '2026-06-01' '18:00' 200) $CH
Log 'A4c 200 guests (over @Max=100)' $big.status $big.body

# A8: missing fields
$miss = Hit 'POST' "$gw/api/v1/bookings" @{ eventTypeId = $null } $CH
Log 'A8 Missing required fields' $miss.status $miss.body

# A8b: null event type
$miss2 = Hit 'POST' "$gw/api/v1/bookings" @{ eventTypeId = $null; bookingDate='2026-06-01'; startTime='18:00'; durationMinutes=120; numberOfGuests=2 } $CH
Log 'A8b Null eventTypeId' $miss2.status $miss2.body

# A8c: invalid duration < 30
$badDur = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking '2026-06-01' '18:00' 2 5) $CH
Log 'A8c Duration 5 minutes (< @Min=30)' $badDur.status $badDur.body

# A1: Double-submit with same idempotency key
$idemKey = [guid]::NewGuid().ToString()
$AH_idem = @{ Authorization = "Bearer $custTok"; 'X-Binge-Id' = '1'; 'Idempotency-Key' = $idemKey }
$dateA = '2026-08-15'
$d1 = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking $dateA '14:00') $AH_idem
$d2 = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking $dateA '14:00') $AH_idem
Log 'A1a Double-submit SAME idem key #1' $d1.status $d1.body
Log 'A1a Double-submit SAME idem key #2' $d2.status $d2.body

# A1b: Double-submit WITHOUT idem key (same body)
$d3 = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking '2026-09-01' '14:00') $CH
$d4 = Hit 'POST' "$gw/api/v1/bookings" (MakeBooking '2026-09-01' '14:00') $CH
Log 'A1b Double-submit NO idem #1' $d3.status $d3.body
Log 'A1b Double-submit NO idem #2' $d4.status $d4.body

# Capture a booking ref for later tests
$validBooking = $null
try {
    $validBooking = ($d3.body | ConvertFrom-Json).data
    Write-Host "VALID BOOKING REF: $($validBooking.bookingRef)"
} catch { Write-Host "Could not parse" }

# A5: race - simultaneous bookings same slot from same user
$racedate = '2026-10-10'
$raceTime = '20:00'
$jobs = 1..5 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($url, $body, $tok)
        try {
            $r = Invoke-WebRequest -Uri $url -Method POST -ContentType 'application/json' `
                -Headers @{ Authorization = "Bearer $tok"; 'X-Binge-Id' = '1' } `
                -Body $body -UseBasicParsing -ErrorAction Stop
            return "OK $($r.StatusCode)"
        } catch {
            $resp = $_.Exception.Response
            if ($resp) { return "ERR $([int]$resp.StatusCode) $(($_.ErrorDetails.Message | Out-String).Trim())" }
            return "ERR -- $($_.Exception.Message)"
        }
    } -ArgumentList "$gw/api/v1/bookings", ((MakeBooking $racedate $raceTime) | ConvertTo-Json -Compress), $custTok
}
$jobs | Wait-Job -Timeout 30 | Out-Null
$raceResults = $jobs | Receive-Job
$jobs | Remove-Job -Force
$rs = ($raceResults -join ' | ')
Log 'A5 Race - 5 concurrent same slot/user' '?' $rs

# === B. PAYMENT EDGE CASES ===
if ($validBooking) {
    $bref = $validBooking.bookingRef
    
    # B2a: Payment underpayment manipulation
    $payInit = Hit 'POST' "$gw/api/v1/payments/initiate" @{ bookingRef = $bref; amount = 1; method = 'UPI' } $CH
    Log "B2a Payment underpayment (amount=1)" $payInit.status $payInit.body 'HIGH?'

    # B2b: Payment overpayment
    $payOver = Hit 'POST' "$gw/api/v1/payments/initiate" @{ bookingRef = $bref; amount = 99999999; method = 'UPI' } $CH
    Log "B2b Payment overpayment 99999999" $payOver.status $payOver.body
    
    # B3: Replay callback - try simulating success twice
    try {
        $payJson = ($payInit.body | ConvertFrom-Json).data
        $txnId = $payJson.transactionId
        if ($txnId) {
            $sim1 = Hit 'POST' "$gw/api/v1/payments/admin/simulate/$txnId" @{ outcome='SUCCESS' } $AH
            $sim2 = Hit 'POST' "$gw/api/v1/payments/admin/simulate/$txnId" @{ outcome='SUCCESS' } $AH
            Log "B3a Replay payment simulate #1" $sim1.status $sim1.body
            Log "B3b Replay payment simulate #2" $sim2.status $sim2.body
        }
    } catch { Log "B3 Replay setup failed" 0 $_.Exception.Message }
}

# === C. AUTH / SESSION ===

# C1: expired JWT (use fake token)
$badTok = $custTok.Substring(0,$custTok.Length-5) + "XXXXX"
$BH = @{ Authorization = "Bearer $badTok"; 'X-Binge-Id' = '1' }
$bad = Hit 'GET' "$gw/api/v1/bookings/my" $null $BH
Log 'C1 Tampered JWT signature' $bad.status $bad.body

# C2: client tries to inject X-User-Id (gateway should strip)
$inject = @{ Authorization = "Bearer $custTok"; 'X-Binge-Id' = '1'; 'X-User-Id' = '6' }
$inj = Hit 'GET' "$gw/api/v1/bookings/my" $null $inject
Log 'C2 Inject X-User-Id=6 (admin)' $inj.status $inj.body

# C3: Customer A views Customer B booking by ref
if ($validBooking) {
    # Register a 2nd customer
    $ts2 = [DateTimeOffset]::Now.ToUnixTimeSeconds()
    $reg2 = Hit 'POST' "$gw/api/v1/auth/register" @{firstName='Other';lastName='User';email="other_$ts2@example.com";password='Aa1!aaaaaa';phone='9876500001'}
    try {
        $cust2Tok = (($reg2.body | ConvertFrom-Json).data.token)
        $C2H = @{ Authorization = "Bearer $cust2Tok"; 'X-Binge-Id' = '1' }
        $other = Hit 'GET' "$gw/api/v1/bookings/$($validBooking.bookingRef)" $null $C2H
        Log "C3 Customer B viewing Customer A booking" $other.status $other.body 'CRITICAL?'

        # C4: Cancel another customer's booking
        $cnc = Hit 'POST' "$gw/api/v1/bookings/$($validBooking.bookingRef)/cancel" @{ reason='evil' } $C2H
        Log "C4 Customer B cancels A booking" $cnc.status $cnc.body 'CRITICAL?'
    } catch { Log "C3/C4 setup failed" 0 $_.Exception.Message }
}

# === D. ADMIN EDGE CASES ===

# D3: ADMIN trying SUPER_ADMIN endpoint - we only have SUPER_ADMIN. Try anyway with v2 super-admin endpoint
$saTest = Hit 'GET' "$gw/api/v1/loyalty/v2/super-admin/program" $null $CH
Log 'D3a Customer hitting super-admin endpoint' $saTest.status $saTest.body

# D5: Admin GET loyalty without X-Binge-Id (already known issue)
$noBinge = Hit 'GET' "$gw/api/v1/loyalty/v2/admin/program" $null $AH_NoBinge
Log 'D5 Admin loyalty without X-Binge-Id' $noBinge.status $noBinge.body

# D4: extreme loyalty adjust values - find adjust endpoint
$adjustExtreme = Hit 'POST' "$gw/api/v1/loyalty/v2/admin/customers/$custId/adjust" @{ delta = 9223372036854775807; reason = 'long max' } $AH
Log 'D4a Loyalty adjust Long.MAX_VALUE' $adjustExtreme.status $adjustExtreme.body 'HIGH?'

$adjustNeg = Hit 'POST' "$gw/api/v1/loyalty/v2/admin/customers/$custId/adjust" @{ delta = -1000000; reason = 'huge neg' } $AH
Log 'D4b Loyalty adjust -1,000,000' $adjustNeg.status $adjustNeg.body

$adjustZero = Hit 'POST' "$gw/api/v1/loyalty/v2/admin/customers/$custId/adjust" @{ delta = 0; reason = 'zero' } $AH
Log 'D4c Loyalty adjust 0' $adjustZero.status $adjustZero.body

$adjustHuge = Hit 'POST' "$gw/api/v1/loyalty/v2/admin/customers/$custId/adjust" @{ delta = 200000; reason = 'over cap' } $AH
Log 'D4d Loyalty adjust 200,000 (>100k cap)' $adjustHuge.status $adjustHuge.body

$adjustStr = Hit 'POST' "$gw/api/v1/loyalty/v2/admin/customers/$custId/adjust" @{ delta = 'abc'; reason = 'str' } $AH
Log 'D4e Loyalty adjust "abc"' $adjustStr.status $adjustStr.body

# D1: admin walk-in with past date
$walkPast = Hit 'POST' "$gw/api/v1/bookings/admin" (MakeBooking '2024-01-01' '18:00') $AH
Log 'D1 Admin walk-in past date' $walkPast.status $walkPast.body

# === F. DATA INTEGRITY ===

# F1: emoji + RTL + long text in notes
$weirdNotes = "Hello 🎬🎥 שלום مرحبا 测试 " + ('A' * 900)
$f1 = Hit 'POST' "$gw/api/v1/bookings" @{
    eventTypeId = $eventTypeId
    bookingDate = '2026-11-01'
    startTime = '18:00'
    durationMinutes = 120
    numberOfGuests = 2
    addOns = @()
    specialNotes = $weirdNotes
} $CH
Log 'F1 Emoji/RTL/long notes' $f1.status $f1.body

# F1b: > 1000 chars notes (should reject per @Size(max=1000))
$f1b = Hit 'POST' "$gw/api/v1/bookings" @{
    eventTypeId = $eventTypeId
    bookingDate = '2026-11-02'
    startTime = '18:00'
    durationMinutes = 120
    numberOfGuests = 2
    addOns = @()
    specialNotes = ('X' * 5000)
} $CH
Log 'F1b 5000-char notes' $f1b.status $f1b.body

# F3: 23:30 IST booking + duration past midnight
$f3 = Hit 'POST' "$gw/api/v1/bookings" @{
    eventTypeId = $eventTypeId
    bookingDate = '2026-11-15'
    startTime = '23:30'
    durationMinutes = 180
    numberOfGuests = 2
    addOns = @()
    specialNotes = 'Late night'
} $CH
Log 'F3 Late-night 23:30 + 180min spans midnight' $f3.status $f3.body

# === E. RACE CONCURRENCY ===
# E1: simultaneous loyalty redeem (need balance first)
$adminCredit = Hit 'POST' "$gw/api/v1/loyalty/v2/admin/customers/$custId/adjust" @{ delta = 5000; reason = 'seed for race' } $AH
Log 'E0 Seed loyalty +5000' $adminCredit.status $adminCredit.body

# Concurrent redeem (5 attempts of 1500 each = 7500 vs balance 5000)
$rjobs = 1..5 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($url, $tok)
        try {
            $r = Invoke-WebRequest -Uri $url -Method POST -ContentType 'application/json' `
                -Headers @{ Authorization = "Bearer $tok"; 'X-Binge-Id' = '1' } `
                -Body '{"points":1500,"context":"BOOKING","reason":"race"}' -UseBasicParsing -ErrorAction Stop
            return "OK $($r.StatusCode)"
        } catch {
            $resp = $_.Exception.Response
            if ($resp) { return "ERR $([int]$resp.StatusCode)" }
            return "ERR --"
        }
    } -ArgumentList "$gw/api/v1/loyalty/v2/customers/me/redeem", $custTok
}
$rjobs | Wait-Job -Timeout 30 | Out-Null
$rraceResults = $rjobs | Receive-Job
$rjobs | Remove-Job -Force
$rrs = ($rraceResults -join ' | ')
Log 'E1 Concurrent redeem 5x1500 vs balance 5000' '?' $rrs 'HIGH'

# Final balance check
$finalBal = Hit 'GET' "$gw/api/v1/loyalty/v2/customers/me/account" $null $CH
Log 'E1-after Final balance' $finalBal.status $finalBal.body

Write-Host "=== DONE ==="
