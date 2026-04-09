#!/usr/bin/env bash
set -euo pipefail

BACKUP_FILE=${1:?Usage: restore-postgres-backup.sh <backup.sql.gz> <database>}
DATABASE_NAME=${2:?Usage: restore-postgres-backup.sh <backup.sql.gz> <database>}

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"

if [ ! -f "$BACKUP_FILE" ]; then
  echo "Backup file not found: $BACKUP_FILE" >&2
  exit 1
fi

gunzip -c "$BACKUP_FILE" | psql -v ON_ERROR_STOP=1 -h "$PGHOST" -U "$PGUSER" -d "$DATABASE_NAME"

echo "Restored PostgreSQL backup into $DATABASE_NAME on $PGHOST"