#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_port_allocator.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb013-test-$$"
fault_project="${project}-missing-proxy"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
fault_compose=(docker compose --project-name "$fault_project" --env-file "$env_file" --file compose.yaml)
topic="cb013-probe-$$"
consumer_group="cb013-probe-group-$$"
message_key="cb013-message-$$"
topic_created=0

admin() {
  "${compose[@]}" run --rm --no-deps rocketmq-admin "$@"
}

cleanup() {
  local status=$?
  local resource_stop_status=0
  if [[ "$topic_created" = 1 ]] && [[ -n "$("${compose[@]}" ps --status running -q rocketmq-broker-proxy 2>/dev/null)" ]]; then
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$consumer_group" --removeOffset true >/dev/null 2>&1 || true
    admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --topic "$topic" >/dev/null 2>&1 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || resource_stop_status=$?
  "${fault_compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || resource_stop_status=$?
  rm -rf "$tmp_dir"
  finalize_test_port_cleanup "$status" "$resource_stop_status"
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

export MYSQL_PORT REDIS_COMMERCE_PORT REDIS_SUPPORT_PORT ELASTICSEARCH_PORT ELASTICSEARCH_IMAGE
export ROCKETMQ_PROXY_PORT ROCKETMQ_PROBE_IMAGE
allocate_test_ports MYSQL_PORT REDIS_COMMERCE_PORT REDIS_SUPPORT_PORT ELASTICSEARCH_PORT \
  ROCKETMQ_PROXY_PORT
ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
ROCKETMQ_PROBE_IMAGE="citybuddy-rocketmq-probe:${project}"

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" up

rocketmq_image="apache/rocketmq:5.5.0@sha256:7e8f6c9dbd9df742ed26ba69c00d4ad69e2f86a56f3ca7782ff8144dd0798132"
namesrv_id="$("${compose[@]}" ps -q rocketmq-namesrv)"
broker_id="$("${compose[@]}" ps -q rocketmq-broker-proxy)"
probe_id="$("${compose[@]}" ps -q rocketmq-probe)"
test -n "$namesrv_id"
test -n "$broker_id"
test -n "$probe_id"
test "$(docker inspect --format '{{.Config.Image}}' "$namesrv_id")" = "$rocketmq_image"
test "$(docker inspect --format '{{.Config.Image}}' "$broker_id")" = "$rocketmq_image"
test "$(docker inspect --format '{{.State.Health.Status}}' "$namesrv_id")" = healthy
test "$(docker inspect --format '{{.State.Health.Status}}' "$broker_id")" = healthy
test "$(docker inspect --format '{{.State.Health.Status}}' "$probe_id")" = healthy

probe_health="$(docker inspect --format '{{range .State.Health.Log}}{{.Output}}{{end}}' "$probe_id")"
assert_contains "Proxy client readiness" \
  'PROXY_ROUTE_OK endpoint=rocketmq-broker-proxy:8081 topic=cb013-readiness' "$probe_health"
proxy_binding="$("${compose[@]}" port rocketmq-broker-proxy 8081)"
assert_contains "Proxy host binding" "127.0.0.1:$ROCKETMQ_PROXY_PORT" "$proxy_binding"
echo "Verified pinned NameServer and Broker/Proxy images plus gRPC client-level readiness."

admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --topic "$topic" --readQueueNums 1 --writeQueueNums 1
topic_created=1
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$consumer_group" --consumeEnable true

round_trip="$("${compose[@]}" run --rm --no-deps rocketmq-probe \
  roundtrip rocketmq-broker-proxy:8081 "$topic" "$consumer_group" "$message_key")"
assert_contains "round-trip producer" \
  "PRODUCED endpoint=rocketmq-broker-proxy:8081 topic=$topic key=$message_key" "$round_trip"
assert_contains "round-trip consumer" "CONSUMED topic=$topic key=$message_key" "$round_trip"
assert_contains "round-trip result" \
  "ROUND_TRIP_OK endpoint=rocketmq-broker-proxy:8081 topic=$topic key=$message_key produced=1 consumed=1" \
  "$round_trip"
echo "$round_trip"

admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$consumer_group" --removeOffset true
admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic "$topic"
topic_created=0
topic_inventory="$(admin topicList --namesrvAddr rocketmq-namesrv:9876)"
if grep -Fxq "$topic" <<<"$topic_inventory"; then
  echo "Disposable RocketMQ topic remained after cleanup: $topic" >&2
  exit 1
fi
echo "Verified uniquely identified normal-message round trip and explicit topic/group cleanup."

"${compose[@]}" down --volumes --remove-orphans

assert_fails "startup rejects a Broker path without RocketMQ 5 Proxy" \
  'container .*rocketmq-probe.* is unhealthy' \
  env ROCKETMQ_PROXY_ARGS= \
    make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$fault_project" COMPOSE_WAIT_TIMEOUT=60 up

fault_broker_id="$("${fault_compose[@]}" ps -q rocketmq-broker-proxy)"
fault_probe_id="$("${fault_compose[@]}" ps -q rocketmq-probe)"
test -n "$fault_broker_id"
test -n "$fault_probe_id"
fault_command="$(docker inspect --format '{{json .Config.Cmd}}' "$fault_broker_id")"
if grep -Fq -- '--enable-proxy' <<<"$fault_command"; then
  echo "Missing-Proxy fixture unexpectedly enabled Proxy: $fault_command" >&2
  exit 1
fi
test "$(docker inspect --format '{{.State.Health.Status}}' "$fault_probe_id")" = unhealthy
fault_health_log="$(docker inspect --format '{{range .State.Health.Log}}{{.Output}}{{end}}' "$fault_probe_id")"
test -n "$fault_health_log"

echo "CB-013 RocketMQ Broker and Proxy integration checks passed."
