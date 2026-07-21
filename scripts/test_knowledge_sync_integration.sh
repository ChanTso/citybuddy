#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_port_allocator.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb111-test-$$"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
topic="cb111-knowledge-sync-$$"
group="cb111-knowledge-indexer-$$"
index="knowledge_docs_v1"
indexer_image="citybuddy-knowledge-indexer:${project}"
proxy_name="${project}-drop-proxy"
topic_created=0

admin() {
  "${compose[@]}" run --rm --no-deps rocketmq-admin "$@"
}

cleanup() {
  docker stop "$proxy_name" >/dev/null 2>&1 || true
  docker rm "$proxy_name" >/dev/null 2>&1 || true
  if [[ "$topic_created" = 1 ]] && [[ -n "$("${compose[@]}" ps --status running -q rocketmq-broker-proxy 2>/dev/null)" ]]; then
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$group" --removeOffset true >/dev/null 2>&1 || true
    admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --topic "$topic" >/dev/null 2>&1 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  docker image rm --force "$indexer_image" >/dev/null 2>&1 || true
  release_test_ports
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

ENV_FILE="$env_file" ./scripts/init_local.sh

export ELASTICSEARCH_PORT ELASTICSEARCH_IMAGE ROCKETMQ_PROXY_PORT ROCKETMQ_PROBE_IMAGE
allocate_test_ports ELASTICSEARCH_PORT ROCKETMQ_PROXY_PORT
ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
ROCKETMQ_PROBE_IMAGE="citybuddy-rocketmq-probe:${project}"

docker build --file infra/knowledge-indexer/Dockerfile --tag "$indexer_image" .
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" rocketmq-store-init
"${compose[@]}" up --build --detach --wait --wait-timeout 90 \
  mysql redis-support elasticsearch rocketmq-namesrv rocketmq-broker-proxy

admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --topic "$topic" --readQueueNums 1 --writeQueueNums 1
topic_created=1
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$group" --consumeEnable true

docker run --rm --network "${project}_default" \
  --volume "$repo_root/scripts:/opt/citybuddy/scripts:ro" \
  --entrypoint /opt/citybuddy/.venv/bin/python "$indexer_image" \
  /opt/citybuddy/scripts/seed_legacy_knowledge_mapping.py \
  --elasticsearch-url http://elasticsearch:9200 --index "$index"
docker run --rm --network "${project}_default" "$indexer_image" \
  bootstrap --elasticsearch-url http://elasticsearch:9200 --index "$index"

docker run --detach --name "$proxy_name" --network "${project}_default" \
  --volume "$repo_root/scripts:/opt/citybuddy/scripts:ro" \
  --entrypoint /opt/citybuddy/.venv/bin/python "$indexer_image" \
  /opt/citybuddy/scripts/drop_response_proxy.py \
  --host 0.0.0.0 --port 8765 --upstream http://elasticsearch:9200 \
  --method PUT --path-prefix "/$index/_doc/faq-cb111-delivery%3Aanswer" \
  --drop-count 1 >/dev/null

docker run --rm --network "${project}_default" \
  --volume "$repo_root/scripts:/opt/citybuddy/scripts:ro" \
  --entrypoint /opt/citybuddy/.venv/bin/python "$indexer_image" \
  /opt/citybuddy/scripts/check_incremental_knowledge_sync.py \
  --endpoints rocketmq-broker-proxy:8081 \
  --topic "$topic" \
  --group "$group" \
  --elasticsearch-url http://elasticsearch:9200 \
  --drop-proxy-url http://"$proxy_name":8765 \
  --index "$index"

# Re-running the baseline bootstrap must preserve and tolerate incremental projection records.
docker run --rm --network "${project}_default" "$indexer_image" \
  bootstrap --elasticsearch-url http://elasticsearch:9200 --index "$index"

if rg -n 'mysql|commerce_db|cs_db|redis' \
  knowledge-indexer/src knowledge-indexer/pyproject.toml infra/knowledge-indexer/Dockerfile; then
  echo "Knowledge indexer production boundary contains a forbidden database/cache dependency." >&2
  exit 1
fi

if "${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD=not-configured mysql --protocol=tcp --host=127.0.0.1 --user=knowledge_indexer --database=commerce_db --execute="SELECT 1"'; then
  echo "Knowledge indexer unexpectedly authenticated to commerce_db." >&2
  exit 1
fi
if "${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD=not-configured mysql --protocol=tcp --host=127.0.0.1 --user=knowledge_indexer --database=cs_db --execute="SELECT 1"'; then
  echo "Knowledge indexer unexpectedly authenticated to cs_db." >&2
  exit 1
fi
redis_denial="$("${compose[@]}" exec -T redis-support redis-cli PING 2>&1)"
if ! grep -Fq 'NOAUTH Authentication required' <<<"$redis_denial"; then
  echo "Support Redis did not deny the credential-free knowledge-indexer boundary." >&2
  exit 1
fi
echo "Verified knowledge-indexer has no MySQL/Support Redis client dependency or credentialed access."

admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$group" --removeOffset true
admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic "$topic"
topic_created=0

echo "CB-111 incremental public-knowledge synchronization integration checks passed."
