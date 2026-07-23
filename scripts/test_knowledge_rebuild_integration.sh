#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_dynamic_ports.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb113-test-$$"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
topic="cb113-knowledge-rebuild-$$"
group="cb113-knowledge-rebuild-$$"
indexer_image="citybuddy-knowledge-indexer:${project}"
commerce_container=""
commerce_port=""
topic_created=0

admin() {
  "${compose[@]}" run --rm --no-deps rocketmq-admin "$@"
}

read_value() {
  local name="$1"
  sed -n "s/^${name}=//p" "$env_file"
}

mysql_query() {
  local statement="$1"
  "${compose[@]}" exec -T -e MYSQL_PWD="$(read_value MYSQL_COMMERCE_APP_PASSWORD)" mysql \
    mysql --protocol=tcp --host=127.0.0.1 --port=3306 --user=commerce_app \
    --database=commerce_db --execute="$statement"
}

cleanup() {
  local status=$?
  local resource_stop_status=0
  if [[ -n "$commerce_container" ]]; then
    docker rm --force "$commerce_container" >/dev/null 2>&1 || resource_stop_status=$?
  fi
  if [[ "$topic_created" = 1 ]] && [[ -n "$("${compose[@]}" ps --status running -q rocketmq-broker-proxy 2>/dev/null)" ]]; then
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$group" --removeOffset true >/dev/null 2>&1 || true
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$group-replay" --removeOffset true >/dev/null 2>&1 || true
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$group-concurrency" --removeOffset true >/dev/null 2>&1 || true
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$group-restart" --removeOffset true >/dev/null 2>&1 || true
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$group-race" --removeOffset true >/dev/null 2>&1 || true
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$group-outage" --removeOffset true >/dev/null 2>&1 || true
    admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --groupName "$group-commit" --removeOffset true >/dev/null 2>&1 || true
    admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
      --topic "$topic" >/dev/null 2>&1 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || resource_stop_status=$?
  docker image rm --force "$indexer_image" >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
  finish_test_cleanup "$status" "$resource_stop_status"
}
trap cleanup EXIT

ENV_FILE="$env_file" ./scripts/init_local.sh
export ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"
export ROCKETMQ_PROBE_IMAGE="citybuddy-rocketmq-probe:${project}"

docker build --file infra/knowledge-indexer/Dockerfile --tag "$indexer_image" .
./mvnw -q -pl commerce-service -am -DskipTests package
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" rocketmq-store-init
"${compose[@]}" up --build --detach --wait --wait-timeout 90 \
  mysql redis-support elasticsearch rocketmq-namesrv rocketmq-broker-proxy
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" \
  grant-access migrate-auth migrate-commerce
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access

mysql_query "
INSERT INTO product
  (product_id, name, description, price_minor, currency, stock_quantity, available,
   publication_state, publication_version)
VALUES
  ('product-jasmine-tea', '茉莉绿茶 Jasmine green tea',
   'A public product description for jasmine green tea with a floral aroma. 茉莉绿茶带有清新的花香。',
   1200, 'CNY', 10, TRUE, 'PUBLISHED', 3);
INSERT INTO commerce_outbox
  (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload,
   publication_state, publish_attempts, created_at, published_at)
VALUES
  ('22222222-2222-4222-8222-222222222222', 'PRODUCT', 'product-jasmine-tea', 3,
   'PRODUCT_PUBLICATION_CHANGED', JSON_OBJECT('productId', 'product-jasmine-tea'),
   'PUBLISHED', 1, '2026-07-22 00:00:03.123456', '2026-07-22 00:00:03.123456');
INSERT INTO faq_source
  (faq_id, draft_question, draft_answer, draft_revision, working_state,
   published_question, published_answer, published_version, published_at, created_at, updated_at)
