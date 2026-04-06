-- Initial database creation for all microservices (PostgreSQL)
-- This script runs automatically when the postgres container starts for the first time.

CREATE DATABASE auth_db;
CREATE DATABASE availability_db;
CREATE DATABASE booking_db;
CREATE DATABASE payment_db;

-- ShedLock table for cluster-safe scheduling (booking_db)
\c booking_db;
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
