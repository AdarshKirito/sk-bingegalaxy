<#
.SYNOPSIS
    Runs Maven on this Windows host with the correct TLS truststore.

.DESCRIPTION
    `.mvn/jvm.config` is deliberately PORTABLE — it must not contain anything
    OS-specific, because the same file is read by the Jenkins Linux agent and by
    every Maven-in-Docker build. See .mvn/README.md.

    It previously carried `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`, which is
    correct on this host (AVG intercepts TLS, so the JVM must read the Windows
    certificate store to trust the intercepted chain) and FATAL on Linux, where that
    truststore type does not exist and every artifact download dies with:

      java.security.NoSuchAlgorithmException: Error constructing implementation
        (algorithm: Default, provider: SunJSSE, class: ...DefaultSSLContext)

    Moving it out of jvm.config fixed CI but left host builds without it. This script
    is the replacement: it sets the setting per-invocation, for this machine only.

.EXAMPLE
    ./scripts/mvn-host.ps1 test
    ./scripts/mvn-host.ps1 -pl booking-service -am verify

.NOTES
    No local JDK? Build in Docker instead — no truststore override needed, because
    the container trusts Maven Central directly:

      docker run --rm -v "${PWD}:/app" -v skbg-m2:/root/.m2 `
        -w /app/backend maven:3.9-eclipse-temurin-17 mvn -B test
#>
[CmdletBinding()]
param(
    # Everything after the script name is passed straight through to Maven.
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArgs
)

$ErrorActionPreference = 'Continue'

if (-not $MavenArgs -or $MavenArgs.Count -eq 0) {
    Write-Host "Usage: ./scripts/mvn-host.ps1 <maven args>   e.g. ./scripts/mvn-host.ps1 test" -ForegroundColor Yellow
    exit 2
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host "No 'mvn' on PATH." -ForegroundColor Red
    Write-Host "Build in Docker instead (see .NOTES in this script, or .mvn/README.md)." -ForegroundColor Yellow
    exit 127
}

# Appended, not replaced: preserve anything the developer already set.
$existing = $env:MAVEN_OPTS
$env:MAVEN_OPTS = ("$existing -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT").Trim()

$backend = Join-Path (Split-Path -Parent $PSScriptRoot) 'backend'
Write-Host "MAVEN_OPTS = $env:MAVEN_OPTS" -ForegroundColor DarkGray
Push-Location $backend
try {
    & mvn @MavenArgs
    $code = $LASTEXITCODE
}
finally {
    Pop-Location
    $env:MAVEN_OPTS = $existing   # never leak the override into the caller's session
}

exit $code
