#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_port_allocator.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb085-test-$$"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
topic="cb085-spike-$$"
simple_group="cb085-simple-$$"
push_group="cb085-push-$$"
old_index="cb085-spike-$$-old"
new_index="cb085-spike-$$-new"
alias_name="cb085-spike-$$-read"
spike_image="citybuddy-cb085-python-spike:${project}"
topic_created=0

admin() {
  "${compose[@]}" run --rm --no-deps rocketmq-admin "$@"
}

cleanup() {
  if [[ "$topic_created" = 1 ]] && [[ -n "$("${compose[@]}" ps --status running -q rocketmq-broker-proxy 2>/dev/null)" ]]; then
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$simple_group" --removeOffset true >/dev/null 2>&1 || true
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$push_group" --removeOffset true >/dev/null 2>&1 || true
    admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --topic "$topic" >/dev/null 2>&1 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  docker image rm --force "$spike_image" >/dev/null 2>&1 || true
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

ENV_FILE="$env_file" ./scripts/init_local.sh

export ELASTICSEARCH_PORT ELASTICSEARCH_IMAGE ROCKETMQ_PROXY_PORT ROCKETMQ_PROBE_IMAGE
allocate_test_ports ELASTICSEARCH_PORT ROCKETMQ_PROXY_PORT
ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
ROCKETMQ_PROBE_IMAGE="citybuddy-rocketmq-probe:${project}"

docker build --file infra/rocketmq/python-spike/Dockerfile --tag "$spike_image" .
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" rocketmq-store-init
"${compose[@]}" up --build --detach --wait --wait-timeout 90 \
  elasticsearch rocketmq-namesrv rocketmq-broker-proxy

rocketmq_image="apache/rocketmq:5.5.0@sha256:7e8f6c9dbd9df742ed26ba69c00d4ad69e2f86a56f3ca7782ff8144dd0798132"
elasticsearch_image="citybuddy-elasticsearch-ik:${project}"
broker_id="$("${compose[@]}" ps -q rocketmq-broker-proxy)"
elasticsearch_id="$("${compose[@]}" ps -q elasticsearch)"
broker_proxy_image="$(docker inspect --format '{{.Config.Image}}' "$broker_id")"
test "$broker_proxy_image" = "$rocketmq_image"
test "$(docker inspect --format '{{.Config.Image}}' "$elasticsearch_id")" = "$elasticsearch_image"
elasticsearch_info="$("${compose[@]}" exec -T elasticsearch curl --fail --silent http://localhost:9200/)"
assert_contains "Elasticsearch runtime version" '"number" : "8.19.8"' "$elasticsearch_info"
elasticsearch_version="8.19.8"
echo "Verified Broker/Proxy image: $broker_proxy_image"
echo "Verified Elasticsearch runtime: $elasticsearch_info"

admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --topic "$topic" --readQueueNums 1 --writeQueueNums 1
topic_created=1
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$simple_group" --consumeEnable true
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$push_group" --consumeEnable true

spike_log="$tmp_dir/spike.log"
docker run --rm --network "${project}_default" --env HOME=/tmp/cb085-home \
  --entrypoint timeout "$spike_image" \
  180 /opt/citybuddy/.venv/bin/python -m citybuddy_indexer.rocketmq_spike \
  --endpoints "rocketmq-broker-proxy:8081" \
  --topic "$topic" \
  --simple-group "$simple_group" \
  --push-group "$push_group" \
  --elasticsearch-url "http://elasticsearch:9200" \
  --old-index "$old_index" \
  --new-index "$new_index" \
  --alias "$alias_name" \
  --broker-proxy-image "$broker_proxy_image" \
  --elasticsearch-version "$elasticsearch_version" | tee "$spike_log"

output="$(<"$spike_log")"
assert_contains "runtime evidence" '"client": "5.1.1"' "$output"
assert_contains "runtime evidence" '"python": "3.11.15"' "$output"
assert_contains "runtime evidence" '"endpoints": "rocketmq-broker-proxy:8081"' "$output"
assert_contains "runtime evidence" 'apache/rocketmq:5.5.0@sha256:' "$output"
assert_contains "runtime evidence" '"elasticsearch_version": "8.19.8"' "$output"
assert_contains "SDK log limit" '"sdk_log_backup_count": 10' "$output"
assert_contains "runtime evidence" '"selected_mode": "SimpleConsumer"' "$output"
assert_contains "tag filtering and explicit ack" '"event": "simple-explicit-ack"' "$output"
assert_contains "controlled redelivery" '"event": "bounded-redelivery-acked"' "$output"
assert_contains "duplicate handling" '"outcome": "duplicate"' "$output"
assert_contains "long processing" '"event": "long-processing-acked"' "$output"
assert_contains "long processing" '"premature_redelivery": false' "$output"
assert_contains "old version rejection" '"outcome": "older-rejected"' "$output"
assert_contains "push comparison" '"event": "push-mode-observation"' "$output"
assert_contains "push long processing" '"event": "push-long-processing-observation"' "$output"
assert_contains "atomic rebuild" '"event": "validated-atomic-alias-switch"' "$output"
assert_contains "viability decision" '"decision": "viable"' "$output"

admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$simple_group" --removeOffset true
admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$push_group" --removeOffset true
admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic "$topic"
topic_created=0

echo "CB-085 Python RocketMQ consumer viability spike passed."
