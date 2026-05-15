#!/bin/bash
set -e

# Script tự động tạo tất cả database khi PostgreSQL container khởi động lần đầu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE iam_db;
    CREATE DATABASE tour_catalog_db;
    CREATE DATABASE booking_db;
    CREATE DATABASE payment_db;
    CREATE DATABASE forum_db;
    CREATE DATABASE notification_db;
    CREATE DATABASE analytics_db;
    CREATE DATABASE keycloak_db;
EOSQL

echo "All 8 databases created successfully!"
