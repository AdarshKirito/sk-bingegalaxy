#!/usr/bin/env bash
# Clean, disk-bounded rebuild of the SK Binge Galaxy stack.
#
# Tears the stack down INCLUDING volumes (-v), rebuilds images, brings it back
# up, then prunes dangling images + build cache so the Docker WSL2 disk
# (docker_data.vhdx) does not creep upward across rebuilds.
#
# WARNING: `down -v` DELETES named volumes (postgres-data, mongo-data, …) — this
# WIPES the database. Flyway re-runs all migrations on the next boot (clean
# schema, no data). This is intentional and keeps disk usage bounded. Pass
# --keep-data to rebuild without dropping volumes.
#
# Usage:
#   ./rebuild.sh                # full clean rebuild, volumes removed, then prune
#   ./rebuild.sh --keep-data    # rebuild but keep the database
#   ./rebuild.sh --no-prune     # skip the post-build prune
set -euo pipefail
cd "$(dirname "$0")"

KEEP_DATA=0
NO_PRUNE=0
for arg in "$@"; do
  case "$arg" in
    --keep-data) KEEP_DATA=1 ;;
    --no-prune)  NO_PRUNE=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

if [ "$KEEP_DATA" -eq 1 ]; then
  echo "==> Stopping stack (KEEPING volumes / database)..."
  docker compose down
else
  echo "==> Stopping stack and REMOVING volumes (database will be wiped)..."
  docker compose down -v --remove-orphans
fi

echo "==> Building images..."
docker compose build

echo "==> Starting stack..."
docker compose up -d

if [ "$NO_PRUNE" -eq 0 ]; then
  echo "==> Pruning dangling images and build cache (reclaim disk)..."
  docker image prune -f >/dev/null
  docker builder prune -f >/dev/null
fi

echo "==> Done. Current state:"
docker compose ps
docker system df
