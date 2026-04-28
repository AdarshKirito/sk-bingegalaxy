$ErrorActionPreference = "Stop"
$body = @{ email = "kirito@gmail.com"; password = "Kirito@gmail.com1" } | ConvertTo-Json
$loginUrl = "http://localhost:8080/api/v1/auth/admin/login"
try {
    $login = Invoke-RestMethod -Uri $loginUrl -Method Post -ContentType "application/json" -Body $body
} catch {
    Write-Host "Login to $loginUrl failed: $($_.Exception.Message)"
    $loginUrl = "http://localhost:8080/auth/admin/login"
    Write-Host "Trying $loginUrl..."
    try {
        $login = Invoke-RestMethod -Uri $loginUrl -Method Post -ContentType "application/json" -Body $body
    } catch {
       Write-Host "Login to $loginUrl failed as well: $($_.Exception.Message)"
       return
    }
}

$token = $login.data.accessToken
$userId = $login.data.user.id
$role = $login.data.user.role
Write-Host "TOKEN_USER_ID=$userId ROLE=$role"
$headers = @{ Authorization = "Bearer $token"; "X-User-Id" = "$userId"; "X-User-Role" = "$role" }

try {
  $binges = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/bookings/admin/binges" -Method Get -Headers $headers
  Write-Host "ADMIN_BINGES:"
  $binges | ConvertTo-Json -Depth 6
} catch { Write-Host "admin/binges failed: $($_.Exception.Message)" }

try {
  $b2 = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/bookings/binges" -Method Get -Headers $headers
  Write-Host "PUBLIC_BINGES:"
  $b2 | ConvertTo-Json -Depth 6
} catch { Write-Host "public/binges failed: $($_.Exception.Message)" }

try {
  $b3 = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/availability/binges" -Method Get -Headers $headers
  Write-Host "AVAIL_BINGES:"
  $b3 | ConvertTo-Json -Depth 6
} catch { Write-Host "availability/binges failed: $($_.Exception.Message)" }
