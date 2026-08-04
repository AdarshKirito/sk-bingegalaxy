# Tool Detection Results — AUD-2026-07-25-01 (execution phase)

> Machine: Windows 11 (NT 10.0.26200), PowerShell 5.1.26100, 64-bit · Detected 2026-07-25
> Method: `Get-Command` sweep + filesystem probe of common install roots + daemon/service checks.

## PATH sweep (`Get-Command`)

| Tool | Result |
|---|---|
| java / javac | NOT FOUND |
| mvn | NOT FOUND |
| node / npm / npx / corepack / pnpm / yarn | NOT FOUND |
| docker | **FOUND** — C:\Program Files\Docker\Docker\resources\bin\docker.exe |
| docker-compose | **FOUND** — same directory (v2 shim) |
| kubectl | FOUND (Docker Desktop bundled) |
| podman / nerdctl | NOT FOUND |
| k6 | NOT FOUND on PATH — **repo ships binary** k6_bin/k6-v0.54.0-windows-amd64/k6.exe → `k6.exe v0.54.0 (go1.23.1, windows/amd64)` verified executable |
| psql | NOT FOUND on host — available inside skbg-postgres container |
| wsl | FOUND — distro `docker-desktop` **Running** (WSL2) |
| winget | FOUND (recovery path, not needed — container route chosen) |
| choco / scoop / nvm | NOT FOUND |

## Filesystem probe (all NOT FOUND)

C:\Program Files\Java · C:\Program Files\Eclipse Adoptium · C:\Program Files\Microsoft\jdk* · Zulu · Amazon Corretto · %USERPROFILE%\.jdks · C:\Program Files\Apache\maven* · C:\Program Files\nodejs · %LOCALAPPDATA%\Programs\nodejs · %APPDATA%\nvm · .sdkman · C:\tools

## Repository-provided tooling

| Item | Result |
|---|---|
| Maven Wrapper (mvnw) | NOT present anywhere in repo (searched depth-2 recursive) |
| k6 binary | PRESENT and runs (v0.54.0) |

## Docker daemon

- `docker version --format {{.Server.Version}}` → **29.6.1** — daemon **RUNNING** (was DOWN during the July-25 static pass; Docker Desktop has since started)
- Full application stack auto-started via restart policies: 15 project containers observed **healthy** (see CURRENT-RUNTIME-VERIFICATION.md)

## Version requirements extracted from source (read-only)

| Requirement | Source | Value |
|---|---|---|
| Java | backend/pom.xml `<java.version>` | **17** |
| Spring Boot | backend/pom.xml | 3.4.5 |
| Node engines | frontend/package.json | not pinned (no `engines` field) — node:20 chosen to match Dockerfile |

## Local images already present (no pull needed)

| Image | Created | Use |
|---|---|---|
| maven:3.9-eclipse-temurin-17 | 2026-07-02 | backend build/test container (Java 17 = exact requirement) |
| node:20 / node:20-alpine | 2026-04 | frontend install/test/build container |
| postgres:16-alpine, mongo:7, redis:7-alpine, cp-kafka:7.6.0, cp-zookeeper:7.6.0, zipkin:3.5.1 | various | runtime stack |
| sk-binge-galaxy-* (9 service images) | 2026-07-18 … 2026-07-24 | running stack — **provenance vs HEAD checked separately** |

## Decision

Containerized execution selected over host installation: Java 17 + Maven and Node 20 are exact-match available as local images; no host mutation, no PATH changes, fully isolated via audit-specific named volumes (`skbg-audit-m2`, `skbg-audit-fe`) and logs outside the source tree (%TEMP%\skbg-audit\). winget host installs kept as fallback — not needed.
