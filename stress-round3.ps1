$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
function Hit($method, $url, $body, $headers) {
    $h = @{}; if ($headers) { foreach ($k in $headers.Keys) { $h[$k] = $headers[$k] } }
    try {
        $params = @{ Uri = $url; Method = $method; ContentType = 'application/json'; UseBasicParsing = $true; Headers = $h; TimeoutSec = 30 }
        if ($body -ne $null) { $params.Body = ($body | ConvertTo-Json -Depth 10 -Compress) }
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        $sc = 0; $bs = $_.Exception.Message
        if ($resp -and $resp.GetResponseStream) {
            $sc = [int]$resp.StatusCode
            try { $bs = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch {}
        }
        return @{ status = $sc; body = $bs }
    }
}

$adminTok = (Get-Content stress-tokens.txt | Select-String '^ADMIN=').Line.Substring(6)
$custTok  = (Get-Content stress-tokens.txt | Select-String '^CUST=').Line.Substring(5)
$custId   = (Get-Content stress-tokens.txt | Select-String '^ID=').Line.Substring(3)
$AH = @{ Authorization = "Bearer $adminTok"; 'X-Binge-Id'='1' }
$CH = @{ Authorization = "Bearer $custTok";  'X-Binge-Id'='1' }

function Log($scenario, $status, $resp) {
    $ex = $resp; if ($ex.Length -gt 350) { $ex = $ex.Substring(0,350) + '...' }
    $line = "$scenario => HTTP $status :: $ex"
    Write-Host $line
    Add-Content -Path stress-results.txt -Value $line
}

"== ROUND 3 $(Get-Date -Format HH:mm:ss) ==" | Add-Content stress-results.txt

# (1) Mutate super-admin tier as customer
$tier = Hit 'POST' 'http://localhost:8080/api/v2/loyalty/super-admin/tiers' @{
    code = 'CUSTOMER_PWN'; displayName='Pwned Tier'; minQualifyingPoints=0; tierOrder=99; active=$true
} $CH
Log 'CRIT-1 Customer creates loyalty tier (super-admin)' $tier.status $tier.body

$perk = Hit 'POST' 'http://localhost:8080/api/v2/loyalty/super-admin/perks' @{
    code='CUST_FREE_VIP'; displayName='Free VIP everything'; description='hax'; category='FINANCIAL'
    fulfillmentType='AUTOMATIC'; deliveryHandlerKey='WELCOME_BONUS_POINTS'; defaultParameters='{}'
} $CH
Log 'CRIT-2 Customer creates super-admin perk' $perk.status $perk.body

# (2) Customer creates a loyalty binge binding as if admin (POST endpoint at /api/v2/loyalty/admin/bindings/{bingeId}/enable)
$binding = Hit 'POST' 'http://localhost:8080/api/v2/loyalty/admin/bindings/1/enable' @{} $CH
Log 'CRIT-3 Customer enables loyalty binding for binge 1' $binding.status $binding.body

# (3) Admin endpoints: customer reads admin bindings
$adminB = Hit 'GET' 'http://localhost:8080/api/v2/loyalty/admin/bindings/1' $null $CH
Log 'CRIT-4 Customer reads admin bindings' $adminB.status $adminB.body

# (4) Customer reads super-admin tiers
$tiers = Hit 'GET' 'http://localhost:8080/api/v2/loyalty/super-admin/tiers' $null $CH
Log 'CRIT-5 Customer reads super-admin tiers' $tiers.status $tiers.body

# (5) Pricing surge rules (admin?)
$surge = Hit 'GET' 'http://localhost:8080/api/v1/bookings/admin/pricing/surge-rules' $null $CH
Log 'CRIT-6 Customer reads admin pricing surge rules' $surge.status $surge.body

# (6) Test cross-tenant: 2nd customer registered earlier
# Already confirmed - C3 returned 403 properly. Skipping.

# (7) Booking ref enumeration: try existing system refs that aren't ours
$enum = Hit 'GET' 'http://localhost:8080/api/v1/bookings/SKBG0000000001' $null $CH
Log 'C3-enum non-existent ref' $enum.status $enum.body

# (8) Admin uses ADMIN-only endpoint for own data - we have SUPER_ADMIN. Need plain ADMIN. Skip.

# (9) Check whether GET /my returns OWN bookings only when X-User-Id is forged
$forge = @{ Authorization = "Bearer $custTok"; 'X-Binge-Id'='1'; 'X-User-Id'='6' }
$my = Hit 'GET' 'http://localhost:8080/api/v1/bookings/my' $null $forge
Log 'C2 X-User-Id forge=6 (customer 17 token)' $my.status $my.body
# Look for customerId in response
if ($my.body -match '"customerId":(\d+)') { Write-Host "Returned customerId: $($matches[1])" }

# (10) Test what happens if we POST X-User-Id forge to mutating endpoint
$forgeBook = Hit 'POST' 'http://localhost:8080/api/v1/bookings' @{
    eventTypeId=8; bookingDate='2027-02-15'; startTime='10:00'; durationMinutes=120
    numberOfGuests=2; addOns=@(); specialNotes='forgery'
} $forge
Log 'C2b Forged X-User-Id booking creation' $forgeBook.status $forgeBook.body

# (11) Simulate JWT with no signature (alg=none) - test for alg:none vulnerability
$h64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"alg":"none","typ":"JWT"}')).TrimEnd('=').Replace('+','-').Replace('/','_')
$p64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"sub":"6","role":"SUPER_ADMIN","email":"admin@skbingegalaxy.com","iss":"skbingegalaxy-auth","aud":["skbingegalaxy-web"],"exp":99999999999}')).TrimEnd('=').Replace('+','-').Replace('/','_')
$noneTok = "$h64.$p64."
$noneH = @{ Authorization = "Bearer $noneTok"; 'X-Binge-Id'='1' }
$none = Hit 'GET' 'http://localhost:8080/api/v1/bookings/my' $null $noneH
Log 'C-alg-none JWT alg=none attack' $none.status $none.body
