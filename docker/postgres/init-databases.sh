#!/bin/bash
set -e

# Script tự động tạo tất cả database khi PostgreSQL container khởi động lần đầu
# Tạo với UTF8 encoding để tránh lỗi tiếng Việt
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE iam_db ENCODING 'UTF8' TEMPLATE template0;
    CREATE DATABASE tour_catalog_db ENCODING 'UTF8' TEMPLATE template0;
    CREATE DATABASE booking_db ENCODING 'UTF8' TEMPLATE template0;
    CREATE DATABASE payment_db ENCODING 'UTF8' TEMPLATE template0;
    CREATE DATABASE forum_db ENCODING 'UTF8' TEMPLATE template0;
    CREATE DATABASE notification_db ENCODING 'UTF8' TEMPLATE template0;
    CREATE DATABASE analytics_db ENCODING 'UTF8' TEMPLATE template0;
    CREATE DATABASE keycloak_db ENCODING 'UTF8' TEMPLATE template0;
EOSQL

echo "All 8 databases created successfully with UTF8 encoding!"