VALUES
  ('faq-delivery', '配送说明 Delivery guide',
   'Public delivery guidance describes the merchant delivery area and estimated handoff process.',
   1, 'PUBLISHED', '配送说明 Delivery guide',
   'Public delivery guidance describes the merchant delivery area and estimated handoff process.',
   2, '2026-07-22 00:00:02.123456', '2026-07-22 00:00:02.123456', '2026-07-22 00:00:02.123456'),
  ('faq-refund-policy', '退款政策 Refund policy',
   'Eligible unused goods may be requested for return or refund under the merchant policy.',
   1, 'PUBLISHED', '退款政策 Refund policy',
   'Eligible unused goods may be requested for return or refund under the merchant policy.',
   1, '2026-07-22 00:00:01.123456', '2026-07-22 00:00:01.123456', '2026-07-22 00:00:01.123456'),
  ('faq-store-hours', '营业时间 Store hours',
   'Public store-hours guidance explains ordinary opening hours.',
   1, 'PUBLISHED', '营业时间 Store hours',
   'Public store-hours guidance explains ordinary opening hours.',
   1, '2026-07-22 00:00:04.123456', '2026-07-22 00:00:04.123456', '2026-07-22 00:00:04.123456');
INSERT INTO faq_publication_command
  (idempotency_key, event_id, faq_id, expected_draft_revision,
   expected_published_version, source_version, intent_hash, occurred_at, created_at)
VALUES
  ('cb113-delivery', '33333333-3333-4333-8333-333333333333', 'faq-delivery', 1, 1, 2,
   REPEAT('3', 64), '2026-07-22 00:00:02.123456', '2026-07-22 00:00:02.123456'),
  ('cb113-refund', '11111111-1111-4111-8111-111111111111', 'faq-refund-policy', 1, 0, 1,
   REPEAT('1', 64), '2026-07-22 00:00:01.123456', '2026-07-22 00:00:01.123456'),
  ('cb113-hours', '44444444-4444-4444-8444-444444444444', 'faq-store-hours', 1, 0, 1,
   REPEAT('4', 64), '2026-07-22 00:00:04.123456', '2026-07-22 00:00:04.123456');
"

snapshot_client="knowledge-indexer"
snapshot_secret="$(openssl rand -hex 24)"
commerce_container="$(docker run --detach --rm \
  --name "$project-commerce" \
  --network "${project}_default" \
  --network-alias commerce-snapshot \
  --publish '127.0.0.1::8080' \
  --volume "$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar:/opt/citybuddy/commerce.jar:ro" \
  --env SPRING_DATASOURCE_PASSWORD="$(read_value MYSQL_COMMERCE_APP_PASSWORD)" \
  eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd \
  java -jar /opt/citybuddy/commerce.jar \
  --server.port=8080 \
  --spring.datasource.url='jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true' \
  --spring.datasource.username=commerce_app \
  --citybuddy.knowledge-snapshot.enabled=true \
  --citybuddy.knowledge-snapshot.client-id="$snapshot_client" \
  --citybuddy.knowledge-snapshot.client-secret="$snapshot_secret")"
container_host_port commerce_port "$commerce_container" 8080
for _ in {1..60}; do
  if curl --silent --output /dev/null --write-out '%{http_code}' \
      "http://127.0.0.1:$commerce_port/internal/knowledge/snapshot" | grep -q 401; then
    break
  fi
  sleep 1
done

admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --topic "$topic" --readQueueNums 1 --writeQueueNums 1
topic_created=1
for consumer_group in \
  "$group" "$group-replay" "$group-concurrency" "$group-restart" "$group-race" \
  "$group-outage" "$group-commit"; do
  admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
    --groupName "$consumer_group" --consumeEnable true
done

docker run --rm --network "${project}_default" "$indexer_image" \
  bootstrap --elasticsearch-url http://elasticsearch:9200 --index knowledge_docs_v1
