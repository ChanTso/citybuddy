#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_port_allocator.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb014-test-$$"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
services=(
  mysql
  redis-commerce
  redis-support
  elasticsearch
  rocketmq-namesrv
  rocketmq-broker-proxy
  rocketmq-probe
)
volumes=(
  mysql-data
  redis-commerce-data
  redis-support-data
  elasticsearch-data
  rocketmq-store
)

cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  release_test_ports
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

read_value() {
  local name="$1"
  sed -n "s/^${name}=//p" "$env_file"
}

mysql_count() {
  local user="$1"
  local password="$2"
  local database="$3"
  local table="$4"
  "${compose[@]}" exec -T -e MYSQL_PWD="$password" mysql \
    mysql --protocol=tcp --host=127.0.0.1 --port=3306 --user="$user" \
    --database="$database" --batch --skip-column-names \
    --execute="SELECT COUNT(*) FROM $table WHERE success = TRUE"
}

assert_clean_project() {
  if [[ -n "$("${compose[@]}" ps --all --quiet 2>/dev/null)" ]]; then
    echo "Aggregate integration project unexpectedly has containers before startup." >&2
    exit 1
  fi
  local volume
  for volume in "${volumes[@]}"; do
    if docker volume inspect "${project}_${volume}" >/dev/null 2>&1; then
      echo "Aggregate integration project unexpectedly has volume ${project}_${volume}." >&2
      exit 1
    fi
  done
}

assert_runtime_healthy() {
  local service
  local container_id
  local health
  for service in "${services[@]}"; do
    container_id="$("${compose[@]}" ps --quiet "$service")"
    if [[ -z "$container_id" ]]; then
      echo "Required runtime service is not running: $service" >&2
      exit 1
    fi
    health="$(docker inspect --format '{{.State.Health.Status}}' "$container_id")"
    if [[ "$health" != healthy ]]; then
      echo "Required runtime service is not healthy: $service ($health)" >&2
      exit 1
    fi
  done
}

assert_migrations_complete() {
  local auth_password
  local commerce_password
  local agent_password
  auth_password="$(read_value MYSQL_AUTH_MIGRATION_PASSWORD)"
  commerce_password="$(read_value MYSQL_COMMERCE_MIGRATION_PASSWORD)"
  agent_password="$(read_value MYSQL_AGENT_MIGRATION_PASSWORD)"

  local expected_auth
  local expected_commerce
  local expected_agent
  expected_auth="$(find infra/mysql/migrations/auth -maxdepth 1 -type f -name 'V*__*.sql' | wc -l | tr -d ' ')"
  expected_commerce="$(find infra/mysql/migrations/commerce -maxdepth 1 -type f -name 'V*__*.sql' | wc -l | tr -d ' ')"
  expected_agent="$(find infra/mysql/migrations/agent -maxdepth 1 -type f -name 'V*__*.sql' | wc -l | tr -d ' ')"
  test "$(mysql_count auth_migration "$auth_password" commerce_db auth_schema_history)" = "$expected_auth"
  test "$(mysql_count commerce_migration "$commerce_password" commerce_db commerce_schema_history)" = "$expected_commerce"
  test "$(mysql_count agent_migration "$agent_password" cs_db agent_schema_history)" = "$expected_agent"
}

make ENV_FILE="$env_file" init-local
credential_hash="$(sha256sum "$env_file" | awk '{print $1}')"
assert_clean_project

export MYSQL_PORT REDIS_COMMERCE_PORT REDIS_SUPPORT_PORT ELASTICSEARCH_PORT ELASTICSEARCH_IMAGE
export ROCKETMQ_PROXY_PORT ROCKETMQ_PROBE_IMAGE
allocate_test_ports MYSQL_PORT REDIS_COMMERCE_PORT REDIS_SUPPORT_PORT ELASTICSEARCH_PORT \
  ROCKETMQ_PROXY_PORT
ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
ROCKETMQ_PROBE_IMAGE="citybuddy-rocketmq-probe:${project}"

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" up
assert_runtime_healthy
assert_migrations_complete
echo "Verified clean aggregate startup reaches all health and migration gates."

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" up
assert_runtime_healthy
assert_migrations_complete
test "$(sha256sum "$env_file" | awk '{print $1}')" = "$credential_hash"
echo "Verified repeat aggregate startup is idempotent and preserves existing credentials."

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" down
if [[ -n "$("${compose[@]}" ps --all --quiet)" ]]; then
  echo "make down left aggregate runtime containers behind." >&2
  exit 1
fi
for volume in "${volumes[@]}"; do
  docker volume inspect "${project}_${volume}" >/dev/null
done
echo "Verified make down preserves all durable volumes and removes the aggregate topology."

echo "CB-014 aggregate runtime lifecycle checks passed."
