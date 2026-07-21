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
  REDIS_COMMERCE_PASSWORD
  REDIS_SUPPORT_PASSWORD
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

commerce_redis_password="$(sed -n 's/^REDIS_COMMERCE_PASSWORD=//p' "$env_file")"
support_redis_password="$(sed -n 's/^REDIS_SUPPORT_PASSWORD=//p' "$env_file")"
commerce_redis_url="$(sed -n 's/^COMMERCE_REDIS_URL=//p' "$env_file")"
support_redis_url="$(sed -n 's/^SUPPORT_REDIS_URL=//p' "$env_file")"

if [[ "$commerce_redis_url" != "redis://:$commerce_redis_password@redis-commerce:6379/0" ]]; then
  echo "Invalid or missing COMMERCE_REDIS_URL in $env_file; expected the generated Commerce Redis URL." >&2
  exit 1
fi
if [[ "$support_redis_url" != "redis://:$support_redis_password@redis-support:6379/0" ]]; then
  echo "Invalid or missing SUPPORT_REDIS_URL in $env_file; expected the generated Support Redis URL." >&2
  exit 1
fi
if [[ "$commerce_redis_password" == "$support_redis_password" || "$commerce_redis_url" == "$support_redis_url" ]]; then
  echo "Commerce and Support Redis credentials and URLs must be distinct." >&2
  exit 1
fi
