$ErrorActionPreference = 'Continue'
$gw = 'http://localhost:8080'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Hit($method, $url, $body, $headers) {
    try {
        $params = @{ Uri = $url; Method = $method; ContentType = 'application/json'; ErrorAction = 'Stop' }
        if ($headers) { $params.Headers = $headers }
        if ($body -ne $null) { $params.Body = ($body | ConvertTo-Json -Depth 10 -Compress) }
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = $r.Content }
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        $sc = if ($resp) { [int]$resp.StatusCode } else { 0 }
        $stream = if ($resp) { (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } else { $_.Exception.Message }
        return @{ status = $sc; body = $stream }
    } catch {
        return @{ status = 0; body = $_.Exception.Message }
    }
}

$adminLogin = Hit 'POST' "$gw/api/v1/auth/admin/login" @{email='admin@skbingegalaxy.com'; password='Admin@123Local'}
$adminTok = ($adminLogin.body | ConvertFrom-Json).data.token
Write-Host "ADMIN_TOKEN_LEN=$($adminTok.Length)"

$ts = [DateTimeOffset]::Now.ToUnixTimeSeconds()
$email = "stresstest_$ts@example.com"
$reg = Hit 'POST' "$gw/api/v1/auth/register" @{firstName='Stress';lastName='Test';email=$email;password='Aa1!aaaaaa';phone='9876500000'}
Write-Host "REGISTER status=$($reg.status)"
Write-Host $reg.body
$cust = ($reg.body | ConvertFrom-Json).data
$custTok = $cust.token
$custId = $cust.user.id
Write-Host "CUSTOMER_ID=$custId TOKEN_LEN=$($custTok.Length)"
"ADMIN=$adminTok`nCUST=$custTok`nID=$custId`nEMAIL=$email" | Out-File -FilePath stress-tokens.txt -Encoding ascii
