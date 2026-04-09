#!/usr/bin/env bash
set -euo pipefail

BACKUP_FILE=${1:?Usage: restore-mongodb-backup.sh <backup.archive.gz>}

: "${MONGODB_URI:?MONGODB_URI is required}"

if [ ! -f "$BACKUP_FILE" ]; then
  echo "Backup file not found: $BACKUP_FILE" >&2
  exit 1
fi

mongorestore --uri "$MONGODB_URI" --gzip --archive="$BACKUP_FILE" --drop

echo "Restored MongoDB backup from $BACKUP_FILE"