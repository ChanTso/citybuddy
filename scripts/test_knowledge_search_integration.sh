#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_port_allocator.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb090-test-$$"
allocate_test_ports ELASTICSEARCH_PORT
export ELASTICSEARCH_PORT
export ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)

cleanup() {
  local status=$?
  if ((status != 0)); then
    echo "CB-090 integration failed; collecting Elasticsearch diagnostics." >&2
    "${compose[@]}" ps --all >&2 || true
    "${compose[@]}" logs --no-color elasticsearch >&2 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  release_test_ports
  rm -rf "$tmp_dir"
  return "$status"
}
trap cleanup EXIT

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
"${compose[@]}" up --build --detach --wait --wait-timeout 90 elasticsearch

bootstrap_expected='{"alias":"knowledge_docs_read","documentCount":4,"indexVersion":"knowledge_docs_v1"}'
bootstrap=(
  uv run citybuddy-indexer bootstrap
  --elasticsearch-url "http://127.0.0.1:$ELASTICSEARCH_PORT"
  --index knowledge_docs_v1
  --alias knowledge_docs_read
)
assert_exact "first index bootstrap" "$bootstrap_expected" "$("${bootstrap[@]}")"
assert_exact "idempotent index bootstrap" "$bootstrap_expected" "$("${bootstrap[@]}")"

probe_expected='{"alias":"knowledge_docs_read","denseRecall":"passed","indexVersion":"knowledge_docs_v1","mixedLanguageBm25":"passed","oboCalls":0,"realBoundedResultCount":5,"realRrfTieOrder":"passed","rrfRepeatable":true}'
probe_output="$(
  uv run python scripts/check_knowledge_search.py \
    --elasticsearch-url "http://127.0.0.1:$ELASTICSEARCH_PORT"
)"
assert_exact "knowledge search probe" "$probe_expected" "$probe_output"

echo "CB-090 versioned hybrid knowledge integration checks passed."
