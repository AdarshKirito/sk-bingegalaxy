$base = "http://localhost:3000"
$adminEmail = "admin@skbingegalaxy.com"
try {
    $adminRes = docker inspect skbg-auth --format "{{range .Config.Env}}{{println .}}{{end}}" | Select-String "ADMIN_PASSWORD"
    $adminPassword = $adminRes.ToString().Split("=")[1].Trim()
} catch {
    $adminPassword = "adminpassword"
}

# Register a fresh customer
$uid = [Guid]::NewGuid().ToString("N").Substring(0,8)
$custEmail = "browseruser_$uid@example.com"
$custPass = "StrongPass@123"
$regBody = @{ firstName="Browser"; lastName="Test"; email=$custEmail; password=$custPass; phone="9$(Get-Random -Min 100000000 -Max 999999999)" } | ConvertTo-Json
$reg = Invoke-RestMethod -Uri "$base/api/v1/auth/register" -Method POST -ContentType "application/json" -Body $regBody

function Cycle($email, $pass, $loginPath) {
    try {
        $loginRes = try { 
            Invoke-WebRequest -Uri "$base$loginPath" -Method POST -ContentType "application/json" -Body (@{ email=$email; password=$pass } | ConvertTo-Json) 
        } catch { 
            $_.Exception.Response 
        }
        
        $loginCode = [int]$loginRes.StatusCode
        if ($loginCode -eq 429) { return @{login=429; profile="N/A"; logout="N/A"} }
        if ($loginCode -ge 400) { return @{login=$loginCode; profile="N/A"; logout="N/A"} }
        
        $tok = ($loginRes.Content | ConvertFrom-Json).accessToken
        $hdr = @{ Authorization = "Bearer $tok" }
        
        $profRes = try { 
            Invoke-WebRequest -Uri "$base/api/v1/auth/profile" -Headers $hdr 
        } catch { 
            $_.Exception.Response 
        }
        
        $logoutRes = try { 
            Invoke-WebRequest -Uri "$base/api/v1/auth/logout" -Method POST -Headers $hdr 
        } catch { 
            $_.Exception.Response 
        }
        
        return @{login=$loginCode; profile=[int]$profRes.StatusCode; logout=[int]$logoutRes.StatusCode}
    } catch {
        return @{error=$_.Exception.Message}
    }
}

$custResults=@(); for ($i=0;$i -lt 12;$i++) { $custResults += ,(Cycle $custEmail $custPass "/api/v1/auth/login") }
$admResults=@();  for ($i=0;$i -lt 12;$i++) { $admResults += ,(Cycle $adminEmail $adminPassword "/api/v1/auth/admin/login") }

$allCodes = ($custResults + $admResults) | ForEach-Object { $_.Values }
$has429 = $allCodes -contains 429

Write-Output "Customer Results:"
$custResults | ConvertTo-Json
Write-Output "Admin Results:"
$admResults | ConvertTo-Json
Write-Output "Any 429 detected: $has429"
Write-Output "Summary: Tested 12 customer cycles and 12 admin cycles. Total 429s: $( ($allCodes | Where-Object { $_ -eq 429 }).Count )"
