param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $RepositoryRoot

function Get-MappingPath([string]$ArgsText) {
    if ([string]::IsNullOrWhiteSpace($ArgsText)) { return '' }
    if ($ArgsText -match '(?:^|,\s*)(?:value|path)\s*=\s*"(?<p>[^"]*)"') { return $Matches.p }
    if ($ArgsText -match '^\s*"(?<p>[^"]*)"') { return $Matches.p }
    return ''
}

function Join-UrlPath([string]$Base, [string]$Sub) {
    $path = ($Base + $Sub) -replace '/+', '/'
    if ([string]::IsNullOrEmpty($path)) { return '/' }
    if (-not $path.StartsWith('/')) { $path = '/' + $path }
    if ($path.Length -gt 1) { $path = $path.TrimEnd('/') }
    return $path
}

function Normalize-RoutePath([string]$Path) {
    $value = $Path -replace '\?.*$', ''
    $value = $value -replace '\$\{[^}]+\}', '{}'
    $value = $value -replace '\{[^}]+\}', '{}'
    $value = $value -replace '/+', '/'
    if (-not $value.StartsWith('/')) { $value = '/' + $value }
    if ($value.Length -gt 1) { $value = $value.TrimEnd('/') }
    return $value
}

function Write-Tsv([string]$Path, [string[]]$Header, [object[]]$Rows, [scriptblock]$Selector) {
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add(($Header -join "`t"))
    foreach ($row in $Rows) {
        $values = & $Selector $row
        $clean = @($values | ForEach-Object { if ($null -eq $_) { '' } else { ([string]$_) -replace "`t|`r|`n", ' ' } })
        $lines.Add(($clean -join "`t"))
    }
    $lines | Set-Content -Encoding UTF8 -LiteralPath $Path
}

# --- Backend endpoint inventory ------------------------------------------------
$endpointRows = @(
    foreach ($file in (rg --files backend -g '*Controller.java' | Where-Object { $_ -match 'src[\\/]main[\\/]java' })) {
        $lines = @(Get-Content -Encoding UTF8 -LiteralPath $file)
        $classHit = $lines | Select-String -Pattern '^\s*public\s+class\s+\w+Controller\b' | Select-Object -First 1
        if (-not $classHit) { continue }
        $classIndex = $classHit.LineNumber - 1
        $base = ''
        if ($classIndex -gt 0) {
            $classMapping = $lines[0..($classIndex - 1)] |
                Select-String -Pattern '@RequestMapping(?:\((?<args>.*)\))?' |
                Select-Object -Last 1
            if ($classMapping) { $base = Get-MappingPath $classMapping.Matches[0].Groups['args'].Value }
        }
        for ($i = $classIndex + 1; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -notmatch '@(?<kind>Get|Post|Put|Patch|Delete|Request)Mapping(?:\((?<args>.*)\))?') { continue }
            $kind = $Matches.kind
            $sub = Get-MappingPath $Matches.args
            $method = if ($kind -eq 'Request') { 'ANY' } else { $kind.ToUpperInvariant() }
            $path = Join-UrlPath $base $sub
            [pscustomobject]@{
                Module = ($file -split '[\\/]')[1]
                Controller = [IO.Path]::GetFileNameWithoutExtension($file)
                Method = $method
                Path = $path
                NormalizedPath = Normalize-RoutePath $path
                Source = $file
                Line = $i + 1
            }
        }
    }
) | Sort-Object Module, Controller, Line, Method, Path

if ($endpointRows.Count -ne 421) { throw "Expected 421 endpoint mappings, found $($endpointRows.Count)" }

$endpointTsv = Join-Path $PSScriptRoot 'endpoint-inventory-current.tsv'
Write-Tsv $endpointTsv @('module','controller','method','path','normalized_path','source','line') $endpointRows {
    param($r) @($r.Module,$r.Controller,$r.Method,$r.Path,$r.NormalizedPath,$r.Source,$r.Line)
}

$catalog = [System.Collections.Generic.List[string]]::new()
$catalog.Add('# Current Backend Endpoint Catalog')
$catalog.Add('')
$catalog.Add('Generated from the current working tree by `generate-current-audit-inventories.ps1`. Exactly **421** method mappings across **47** controller classes. Paths retain named Spring parameters; `endpoint-inventory-current.tsv` also provides normalized paths.')
$catalog.Add('')
$catalog.Add('| Method | Path | Module | Controller | Source |')
$catalog.Add('|---|---|---|---|---|')
foreach ($row in $endpointRows) {
    $catalog.Add("| $($row.Method) | ``$($row.Path)`` | $($row.Module) | $($row.Controller) | ``$($row.Source):$($row.Line)`` |")
}
$catalog | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot '..\06b-ENDPOINT-CATALOG-CURRENT.md')

