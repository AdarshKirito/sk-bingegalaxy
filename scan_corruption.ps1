$files = @(
  'backend\availability-service\src\main\java\com\skbingegalaxy\availability\service\AvailabilityService.java',
  'backend\availability-service\src\main\java\com\skbingegalaxy\availability\dto\BookingBingeDto.java',
  'backend\availability-service\src\test\java\com\skbingegalaxy\availability\service\AvailabilityServiceTest.java',
  'backend\payment-service\src\main\java\com\skbingegalaxy\payment\service\PaymentService.java',
  'backend\payment-service\src\test\java\com\skbingegalaxy\payment\service\PaymentServiceTest.java',
  'backend\booking-service\src\main\java\com\skbingegalaxy\booking\service\BookingService.java',
  'backend\booking-service\src\main\java\com\skbingegalaxy\booking\service\BingeService.java',
  'backend\booking-service\src\main\java\com\skbingegalaxy\booking\entity\Binge.java',
  'backend\booking-service\src\main\java\com\skbingegalaxy\booking\dto\BingeDto.java',
  'backend\booking-service\src\main\java\com\skbingegalaxy\booking\dto\BingeSaveRequest.java',
  'backend\booking-service\src\main\java\com\skbingegalaxy\booking\controller\BingeController.java',
  'backend\booking-service\src\main\resources\db\migration\V26__add_binge_operating_hours.sql',
  'frontend\src\pages\BingeManagement.jsx'
)
$cAtilde = [char]0x00C3
$cReplacement = [char]0xFFFD
$pattern = [string]::Format('{0}|{1}', $cAtilde, $cReplacement)
foreach ($f in $files) {
  if (Test-Path $f) {
    $t = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
    $moji = ([regex]::Matches($t, $pattern)).Count
    $bom  = '-'
    if ($t.Length -gt 0 -and [int][char]$t[0] -eq 0xFEFF) { $bom = 'BOM' }
    Write-Host ("{0,-110}  moji={1,-4}  {2}" -f $f, $moji, $bom)
  } else {
    Write-Host "$f MISSING"
  }
}
