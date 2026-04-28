# Round 2 - corrected paths
$ErrorActionPreference = 'Continue'
$gw = 'http://localhost:8080'

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

Get-Content stress-tokens.txt | ForEach-Object {
    $kv = $_ -split '=', 2
    if ($kv.Length -eq 2) { Set-Variable -Name $kv[0] -Value $kv[1] }
}
$adminTok = (Get-Variable ADMIN -ValueOnly)
$custTok  = (Get-Variable CUST  -ValueOnly)
$custId   = (Get-Variable ID    -ValueOnly)

$AH = @{ Authorization = "Bearer $adminTok"; 'X-Binge-Id' = '1' }
$CH = @{ Authorization = "Bearer $custTok";  'X-Binge-Id' = '1' }
$AH_NoBinge = @{ Authorization = "Bearer $adminTok" }

function Log($scenario, $status, $resp, $sev='') {
    $excerpt = $resp; if ($excerpt.Length -gt 300) { $excerpt = $excerpt.Substring(0,300) + '...' }
    $line = "[$sev] $scenario => HTTP $status :: $excerpt"
    Write-Host $line
    Add-Content -Path stress-results.txt -Value $line
}

"== ROUND 2 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==" | Add-Content stress-results.txt

# Wait for rate limit reset
Write-Host "Waiting 75s for booking rate-limit reset..."
Start-Sleep -Seconds 75

# === A1 Real double-submit (no idem key, no rate limit) ===
$same = @{
    eventTypeId = 8; bookingDate = '2026-12-05'; startTime = '15:00'
    durationMinutes = 120; numberOfGuests = 2; addOns = @(); specialNotes='dbl'
}
# Two parallel jobs
$dj = 1..2 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($url, $body, $tok)
        try {
            $r = Invoke-WebRequest -Uri $url -Method POST -ContentType 'application/json' `
                -Headers @{ Authorization = "Bearer $tok"; 'X-Binge-Id' = '1' } `
                -Body $body -UseBasicParsing -ErrorAction Stop
            return "OK $($r.StatusCode) $($r.Content.Substring(0,[Math]::Min(150,$r.Content.Length)))"
        } catch {
            $resp = $_.Exception.Response
            if ($resp) { return "ERR $([int]$resp.StatusCode)" }
            return "ERR --"
        }
    } -ArgumentList "$gw/api/v1/bookings", ($same|ConvertTo-Json -Compress), $custTok
}
$dj | Wait-Job -Timeout 30 | Out-Null
$drj = ($dj | Receive-Job) -join " | "
$dj | Remove-Job -Force
Log 'A1 Parallel double-submit (no idem)' '?' $drj 'HIGH?'

# === Capture a fresh booking to use ===
Start-Sleep -Seconds 5
$mineList = Hit 'GET' "$gw/api/v1/bookings/my" $null $CH
$bref = $null
try {
    $arr = ($mineList.body | ConvertFrom-Json).data
    if ($arr.Count -gt 0) { $bref = $arr[0].bookingRef; Write-Host "Using bref=$bref" }
} catch {}

# === D1 Admin walk-in past date ===
$walkPast = Hit 'POST' "$gw/api/v1/bookings/admin/create-booking" @{
    customerId = $custId; eventTypeId = 8; bookingDate = '2024-01-01'; startTime='18:00'
    durationMinutes = 120; numberOfGuests = 2; addOns = @()
} $AH
Log 'D1 Admin walk-in past date' $walkPast.status $walkPast.body

# Admin walk-in no customer
$walkNoCust = Hit 'POST' "$gw/api/v1/bookings/admin/create-booking" @{
    customerId = $null; eventTypeId = 8; bookingDate = '2026-12-15'; startTime='18:00'
    durationMinutes = 120; numberOfGuests = 2; addOns = @()
} $AH
Log 'D1b Admin walk-in null customer' $walkNoCust.status $walkNoCust.body

# === D5 admin loyalty without binge ===
$lv2nb = Hit 'GET' "$gw/api/v2/loyalty/admin/bindings/1" $null $AH_NoBinge
Log 'D5 Admin loyalty bindings WITHOUT X-Binge-Id' $lv2nb.status $lv2nb.body

$lv2wb = Hit 'GET' "$gw/api/v2/loyalty/admin/bindings/1" $null $AH
Log 'D5b Admin loyalty bindings WITH X-Binge-Id' $lv2wb.status $lv2wb.body

# === D3 Customer hits super-admin route ===
$saTest = Hit 'GET' "$gw/api/v2/loyalty/super-admin/program" $null $CH
Log 'D3 Customer hitting super-admin program' $saTest.status $saTest.body

$saTest2 = Hit 'GET' "$gw/api/v2/loyalty/super-admin/perks" $null $CH
Log 'D3b Customer hitting super-admin perks' $saTest2.status $saTest2.body

