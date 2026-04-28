$gw="http://localhost:8080"
function POST($url,$body,$hdr){
  try{ $r=Invoke-WebRequest -Uri $url -Method POST -Body $body -Headers $hdr -ContentType "application/json" -UseBasicParsing -ErrorAction Stop; return @{ok=$true;status=$r.StatusCode;body=$r.Content} }
  catch{ $rs=$_.Exception.Response; if($rs){$sr=New-Object System.IO.StreamReader($rs.GetResponseStream());$b=$sr.ReadToEnd();return @{ok=$false;status=$rs.StatusCode.value__;body=$b}} else {return @{ok=$false;status=-1;body=$_.Exception.Message}} }
}
function PUT($url,$body,$hdr){
  try{ $r=Invoke-WebRequest -Uri $url -Method PUT -Body $body -Headers $hdr -ContentType "application/json" -UseBasicParsing -ErrorAction Stop; return @{ok=$true;status=$r.StatusCode;body=$r.Content} }
  catch{ $rs=$_.Exception.Response; if($rs){$sr=New-Object System.IO.StreamReader($rs.GetResponseStream());$b=$sr.ReadToEnd();return @{ok=$false;status=$rs.StatusCode.value__;body=$b}} else {return @{ok=$false;status=-1;body=$b}} }
}
function GET($url,$hdr){
  try{ $r=Invoke-WebRequest -Uri $url -Method GET -Headers $hdr -UseBasicParsing -ErrorAction Stop; return @{ok=$true;status=$r.StatusCode;body=$r.Content} }
  catch{ $rs=$_.Exception.Response; if($rs){$sr=New-Object System.IO.StreamReader($rs.GetResponseStream());$b=$sr.ReadToEnd();return @{ok=$false;status=$rs.StatusCode.value__;body=$b}} else {return @{ok=$false;status=-1;body=$_.Exception.Message}} }
}

# Login admin
$adm=POST "$gw/api/v1/auth/login" (@{email="admin@skbingegalaxy.com";password="Admin@123Local"}|ConvertTo-Json) @{"X-Binge-Id"="1"}
$adminTok=(ConvertFrom-Json $adm.body).data.token
$AH=@{Authorization="Bearer $adminTok";"X-Binge-Id"="1"}

# Register customer
$em="probe_$(Get-Random)@x.com"
$reg=@{firstName="ProbeUser";lastName="Test";email=$em;password="Test@123Secure";phone="9"+(Get-Random -Min 100000000 -Max 999999999).ToString()}|ConvertTo-Json
$cus=POST "$gw/api/v1/auth/register" $reg @{"X-Binge-Id"="1"}
Write-Host "Register Result: $($cus.status) $($cus.body)"
if($cus.ok){
    $tok=(ConvertFrom-Json $cus.body).data.token
    $H=@{Authorization="Bearer $tok";"X-Binge-Id"="1"}
} else {
    Write-Host "FallBack to Admin for Customer role tests"
    $H=$AH
}

$d=(Get-Date).AddDays(15).ToString("yyyy-MM-dd")

Write-Host "===== T1: ADMIN PUT close < open ====="
$cur=GET "$gw/api/v1/bookings/binges/1" $AH
$c=(ConvertFrom-Json $cur.body).data
$base=@{name=$c.name;address=$c.address;supportEmail=$c.supportEmail;supportPhone=$c.supportPhone;supportWhatsapp=$c.supportWhatsapp;customerCancellationEnabled=$c.customerCancellationEnabled;customerCancellationCutoffMinutes=$c.customerCancellationCutoffMinutes;maxConcurrentBookings=$c.maxConcurrentBookings}
$body=$base.Clone(); $body.openTime="22:00"; $body.closeTime="10:00"
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Write-Host "  close<open status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T2: ADMIN PUT close == open ====="
$body=$base.Clone(); $body.openTime="12:00"; $body.closeTime="12:00"
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Write-Host "  close==open status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T3: ADMIN PUT garbage time '25:99' ====="
$body=$base.Clone(); $body.openTime="25:99"; $body.closeTime="30:30"
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Write-Host "  garbage status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T4: ADMIN PUT non-aligned closeTime 21:45 ====="
$body=$base.Clone(); $body.openTime="10:00"; $body.closeTime="21:45"
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Write-Host "  21:45 status=$($r.status) ok=$($r.ok)"
if($r.ok){
  $sl=GET "$gw/api/v1/availability/slots?date=$d" $H
  if($sl.ok){
    $slots=(ConvertFrom-Json $sl.body).data.availableSlots
    Write-Host "  slot grid first=$($slots[0].label) last=$($slots[-1].label) count=$($slots.Count)"
  }
}

