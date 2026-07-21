#!/usr/bin/env bash
set -euo pipefail

env_file="${ENV_FILE:-.env}"

credential() {
  openssl rand -hex 24
}

if [[ -e "$env_file" ]]; then
  support_password="$(sed -n 's/^REDIS_SUPPORT_PASSWORD=//p' "$env_file")"
  agent_cache_password="$(sed -n 's/^REDIS_AGENT_CACHE_PASSWORD=//p' "$env_file")"
  indexer_cache_password="$(sed -n 's/^REDIS_INDEXER_CACHE_PASSWORD=//p' "$env_file")"
  agent_cache_url="$(sed -n 's/^AGENT_SUPPORT_REDIS_URL=//p' "$env_file")"
  indexer_cache_url="$(sed -n 's/^INDEXER_SUPPORT_REDIS_URL=//p' "$env_file")"
  if [[ -z "$agent_cache_password$indexer_cache_password$agent_cache_url$indexer_cache_url" ]]; then
    if [[ ! "$support_password" =~ ^[0-9a-f]{48}$ ]]; then
      echo "Cannot add CB-112 cache identities to malformed local configuration at $env_file." >&2
      exit 1
    fi
    agent_cache_password="$(credential)"
    indexer_cache_password="$(credential)"
    umask 077
    {
      echo "REDIS_AGENT_CACHE_PASSWORD=$agent_cache_password"
      echo "REDIS_INDEXER_CACHE_PASSWORD=$indexer_cache_password"
      echo "AGENT_SUPPORT_REDIS_URL=redis://agent_cache:$agent_cache_password@redis-support:6379/0"
      echo "INDEXER_SUPPORT_REDIS_URL=redis://knowledge_indexer:$indexer_cache_password@redis-support:6379/0"
    } >>"$env_file"
    chmod 600 "$env_file"
    echo "Added CB-112 Support Redis cache identities to $env_file; existing values were preserved."
    exit 0
  fi
  if [[ ! "$agent_cache_password" =~ ^[0-9a-f]{48}$
    || ! "$indexer_cache_password" =~ ^[0-9a-f]{48}$
    || "$agent_cache_url" != "redis://agent_cache:$agent_cache_password@redis-support:6379/0"
    || "$indexer_cache_url" != "redis://knowledge_indexer:$indexer_cache_password@redis-support:6379/0" ]]; then
    echo "Invalid CB-112 Support Redis cache identities in $env_file." >&2
    exit 1
  fi
  echo "Local configuration already exists at $env_file; preserving it unchanged."
  exit 0
fi

env_dir="$(dirname "$env_file")"
mkdir -p "$env_dir"
umask 077

mysql_bootstrap_password="$(credential)"
mysql_auth_migration_password="$(credential)"
mysql_commerce_migration_password="$(credential)"
mysql_agent_migration_password="$(credential)"
mysql_auth_app_password="$(credential)"
mysql_commerce_app_password="$(credential)"
mysql_agent_app_password="$(credential)"
redis_commerce_password="$(credential)"
redis_support_password="$(credential)"
redis_agent_cache_password="$(credential)"
redis_indexer_cache_password="$(credential)"

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
  echo "REDIS_AGENT_CACHE_PASSWORD=$redis_agent_cache_password"
  echo "REDIS_INDEXER_CACHE_PASSWORD=$redis_indexer_cache_password"
  echo "COMMERCE_REDIS_URL=redis://:$redis_commerce_password@redis-commerce:6379/0"
  echo "SUPPORT_REDIS_URL=redis://:$redis_support_password@redis-support:6379/0"
  echo "AGENT_SUPPORT_REDIS_URL=redis://agent_cache:$redis_agent_cache_password@redis-support:6379/0"
  echo "INDEXER_SUPPORT_REDIS_URL=redis://knowledge_indexer:$redis_indexer_cache_password@redis-support:6379/0"
} >"$env_file"

echo "Created fresh synthetic local configuration at $env_file."
