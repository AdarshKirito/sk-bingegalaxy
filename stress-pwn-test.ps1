$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$tok = (Get-Content stress-tokens.txt | Select-String '^CUST=').Line.Substring(5)
$h = @{ Authorization = "Bearer $tok"; 'X-Binge-Id'='1' }
$r = Invoke-WebRequest -Uri 'http://localhost:8080/api/v2/loyalty/super-admin/program' -Method GET -Headers $h -UseBasicParsing
Write-Host 'GET status:' $r.StatusCode
$prog = ($r.Content | ConvertFrom-Json).data
Write-Host 'BEFORE displayName:' $prog.displayName

$prog.displayName = 'PWNED-BY-CUSTOMER-' + (Get-Date -Format HHmmss)
$body = ($prog | ConvertTo-Json -Depth 10 -Compress)
try {
  $put = Invoke-WebRequest -Uri 'http://localhost:8080/api/v2/loyalty/super-admin/program' -Method PUT -Headers $h -ContentType 'application/json' -Body $body -UseBasicParsing
  Write-Host 'PUT status:' $put.StatusCode
  $resp = $put.Content; if ($resp.Length -gt 400) { $resp = $resp.Substring(0, 400) }
  Write-Host $resp
} catch {
  $rsp = $_.Exception.Response
  if ($rsp) { Write-Host 'PUT FAILED:' ([int]$rsp.StatusCode) }
  else { Write-Host 'PUT EXCEPTION:' $_.Exception.Message }
}

Start-Sleep 1
$r2 = Invoke-WebRequest -Uri 'http://localhost:8080/api/v2/loyalty/super-admin/program' -Method GET -Headers $h -UseBasicParsing
$p2 = ($r2.Content | ConvertFrom-Json).data
Write-Host 'AFTER displayName:' $p2.displayName

# Restore via admin token
$atok = (Get-Content stress-tokens.txt | Select-String '^ADMIN=').Line.Substring(6)
$ah = @{ Authorization = "Bearer $atok"; 'X-Binge-Id'='1' }
$p2.displayName = 'SK Binge Galaxy Membership'
$rb = ($p2 | ConvertTo-Json -Depth 10 -Compress)
try {
  $rest = Invoke-WebRequest -Uri 'http://localhost:8080/api/v2/loyalty/super-admin/program' -Method PUT -Headers $ah -ContentType 'application/json' -Body $rb -UseBasicParsing
  Write-Host 'RESTORE status:' $rest.StatusCode
} catch { Write-Host 'RESTORE failed' }
