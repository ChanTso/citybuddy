#!/usr/bin/env bash
set -euo pipefail

env_file="${ENV_FILE:-.env}"

if [[ -e "$env_file" ]]; then
  echo "Local configuration already exists at $env_file; preserving it unchanged."
  exit 0
fi

env_dir="$(dirname "$env_file")"
mkdir -p "$env_dir"
umask 077

credential() {
  openssl rand -hex 24
}

mysql_bootstrap_password="$(credential)"
mysql_auth_migration_password="$(credential)"
mysql_commerce_migration_password="$(credential)"
mysql_agent_migration_password="$(credential)"
mysql_auth_app_password="$(credential)"
mysql_commerce_app_password="$(credential)"
mysql_agent_app_password="$(credential)"
redis_commerce_password="$(credential)"
redis_support_password="$(credential)"

{
  echo "# Synthetic local-only credentials. Do not commit this file."
  echo "MYSQL_BOOTSTRAP_PASSWORD=$mysql_bootstrap_password"
  echo "MYSQL_AUTH_MIGRATION_PASSWORD=$mysql_auth_migration_password"
  echo "MYSQL_COMMERCE_MIGRATION_PASSWORD=$mysql_commerce_migration_password"
  echo "MYSQL_AGENT_MIGRATION_PASSWORD=$mysql_agent_migration_password"
  echo "MYSQL_AUTH_APP_PASSWORD=$mysql_auth_app_password"
  echo "MYSQL_COMMERCE_APP_PASSWORD=$mysql_commerce_app_password"
  echo "MYSQL_AGENT_APP_PASSWORD=$mysql_agent_app_password"
  echo "REDIS_COMMERCE_PASSWORD=$redis_commerce_password"
  echo "REDIS_SUPPORT_PASSWORD=$redis_support_password"
  echo "COMMERCE_REDIS_URL=redis://:$redis_commerce_password@redis-commerce:6379/0"
  echo "SUPPORT_REDIS_URL=redis://:$redis_support_password@redis-support:6379/0"
} >"$env_file"

echo "Created fresh synthetic local configuration at $env_file."
