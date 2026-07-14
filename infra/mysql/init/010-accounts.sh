#!/usr/bin/env bash
set -euo pipefail

credentials=(
  MYSQL_BOOTSTRAP_PASSWORD
  MYSQL_AUTH_MIGRATION_PASSWORD
  MYSQL_COMMERCE_MIGRATION_PASSWORD
  MYSQL_AGENT_MIGRATION_PASSWORD
  MYSQL_AUTH_APP_PASSWORD
  MYSQL_COMMERCE_APP_PASSWORD
  MYSQL_AGENT_APP_PASSWORD
)

for name in "${credentials[@]}"; do
  value="${!name:-}"
  if [[ ! "$value" =~ ^[0-9a-f]{48}$ ]]; then
    echo "Refusing account initialization: $name is not a generated hexadecimal credential." >&2
    exit 1
  fi
done

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root <<SQL
CREATE USER 'bootstrap_admin'@'%' IDENTIFIED BY '${MYSQL_BOOTSTRAP_PASSWORD}';
CREATE ROLE 'bootstrap_grant_role';
GRANT CREATE, CREATE USER ON *.* TO 'bootstrap_admin'@'%';
GRANT 'bootstrap_grant_role' TO 'bootstrap_admin'@'%';
SQL

MYSQL_PWD="$MYSQL_BOOTSTRAP_PASSWORD" mysql --protocol=socket --user=bootstrap_admin <<SQL
CREATE DATABASE commerce_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE cs_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'auth_migration'@'%' IDENTIFIED BY '${MYSQL_AUTH_MIGRATION_PASSWORD}';
CREATE USER 'commerce_migration'@'%' IDENTIFIED BY '${MYSQL_COMMERCE_MIGRATION_PASSWORD}';
CREATE USER 'agent_migration'@'%' IDENTIFIED BY '${MYSQL_AGENT_MIGRATION_PASSWORD}';
CREATE USER 'auth_app'@'%' IDENTIFIED BY '${MYSQL_AUTH_APP_PASSWORD}';
CREATE USER 'commerce_app'@'%' IDENTIFIED BY '${MYSQL_COMMERCE_APP_PASSWORD}';
CREATE USER 'agent_app'@'%' IDENTIFIED BY '${MYSQL_AGENT_APP_PASSWORD}';
SQL

# The grant role is deliberately not a default role. Root seeds its delegation
# capability, while the separately invoked grant job controls role activation
# and the exact grants issued through it.
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root <<SQL
GRANT ALL PRIVILEGES ON commerce_db.* TO 'bootstrap_grant_role' WITH GRANT OPTION;
GRANT ALL PRIVILEGES ON cs_db.* TO 'bootstrap_grant_role' WITH GRANT OPTION;
SQL
