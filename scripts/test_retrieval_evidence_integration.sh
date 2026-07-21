#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_dynamic_ports.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb091-test-$$"
model_port=""
MYSQL_PORT=""
ELASTICSEARCH_PORT=""
REDIS_SUPPORT_PORT=""
export ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
model_pid=""

cleanup() {
  local status=$?
  local resource_stop_status=0
  if [[ -n "$model_pid" ]]; then
    kill "$model_pid" >/dev/null 2>&1 || true
    wait "$model_pid" >/dev/null 2>&1 || true
  fi
  if (( status != 0 )); then
    echo "CB-091 integration failed; collecting diagnostics." >&2
    "${compose[@]}" ps --all >&2 || true
    "${compose[@]}" logs --no-color mysql elasticsearch redis-commerce redis-support >&2 || true
    sed -E 's/[0-9a-f]{48}/<redacted>/g' "$tmp_dir/model.log" >&2 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || resource_stop_status=$?
  rm -rf "$tmp_dir"
  finish_test_cleanup "$status" "$resource_stop_status"
}
trap cleanup EXIT

read_value() {
  local name="$1"
  sed -n "s/^${name}=//p" "$env_file"
}

assert_exact() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "Unexpected $label:" >&2
    echo "$actual" >&2
    exit 1
  fi
}

ENV_FILE="$env_file" ./scripts/init_local.sh
"${compose[@]}" up --build --detach --wait --wait-timeout 90 \
  mysql elasticsearch redis-commerce redis-support
compose_host_port MYSQL_PORT mysql 3306
compose_host_port ELASTICSEARCH_PORT elasticsearch 9200
compose_host_port REDIS_SUPPORT_PORT redis-support 6379

for identity in agent_cache knowledge_indexer; do
  password_name="REDIS_AGENT_CACHE_PASSWORD"
  if [[ "$identity" = "knowledge_indexer" ]]; then
    password_name="REDIS_INDEXER_CACHE_PASSWORD"
  fi
  commerce_denial="$(
    "${compose[@]}" exec -T redis-commerce redis-cli --no-auth-warning \
      --user "$identity" --pass "$(read_value "$password_name")" PING 2>&1 || true
  )"
  if ! grep -Fq 'WRONGPASS' <<<"$commerce_denial"; then
    echo "$identity unexpectedly authenticated to Commerce Redis." >&2
    exit 1
  fi
done
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-auth
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-agent
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access

bootstrap=(
  uv run citybuddy-indexer bootstrap
  --elasticsearch-url "http://127.0.0.1:$ELASTICSEARCH_PORT"
  --index knowledge_docs_v1
  --alias knowledge_docs_read
)
assert_exact \
  "knowledge bootstrap" \
  '{"alias":"knowledge_docs_read","documentCount":4,"indexVersion":"knowledge_docs_v1"}' \
  "$("${bootstrap[@]}")"

uv run python scripts/check_faq_cache.py \
  --env-file "$env_file" --redis-port "$REDIS_SUPPORT_PORT" --mode normal
"${compose[@]}" restart redis-support >/dev/null
"${compose[@]}" up --detach --wait --wait-timeout 30 redis-support >/dev/null
compose_host_port REDIS_SUPPORT_PORT redis-support 6379
uv run python scripts/check_faq_cache.py \
  --env-file "$env_file" --redis-port "$REDIS_SUPPORT_PORT" --mode restart
"${compose[@]}" stop redis-support >/dev/null
uv run python scripts/check_faq_cache.py \
  --env-file "$env_file" --redis-port "$REDIS_SUPPORT_PORT" --mode outage
"${compose[@]}" start redis-support >/dev/null
"${compose[@]}" up --detach --wait --wait-timeout 30 redis-support >/dev/null
compose_host_port REDIS_SUPPORT_PORT redis-support 6379

uv run python scripts/fake_litellm_server.py --port 0 \
  >"$tmp_dir/model.log" 2>&1 &
model_pid=$!
process_bound_port model_port uvicorn "$model_pid" "$tmp_dir/model.log" 0
for _ in {1..40}; do
  if curl --fail --silent "http://127.0.0.1:$model_port/fixture/counts" >/dev/null; then
    break
  fi
  sleep 0.1
done
curl --fail --silent "http://127.0.0.1:$model_port/fixture/counts" >/dev/null

probe_expected='{"atomicRollback":"passed","cacheDurableReplay":true,"cacheFinalizeWindow":true,"cacheHitEvidence":true,"cacheOutageFallback":true,"calibrationVersion":"cb091-calibration-v1","indexVersion":"knowledge_docs_v1","outcomes":10,"replayWithoutExecution":true,"runtimeIsolation":"passed","storedEvidenceCount":3}'
probe_output="$(
  uv run python scripts/check_retrieval_evidence.py \
    --mysql-host 127.0.0.1 \
    --mysql-port "$MYSQL_PORT" \
    --agent-password "$(read_value MYSQL_AGENT_APP_PASSWORD)" \
    --mysql-root-password "$(read_value MYSQL_BOOTSTRAP_PASSWORD)" \
    --auth-password "$(read_value MYSQL_AUTH_APP_PASSWORD)" \
    --commerce-password "$(read_value MYSQL_COMMERCE_APP_PASSWORD)" \
    --elasticsearch-url "http://127.0.0.1:$ELASTICSEARCH_PORT" \
    --model-url "http://127.0.0.1:$model_port" \
    --agent-cache-url \
    "redis://agent_cache:$(read_value REDIS_AGENT_CACHE_PASSWORD)@127.0.0.1:$REDIS_SUPPORT_PORT/0" \
    --indexer-cache-url \
    "redis://knowledge_indexer:$(read_value REDIS_INDEXER_CACHE_PASSWORD)@127.0.0.1:$REDIS_SUPPORT_PORT/0"
)"
assert_exact "retrieval evidence probe" "$probe_expected" "$probe_output"

echo "CB-091/CB-112 rerank, cache, and atomic retrieval evidence checks passed."
