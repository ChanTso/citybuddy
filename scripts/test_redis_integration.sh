#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_dynamic_ports.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb011-test-$$"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)

cleanup() {
  local status=$?
  local resource_stop_status=0
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || resource_stop_status=$?
  rm -rf "$tmp_dir"
  finish_test_cleanup "$status" "$resource_stop_status"
}
trap cleanup EXIT

read_value() {
  local name="$1"
  sed -n "s/^${name}=//p" "$env_file"
}

redis_query() {
  local service="$1"
  local password="$2"
  shift 2
  "${compose[@]}" exec -T "$service" redis-cli --no-auth-warning --pass "$password" "$@"
}

assert_fails() {
  local label="$1"
  local expected="$2"
  shift 2
  local log="$tmp_dir/rejection.log"
  local status
  if "$@" >"$log" 2>&1; then
    echo "Expected rejection succeeded unexpectedly: $label" >&2
    cat "$log" >&2
    exit 1
  else
    status=$?
  fi
  if ! grep -Eiq "$expected" "$log"; then
    echo "Rejection '$label' failed for an unexpected reason (exit $status):" >&2
    sed -E 's/[0-9a-f]{48}/<redacted>/g' "$log" >&2
    exit 1
  fi
  echo "Verified rejection (exit $status): $label"
  sed -E 's/[0-9a-f]{48}/<redacted>/g' "$log"
}

assert_unauthenticated_rejected() {
  local service="$1"
  local output
  output="$("${compose[@]}" exec -T "$service" redis-cli PING 2>&1)"
  if ! grep -Fq 'NOAUTH Authentication required' <<<"$output" || grep -qx PONG <<<"$output"; then
    echo "$service did not reject an unauthenticated command:" >&2
    echo "$output" >&2
    exit 1
  fi
  echo "Verified unauthenticated command rejection: $service"
}

wait_for_health() {
  local service="$1"
  local expected="$2"
  local container_id
  local health
  container_id="$("${compose[@]}" ps -q "$service")"
  for _ in $(seq 1 30); do
    health="$(docker inspect --format '{{.State.Health.Status}}' "$container_id")"
    if [[ "$health" == "$expected" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $service health=$expected." >&2
  docker inspect --format '{{json .State.Health}}' "$container_id" >&2
  return 1
}

break_and_restore_health() {
  local service="$1"
  local password="$2"
  local controlled_password
  controlled_password="$(printf '%048d' 0)"

  redis_query "$service" "$password" CONFIG SET requirepass "$controlled_password"
  wait_for_health "$service" unhealthy
  assert_fails "$service health failure makes startup non-zero" 'unhealthy|failed|timeout' \
    make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" COMPOSE_WAIT_TIMEOUT=15 up
  redis_query "$service" "$controlled_password" CONFIG SET requirepass "$password"
  wait_for_health "$service" healthy
}

ENV_FILE="$env_file" ./scripts/init_local.sh

export ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
export ROCKETMQ_PROBE_IMAGE="citybuddy-rocketmq-probe:${project}"

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" up

commerce_password="$(read_value REDIS_COMMERCE_PASSWORD)"
support_password="$(read_value REDIS_SUPPORT_PASSWORD)"
commerce_url="$(read_value COMMERCE_REDIS_URL)"
support_url="$(read_value SUPPORT_REDIS_URL)"

test "$commerce_url" = "redis://:$commerce_password@redis-commerce:6379/0"
test "$support_url" = "redis://:$support_password@redis-support:6379/0"
test "$commerce_url" != "$support_url"

commerce_container="$("${compose[@]}" ps -q redis-commerce)"
support_container="$("${compose[@]}" ps -q redis-support)"
test -n "$commerce_container"
test -n "$support_container"
test "$commerce_container" != "$support_container"

commerce_mount="$(docker inspect --format '{{range .Mounts}}{{.Name}}:{{.Destination}}{{end}}' "$commerce_container")"
support_mount="$(docker inspect --format '{{range .Mounts}}{{.Name}}:{{.Destination}}{{end}}' "$support_container")"
test "$commerce_mount" = "${project}_redis-commerce-data:/data"
test "$support_mount" = "${project}_redis-support-data:/data"
test "$commerce_mount" != "$support_mount"
echo "Verified distinct Redis URLs, containers, and data paths."

test "$(redis_query redis-commerce "$commerce_password" PING)" = PONG
test "$(redis_query redis-commerce "$commerce_password" CONFIG GET maxmemory-policy | tail -n 1)" = noeviction
test "$(redis_query redis-commerce "$commerce_password" CONFIG GET appendonly | tail -n 1)" = yes
test "$(redis_query redis-support "$support_password" PING)" = PONG
test "$(redis_query redis-support "$support_password" CONFIG GET maxmemory-policy | tail -n 1)" = volatile-lfu
test "$(redis_query redis-support "$support_password" CONFIG GET appendonly | tail -n 1)" = no
test "$(redis_query redis-support "$support_password" CONFIG GET maxmemory | tail -n 1)" -gt 0
redis_query redis-support "$support_password" SET cb011:support:ttl ephemeral EX 60 >/dev/null
test "$(redis_query redis-support "$support_password" TTL cb011:support:ttl)" -gt 0
echo "Verified effective Commerce and Support Redis policies with authenticated commands."

persistence_value="cb011-persist-$$"
redis_query redis-commerce "$commerce_password" SET cb011:commerce:persistence "$persistence_value" >/dev/null
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" down
docker volume inspect "${project}_redis-commerce-data" >/dev/null
docker volume inspect "${project}_redis-support-data" >/dev/null
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" up
test "$(redis_query redis-commerce "$commerce_password" GET cb011:commerce:persistence)" = "$persistence_value"
echo "Verified Commerce Redis AOF data survives the documented non-destructive restart path."

assert_unauthenticated_rejected redis-commerce
assert_unauthenticated_rejected redis-support

break_and_restore_health redis-commerce "$commerce_password"
break_and_restore_health redis-support "$support_password"

echo "CB-011 dual Redis integration checks passed."
