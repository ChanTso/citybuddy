#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_port_allocator.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb030-test-$$"
allocate_test_ports auth_port MYSQL_PORT REDIS_COMMERCE_PORT ROCKETMQ_PROXY_PORT
export MYSQL_PORT REDIS_COMMERCE_PORT ROCKETMQ_PROXY_PORT
topic="cb030-catalog-$$"
consumer_group="cb030-catalog-consumer-$$"
transaction_topic="cb060-seckill-transaction-$$"
transaction_group="cb060-seckill-order-consumer-$$"
timeout_topic="cb061-seckill-timeout-$$"
timeout_group="cb061-seckill-timeout-consumer-$$"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
auth_container=""
topic_created=0
groups_created=0

admin() {
  "${compose[@]}" run --rm --no-deps rocketmq-admin "$@"
}

cleanup() {
  if [[ -n "$auth_container" ]]; then
    docker rm --force "$auth_container" >/dev/null 2>&1 || true
  fi
  if [[ -n "$("${compose[@]}" ps --status running -q rocketmq-broker-proxy 2>/dev/null)" ]]; then
    if [[ "$groups_created" = 1 ]]; then
      admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
        --groupName "$consumer_group" --removeOffset true >/dev/null 2>&1 || true
      admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
        --groupName "$consumer_group-closed" --removeOffset true >/dev/null 2>&1 || true
      admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
        --groupName "$consumer_group-faq" --removeOffset true >/dev/null 2>&1 || true
      admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
        --groupName "$consumer_group-faq-closed" --removeOffset true >/dev/null 2>&1 || true
      admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
        --groupName "$transaction_group" --removeOffset true >/dev/null 2>&1 || true
      admin deleteSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
        --groupName "$timeout_group" --removeOffset true >/dev/null 2>&1 || true
    fi
    if [[ "$topic_created" = 1 ]]; then
      admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
        --topic "$topic" >/dev/null 2>&1 || true
      admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
        --topic "$transaction_topic" >/dev/null 2>&1 || true
      admin deleteTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
        --topic "$timeout_topic" >/dev/null 2>&1 || true
    fi
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  release_test_ports
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

read_value() {
  local name="$1"
  sed -n "s/^${name}=//p" "$env_file"
}

mysql_query() {
  local user="$1"
  local password="$2"
  local database="$3"
  local statement="$4"
  local args=(mysql --protocol=tcp --host=127.0.0.1 --port=3306 --user="$user" --batch --skip-column-names)
  if [[ -n "$database" ]]; then
    args+=(--database="$database")
  fi
  "${compose[@]}" exec -T -e MYSQL_PWD="$password" mysql "${args[@]}" --execute="$statement"
}

assert_mysql_fails() {
  local label="$1"
  local expected="$2"
  shift 2
  if "$@" >"$tmp_dir/mysql-rejection.log" 2>&1; then
    echo "Expected MySQL rejection succeeded: $label" >&2
    exit 1
  fi
  if ! grep -Eq "$expected" "$tmp_dir/mysql-rejection.log"; then
    echo "Unexpected MySQL failure for $label:" >&2
    cat "$tmp_dir/mysql-rejection.log" >&2
    exit 1
  fi
  echo "Verified MySQL rejection: $label"
}

wait_http() {
  local url="$1"
  local container="$2"
  for _ in {1..60}; do
    if curl --silent --fail "$url" >/dev/null 2>&1; then
      return
    fi
    if [[ "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null || true)" != true ]]; then
      docker logs "$container" >&2 || true
      exit 1
    fi
    sleep 1
  done
  docker logs "$container" >&2 || true
  echo "Timed out waiting for $url" >&2
  exit 1
}

ENV_FILE="$env_file" ./scripts/init_local.sh
auth_app_password="$(read_value MYSQL_AUTH_APP_PASSWORD)"
commerce_app_password="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
mysql_bootstrap_password="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
agent_app_password="$(read_value MYSQL_AGENT_APP_PASSWORD)"
redis_password="$(read_value REDIS_COMMERCE_PASSWORD)"

"${compose[@]}" run --rm --no-deps rocketmq-store-init
"${compose[@]}" up --detach --wait --wait-timeout 90 \
  mysql redis-commerce rocketmq-namesrv rocketmq-broker-proxy
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-auth migrate-agent
legacy_commerce_migrations="$tmp_dir/legacy-commerce-migrations"
mkdir -p "$legacy_commerce_migrations"
cp infra/mysql/migrations/commerce/V001__validate_commerce_target.sql \
  "$legacy_commerce_migrations/"
"${compose[@]}" run --rm \
  --volume "$legacy_commerce_migrations:/opt/citybuddy/migrations:ro" commerce-migrate
legacy_grant_output="$(make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access)"
grep -q 'runtime-grants=legacy-applied-awaiting-migrations' <<<"$legacy_grant_output"
echo "Verified exact CB-020 five-table grant state permits the CB-030 upgrade."
catalog_commerce_migrations="$tmp_dir/catalog-commerce-migrations"
mkdir -p "$catalog_commerce_migrations"
cp infra/mysql/migrations/commerce/V001__validate_commerce_target.sql \
  infra/mysql/migrations/commerce/V002__product_catalog_outbox.sql \
  "$catalog_commerce_migrations/"
"${compose[@]}" run --rm \
  --volume "$catalog_commerce_migrations:/opt/citybuddy/migrations:ro" commerce-migrate
catalog_grant_output="$(make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access)"
grep -q 'runtime-grants=catalog-applied-awaiting-order-migration' <<<"$catalog_grant_output"
echo "Verified exact CB-030 nine-table grant state permits the CB-040 upgrade."
order_commerce_migrations="$tmp_dir/order-commerce-migrations"
mkdir -p "$order_commerce_migrations"
cp infra/mysql/migrations/commerce/V001__validate_commerce_target.sql \
  infra/mysql/migrations/commerce/V002__product_catalog_outbox.sql \
  infra/mysql/migrations/commerce/V003__standard_ordering.sql \
  "$order_commerce_migrations/"
"${compose[@]}" run --rm \
  --volume "$order_commerce_migrations:/opt/citybuddy/migrations:ro" commerce-migrate
order_grant_output="$(make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access)"
grep -q 'runtime-grants=order-applied-awaiting-seckill-migration' <<<"$order_grant_output"
echo "Verified exact CB-040 eleven-table grant state permits the CB-050 upgrade."
seckill_commerce_migrations="$tmp_dir/seckill-commerce-migrations"
mkdir -p "$seckill_commerce_migrations"
cp infra/mysql/migrations/commerce/V001__validate_commerce_target.sql \
  infra/mysql/migrations/commerce/V002__product_catalog_outbox.sql \
  infra/mysql/migrations/commerce/V003__standard_ordering.sql \
  infra/mysql/migrations/commerce/V004__seckill_activity_projection.sql \
  "$seckill_commerce_migrations/"
"${compose[@]}" run --rm \
  --volume "$seckill_commerce_migrations:/opt/citybuddy/migrations:ro" commerce-migrate
seckill_grant_output="$(make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access)"
grep -q 'runtime-grants=seckill-applied-awaiting-reservation-migration' <<<"$seckill_grant_output"
echo "Verified exact CB-050 twelve-table grant state permits the CB-051 upgrade."
reservation_commerce_migrations="$tmp_dir/reservation-commerce-migrations"
mkdir -p "$reservation_commerce_migrations"
cp infra/mysql/migrations/commerce/V001__validate_commerce_target.sql \
  infra/mysql/migrations/commerce/V002__product_catalog_outbox.sql \
  infra/mysql/migrations/commerce/V003__standard_ordering.sql \
  infra/mysql/migrations/commerce/V004__seckill_activity_projection.sql \
  infra/mysql/migrations/commerce/V005__seckill_reservation_admission.sql \
  "$reservation_commerce_migrations/"
"${compose[@]}" run --rm \
  --volume "$reservation_commerce_migrations:/opt/citybuddy/migrations:ro" commerce-migrate
reservation_grant_output="$(make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access)"
grep -q 'runtime-grants=reservation-applied-awaiting-transaction-order-migration' \
  <<<"$reservation_grant_output"
echo "Verified exact CB-051 thirteen-table grant state permits the CB-060 upgrade."
transaction_commerce_migrations="$tmp_dir/transaction-commerce-migrations"
mkdir -p "$transaction_commerce_migrations"
cp infra/mysql/migrations/commerce/V001__validate_commerce_target.sql \
  infra/mysql/migrations/commerce/V002__product_catalog_outbox.sql \
  infra/mysql/migrations/commerce/V003__standard_ordering.sql \
  infra/mysql/migrations/commerce/V004__seckill_activity_projection.sql \
  infra/mysql/migrations/commerce/V005__seckill_reservation_admission.sql \
  infra/mysql/migrations/commerce/V006__seckill_transaction_order.sql \
  infra/mysql/migrations/commerce/V007__seckill_unpaid_cancellation.sql \
  "$transaction_commerce_migrations/"
"${compose[@]}" run --rm \
  --volume "$transaction_commerce_migrations:/opt/citybuddy/migrations:ro" commerce-migrate
transaction_grant_output="$(make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access)"
grep -q 'runtime-grants=transaction-applied-awaiting-payment-migration' \
  <<<"$transaction_grant_output"
echo "Verified exact CB-061 fifteen-table grant state permits the CB-070 upgrade."
payment_commerce_migrations="$tmp_dir/payment-commerce-migrations"
mkdir -p "$payment_commerce_migrations"
cp infra/mysql/migrations/commerce/V001__validate_commerce_target.sql \
  infra/mysql/migrations/commerce/V002__product_catalog_outbox.sql \
  infra/mysql/migrations/commerce/V003__standard_ordering.sql \
  infra/mysql/migrations/commerce/V004__seckill_activity_projection.sql \
  infra/mysql/migrations/commerce/V005__seckill_reservation_admission.sql \
  infra/mysql/migrations/commerce/V006__seckill_transaction_order.sql \
  infra/mysql/migrations/commerce/V007__seckill_unpaid_cancellation.sql \
  infra/mysql/migrations/commerce/V008__mock_payment.sql \
  "$payment_commerce_migrations/"
"${compose[@]}" run --rm \
  --volume "$payment_commerce_migrations:/opt/citybuddy/migrations:ro" commerce-migrate
payment_grant_output="$(make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access)"
grep -q 'runtime-grants=payment-applied-awaiting-refund-migration' \
  <<<"$payment_grant_output"
echo "Verified exact CB-070 seventeen-table grant state permits the CB-071 upgrade."
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce
complete_grant_output="$(make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access)"
grep -q 'runtime-grants=applied' <<<"$complete_grant_output"
echo "Verified CB-110 upgrade reaches the exact FAQ-extended runtime grant state."

test "$(mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT COUNT(*) FROM crm_profile')" = 0
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT COUNT(*) FROM product')" = 0
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT COUNT(*) FROM commerce_outbox')" = 0
mysql_query commerce_app "$commerce_app_password" commerce_db \
  "INSERT INTO crm_profile (user_subject, display_name) VALUES ('catalog-user', 'Catalog User')"
mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE crm_profile SET display_name = 'Catalog User Updated' WHERE user_subject = 'catalog-user'"
assert_mysql_fails "one CRM profile per immutable subject" 'Duplicate entry' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "INSERT INTO crm_profile (user_subject, display_name) VALUES ('catalog-user', 'Duplicate')"

for table in crm_profile product commerce_outbox; do
  assert_mysql_fails "auth_app cannot read $table" '(SELECT command denied|Access denied)' \
    mysql_query auth_app "$auth_app_password" commerce_db "SELECT * FROM $table"
done
for table in faq_source faq_draft_command faq_publication_command; do
  test "$(mysql_query commerce_app "$commerce_app_password" commerce_db "SELECT COUNT(*) FROM $table")" = 0
  assert_mysql_fails "auth_app cannot read $table" '(SELECT command denied|Access denied)' \
    mysql_query auth_app "$auth_app_password" commerce_db "SELECT * FROM $table"
  assert_mysql_fails "agent_app cannot read $table" '(SELECT command denied|Access denied)' \
    mysql_query agent_app "$agent_app_password" commerce_db "SELECT * FROM $table"
done
assert_mysql_fails "commerce_app cannot delete FAQ source truth" '(DELETE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM faq_source WHERE faq_id = 'none'"
assert_mysql_fails "commerce_app cannot update immutable FAQ publication commands" \
  '(UPDATE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE faq_publication_command SET intent_hash = REPEAT('0', 64) WHERE idempotency_key = 'none'"
assert_mysql_fails "commerce_app cannot update immutable FAQ draft commands" \
  '(UPDATE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE faq_draft_command SET intent_hash = REPEAT('0', 64) WHERE faq_id = 'none'"
if rg -n 'mysql|commerce_db|cs_db|spring.datasource' knowledge-indexer/src knowledge-indexer/pyproject.toml; then
  echo "Knowledge indexer contains forbidden direct database access." >&2
  exit 1
fi
for table in standard_order order_idempotency mock_payment_attempt mock_payment_callback mock_refund; do
  test "$(mysql_query commerce_app "$commerce_app_password" commerce_db "SELECT COUNT(*) FROM $table")" = 0
  assert_mysql_fails "auth_app cannot read $table" '(SELECT command denied|Access denied)' \
    mysql_query auth_app "$auth_app_password" commerce_db "SELECT * FROM $table"
  assert_mysql_fails "agent_app cannot read $table" '(SELECT command denied|Access denied)' \
    mysql_query agent_app "$agent_app_password" commerce_db "SELECT * FROM $table"
done
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT COUNT(*) FROM seckill_activity')" = 0
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT COUNT(*) FROM seckill_reservation')" = 0
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT COUNT(*) FROM seckill_order')" = 0
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT COUNT(*) FROM inventory_ledger')" = 0
for identity in auth_app agent_app; do
  password="$auth_app_password"
  if [[ "$identity" = agent_app ]]; then
    password="$agent_app_password"
  fi
  assert_mysql_fails "$identity cannot read seckill activities" '(SELECT command denied|Access denied)' \
    mysql_query "$identity" "$password" commerce_db 'SELECT * FROM seckill_activity'
  assert_mysql_fails "$identity cannot write seckill activities" '(INSERT command denied|Access denied)' \
    mysql_query "$identity" "$password" commerce_db \
    "INSERT INTO seckill_activity (activity_id, product_id, starts_at, ends_at, state, allocated_quota, projection_version) VALUES ('forbidden', 'none', '2030-01-01 00:00:00', '2030-01-01 01:00:00', 'DRAFT', 1, 1)"
  assert_mysql_fails "$identity cannot read seckill reservations" '(SELECT command denied|Access denied)' \
    mysql_query "$identity" "$password" commerce_db 'SELECT * FROM seckill_reservation'
  assert_mysql_fails "$identity cannot write seckill reservations" '(INSERT command denied|Access denied)' \
    mysql_query "$identity" "$password" commerce_db \
    "INSERT INTO seckill_reservation (reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity, activity_projection_version) VALUES ('00000000-0000-0000-0000-000000000051', 'forbidden', 'none', 'forbidden', REPEAT('0', 64), 1, 1)"
  for table in seckill_order inventory_ledger; do
    assert_mysql_fails "$identity cannot read $table" '(SELECT command denied|Access denied)' \
      mysql_query "$identity" "$password" commerce_db "SELECT * FROM $table"
  done
done
assert_mysql_fails "commerce_app cannot delete seckill activity truth" '(DELETE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM seckill_activity WHERE activity_id = 'none'"
assert_mysql_fails "commerce_app cannot delete seckill reservation truth" '(DELETE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM seckill_reservation WHERE reservation_id = 'none'"
assert_mysql_fails "commerce_app cannot delete seckill orders" '(DELETE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM seckill_order WHERE order_id = 'none'"
assert_mysql_fails "commerce_app cannot delete payment attempts" '(DELETE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM mock_payment_attempt WHERE attempt_id = 'none'"
assert_mysql_fails "commerce_app cannot update callback truth" '(UPDATE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE mock_payment_callback SET result_state = 'APPLIED' WHERE callback_event_id = 'none'"
assert_mysql_fails "commerce_app cannot delete callback truth" '(DELETE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM mock_payment_callback WHERE callback_event_id = 'none'"
assert_mysql_fails "commerce_app cannot delete refund truth" '(DELETE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM mock_refund WHERE refund_id = 'none'"
assert_mysql_fails "commerce_app cannot update append-only inventory ledger" '(UPDATE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE inventory_ledger SET inventory_delta = inventory_delta WHERE movement_id = 'none'"
assert_mysql_fails "commerce_app cannot delete append-only inventory ledger" '(DELETE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM inventory_ledger WHERE movement_id = 'none'"
assert_mysql_fails "database rejects an invalid seckill window" '(Check constraint|CONSTRAINT.*failed)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "INSERT INTO seckill_activity (activity_id, product_id, starts_at, ends_at, state, allocated_quota, projection_version) VALUES ('invalid-window', 'none', '2030-01-01 01:00:00', '2030-01-01 00:00:00', 'DRAFT', 1, 1)"
assert_mysql_fails "database rejects an invalid seckill state" '(Data truncated|Incorrect.*value)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "INSERT INTO seckill_activity (activity_id, product_id, starts_at, ends_at, state, allocated_quota, projection_version) VALUES ('invalid-state', 'none', '2030-01-01 00:00:00', '2030-01-01 01:00:00', 'UNKNOWN', 1, 1)"
assert_mysql_fails "database rejects a zero reservation quantity" '(Check constraint|CONSTRAINT.*failed)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "INSERT INTO seckill_reservation (reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity, activity_projection_version, transaction_resolution_due_at) VALUES ('00000000-0000-0000-0000-000000000052', 'invalid', 'none', 'zero', REPEAT('0', 64), 0, 1, CURRENT_TIMESTAMP(6))"
assert_mysql_fails "database rejects a terminal reservation without a decision" '(Check constraint|CONSTRAINT.*failed)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "INSERT INTO seckill_reservation (reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity, activity_projection_version, state, projection_version, transaction_resolution_due_at) VALUES ('00000000-0000-0000-0000-000000000053', 'invalid', 'none', 'terminal', REPEAT('0', 64), 1, 1, 'ADMITTED', 2, CURRENT_TIMESTAMP(6))"
assert_mysql_fails "commerce_app cannot delete idempotency truth" '(DELETE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM order_idempotency WHERE user_subject = 'none'"
assert_mysql_fails "auth_app cannot write CRM" '(INSERT command denied|Access denied)' \
  mysql_query auth_app "$auth_app_password" commerce_db \
  "INSERT INTO crm_profile (user_subject, display_name) VALUES ('forbidden', 'Forbidden')"
assert_mysql_fails "auth_app cannot write products" '(UPDATE command denied|Access denied)' \
  mysql_query auth_app "$auth_app_password" commerce_db \
  "UPDATE product SET name = 'forbidden' WHERE product_id = 'none'"
assert_mysql_fails "auth_app cannot write Outbox" '(INSERT command denied|Access denied)' \
  mysql_query auth_app "$auth_app_password" commerce_db \
  "INSERT INTO commerce_outbox (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload) VALUES ('00000000-0000-0000-0000-000000000030', 'X', 'X', 1, 'X', JSON_OBJECT())"
assert_mysql_fails "agent_app cannot read products" '(SELECT command denied|Access denied)' \
  mysql_query agent_app "$agent_app_password" commerce_db 'SELECT * FROM product'
assert_mysql_fails "commerce_app cannot read auth-private credentials" '(SELECT command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT * FROM auth_login_credential'
assert_mysql_fails "commerce_app cannot execute DDL" '(CREATE command denied|Access denied)' \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  'CREATE TABLE forbidden_catalog_ddl (id INT)'

if rg -n 'bootstrap_admin|commerce_migration|MYSQL_BOOTSTRAP|MYSQL_COMMERCE_MIGRATION' \
  commerce-service/src/main commerce-service/src/test/resources; then
  echo "Commerce application configuration contains migration or bootstrap credentials." >&2
  exit 1
fi
if rg -n 'mock.?payment|refund|PendingAction|ActionReceipt' \
  commerce-service/src/main/java/io/citybuddy/commerce/seckill \
  infra/mysql/migrations/commerce/V006__seckill_transaction_order.sql \
  infra/mysql/migrations/commerce/V007__seckill_unpaid_cancellation.sql; then
  echo "Seckill implementation contains payment, refund, or agent behavior." >&2
  exit 1
fi

user_password="$(openssl rand -hex 24)"
user_hash="$(uv run python scripts/hash_test_credential.py "$user_password")"
other_password="$(openssl rand -hex 24)"
other_hash="$(uv run python scripts/hash_test_credential.py "$other_password")"
limited_password="$(openssl rand -hex 24)"
limited_hash="$(uv run python scripts/hash_test_credential.py "$limited_password")"
mysql_query auth_app "$auth_app_password" commerce_db "
INSERT INTO auth_user_principal (principal_id, subject, login_identifier, state, permissions)
VALUES
  ('00000000-0000-0000-0000-000000000030', 'catalog-user', 'catalog-user', 'ACTIVE', 'catalog:read order:create seckill:reserve payment:create refund:create'),
  ('00000000-0000-0000-0000-000000000031', 'other-user', 'other-user', 'ACTIVE', 'catalog:read order:create seckill:reserve payment:create refund:create'),
  ('00000000-0000-0000-0000-000000000032', 'limited-user', 'limited-user', 'ACTIVE', 'catalog:read');
INSERT INTO auth_login_credential (principal_id, password_hash)
VALUES
  ('00000000-0000-0000-0000-000000000030', '$user_hash'),
  ('00000000-0000-0000-0000-000000000031', '$other_hash'),
  ('00000000-0000-0000-0000-000000000032', '$limited_hash');
INSERT INTO auth_signing_key_metadata (kid, state, activated_at, retire_after)
VALUES ('catalog-current', 'CURRENT', CURRENT_TIMESTAMP(6), NULL);
"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$tmp_dir/current-private.pem" 2>/dev/null
openssl pkey -in "$tmp_dir/current-private.pem" -pubout \
  -out "$tmp_dir/current-public.pem" 2>/dev/null

./mvnw -q -pl auth-service -am -DskipTests package
auth_container="$(docker run --detach --rm \
  --name "$project-auth" \
  --network "${project}_default" \
  --publish "127.0.0.1:$auth_port:8080" \
  --volume "$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar:/opt/citybuddy/auth.jar:ro" \
  --volume "$tmp_dir/current-private.pem:/opt/citybuddy/current-private.pem:ro" \
  --volume "$tmp_dir/current-public.pem:/opt/citybuddy/current-public.pem:ro" \
  --env SPRING_DATASOURCE_PASSWORD="$auth_app_password" \
  eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd \
  java -jar /opt/citybuddy/auth.jar \
  --server.port=8080 \
  --spring.datasource.url='jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true' \
  --spring.datasource.username=auth_app \
  --citybuddy.identity.enabled=true \
  --citybuddy.identity.issuer=https://identity.citybuddy.test \
  --citybuddy.identity.user-audience=citybuddy-web \
  --citybuddy.identity.current-kid=catalog-current \
  --citybuddy.identity.current-private-key-path=/opt/citybuddy/current-private.pem \
  --citybuddy.identity.current-public-key-path=/opt/citybuddy/current-public.pem)"
wait_http "http://127.0.0.1:$auth_port/auth/jwks" "$auth_container"

login_status="$(curl --silent --show-error --output "$tmp_dir/login.json" --write-out '%{http_code}' \
  --request POST "http://127.0.0.1:$auth_port/auth/login" \
  --header 'Content-Type: application/json' \
  --data "{\"loginIdentifier\":\"catalog-user\",\"password\":\"$user_password\"}")"
