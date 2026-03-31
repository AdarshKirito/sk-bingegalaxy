-- Initial database creation for all microservices (PostgreSQL)
-- This script runs automatically when the postgres container starts for the first time.

CREATE DATABASE auth_db;
CREATE DATABASE availability_db;
CREATE DATABASE booking_db;
CREATE DATABASE payment_db;
