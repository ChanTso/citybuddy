#!/usr/bin/env bash
set -euo pipefail

env_file="${ENV_FILE:-.env}"
required=(
  MYSQL_BOOTSTRAP_PASSWORD
  MYSQL_AUTH_MIGRATION_PASSWORD
  MYSQL_COMMERCE_MIGRATION_PASSWORD
  MYSQL_AGENT_MIGRATION_PASSWORD
  MYSQL_AUTH_APP_PASSWORD
  MYSQL_COMMERCE_APP_PASSWORD
  MYSQL_AGENT_APP_PASSWORD
)

if [[ ! -f "$env_file" ]]; then
  echo "Missing local configuration: $env_file. Run 'make init-local' first." >&2
  exit 1
fi

for name in "${required[@]}"; do
  value="$(sed -n "s/^${name}=//p" "$env_file")"
  if [[ ! "$value" =~ ^[0-9a-f]{48}$ ]]; then
    echo "Invalid or missing $name in $env_file; expected one generated 48-character hexadecimal credential." >&2
    exit 1
  fi
done

port="$(sed -n 's/^MYSQL_PORT=//p' "$env_file")"
if [[ -n "$port" && ! "$port" =~ ^[0-9]{1,5}$ ]]; then
  echo "Invalid MYSQL_PORT in $env_file." >&2
  exit 1
fi