# --- Frontend API pair inventory ----------------------------------------------
$apiPattern = '\b(?<client>api|v2|axios)\.(?<verb>get|post|put|patch|delete)\s*\(\s*(?<quote>[\x27\x22`])(?<path>[^\r\n]*?)\k<quote>'
$axiosRows = @(
    foreach ($file in (Get-ChildItem frontend/src -Recurse -File | Where-Object { $_.Extension -in '.js','.jsx','.ts','.tsx' })) {
        $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName
        foreach ($match in [regex]::Matches($raw, $apiPattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
            $client = $match.Groups['client'].Value.ToLowerInvariant()
            $prefix = switch ($client) { 'api' { '/api/v1' } 'v2' { '/api/v2/loyalty' } default { '' } }
            [pscustomobject]@{
                Client = $client
                Method = $match.Groups['verb'].Value.ToUpperInvariant()
                Path = Normalize-RoutePath ($prefix + $match.Groups['path'].Value)
                Source = '{0}:{1}' -f $file.FullName.Substring($RepositoryRoot.Length + 1), (($raw.Substring(0, $match.Index) -split "`n").Count)
            }
        }
    }
)
if ($axiosRows.Count -ne 413) { throw "Expected 413 Axios-family invocations, found $($axiosRows.Count)" }
if (@($axiosRows | Sort-Object Method, Path -Unique).Count -ne 405) { throw 'Expected 405 unique Axios method/path pairs' }

$extraRows = @(
    [pscustomobject]@{ Client='eventsource'; Method='GET'; Path='/api/v1/bookings/admin/events/stream'; Source='frontend\src\hooks\useRealtimeUpdates.js:47' },
    [pscustomobject]@{ Client='sendbeacon/fetch'; Method='POST'; Path='/api/v1/bookings/analytics/funnel'; Source='frontend\src\services\analytics.js:76' }
)
$frontendPairs = @(
    ($axiosRows + $extraRows) | Group-Object Method, Path | ForEach-Object {
        $g = @($_.Group)
        [pscustomobject]@{
            Method = $g[0].Method
            Path = $g[0].Path
            Clients = (($g.Client | Sort-Object -Unique) -join ',')
            Sources = (($g.Source | Sort-Object -Unique) -join ';')
        }
    } | Sort-Object Method, Path
)
if ($frontendPairs.Count -ne 407) { throw "Expected 407 frontend pairs, found $($frontendPairs.Count)" }
$backendKeys = @{}; foreach ($r in $endpointRows) { $backendKeys["$($r.Method) $($r.NormalizedPath)"] = $true; if ($r.Method -eq 'ANY') { $backendKeys["ANY $($r.NormalizedPath)"] = $true } }
$unmatched = @($frontendPairs | Where-Object { -not $backendKeys.ContainsKey("$($_.Method) $($_.Path)") -and -not $backendKeys.ContainsKey("ANY $($_.Path)") })
if ($unmatched.Count -ne 0) { throw "Frontend/backend diff has $($unmatched.Count) unmatched pair(s)" }

Write-Tsv (Join-Path $PSScriptRoot 'frontend-api-pairs-current.tsv') @('method','path','clients','sources') $frontendPairs {
    param($r) @($r.Method,$r.Path,$r.Clients,$r.Sources)
}

# --- Frontend route declaration inventory -------------------------------------
$appLines = @(Get-Content -Encoding UTF8 -LiteralPath 'frontend\src\App.jsx')
$routeRows = @(
    for ($i = 0; $i -lt $appLines.Count; $i++) {
        if ($appLines[$i] -match '<Route\s+path="(?<path>[^"]+)"\s+element=\{(?<element>.*)\}\s*/>') {
            [pscustomobject]@{ Path=$Matches.path; Element=$Matches.element.Trim(); Source='frontend\src\App.jsx'; Line=$i+1 }
        }
    }
)
if ($routeRows.Count -ne 70) { throw "Expected 70 frontend routes, found $($routeRows.Count)" }
Write-Tsv (Join-Path $PSScriptRoot 'frontend-routes-current.tsv') @('path','element_guard_chain','source','line') $routeRows {
    param($r) @($r.Path,$r.Element,$r.Source,$r.Line)
}

# --- Honest per-file coverage manifest ----------------------------------------
$excludedPatterns = @(
    '(^|[\\/])\.git([\\/]|$)', '(^|[\\/])node_modules([\\/]|$)', '(^|[\\/])target([\\/]|$)',
    '(^|[\\/])dist([\\/]|$)', '(^|[\\/])playwright-report([\\/]|$)', '(^|[\\/])test-results([\\/]|$)',
    '(^|[\\/])k6_bin([\\/]|$)', '(^|[\\/])\.vite([\\/]|$)', '(^|[\\/])\.npm-cache([\\/]|$)',
    '(^|[\\/])\.tmp([\\/]|$)', '^docs[\\/]_previous([\\/]|$)'
)
$files = @(rg --files -uu --hidden | Where-Object {
    $p = $_; -not ($excludedPatterns | Where-Object { $p -match $_ })
} | Sort-Object -Unique)

$deepMap = @{
    'frontend\vite.config.js'='SEC-009,PERF-002'; 'frontend\src\main.jsx'='SEC-012';
    'frontend\src\services\api.js'='SEC-009,SEC-012'; 'frontend\src\stores\authStore.ts'='SEC-009';
    'frontend\src\App.jsx'=''; 'frontend\src\services\endpoints.js'='API-002,FE-001';
    'frontend\src\pages\CustomerPayments.jsx'='FE-001'; 'frontend\src\index.css'='A11Y-004';
    'frontend\src\components\form\AddressFields.jsx'='PERF-002';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\service\PaymentService.java'='SEC-011,PAY-005,PAY-006,PAY-007,PAY-008,PAY-010';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\controller\PaymentController.java'='SEC-011,PAY-006';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\controller\AdminApprovalController.java'='SEC-010';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\service\AdminApprovalService.java'='SEC-010';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\service\WebhookDedupService.java'='PAY-008';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\service\DisputeWebhookService.java'='PAY-008,PAY-009';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\service\IdempotencyService.java'='PAY-006';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\client\RazorpayGatewayClient.java'='PAY-006,PAY-007';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\scheduler\PaymentReconciliationScheduler.java'='PAY-007,REL-002';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\repository\PaymentRepository.java'='SEC-011,PAY-010';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\repository\AdminApprovalRequestRepository.java'='SEC-010';
    'backend\payment-service\src\main\java\com\skbingegalaxy\payment\client\BookingAmountClient.java'='SEC-011';
    'backend\booking-service\src\main\java\com\skbingegalaxy\booking\service\BookingService.java'='BOOK-004,API-002,PERF-001';
    'backend\booking-service\src\main\java\com\skbingegalaxy\booking\listener\PaymentEventListener.java'='SEC-011';
    'backend\booking-service\src\main\java\com\skbingegalaxy\booking\controller\InternalBookingController.java'='SEC-011';
    'backend\booking-service\src\main\java\com\skbingegalaxy\booking\controller\BookingController.java'='API-002';
    'backend\common-lib\src\main\java\com\skbingegalaxy\common\event\BookingEvent.java'='BOOK-004'
}

$coverageRows = foreach ($path in $files) {
    $normalized = $path -replace '/', '\'
    $item = Get-Item -LiteralPath $path
    $subsystem = if ($normalized -match '^backend\\([^\\]+)') { "backend/$($Matches[1])" } elseif ($normalized -match '^frontend\\') { 'frontend' } elseif ($normalized -match '^docs\\') { 'docs' } elseif ($normalized -match '^(k8s|grafana)\\') { 'infrastructure' } elseif ($normalized -match '^production-proof\\') { 'production-proof' } elseif ($normalized -match '^load-tests\\') { 'load-tests' } elseif ($normalized -match '^scripts\\') { 'scripts' } else { 'repository-root' }
    $kind = switch -Regex ($normalized) {
        '^docs\\(0[0-9]|1[0-9]|2[0-8])-.*\.md$' { 'audit-output'; break }
        '^docs\\audit\\' { 'audit-output'; break }
        'src\\test\\.*\.java$' { 'backend-test'; break }
        'src\\main\\java\\.*controller.*\.java$' { 'backend-controller'; break }
        'src\\main\\java\\.*service.*\.java$' { 'backend-service'; break }
        'src\\main\\java\\.*repository.*\.java$' { 'backend-repository'; break }
        'src\\main\\java\\.*entity.*\.java$' { 'backend-entity'; break }
        'src\\main\\java\\.*(client|listener|scheduler|config|security).*\.java$' { 'backend-integration'; break }
        'src\\main\\java\\.*dto.*\.java$' { 'backend-dto'; break }
        '\\db\\migration\\.*\.sql$' { 'flyway-migration'; break }
        '^frontend\\src\\test\\|^frontend\\.*\.(test|spec)\.' { 'frontend-test'; break }
        '^frontend\\src\\pages\\' { 'frontend-page'; break }
        '^frontend\\src\\components\\' { 'frontend-component'; break }
        '^frontend\\src\\(services|stores|hooks|context)\\' { 'frontend-state-integration'; break }
        '\.(css|scss)$' { 'style'; break }
        '\.(yml|yaml|json|toml|properties|conf)$' { 'configuration'; break }
        '\.(ps1|sh|cjs|mjs|py)$' { 'script'; break }
        '\.md$' { 'documentation'; break }
        '\.(png|jpg|jpeg|gif|ico|woff2|zip|crt|key|p12)$' { 'binary-asset'; break }
        default { 'source-or-artifact' }
    }
    $responsibility = switch ($kind) {
        'backend-controller' { 'HTTP contract and authorization entry point' }
        'backend-service' { 'domain/application transaction logic' }
        'backend-repository' { 'persistence query/locking contract' }
        'backend-entity' { 'persistent domain model' }
        'backend-integration' { 'external/event/configuration integration' }
        'backend-dto' { 'request/response/event shape' }
        'flyway-migration' { 'relational schema evolution' }
        'backend-test' { 'backend regression evidence' }
        'frontend-page' { 'route-level product workflow' }
        'frontend-component' { 'reusable UI/form behavior' }
        'frontend-state-integration' { 'frontend state/API/integration behavior' }
        'frontend-test' { 'frontend regression evidence' }
        'style' { 'visual/accessibility presentation' }
        'configuration' { 'build/runtime/deployment configuration' }
        'script' { 'automation/operations' }
        'documentation' { 'developer/operator reference' }
        'audit-output' { 'audit evidence/deliverable' }
        default { 'repository asset' }
    }
    $expected = if ($normalized -match '^backend\\(payment-service|auth-service)\\src\\main' -or $normalized -match '^backend\\booking-service\\src\\main' -or $normalized -in @('frontend\vite.config.js','frontend\src\main.jsx','frontend\src\services\api.js','frontend\src\stores\authStore.ts')) { 'A' } elseif ($kind -in @('binary-asset','style','source-or-artifact')) { 'C' } else { 'B' }
    if ($kind -eq 'audit-output') { $actual='X'; $status='excluded'; $inspector='audit-output'; $limitation='Generated audit output; outside product-code coverage denominator' }
    elseif ($deepMap.ContainsKey($normalized)) { $actual='A'; $status='complete'; $inspector='Codex + independent verifier'; $limitation='Static deep trace; runtime limitations recorded in issue/evidence' }
    elseif ($kind -in @('backend-controller','backend-integration','frontend-state-integration') -or $normalized -eq 'frontend\src\App.jsx') { $actual='B'; $status='partial'; $inspector='Codex/current catalog sweep'; $limitation='Structural/contract inspection; not whole-file logic proof' }
    elseif ($kind -in @('backend-service','backend-repository','backend-entity','backend-dto','flyway-migration','backend-test','frontend-page','frontend-component','frontend-test','configuration','script','documentation')) { $actual='B'; $status='partial'; $inspector='Prior audit + Codex census'; $limitation='Structural or targeted inspection; no claim of exhaustive behavior' }
    else { $actual='C'; $status='census'; $inspector='Codex census'; $limitation='Classified only; behavioral content not deeply inspected' }
    if ($normalized -match '(^|\\)\.env($|\.)|token|secret|\.key$|\.p12$') { $limitation = 'SENSITIVE - names/patterns only; values redacted' }
    $evidence = if ($deepMap.ContainsKey($normalized)) { 'specialist-06/07/08/09; issue register' } elseif ($kind -match 'controller|frontend-state') { 'endpoint/frontend inventories' } elseif ($kind -eq 'audit-output') { 'n/a' } else { 'final-file-coverage census' }
    [pscustomobject]@{
        Path=$path; Bytes=$item.Length; Subsystem=$subsystem; ArtifactKind=$kind; Responsibility=$responsibility;
        KeySymbolsOrSurface=[IO.Path]::GetFileNameWithoutExtension($item.Name); ExpectedDepth=$expected; ActualDepth=$actual;
        Status=$status; Inspector=$inspector; EvidenceRefs=$evidence; IssueIds=if($deepMap.ContainsKey($normalized)){$deepMap[$normalized]}else{''}; Limitations=$limitation
    }
}

Write-Tsv (Join-Path $PSScriptRoot 'final-file-coverage.tsv') @('path','bytes','subsystem','artifact_kind','responsibility','key_symbols_or_surface','expected_depth','actual_depth','status','inspector','evidence_refs','issue_ids','limitations_or_exclusion_reason') @($coverageRows) {
    param($r) @($r.Path,$r.Bytes,$r.Subsystem,$r.ArtifactKind,$r.Responsibility,$r.KeySymbolsOrSurface,$r.ExpectedDepth,$r.ActualDepth,$r.Status,$r.Inspector,$r.EvidenceRefs,$r.IssueIds,$r.Limitations)
}

$depth = $coverageRows | Group-Object ActualDepth | Sort-Object Name
$summary = ($depth | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ', '
Write-Output "Generated: endpoints=$($endpointRows.Count); frontendPairs=$($frontendPairs.Count); routes=$($routeRows.Count); coverageFiles=$($coverageRows.Count); depth[$summary]"
