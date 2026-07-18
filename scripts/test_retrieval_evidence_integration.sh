#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb091-test-$$"
model_port="$((50000 + ($$ % 500)))"
export MYSQL_PORT="$((44000 + ($$ % 500)))"
export ELASTICSEARCH_PORT="$((45000 + ($$ % 500)))"
export ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
model_pid=""

cleanup() {
  local status=$?
  if [[ -n "$model_pid" ]]; then
    kill "$model_pid" >/dev/null 2>&1 || true
    wait "$model_pid" >/dev/null 2>&1 || true
  fi
  if (( status != 0 )); then
    echo "CB-091 integration failed; collecting diagnostics." >&2
    "${compose[@]}" ps --all >&2 || true
    "${compose[@]}" logs --no-color mysql elasticsearch >&2 || true
    sed -E 's/[0-9a-f]{48}/<redacted>/g' "$tmp_dir/model.log" >&2 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
  return "$status"
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
"${compose[@]}" up --build --detach --wait --wait-timeout 90 mysql elasticsearch
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

uv run python scripts/fake_litellm_server.py --port "$model_port" \
  >"$tmp_dir/model.log" 2>&1 &
model_pid=$!
for _ in {1..40}; do
  if curl --fail --silent "http://127.0.0.1:$model_port/fixture/counts" >/dev/null; then
    break
  fi
  sleep 0.1
done
curl --fail --silent "http://127.0.0.1:$model_port/fixture/counts" >/dev/null

probe_expected='{"atomicRollback":"passed","calibrationVersion":"cb091-calibration-v1","indexVersion":"knowledge_docs_v1","outcomes":7,"replayWithoutExecution":true,"runtimeIsolation":"passed","storedEvidenceCount":3}'
probe_output="$(
  uv run python scripts/check_retrieval_evidence.py \
    --mysql-host 127.0.0.1 \
    --mysql-port "$MYSQL_PORT" \
    --agent-password "$(read_value MYSQL_AGENT_APP_PASSWORD)" \
    --mysql-root-password "$(read_value MYSQL_BOOTSTRAP_PASSWORD)" \
    --auth-password "$(read_value MYSQL_AUTH_APP_PASSWORD)" \
    --commerce-password "$(read_value MYSQL_COMMERCE_APP_PASSWORD)" \
    --elasticsearch-url "http://127.0.0.1:$ELASTICSEARCH_PORT" \
    --model-url "http://127.0.0.1:$model_port"
)"
assert_exact "retrieval evidence probe" "$probe_expected" "$probe_output"

echo "CB-091 rerank, calibrated sufficiency, and atomic retrieval evidence checks passed."
