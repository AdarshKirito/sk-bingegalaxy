-- distribution_db bootstrap — the distribution bounded context.
--
-- WHY THIS IS A SEPARATE, IDEMPOTENT FILE rather than four more lines in
-- init-databases.sql: `/docker-entrypoint-initdb.d` runs ONCE, when the Postgres
-- data volume is first created. Every developer and environment that already has a
-- postgres-data volume would therefore never get this database, and
-- distribution-service would crash-loop on "database distribution_db does not exist".
--
-- So this script is written to be safe to run any number of times, against a fresh
-- volume or an existing one, and the `postgres-init` one-shot in docker-compose.yml
-- runs it on every `up`. Nothing here may assume it is running for the first time.

-- CREATE DATABASE has no IF NOT EXISTS. This is the standard psql idiom: build the
-- statement only when the database is absent, then \gexec runs whatever the query
-- returned (nothing, if it already exists).
SELECT 'CREATE DATABASE distribution_db'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'distribution_db')\gexec

-- Least privilege, matching the other services: distribution_svc can reach
-- distribution_db and nothing else. The password below is LOCAL DEVELOPMENT ONLY;
-- in K8s it comes from a secret.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'distribution_svc') THEN
    CREATE ROLE distribution_svc WITH LOGIN PASSWORD 'distribution_svc_dev';
  END IF;
END
$$;

GRANT CONNECT ON DATABASE distribution_db TO distribution_svc;

\c distribution_db
GRANT ALL PRIVILEGES ON SCHEMA public TO distribution_svc;
GRANT ALL ON ALL TABLES IN SCHEMA public TO distribution_svc;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO distribution_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO distribution_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO distribution_svc;
