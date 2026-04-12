-- Initial database creation for all microservices (PostgreSQL)
-- This script runs automatically when the postgres container starts for the first time.

CREATE DATABASE auth_db;
CREATE DATABASE availability_db;
CREATE DATABASE booking_db;
CREATE DATABASE payment_db;

-- ── Per-service database users (least privilege) ─────────────────────────
-- Each service gets its own user with access ONLY to its own database.
-- For production with managed PostgreSQL, create equivalent users via the
-- cloud console (RDS/Cloud SQL/Azure DB) with the same grants.
--
-- Default passwords below are for LOCAL DEVELOPMENT ONLY.
-- In K8s / production the passwords come from Kubernetes secrets.

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auth_svc') THEN
    CREATE ROLE auth_svc WITH LOGIN PASSWORD 'auth_svc_dev';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'availability_svc') THEN
    CREATE ROLE availability_svc WITH LOGIN PASSWORD 'availability_svc_dev';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'booking_svc') THEN
    CREATE ROLE booking_svc WITH LOGIN PASSWORD 'booking_svc_dev';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'payment_svc') THEN
    CREATE ROLE payment_svc WITH LOGIN PASSWORD 'payment_svc_dev';
  END IF;
END
$$;

GRANT CONNECT ON DATABASE auth_db TO auth_svc;
GRANT CONNECT ON DATABASE availability_db TO availability_svc;
GRANT CONNECT ON DATABASE booking_db TO booking_svc;
GRANT CONNECT ON DATABASE payment_db TO payment_svc;

-- auth_db grants
\c auth_db;
GRANT ALL PRIVILEGES ON SCHEMA public TO auth_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO auth_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO auth_svc;

-- availability_db grants
\c availability_db;
GRANT ALL PRIVILEGES ON SCHEMA public TO availability_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO availability_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO availability_svc;

-- booking_db grants
\c booking_db;
GRANT ALL PRIVILEGES ON SCHEMA public TO booking_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO booking_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO booking_svc;

-- ShedLock table for cluster-safe scheduling (booking_db)
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
GRANT ALL ON TABLE shedlock TO booking_svc;

-- payment_db grants
\c payment_db;
GRANT ALL PRIVILEGES ON SCHEMA public TO payment_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO payment_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO payment_svc;
