#!/bin/bash
# Chạy một lần khi container PostgreSQL khởi tạo lần đầu.
# Mỗi service một database riêng (SRS 4.1: không dùng chung DB / schema).
set -e
for db in nexus_user nexus_catalog nexus_commerce nexus_auction nexus_fulfillment nexus_notification nexus_reputation nexus_keycloak; do
  echo "Creating database $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -c "CREATE DATABASE $db;"
done