test "$login_status" = 200
direct_token="$(uv run python scripts/read_json_field.py "$tmp_dir/login.json" accessToken)"
login_status="$(curl --silent --show-error --output "$tmp_dir/other-login.json" --write-out '%{http_code}' \
  --request POST "http://127.0.0.1:$auth_port/auth/login" \
  --header 'Content-Type: application/json' \
  --data "{\"loginIdentifier\":\"other-user\",\"password\":\"$other_password\"}")"
test "$login_status" = 200
other_direct_token="$(uv run python scripts/read_json_field.py "$tmp_dir/other-login.json" accessToken)"
login_status="$(curl --silent --show-error --output "$tmp_dir/limited-login.json" --write-out '%{http_code}' \
  --request POST "http://127.0.0.1:$auth_port/auth/login" \
  --header 'Content-Type: application/json' \
  --data "{\"loginIdentifier\":\"limited-user\",\"password\":\"$limited_password\"}")"
test "$login_status" = 200
limited_direct_token="$(uv run python scripts/read_json_field.py "$tmp_dir/limited-login.json" accessToken)"

broker_config="$(admin getBrokerConfig -b rocketmq-broker-proxy:10911)"
grep -Eq 'transactionTimeOut[[:space:]]*=[[:space:]]*6000' <<<"$broker_config"
grep -Eq 'transactionCheckInterval[[:space:]]*=[[:space:]]*1000' <<<"$broker_config"
grep -Eq 'transactionCheckMax[[:space:]]*=[[:space:]]*3' <<<"$broker_config"
echo "Verified pinned Broker transaction timeout=6000ms, interval=1000ms, maximum checks=3."

