#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_port_allocator.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb012-test-$$"
fault_project="${project}-missing-ik"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
fault_compose=(docker compose --project-name "$fault_project" --env-file "$env_file" --file compose.yaml)
old_index="cb012-probe-$$-old"
new_index="cb012-probe-$$-new"
alias_name="cb012-probe-$$-read"

es_api() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local args=(curl --fail --silent --show-error --request "$method" "http://localhost:9200$path")
  if [[ -n "$body" ]]; then
    args+=(--header "Content-Type: application/json" --data "$body")
  fi
  "${compose[@]}" exec -T elasticsearch "${args[@]}"
}

cleanup() {
  es_api DELETE "/$old_index,$new_index" >/dev/null 2>&1 || true
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  "${fault_compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  release_test_ports
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

assert_contains() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  if ! grep -Fq "$expected" <<<"$actual"; then
    echo "$label did not contain '$expected':" >&2
    echo "$actual" >&2
    exit 1
  fi
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

ENV_FILE="$env_file" ./scripts/init_local.sh

# Isolate the integration project with probed, process-scoped host-port leases.
export MYSQL_PORT REDIS_COMMERCE_PORT REDIS_SUPPORT_PORT ELASTICSEARCH_PORT ELASTICSEARCH_IMAGE
export ROCKETMQ_PROXY_PORT ROCKETMQ_PROBE_IMAGE
allocate_test_ports MYSQL_PORT REDIS_COMMERCE_PORT REDIS_SUPPORT_PORT ELASTICSEARCH_PORT \
  ROCKETMQ_PROXY_PORT
ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
ROCKETMQ_PROBE_IMAGE="citybuddy-rocketmq-probe:${project}"

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" up

plugin_inventory="$(es_api GET '/_cat/plugins?h=component,version&format=json')"
assert_contains "plugin inventory" '"component":"analysis-ik"' "$plugin_inventory"
assert_contains "plugin inventory" '"version":"8.19.8"' "$plugin_inventory"

analysis="$(es_api POST '/_analyze' '{"analyzer":"ik_smart","text":"CityBuddy本地商店支持"}')"
assert_contains "mixed-language analysis" '"token":"citybuddy"' "$analysis"
assert_contains "mixed-language analysis" '"token":"商店"' "$analysis"
echo "Verified analysis-ik 8.19.8 inventory and mixed-language analyzer output."

mapping='{"mappings":{"properties":{"content":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},"embedding":{"type":"dense_vector","dims":3,"index":true,"similarity":"l2_norm"}}}}'
assert_contains "old index creation" '"acknowledged":true' "$(es_api PUT "/$old_index" "$mapping")"
assert_contains "new index creation" '"acknowledged":true' "$(es_api PUT "/$new_index" "$mapping")"

es_api PUT "/$old_index/_doc/nearest?refresh=true" \
  '{"content":"CityBuddy本地商店","embedding":[1.0,0.0,0.0]}' >/dev/null
es_api PUT "/$old_index/_doc/far?refresh=true" \
  '{"content":"远端记录","embedding":[0.0,1.0,0.0]}' >/dev/null
es_api PUT "/$new_index/_doc/validated?refresh=true" \
  '{"content":"已验证新索引","embedding":[0.0,0.0,1.0]}' >/dev/null

knn_result="$(es_api POST "/$old_index/_search" '{"knn":{"field":"embedding","query_vector":[1.0,0.0,0.0],"k":1,"num_candidates":2},"size":1}')"
assert_contains "kNN result" '"_id":"nearest"' "$knn_result"
echo "Verified dense_vector mapping with a real nearest-neighbor query."

seed_alias="{\"actions\":[{\"add\":{\"index\":\"$old_index\",\"alias\":\"$alias_name\"}}]}"
assert_contains "alias seed" '"acknowledged":true' "$(es_api POST "/_aliases" "$seed_alias")"
assert_contains "old alias target" "\"$old_index\"" "$(es_api GET "/_alias/$alias_name")"
assert_contains "validated target count" '"count":1' "$(es_api GET "/$new_index/_count")"
validated_document="$(es_api GET "/$new_index/_doc/validated")"
assert_contains "validated target document" '"found":true' "$validated_document"
assert_contains "validated target content" '"content":"已验证新索引"' "$validated_document"

switch_alias="{\"actions\":[{\"remove\":{\"index\":\"$old_index\",\"alias\":\"$alias_name\"}},{\"add\":{\"index\":\"$new_index\",\"alias\":\"$alias_name\"}}]}"
assert_contains "atomic alias switch" '"acknowledged":true' "$(es_api POST "/_aliases" "$switch_alias")"
alias_result="$(es_api GET "/_alias/$alias_name")"
assert_contains "new alias target" "\"$new_index\"" "$alias_result"
if grep -Fq "\"$old_index\"" <<<"$alias_result"; then
  echo "Atomic alias switch retained the old target." >&2
  exit 1
fi
echo "Verified one atomic alias operation moved the read alias to a validated target."

assert_contains "probe cleanup" '"acknowledged":true' "$(es_api DELETE "/$old_index,$new_index")"
old_status="$("${compose[@]}" exec -T elasticsearch curl --silent --output /dev/null --write-out '%{http_code}' "http://localhost:9200/$old_index")"
new_status="$("${compose[@]}" exec -T elasticsearch curl --silent --output /dev/null --write-out '%{http_code}' "http://localhost:9200/$new_index")"
alias_status="$("${compose[@]}" exec -T elasticsearch curl --silent --output /dev/null --write-out '%{http_code}' "http://localhost:9200/_alias/$alias_name")"
test "$old_status" = 404
test "$new_status" = 404
test "$alias_status" = 404
echo "Verified disposable probe indexes and alias were removed."

"${compose[@]}" down --volumes --remove-orphans

missing_ik_image="docker.elastic.co/elasticsearch/elasticsearch:8.19.8@sha256:1b6a877f18352510860ee065f01472bd37d33ac5eb1d943e0b9ed366b149638c"
assert_fails "startup rejects Elasticsearch without IK" 'container .*elasticsearch.* is unhealthy' \
  env ELASTICSEARCH_IMAGE="$missing_ik_image" \
    make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$fault_project" \
      COMPOSE_WAIT_TIMEOUT=60 COMPOSE_BUILD= up
fault_container_id="$("${fault_compose[@]}" ps -q elasticsearch)"
if [[ -z "$fault_container_id" ]]; then
  echo "Missing-plugin rejection did not leave an inspectable Elasticsearch container." >&2
  exit 1
fi
fault_image="$(docker inspect --format '{{.Config.Image}}' "$fault_container_id")"
if [[ "$fault_image" != "$missing_ik_image" ]]; then
  echo "Missing-plugin rejection used unexpected image: $fault_image" >&2
  exit 1
fi
fault_health="$(docker inspect --format '{{.State.Health.Status}}' "$fault_container_id")"
if [[ "$fault_health" != unhealthy ]]; then
  echo "Missing-plugin rejection ended with unexpected health: $fault_health" >&2
  exit 1
fi
if "${fault_compose[@]}" exec -T elasticsearch \
  test -d /usr/share/elasticsearch/plugins/analysis-ik; then
  echo "Missing-plugin fixture unexpectedly contains the IK plugin directory." >&2
  exit 1
fi
plugin_list="$("${fault_compose[@]}" exec -T elasticsearch bin/elasticsearch-plugin list)"
if grep -Fxq analysis-ik <<<"$plugin_list"; then
  echo "Missing-plugin fixture unexpectedly lists analysis-ik." >&2
  exit 1
fi

echo "CB-012 Elasticsearch and IK integration checks passed."