if docker run --rm --network "${project}_default" "$indexer_image" \
  rebuild \
  --elasticsearch-url http://127.0.0.1:9 \
  --owner-snapshot-url http://commerce-snapshot:8080/internal/knowledge/snapshot \
  --owner-client-id "$snapshot_client" \
  --owner-client-secret "$snapshot_secret" \
  --rocketmq-endpoints rocketmq-broker-proxy:8081 \
  --topic "$topic" \
  --consumer-group "$group-outage" \
  --invisible-seconds 10; then
  echo "Knowledge rebuild unexpectedly succeeded while Elasticsearch was unavailable." >&2
  exit 1
fi
"${compose[@]}" exec -T elasticsearch \
  curl --fail --silent http://localhost:9200/_alias/knowledge_docs_read \
  | grep -q '"knowledge_docs_v1"'
docker run --rm --network "${project}_default" \
  --volume "$repo_root/scripts:/opt/citybuddy/scripts:ro" \
  --entrypoint /opt/citybuddy/.venv/bin/python "$indexer_image" \
  /opt/citybuddy/scripts/check_knowledge_rebuild.py \
  --elasticsearch-url http://elasticsearch:9200 \
  --owner-url http://commerce-snapshot:8080 \
  --owner-client-id "$snapshot_client" \
  --owner-client-secret "$snapshot_secret" \
  --endpoints rocketmq-broker-proxy:8081 \
  --topic "$topic" \
  --group "$group"

docker run --rm --network "${project}_default" \
  --volume "$repo_root/scripts:/opt/citybuddy/scripts:ro" \
  --entrypoint /opt/citybuddy/.venv/bin/python "$indexer_image" \
  /opt/citybuddy/scripts/check_knowledge_rebuild_concurrency.py \
  --elasticsearch-url http://elasticsearch:9200 \
  --owner-url http://commerce-snapshot:8080 \
  --owner-client-id "$snapshot_client" \
  --owner-client-secret "$snapshot_secret" \
  --endpoints rocketmq-broker-proxy:8081 \
  --topic "$topic" \
  --group "$group-concurrency" \
  --restart-group "$group-restart" \
  --race-group "$group-race" \
  --commit-group "$group-commit"

if rg -n 'pymysql|mysql|commerce_db|cs_db' \
  knowledge-indexer/src knowledge-indexer/pyproject.toml infra/knowledge-indexer/Dockerfile; then
  echo "Knowledge indexer production boundary contains forbidden direct database access." >&2
  exit 1
fi
indexer_mysql_accounts="$(
  "${compose[@]}" exec -T -e MYSQL_PWD="$(read_value MYSQL_BOOTSTRAP_PASSWORD)" mysql \
    mysql --protocol=tcp --host=127.0.0.1 --user=root --database=mysql \
    --batch --skip-column-names \
    --execute="SELECT COUNT(*) FROM mysql.user WHERE user = 'knowledge_indexer'"
)"
if [[ "$indexer_mysql_accounts" != 0 ]]; then
  echo "Knowledge indexer unexpectedly has a MySQL runtime account." >&2
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
indexer_truth_denial="$(
  "${compose[@]}" exec -T redis-support redis-cli --no-auth-warning \
    --user knowledge_indexer --pass "$(read_value REDIS_INDEXER_CACHE_PASSWORD)" \
    HGETALL cb:faq:v1:query:unrelated 2>&1
)"
if ! grep -Fq 'NOPERM' <<<"$indexer_truth_denial"; then
  echo "Knowledge indexer unexpectedly read the agent query-mapping truth prefix." >&2
  exit 1
fi
indexer_admin_denial="$(
  "${compose[@]}" exec -T redis-support redis-cli --no-auth-warning \
    --user knowledge_indexer --pass "$(read_value REDIS_INDEXER_CACHE_PASSWORD)" \
    CONFIG GET maxmemory 2>&1
)"
if ! grep -Fq 'NOPERM' <<<"$indexer_admin_denial"; then
  echo "Knowledge indexer unexpectedly exercised Support Redis administration." >&2
  exit 1
fi

echo "CB-113 complete rebuild, atomic alias switch, and exact runtime denial checks passed."