admin updateTopic \
  --namesrvAddr rocketmq-namesrv:9876 \
  --clusterName DefaultCluster \
  --topic "$topic" \
  --readQueueNums 4 \
  --writeQueueNums 4
topic_created=1
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$consumer_group" --consumeEnable true
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$consumer_group-closed" --consumeEnable true
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$consumer_group-faq" --consumeEnable true
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$consumer_group-faq-closed" --consumeEnable true
admin updateTopic \
  --namesrvAddr rocketmq-namesrv:9876 \
  --clusterName DefaultCluster \
  --topic "$transaction_topic" \
  --readQueueNums 4 \
  --writeQueueNums 4 \
  -a +message.type=TRANSACTION
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$transaction_group" --consumeEnable true
admin updateTopic \
  --namesrvAddr rocketmq-namesrv:9876 \
  --clusterName DefaultCluster \
  --topic "$timeout_topic" \
  --readQueueNums 4 \
  --writeQueueNums 4 \
  -a +message.type=DELAY
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster \
  --groupName "$timeout_group" --consumeEnable true --retryMaxTimes 3
groups_created=1

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --network "${project}_default" \
  --workdir /workspace \
  --volume "$repo_root:/workspace" \
  --volume "$HOME/.m2:/m2" \
  --volume "$tmp_dir/current-private.pem:/tmp/catalog-current-private.pem:ro" \
  --env MAVEN_CONFIG=/tmp/maven \
  --env CATALOG_INTEGRATION=true \
  --env CATALOG_MYSQL_URL='jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true' \
  --env MYSQL_COMMERCE_APP_PASSWORD="$commerce_app_password" \
  --env MYSQL_BOOTSTRAP_PASSWORD="$mysql_bootstrap_password" \
  --env CATALOG_REDIS_URL="redis://:$redis_password@redis-commerce:6379" \
  --env IDENTITY_JWKS_URL="http://$project-auth:8080/auth/jwks" \
  --env CATALOG_DIRECT_TOKEN="$direct_token" \
  --env CATALOG_OTHER_DIRECT_TOKEN="$other_direct_token" \
  --env CATALOG_LIMITED_DIRECT_TOKEN="$limited_direct_token" \
  --env MOCK_PAYMENT_CALLBACK_KEY_ID=integration-key \
  --env MOCK_PAYMENT_CALLBACK_SECRET=not-a-secret-not-a-secret-not-a-secret \
  --env CATALOG_TEST_SIGNING_PRIVATE_KEY_PATH=/tmp/catalog-current-private.pem \
  --env ROCKETMQ_ENDPOINTS=rocketmq-broker-proxy:8081 \
  --env ROCKETMQ_TOPIC="$topic" \
  --env ROCKETMQ_CONSUMER_GROUP="$consumer_group" \
  --env ROCKETMQ_TRANSACTION_TOPIC="$transaction_topic" \
  --env ROCKETMQ_TRANSACTION_GROUP="$transaction_group" \
  --env ROCKETMQ_TIMEOUT_TOPIC="$timeout_topic" \
  --env ROCKETMQ_TIMEOUT_GROUP="$timeout_group" \
  maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 \
  mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=/m2 \
  -pl commerce-service \
  -Dtest=CatalogIntegrationTest,FaqPublicationIntegrationTest,SeckillIntegrationTest,SeckillReservationIntegrationTest,SeckillTransactionIntegrationTest,MockPaymentIntegrationTest,RefundIntegrationTest test

terminal_key='00000000-0000-0000-0000-000000000060'
terminal_output=''
for _ in {1..10}; do
  terminal_output="$(admin queryMsgByKey \
    --namesrvAddr rocketmq-namesrv:9876 \
    --topic TRANS_CHECK_MAX_TIME_TOPIC \
    --msgKey "$terminal_key" 2>&1 || true)"
  if grep -q "$terminal_key" <<<"$terminal_output"; then
    break
  fi
  sleep 1
done
if ! grep -q "$terminal_key" <<<"$terminal_output"; then
  echo "UNKNOWN transaction did not reach the Broker terminal topic." >&2
  printf '%s\n' "$terminal_output" >&2
  exit 1
fi
echo "Verified repeated UNKNOWN reached Broker terminal topic TRANS_CHECK_MAX_TIME_TOPIC."

echo "CB-030 through CB-110 catalog, FAQ publication, order, seckill, payment, refund lifecycle, reconciliation, atomic Outbox, failure, and permission evidence passed."