Write-Host "===== T5: Booking ending EXACTLY at closeTime ====="
$bk=@{eventTypeId=8;bookingDate=$d;startTime="21:00";durationMinutes=45;numberOfGuests=1;addOns=@()}|ConvertTo-Json
$r=POST "$gw/api/v1/bookings" $bk $H
Write-Host "  end==close status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T6: Booking exceeding closeTime by 1 min ====="
$bk=@{eventTypeId=8;bookingDate=$d;startTime="21:00";durationMinutes=46;numberOfGuests=1;addOns=@()}|ConvertTo-Json
$r=POST "$gw/api/v1/bookings" $bk $H
Write-Host "  end>close status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T7: Booking with duration=0 ====="
$bk=@{eventTypeId=8;bookingDate=$d;startTime="12:00";durationMinutes=0;numberOfGuests=1;addOns=@()}|ConvertTo-Json
$r=POST "$gw/api/v1/bookings" $bk $H
Write-Host "  dur=0 status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T8: Booking with duration=-30 ====="
$bk=@{eventTypeId=8;bookingDate=$d;startTime="12:00";durationMinutes=-30;numberOfGuests=1;addOns=@()}|ConvertTo-Json
$r=POST "$gw/api/v1/bookings" $bk $H
Write-Host "  dur=-30 status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T9: Booking crossing midnight ====="
$bk=@{eventTypeId=8;bookingDate=$d;startTime="22:00";durationMinutes=300;numberOfGuests=1;addOns=@()}|ConvertTo-Json
$r=POST "$gw/api/v1/bookings" $bk $H
Write-Host "  midnight-cross status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T10: Time format '07:00:00' ====="
$body=$base.Clone(); $body.openTime="07:00:00"; $body.closeTime="21:30:00"
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Write-Host "  HH:mm:ss status=$($r.status)"

Write-Host "===== T11: Non-admin update ====="
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($base|ConvertTo-Json) $H
Write-Host "  non-admin PUT status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T12: Booking exactly at openTime ====="
$body=$base.Clone(); $body.openTime="10:00"; $body.closeTime="23:00"
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Start-Sleep 1
$bk=@{eventTypeId=8;bookingDate=$d;startTime="10:00";durationMinutes=60;numberOfGuests=1;addOns=@()}|ConvertTo-Json
$r=POST "$gw/api/v1/bookings" $bk $H
Write-Host "  start==open status=$($r.status) body=$($r.body.Substring(0,[Math]::Min(200,$r.body.Length)))"

Write-Host "===== T13: SQL injection ====="
$body=$base.Clone(); $body.name="Star'; DROP TABLE binges;--"
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Write-Host "  sqli status=$($r.status)"
$cur=GET "$gw/api/v1/bookings/binges/1" $AH
Write-Host "  binges still readable: $($cur.ok)"
$body=$base.Clone()
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Write-Host "  name restored=$($r.ok)"

Write-Host "===== T14: Partial PUT ====="
$body=@{name=$c.name;address=$c.address;supportEmail=$c.supportEmail;supportPhone=$c.supportPhone;supportWhatsapp=$c.supportWhatsapp;customerCancellationEnabled=$c.customerCancellationEnabled;customerCancellationCutoffMinutes=$c.customerCancellationCutoffMinutes;maxConcurrentBookings=$c.maxConcurrentBookings;openTime="09:00"}
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
Write-Host "  partial-open-only status=$($r.status)"
$cur=GET "$gw/api/v1/bookings/binges/1" $AH
$c2=(ConvertFrom-Json $cur.body).data
Write-Host "  result: openTime=$($c2.openTime) closeTime=$($c2.closeTime)"

Write-Host "===== T15: Final restore ====="
$body=$base.Clone(); $body.openTime="10:00"; $body.closeTime="23:00"
$r=PUT "$gw/api/v1/bookings/admin/binges/1" ($body|ConvertTo-Json) $AH
$cur=GET "$gw/api/v1/bookings/binges/1" $AH
$c2=(ConvertFrom-Json $cur.body).data
Write-Host "  restored openTime=$($c2.openTime) closeTime=$($c2.closeTime)"