# Admin hits super-admin (regular admin shouldn't either if our admin is plain ADMIN role)
$saAdminTest = Hit 'GET' "$gw/api/v2/loyalty/super-admin/program" $null $AH
Log 'D3c SUPER_ADMIN hitting super-admin program' $saAdminTest.status $saAdminTest.body

# === C3/C4 IDOR cross-customer access ===
if ($bref) {
    $ts2 = [DateTimeOffset]::Now.ToUnixTimeSeconds()
    $reg2 = Hit 'POST' "$gw/api/v1/auth/register" @{firstName='Mallory';lastName='Eve';email="other_$ts2@example.com";password='Aa1!aaaaaa';phone='9876500001'}
    try {
        $cust2Tok = (($reg2.body | ConvertFrom-Json).data.token)
        $C2H = @{ Authorization = "Bearer $cust2Tok"; 'X-Binge-Id' = '1' }
        $other = Hit 'GET' "$gw/api/v1/bookings/$bref" $null $C2H
        Log "C3 Customer B viewing Customer A booking ($bref)" $other.status $other.body 'CRITICAL?'
        $cnc = Hit 'POST' "$gw/api/v1/bookings/$bref/cancel" @{ reason='evil' } $C2H
        Log "C4 Customer B cancels A booking" $cnc.status $cnc.body 'CRITICAL?'
        $resched = Hit 'POST' "$gw/api/v1/bookings/$bref/reschedule" @{ bookingDate='2026-12-31'; startTime='10:00' } $C2H
        Log "C4b Customer B reschedules A booking" $resched.status $resched.body 'CRITICAL?'
    } catch { Log "C3/C4 setup failed" 0 $_.Exception.Message }
}

# === Booking ref enumeration: try guessing 'SKBG' style refs ===
$guessRef = 'SKBG2637B47685'
$guess = Hit 'GET' "$gw/api/v1/bookings/$guessRef" $null $CH
Log 'C3b Customer guesses other booking ref' $guess.status $guess.body

# === B Payment edge cases ===
if ($bref) {
    $pi = Hit 'POST' "$gw/api/v1/payments/initiate" @{ bookingRef = $bref; amount = 1; method = 'UPI' } $CH
    Log 'B2a Payment underpayment amount=1' $pi.status $pi.body 'HIGH?'

    $piNeg = Hit 'POST' "$gw/api/v1/payments/initiate" @{ bookingRef = $bref; amount = -100; method = 'UPI' } $CH
    Log 'B2c Payment negative amount=-100' $piNeg.status $piNeg.body 'CRITICAL?'

    $piHuge = Hit 'POST' "$gw/api/v1/payments/initiate" @{ bookingRef = $bref; amount = 99999999; method = 'UPI' } $CH
    Log 'B2b Payment overpayment 99999999' $piHuge.status $piHuge.body
}

# === Refund overpayment test ===
# Try refund > paid
if ($bref) {
    $refPay = Hit 'GET' "$gw/api/v1/payments/booking/$bref" $null $AH
    Log 'B4-prep get payment' $refPay.status $refPay.body
}

# === F1 unicode/long ===
$fNotes = "Hello 🎬🎥 שלום مرحبا 测试 Café "
$f1 = Hit 'POST' "$gw/api/v1/bookings" @{
    eventTypeId = 8; bookingDate = '2027-01-15'; startTime='18:00'; durationMinutes=120
    numberOfGuests=2; addOns=@(); specialNotes=$fNotes
} $CH
Log 'F1 Emoji/RTL notes' $f1.status $f1.body

$f1b = Hit 'POST' "$gw/api/v1/bookings" @{
    eventTypeId = 8; bookingDate = '2027-01-16'; startTime='18:00'; durationMinutes=120
    numberOfGuests=2; addOns=@(); specialNotes=('X' * 5000)
} $CH
Log 'F1b 5000-char notes (limit=1000)' $f1b.status $f1b.body

# === F3 Late-night midnight crossing ===
$f3 = Hit 'POST' "$gw/api/v1/bookings" @{
    eventTypeId = 8; bookingDate = '2027-01-20'; startTime='23:30'; durationMinutes=180
    numberOfGuests=2; addOns=@(); specialNotes='Midnight'
} $CH
Log 'F3 23:30 + 180min spans midnight' $f3.status $f3.body

# === A3 Far future explore: 50 years ===
$f50 = Hit 'POST' "$gw/api/v1/bookings" @{
    eventTypeId = 8; bookingDate = '2076-04-25'; startTime='18:00'; durationMinutes=120
    numberOfGuests=2; addOns=@(); specialNotes='2076'
} $CH
Log 'A3b Year 2076 booking' $f50.status $f50.body 'HIGH?'

Write-Host "=== ROUND 2 DONE ==="
