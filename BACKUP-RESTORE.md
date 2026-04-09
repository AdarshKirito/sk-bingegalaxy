# Backup And Restore

## PostgreSQL

Backups are written by the CronJob in `k8s/backups.yml` into the `postgres-backup-pvc` volume.

Restore flow:

```bash
# 1. Copy a backup file out of the cluster if needed.
kubectl -n sk-binge-galaxy cp <postgres-backup-pod>:/backups/auth_db_YYYYMMDDTHHMMSSZ.sql.gz ./auth_db.sql.gz

# 2. Point the restore script at the target database.
export PGHOST=<managed-postgres-host>
export PGUSER=<postgres-user>
export PGPASSWORD=<postgres-password>
bash scripts/restore-postgres-backup.sh ./auth_db.sql.gz auth_db
```

Repeat the restore for `availability_db`, `booking_db`, and `payment_db` as required.

## MongoDB

Backups are written by the CronJob in `k8s/backups.yml` into the `mongodb-backup-pvc` volume.

Restore flow:

```bash
# 1. Copy a backup file out of the cluster if needed.
kubectl -n sk-binge-galaxy cp <mongodb-backup-pod>:/backups/notification_db_YYYYMMDDTHHMMSSZ.gz ./notification_db.gz

# 2. Restore into the target replica set / cluster.
export MONGODB_URI='mongodb://<user>:<password>@<host1>:27017,<host2>:27017,<host3>:27017/notification_db?authSource=admin&replicaSet=rs0'
bash scripts/restore-mongodb-backup.sh ./notification_db.gz
```

## Validation

After restore, verify:

```bash
# PostgreSQL
psql -h "$PGHOST" -U "$PGUSER" -d auth_db -c '\dt'

# MongoDB
mongosh "$MONGODB_URI" --eval 'db.getSiblingDB("notification_db").getCollectionNames()'
```

Run an application smoke test after restore before reopening traffic.