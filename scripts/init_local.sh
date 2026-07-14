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

{
  echo "# Synthetic local-only credentials. Do not commit this file."
  echo "MYSQL_BOOTSTRAP_PASSWORD=$(credential)"
  echo "MYSQL_AUTH_MIGRATION_PASSWORD=$(credential)"
  echo "MYSQL_COMMERCE_MIGRATION_PASSWORD=$(credential)"
  echo "MYSQL_AGENT_MIGRATION_PASSWORD=$(credential)"
  echo "MYSQL_AUTH_APP_PASSWORD=$(credential)"
  echo "MYSQL_COMMERCE_APP_PASSWORD=$(credential)"
  echo "MYSQL_AGENT_APP_PASSWORD=$(credential)"
  echo "MYSQL_PORT=3306"
} >"$env_file"

echo "Created fresh synthetic local configuration at $env_file."
