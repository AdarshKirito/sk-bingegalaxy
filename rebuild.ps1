<#
.SYNOPSIS
  Clean, disk-bounded rebuild of the SK Binge Galaxy stack.

.DESCRIPTION
  Tears the stack down INCLUDING volumes (-v), rebuilds images, brings the
  stack back up, then prunes dangling images and build cache so the Docker
  WSL2 disk (docker_data.vhdx) does not creep upward across rebuilds.

  WARNING: `down -v` DELETES the named volumes (postgres-data, mongo-data,
  etc.) — this WIPES the database. Flyway re-runs every migration on the next
  boot, giving you a clean schema but NO data. This is intentional and is what
  keeps disk usage bounded; do not run it against an environment whose data you
  need. Use -KeepData to rebuild without dropping volumes.

.PARAMETER KeepData
  Rebuild without removing volumes (preserves the database).

.PARAMETER NoPrune
  Skip the post-build image/cache prune.

.EXAMPLE
  ./rebuild.ps1                 # full clean rebuild, volumes removed, then prune
  ./rebuild.ps1 -KeepData       # rebuild but keep the database
#>
[CmdletBinding()]
param(
  [switch]$KeepData,
  [switch]$NoPrune
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

if ($KeepData) {
  Write-Host "==> Stopping stack (KEEPING volumes / database)..." -ForegroundColor Cyan
  docker compose down
} else {
  Write-Host "==> Stopping stack and REMOVING volumes (database will be wiped)..." -ForegroundColor Yellow
  docker compose down -v --remove-orphans
}

Write-Host "==> Building images..." -ForegroundColor Cyan
docker compose build

Write-Host "==> Starting stack..." -ForegroundColor Cyan
docker compose up -d

if (-not $NoPrune) {
  Write-Host "==> Pruning dangling images and build cache (reclaim disk)..." -ForegroundColor Cyan
  docker image prune -f | Out-Null
  docker builder prune -f | Out-Null
}

Write-Host "==> Done. Current state:" -ForegroundColor Green
docker compose ps
docker system df
