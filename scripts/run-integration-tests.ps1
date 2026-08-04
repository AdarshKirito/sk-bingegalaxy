<#
.SYNOPSIS
    Runs the Testcontainers integration tests on a machine with no local JDK.

.DESCRIPTION
    Testcontainers needs to talk to a Docker daemon. On Windows, Docker Desktop
    serves its API over a named pipe and proxies it; mounting /var/run/docker.sock
    into a build container yields a socket that answers HTTP 400 to /info, so
    Testcontainers reports "Could not find a valid Docker environment".

    The fix is the same one CI systems use (GitLab, Jenkins Kubernetes agents):
    run a Docker-in-Docker SIDECAR and point the build at it over TCP. No Docker
    Desktop setting has to change, and nothing is installed on the host.

        [ maven container ] --DOCKER_HOST=tcp://docker:2375--> [ docker:dind ]
                    \                                              /
                     \-------------- tc-net bridge ---------------/

    The dind container needs --privileged (it runs a real daemon). Ryuk, the
    Testcontainers reaper, is disabled because this dind instance is torn down
    wholesale at the end, which reclaims everything Ryuk would have.

.NOTES
    PowerShell 5.1 turns a native command's stderr into ErrorRecords, and with
    $ErrorActionPreference='Stop' those become thrown NativeCommandErrors — even
    when the command SUCCEEDED. `docker info` against dind always prints a
    deprecation notice about the unencrypted API, so this script keeps
    $ErrorActionPreference at 'Continue' and gates on $LASTEXITCODE instead.

.EXAMPLE
    ./scripts/run-integration-tests.ps1
    ./scripts/run-integration-tests.ps1 -Test OccupancyContentionIT
    ./scripts/run-integration-tests.ps1 -Modules common-lib,distribution-service
    ./scripts/run-integration-tests.ps1 -ResetImageCache   # reclaim the sidecar's disk
#>
[CmdletBinding()]
param(
    # Surefire -Dtest filter. Default runs every *IT class.
    [string] $Test = '*IT',
    # Maven modules to build.
    [string] $Modules = 'common-lib,booking-service',
    # Repo root; defaults to the parent of this script's directory.
    [string] $RepoRoot = (Split-Path -Parent $PSScriptRoot),
    # Wipe the sidecar's image cache before running. Only needed to reclaim disk or to
    # force a re-pull; a normal run should keep it.
    [switch] $ResetImageCache
)

# Deliberately NOT 'Stop' — see .NOTES. Failures are detected via $LASTEXITCODE.
$ErrorActionPreference = 'Continue'

$network   = 'tc-net'
$dindName  = 'tc-dind'
$cacheVol  = 'tc-dind-cache'
$mavenImg  = 'maven:3.9-eclipse-temurin-17'   # Debian, NOT alpine — see .mvn/README.md
$dindImg   = 'docker:27-dind'

function Invoke-Quiet {
    # Runs a native command, discarding all output and never throwing.
    param([scriptblock] $Command)
    try { & $Command 2>$null | Out-Null } catch { }
}

function Cleanup {
    # The image cache volume deliberately SURVIVES cleanup — see the sidecar comment.
    Write-Host "[cleanup] removing dind sidecar and network..." -ForegroundColor DarkGray
    Invoke-Quiet { docker rm -f $dindName }
    Invoke-Quiet { docker network rm $network }
}

$exitCode = 1
try {
    Invoke-Quiet { docker info }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Docker is not running. Start Docker Desktop and retry." -ForegroundColor Red
        exit 1
    }

    Cleanup   # in case a previous run died mid-way

    if ($ResetImageCache) {
        Write-Host "[0/3] discarding the sidecar image cache..." -ForegroundColor DarkGray
        Invoke-Quiet { docker volume rm $cacheVol }
    }

    Write-Host "[1/3] creating network + Docker-in-Docker sidecar..." -ForegroundColor Cyan
    Invoke-Quiet { docker network create $network }
    # /var/lib/docker on a NAMED VOLUME. The sidecar is destroyed after every run, so
    # without this the daemon starts empty and re-pulls postgres:16-alpine each time —
    # measured at 18m39s on this connection, against ~5s of actual schema testing.
    #
    # The volume costs a few hundred MB and, unlike a container layer, it is not
    # reclaimed by `docker system prune` — a deliberate trade given how badly the
    # Docker disk has grown here before. `-ResetImageCache` reclaims it on demand.
    Invoke-Quiet { docker volume create $cacheVol }
    Invoke-Quiet {
        docker run -d --name $dindName --privileged `
            --network $network --network-alias docker `
            -v "${cacheVol}:/var/lib/docker" `
            -e DOCKER_TLS_CERTDIR="" `
            $dindImg --host=tcp://0.0.0.0:2375 --tls=false
    }

    Write-Host "[2/3] waiting for the sidecar daemon..." -ForegroundColor Cyan
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        Invoke-Quiet { docker exec $dindName docker info }
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }
    if (-not $ready) {
        Write-Host "dind daemon did not become ready in 60s" -ForegroundColor Red
        exit 1
    }

    Write-Host "[3/3] running integration tests ($Test)..." -ForegroundColor Cyan
    # MAVEN_OPTS overrides .mvn/jvm.config. Not needed since jvm.config was made
    # portable, but harmless and keeps this working against an older checkout.
    docker run --rm --network $network `
        -v "${RepoRoot}:/app" -v skbg-m2:/root/.m2 `
        -e MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=JKS" `
        -e DOCKER_HOST="tcp://docker:2375" `
        -e TESTCONTAINERS_RYUK_DISABLED=true `
        -w /app/backend $mavenImg `
        mvn -B -pl $Modules -am test `
            "-Dtest=$Test" `
            "-DfailIfNoSpecifiedTests=false" `
            "-Dsurefire.failIfNoSpecifiedTests=false" `
            "-Dtestcontainers.enabled=true"

    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) {
        Write-Host "`nIntegration tests passed." -ForegroundColor Green
    } else {
        Write-Host "`nIntegration tests FAILED (exit $exitCode)." -ForegroundColor Red
    }
}
finally {
    Cleanup
}

exit $exitCode
