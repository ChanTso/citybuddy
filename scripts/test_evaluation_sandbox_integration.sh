#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_dynamic_ports.sh"

v013_only="${CITYBUDDY_V013_ONLY:-false}"
if [[ "$v013_only" != true && "$v013_only" != false ]]; then
  echo "CITYBUDDY_V013_ONLY must be true or false." >&2
  exit 1
fi
compact_output="${CITYBUDDY_COMPACT_OUTPUT:-false}"
if [[ "$compact_output" != true && "$compact_output" != false ]]; then
  echo "CITYBUDDY_COMPACT_OUTPUT must be true or false." >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb101-test-$$"
auth_port=""
commerce_port=""
agent_port=""
proxy_port=""
drop_proxy_port=""
MYSQL_PORT=""
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
auth_pid=""
commerce_pid=""
agent_pid=""
model_pid=""
drop_proxy_pid=""

cleanup() {
  local status=$?
  local resource_stop_status=0
  for pid in "$agent_pid" "$commerce_pid" "$auth_pid" "$model_pid" "$drop_proxy_pid"; do
    if [[ -n "$pid" ]]; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
  done
  for pid in "$agent_pid" "$commerce_pid" "$auth_pid" "$model_pid" "$drop_proxy_pid"; do
    if [[ -n "$pid" ]]; then
      wait "$pid" >/dev/null 2>&1 || true
    fi
  done
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || resource_stop_status=$?
  rm -rf "$tmp_dir"
  finish_test_cleanup "$status" "$resource_stop_status"
}
trap cleanup EXIT

read_value() {
  sed -n "s/^$1=//p" "$env_file"
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
  shift
  if "$@" >"$tmp_dir/mysql-rejection.log" 2>&1; then
    echo "Expected MySQL rejection succeeded: $label" >&2
    exit 1
  fi
  grep -Eq 'Access denied|command denied' "$tmp_dir/mysql-rejection.log"
  echo "Verified MySQL rejection: $label"
}

assert_mysql_integrity_fails() {
  local label="$1"
  shift
  if "$@" >"$tmp_dir/mysql-integrity-rejection.log" 2>&1; then
    echo "Expected MySQL integrity rejection succeeded: $label" >&2
    exit 1
  fi
  grep -Eqi 'Duplicate entry|foreign key constraint fails|check constraint' \
    "$tmp_dir/mysql-integrity-rejection.log"
  echo "Verified MySQL integrity rejection: $label"
}

wait_for_mysql_value() {
  local expected="$1"
  local statement="$2"
  local label="$3"
  local actual=""
  for _ in {1..200}; do
    actual="$(mysql_query root "$root_password" commerce_db "$statement")"
    if [[ "$actual" == "$expected" ]]; then
      echo "Verified MySQL transition: $label"
      return 0
    fi
    sleep 0.1
  done
  echo "Timed out waiting for $label: expected '$expected', got '$actual'" >&2
  return 1
}

kill_active_commerce_migration() {
  local container_id=""
  for _ in {1..100}; do
    container_id="$(docker ps --quiet \
      --filter "label=com.docker.compose.project=$project" \
      --filter 'label=com.docker.compose.service=commerce-migrate')"
    if [[ -n "$container_id" ]]; then
      docker kill "$container_id" >/dev/null
      echo "Killed controlled commerce migration container $container_id."
      return 0
    fi
    sleep 0.1
  done
  echo "No active commerce migration container was found for controlled interruption." >&2
  return 1
}

kill_mysql_sessions_matching() {
  local predicate="$1"
  local connection_ids
  connection_ids="$(mysql_query root "$root_password" commerce_db \
    "SELECT id FROM information_schema.processlist WHERE $predicate")"
  while IFS= read -r connection_id; do
    if [[ -n "$connection_id" ]]; then
      mysql_query root "$root_password" commerce_db "KILL CONNECTION $connection_id" || true
    fi
  done <<<"$connection_ids"
}

assert_v013_migration_grants_absent() {
  local label="$1"
  local grants
  grants="$(mysql_query commerce_migration "$commerce_migration_password" '' \
    'SHOW GRANTS FOR CURRENT_USER')"
  if grep -Eq 'ON `commerce_db`\.`eval_commerce_audit_(reference|legacy_watermark)`' \
    <<<"$grants"; then
    echo "V013 exact migration grants remained after $label." >&2
    exit 1
  fi
  assert_mysql_fails "$label denies audit source read" \
    mysql_query commerce_migration "$commerce_migration_password" commerce_db \
    'SELECT * FROM eval_commerce_audit_reference'
  assert_mysql_fails "$label denies watermark insert" \
    mysql_query commerce_migration "$commerce_migration_password" commerce_db \
    "INSERT INTO eval_commerce_audit_legacy_watermark (watermark_key, commitment_format, legacy_set_digest, cutoff_sequence_id, legacy_row_count, recorded_at) VALUES ('V013', 'CITYBUDDY_EVAL_AUDIT_LEGACY_LPUTF8_SHA256_CHAIN_V1', REPEAT('0', 64), 0, 0, CURRENT_TIMESTAMP(6))"
}

assert_equal() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "Unexpected value for $label: expected '$expected', got '$actual'" >&2
    for log in auth commerce drop-proxy; do
      if [[ -f "$tmp_dir/$log.log" ]]; then
        echo "${log}-log-tail" >&2
        tail -n 80 "$tmp_dir/$log.log" >&2
      fi
    done
    exit 1
  fi
  if [[ "$compact_output" == false ]]; then
    echo "Verified value: $label"
  fi
}

evaluation_product_reference() {
  uv run python -c '
import hashlib
import sys

digest = hashlib.sha256()
for value in sys.argv[1:]:
    encoded = value.encode()
    digest.update(str(len(encoded)).encode())
    digest.update(b":")
    digest.update(encoded)
    digest.update(b";")
print(digest.hexdigest())
' "$@"
}

request_status() {
  local output="$1"
  shift
  curl --silent --show-error --output "$output" --write-out '%{http_code}' "$@"
}

assert_status() {
  local expected="$1"
  local label="$2"
  shift 2
  local status
  local commerce_log_start=0
  if [[ -f "$tmp_dir/commerce.log" ]]; then
    commerce_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  fi
  status="$(request_status "$tmp_dir/http-response.json" "$@")"
  if [[ "$status" != "$expected" ]]; then
    echo "Unexpected HTTP status for $label: $status" >&2
    cat "$tmp_dir/http-response.json" >&2
    if [[ -f "$tmp_dir/commerce.log" ]]; then
      echo "request-rejection-reasons" >&2
      tail -n "+$((commerce_log_start + 1))" "$tmp_dir/commerce.log" \
        | grep 'evaluation_request_rejected reason_code=' >&2 || true
    fi
    for log in auth commerce agent model drop-proxy; do
      if [[ -f "$tmp_dir/$log.log" ]]; then
        echo "${log}-log-tail" >&2
        tail -n 120 "$tmp_dir/$log.log" >&2
      fi
    done
    exit 1
  fi
  echo "Verified HTTP $expected: $label"
}

report_audit_unavailability_misclassification() {
  local label="$1"
  local status_file="$2"
  local response_file="$3"
  echo "Unexpected audit-unavailability classification for $label: $(cat "$status_file")" >&2
  cat "$response_file" >&2
  echo "sandbox-classification-truth" >&2
  mysql_query root "$root_password" commerce_db \
    "SELECT sandbox_id, lifecycle_state, expires_at, expires_at > CURRENT_TIMESTAMP(6), version FROM eval_sandbox WHERE sandbox_id = 'sandbox-main'" >&2
  echo "commerce-runtime-grants" >&2
  mysql_query commerce_app "$commerce_app_password" '' 'SHOW GRANTS FOR CURRENT_USER' >&2
  echo "commerce-table-access-denials" >&2
  mysql_query root "$root_password" performance_schema \
    "SELECT user, host, error_number, error_name, sum_error_raised FROM events_errors_summary_by_account_by_error WHERE user = 'commerce_app' AND error_number = 1142" >&2
  if [[ -f "$tmp_dir/commerce.log" ]]; then
    echo "commerce-log-tail" >&2
    tail -n 160 "$tmp_dir/commerce.log" >&2
  fi
  exit 1
}

assert_legacy_commitment_fails_closed() {
  local label="$1"
  assert_status 409 "$label rejects migrated legacy state" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
    --user "evaluation-manager:$management_password" \
    --header "X-Eval-Sandbox-Id: $legacy_sandbox_id"
  assert_status 409 "$label rejects migrated legacy audit" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$legacy_session" \
    --user "evaluation-manager:$management_password" \
    --header "X-Eval-Sandbox-Id: $legacy_sandbox_id"
}

assert_legacy_commitment_recovers() {
  local label="$1"
  assert_status 200 "$label restores migrated legacy state" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
    --user "evaluation-manager:$management_password" \
    --header "X-Eval-Sandbox-Id: $legacy_sandbox_id"
  assert_status 200 "$label restores migrated legacy audit" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$legacy_session" \
    --user "evaluation-manager:$management_password" \
    --header "X-Eval-Sandbox-Id: $legacy_sandbox_id"
}

tamper_legacy_column() {
  local label="$1"
  local corrupt_sql="$2"
  local restore_sql="$3"
  mysql_query root "$root_password" commerce_db "$corrupt_sql"
  assert_legacy_commitment_fails_closed "$label"
  mysql_query root "$root_password" commerce_db "$restore_sql"
  assert_legacy_commitment_recovers "$label"
}

wait_http() {
  local url="$1"
  local pid="$2"
  local log="$3"
  local expected_status="${4:-}"
  local actual_status
  if (( $# >= 4 )); then
    shift 4
  else
    shift 3
  fi
  for _ in {1..90}; do
    actual_status="$(
      curl --silent --output /dev/null --write-out '%{http_code}' "$@" "$url" 2>/dev/null || true
    )"
    if [[ -n "$expected_status" && "$actual_status" == "$expected_status" ]]; then
      return
    fi
    if [[ -z "$expected_status" && "$actual_status" != 000 ]]; then
      return
    fi
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      cat "$log" >&2
      exit 1
    fi
    sleep 1
  done
  cat "$log" >&2
  echo "Timed out waiting for $url" >&2
  exit 1
}

stop_process() {
  local name="$1"
  local pid="$2"
  if [[ -n "$pid" ]]; then
    kill "$pid"
    wait "$pid" || true
  fi
  printf -v "$name" '%s' ""
}

start_auth() {
  local profile="$1"
  local -a profile_argument=()
  local log_offset
  if [[ "$profile" == evaluation ]]; then
    profile_argument=(--spring.profiles.active=evaluation)
  fi
  port_log_offset log_offset "$tmp_dir/auth.log"
  SPRING_DATASOURCE_PASSWORD="$auth_app_password" \
    java -jar auth-service/target/auth-service-0.0.1-SNAPSHOT.jar \
    --server.port=0 \
    --spring.datasource.url="jdbc:mysql://127.0.0.1:$MYSQL_PORT/commerce_db?useSSL=false&allowPublicKeyRetrieval=true" \
    --spring.datasource.username=auth_app \
    --citybuddy.identity.enabled=true \
    --citybuddy.identity.issuer=https://identity.citybuddy.test \
    --citybuddy.identity.user-audience=citybuddy-web \
    --citybuddy.identity.current-kid=current-key \
    --citybuddy.identity.current-private-key-path="$tmp_dir/current-private.pem" \
    --citybuddy.identity.current-public-key-path="$tmp_dir/current-public.pem" \
    --citybuddy.identity.overlap-kid=overlap-key \
    --citybuddy.identity.overlap-public-key-path="$tmp_dir/overlap-public.pem" \
    '--citybuddy.identity.exchange-scopes[0]=catalog:read' \
    '--citybuddy.identity.exchange-scopes[1]=refund:create' \
    ${profile_argument[@]+"${profile_argument[@]}"} \
    >>"$tmp_dir/auth.log" 2>&1 &
  auth_pid=$!
  process_bound_port auth_port spring "$auth_pid" "$tmp_dir/auth.log" "$log_offset"
  wait_http "http://127.0.0.1:$auth_port/auth/jwks" "$auth_pid" "$tmp_dir/auth.log"
}

start_commerce() {
  local profile="$1"
  local auth_base="$2"
  local -a profile_argument=()
  local -a payment_arguments=()
  local log_offset
  if [[ "$profile" == evaluation ]]; then
    profile_argument=(--spring.profiles.active=evaluation)
    payment_arguments=(
      --citybuddy.mock-payment.enabled=true
      --citybuddy.mock-payment.required-permission=support:chat
      --citybuddy.mock-payment.callback-key-id="$mock_payment_key"
      --citybuddy.mock-payment.callback-secret="$mock_payment_secret"
      --citybuddy.mock-payment.callback-maximum-age=5m
      --citybuddy.mock-payment.callback-clock-skew=30s
      --citybuddy.refund.enabled=true
      --citybuddy.refund.required-permission=refund:create
      --citybuddy.actions.enabled=true
      --citybuddy.actions.required-scope=refund:create
      --citybuddy.actions.pending-ttl=15m
      --citybuddy.actions.maximum-concurrency-attempts=3
      --citybuddy.actions.lock-wait-timeout-seconds=1
    )
  fi
  port_log_offset log_offset "$tmp_dir/commerce.log"
  SPRING_DATASOURCE_PASSWORD="$commerce_app_password" \
    java -jar commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar \
    --server.port=0 \
    --spring.datasource.url="jdbc:mysql://127.0.0.1:$MYSQL_PORT/commerce_db?useSSL=false&allowPublicKeyRetrieval=true" \
    --spring.datasource.username=commerce_app \
    --spring.datasource.hikari.connection-timeout=2000 \
    --citybuddy.obo.enabled=true \
    --citybuddy.obo.issuer=https://identity.citybuddy.test \
    --citybuddy.obo.jwks-url="http://127.0.0.1:$auth_port/auth/jwks" \
    --citybuddy.obo.jwks-cache-ttl=1s \
    --citybuddy.agent-tools.enabled=true \
    --citybuddy.evaluation.management-client-id=evaluation-manager \
    --citybuddy.evaluation.management-client-secret="$management_password" \
    --citybuddy.evaluation.auth-base-url="$auth_base" \
    --citybuddy.evaluation.auth-client-id=commerce-service \
    --citybuddy.evaluation.auth-client-secret="$commerce_service_password" \
    --citybuddy.evaluation.identity-issuer=https://identity.citybuddy.test \
    --citybuddy.evaluation.user-audience=citybuddy-web \
    --citybuddy.evaluation.jwks-url="http://127.0.0.1:$auth_port/auth/jwks" \
    --citybuddy.evaluation.jwks-cache-ttl=1s \
    --citybuddy.evaluation.provisioning-timeout=10s \
    --citybuddy.evaluation.auth-expiry-safety=2s \
    --citybuddy.evaluation.cleanup-retry=1s \
    --citybuddy.evaluation.janitor-interval=5s \
    --citybuddy.evaluation.max-cleanup-attempts=5 \
    --citybuddy.evaluation.janitor-batch-size=4 \
    --citybuddy.evaluation.build-id=cb102-integration-build \
    --citybuddy.evaluation.schema-compatibility=commerce-evaluation-v1 \
    ${profile_argument[@]+"${profile_argument[@]}"} \
    ${payment_arguments[@]+"${payment_arguments[@]}"} \
    >>"$tmp_dir/commerce.log" 2>&1 &
  commerce_pid=$!
  process_bound_port commerce_port spring "$commerce_pid" "$tmp_dir/commerce.log" "$log_offset"
  wait_http "http://127.0.0.1:$commerce_port/api/products" "$commerce_pid" "$tmp_dir/commerce.log"
}

start_agent() {
  local evaluation_enabled="$1"
  local log_offset
  port_log_offset log_offset "$tmp_dir/agent.log"
  AGENT_PORT=0 \
  AGENT_IDENTITY_ENABLED=true \
  AGENT_EVALUATION_ENABLED="$evaluation_enabled" \
  AGENT_EVALUATION_CLIENT_ID=evaluation-manager \
  AGENT_EVALUATION_CLIENT_SECRET="$management_password" \
  CITYBUDDY_ENVIRONMENT=integration \
  IDENTITY_ISSUER=https://identity.citybuddy.test \
  IDENTITY_USER_AUDIENCE=citybuddy-web \
  IDENTITY_JWKS_URL="http://127.0.0.1:$auth_port/auth/jwks" \
  IDENTITY_EXCHANGE_URL="http://127.0.0.1:$auth_port/auth/token/exchange" \
  MYSQL_HOST=127.0.0.1 \
  MYSQL_PORT="$MYSQL_PORT" \
  MYSQL_AGENT_APP_PASSWORD="$agent_app_password" \
  AGENT_SERVICE_CLIENT_ID=agent-service \
  AGENT_SERVICE_CLIENT_SECRET="$agent_service_password" \
  AGENT_EXCHANGE_SCOPES=catalog:read \
  AGENT_MODEL_PROXY_URL="http://127.0.0.1:$proxy_port" \
  AGENT_COMMERCE_TOOLS_URL="http://127.0.0.1:$commerce_port" \
  AGENT_COMMERCE_LIVENESS_URL="http://127.0.0.1:$commerce_port" \
  uv run citybuddy-agent >>"$tmp_dir/agent.log" 2>&1 &
  agent_pid=$!
  process_bound_port agent_port uvicorn "$agent_pid" "$tmp_dir/agent.log" "$log_offset"
  wait_http "http://127.0.0.1:$agent_port/api/sessions" "$agent_pid" "$tmp_dir/agent.log"
}

reset_body() {
  local sandbox="$1"
  local case_id="$2"
  local product_name="$3"
  local ttl_seconds="${4:-60}"
  printf '{"sandboxId":"%s","caseCorrelation":"%s","ttlSeconds":%s,"testUserLabel":"user-%s","products":[{"productId":"product-1","name":"%s","description":"sandbox fixture","priceMinor":900,"currency":"CNY","stockQuantity":3,"available":true}]}' \
    "$sandbox" "$case_id" "$ttl_seconds" "$sandbox" "$product_name"
}

reset_sandbox() {
  local sandbox="$1"
  local case_id="$2"
  local key="$3"
  local product_name="$4"
  local ttl_seconds="${5:-60}"
  assert_status 200 "reset $sandbox" \
    --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
    --user "evaluation-manager:$management_password" \
    --header "Idempotency-Key: $key" \
    --header 'Content-Type: application/json' \
    --data "$(reset_body "$sandbox" "$case_id" "$product_name" "$ttl_seconds")"
}

payment_reset_body() {
  local sandbox="$1"
  local case_id="$2"
  local order_id="$3"
  local ttl_seconds="${4:-300}"
  printf '{"sandboxId":"%s","caseCorrelation":"%s","ttlSeconds":%s,"testUserLabel":"user-%s","products":[{"productId":"payment-product","name":"Payment fixture","description":"sandbox payment fixture","priceMinor":900,"currency":"CNY","stockQuantity":3,"available":true}],"paymentOrder":{"orderId":"%s","productId":"payment-product","quantity":2}}' \
    "$sandbox" "$case_id" "$ttl_seconds" "$sandbox" "$order_id"
}

reset_payment_sandbox() {
  local sandbox="$1"
  local case_id="$2"
  local key="$3"
  local order_id="$4"
  local ttl_seconds="${5:-300}"
  assert_status 200 "reset payment sandbox $sandbox" \
    --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
    --user "evaluation-manager:$management_password" \
    --header "Idempotency-Key: $key" \
    --header 'Content-Type: application/json' \
    --data "$(payment_reset_body "$sandbox" "$case_id" "$order_id" "$ttl_seconds")"
}

sign_payment_callback() {
  local timestamp="$1"
  local idempotency_key="$2"
  local event_id="$3"
  local correlation_id="$4"
  local order_id="$5"
  local sandbox_id="$6"
  local session_id="$7"
  local trace_id="$8"
  local operation_id="$9"
  local amount_minor="${10:-1800}"
  local currency="${11:-CNY}"
  local outcome="${12:-SUCCEEDED}"
  local canonical
  canonical="$(printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s' \
    "$mock_payment_key" "$timestamp" "$idempotency_key" "$event_id" "$correlation_id" \
    "$order_id" "$amount_minor" "$currency" "$outcome" "$sandbox_id" "$session_id" "$trace_id" \
    "$operation_id")"
  printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$mock_payment_secret" -hex | awk '{print $NF}'
}

ENV_FILE="$env_file" ./scripts/init_local.sh
auth_app_password="$(read_value MYSQL_AUTH_APP_PASSWORD)"
commerce_app_password="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
commerce_migration_password="$(read_value MYSQL_COMMERCE_MIGRATION_PASSWORD)"
agent_app_password="$(read_value MYSQL_AGENT_APP_PASSWORD)"
root_password="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
commerce_service_password="$(openssl rand -hex 24)"
evaluator_password="$(openssl rand -hex 24)"
agent_service_password="$(openssl rand -hex 24)"
management_password="$(openssl rand -hex 24)"
invalid_management_password="$(openssl rand -hex 24)"
mock_payment_key="cb105-$(openssl rand -hex 12)"
mock_payment_secret="$(openssl rand -hex 32)"
legacy_sandbox_id='sandbox-legacy-upgrade'
legacy_case='case-legacy-upgrade'
legacy_handle="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
legacy_session='legacy-upgrade-session'
legacy_trace='legacy-upgrade-trace'
legacy_operation="$(printf '1%.0s' {1..64})"
legacy_product_id='legacy-product'
legacy_reference_id="$(evaluation_product_reference \
  "$legacy_sandbox_id" "$legacy_session" "$legacy_trace" "$legacy_operation" \
  PRODUCT_FIXTURE "$legacy_product_id" 1 OBSERVED)"
legacy_trace_2='legacy-upgrade-trace-2'
legacy_operation_2="$(printf '2%.0s' {1..64})"
legacy_product_id_2='legacy-product-2'
legacy_reference_id_2="$(evaluation_product_reference \
  "$legacy_sandbox_id" "$legacy_session" "$legacy_trace_2" "$legacy_operation_2" \
  PRODUCT_FIXTURE "$legacy_product_id_2" 1 OBSERVED)"
legacy_other_sandbox_id='sandbox-legacy-upgrade-other'
legacy_other_case='case-legacy-upgrade-other'
legacy_other_handle="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
legacy_other_session='legacy-upgrade-other-session'
legacy_other_trace='legacy-upgrade-other-trace'
legacy_other_operation="$(printf '3%.0s' {1..64})"
legacy_other_product_id='legacy-other-product'
legacy_other_reference_id="$(evaluation_product_reference \
  "$legacy_other_sandbox_id" "$legacy_other_session" "$legacy_other_trace" \
  "$legacy_other_operation" PRODUCT_FIXTURE "$legacy_other_product_id" 1 OBSERVED)"
legacy_other_trace_2='legacy-upgrade-other-trace-2'
legacy_other_operation_2="$(printf '4%.0s' {1..64})"
legacy_other_product_id_2='legacy-other-product-2'
legacy_other_reference_id_2="$(evaluation_product_reference \
  "$legacy_other_sandbox_id" "$legacy_other_session" "$legacy_other_trace_2" \
  "$legacy_other_operation_2" PRODUCT_FIXTURE "$legacy_other_product_id_2" 1 OBSERVED)"
commerce_service_hash="$(uv run python scripts/hash_test_credential.py "$commerce_service_password")"
evaluator_hash="$(uv run python scripts/hash_test_credential.py "$evaluator_password")"
agent_service_hash="$(uv run python scripts/hash_test_credential.py "$agent_service_password")"

"${compose[@]}" up --detach --wait --wait-timeout 60 mysql
compose_host_port MYSQL_PORT mysql 3306
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-auth migrate-agent
pre_totality_migrations="$tmp_dir/pre-totality-commerce-migrations"
mkdir -p "$pre_totality_migrations"
cp infra/mysql/migrations/commerce/V00*.sql \
  infra/mysql/migrations/commerce/V010__evaluation_sandbox_lifecycle.sql \
  infra/mysql/migrations/commerce/V011__evaluation_commerce_audit_reference.sql \
  infra/mysql/migrations/commerce/V012__evaluation_mock_payment_callback.sql \
  "$pre_totality_migrations/"
"${compose[@]}" run --rm \
  --volume "$pre_totality_migrations:/opt/citybuddy/migrations:ro" commerce-migrate
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
mysql_query root "$root_password" commerce_db "
INSERT INTO eval_sandbox (
  sandbox_id, case_correlation, reset_idempotency_key, fixture_digest, fixture_count,
  test_user_label, requested_ttl_seconds, auth_provision_idempotency_key,
  auth_revoke_idempotency_key, opaque_handle, lifecycle_state, auth_invalidation_state,
  provisioning_due_at, auth_expiry_upper_bound, expires_at, activated_at
) VALUES
(
  '$legacy_sandbox_id', '$legacy_case', 'reset-legacy-upgrade', REPEAT('a', 64), 2,
  'legacy-upgrade-user', 3600, 'provision-legacy-upgrade', 'revoke-legacy-upgrade',
  '$legacy_handle', 'ACTIVE', 'PROVISIONED', TIMESTAMPADD(MINUTE, 1, CURRENT_TIMESTAMP(6)),
  TIMESTAMPADD(HOUR, 25, CURRENT_TIMESTAMP(6)), TIMESTAMPADD(HOUR, 24, CURRENT_TIMESTAMP(6)),
  CURRENT_TIMESTAMP(6)
),
(
  '$legacy_other_sandbox_id', '$legacy_other_case', 'reset-legacy-upgrade-other',
  REPEAT('b', 64), 2, 'legacy-upgrade-other-user', 3600,
  'provision-legacy-upgrade-other', 'revoke-legacy-upgrade-other',
  '$legacy_other_handle', 'ACTIVE', 'PROVISIONED',
  TIMESTAMPADD(MINUTE, 1, CURRENT_TIMESTAMP(6)),
  TIMESTAMPADD(HOUR, 25, CURRENT_TIMESTAMP(6)),
  TIMESTAMPADD(HOUR, 24, CURRENT_TIMESTAMP(6)), CURRENT_TIMESTAMP(6)
);
INSERT INTO eval_sandbox_product_fixture (
  sandbox_id, product_id, name, description, price_minor, currency, stock_quantity,
  available, publication_version
) VALUES
(
  '$legacy_sandbox_id', '$legacy_product_id', 'Legacy product', 'pre-V013 product fixture',
  700, 'CNY', 2, TRUE, 1
),
(
  '$legacy_sandbox_id', '$legacy_product_id_2', 'Legacy product 2',
  'second pre-V013 product fixture', 701, 'CNY', 2, TRUE, 1
),
(
  '$legacy_other_sandbox_id', '$legacy_other_product_id', 'Other legacy product',
  'other sandbox pre-V013 product fixture', 702, 'CNY', 2, TRUE, 1
),
(
  '$legacy_other_sandbox_id', '$legacy_other_product_id_2', 'Other legacy product 2',
  'other sandbox second pre-V013 product fixture', 703, 'CNY', 2, TRUE, 1
);
INSERT INTO eval_commerce_audit_reference (
  audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id,
  entity_type, entity_id, entity_version, outcome
) VALUES
(
  '$legacy_reference_id', '$legacy_sandbox_id', '$legacy_session', '$legacy_trace',
  '$legacy_operation', 'PRODUCT_FIXTURE', '$legacy_product_id', 1, 'OBSERVED'
),
(
  '$legacy_reference_id_2', '$legacy_sandbox_id', '$legacy_session', '$legacy_trace_2',
  '$legacy_operation_2', 'PRODUCT_FIXTURE', '$legacy_product_id_2', 1, 'OBSERVED'
),
(
  '$legacy_other_reference_id', '$legacy_other_sandbox_id', '$legacy_other_session',
  '$legacy_other_trace', '$legacy_other_operation', 'PRODUCT_FIXTURE',
  '$legacy_other_product_id', 1, 'OBSERVED'
),
(
  '$legacy_other_reference_id_2', '$legacy_other_sandbox_id', '$legacy_other_session',
  '$legacy_other_trace_2', '$legacy_other_operation_2', 'PRODUCT_FIXTURE',
  '$legacy_other_product_id_2', 1, 'OBSERVED'
);
"
legacy_created_at="$(mysql_query root "$root_password" commerce_db \
  "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM eval_commerce_audit_reference WHERE audit_reference_id = '$legacy_reference_id'")"

# Prove a crash before the exact grant barrier cannot publish AWAITING or skip unfinished DDL.
pre_barrier_interrupt_migrations="$tmp_dir/pre-barrier-interrupt-migrations"
mkdir -p "$pre_barrier_interrupt_migrations"
cp infra/mysql/migrations/commerce/*.sql "$pre_barrier_interrupt_migrations/"
awk '
  /^ALTER TABLE mock_payment_callback$/ { print "DO SLEEP(60);" }
  { print }
' infra/mysql/migrations/commerce/V013__evaluation_audit_totality.sql \
  >"$pre_barrier_interrupt_migrations/V013__evaluation_audit_totality.sql"
set +e
"${compose[@]}" run --rm -e MIGRATION_PREPARE_V013=true \
  --volume "$pre_barrier_interrupt_migrations:/opt/citybuddy/migrations:ro" commerce-migrate \
  >"$tmp_dir/v013-pre-barrier-migration.log" 2>&1 &
pre_barrier_migration_pid=$!
set -e
wait_for_mysql_value V013_DDL_PREPARING \
  "SELECT table_comment FROM information_schema.tables WHERE table_schema = 'commerce_db' AND table_name = 'eval_commerce_audit_legacy_watermark'" \
  "V013 partial pre-barrier phase remains DDL_PREPARING"
kill_active_commerce_migration
if wait "$pre_barrier_migration_pid"; then
  echo "Expected the controlled pre-barrier migration interruption to fail." >&2
  exit 1
fi
assert_equal "0:V013_DDL_PREPARING:CURRENT_TIMESTAMP(6)" \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT(h.success, ':', t.table_comment, ':', c.column_default) FROM commerce_schema_history h JOIN information_schema.tables t ON t.table_schema = 'commerce_db' AND t.table_name = 'eval_commerce_audit_legacy_watermark' JOIN information_schema.columns c ON c.table_schema = 'commerce_db' AND c.table_name = 'mock_payment_callback' AND c.column_name = 'created_at' WHERE h.version = '013'")" \
  "partial pre-barrier history, phase, and unfinished callback DDL"
assert_v013_migration_grants_absent "partial pre-barrier cleanup"
if make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce \
  >"$tmp_dir/v013-pre-barrier-retry.log" 2>&1; then
  echo "Partial DDL_PREPARING migration resumed unexpectedly." >&2
  exit 1
fi
assert_v013_migration_grants_absent "partial pre-barrier retry cleanup"

# Reset only the controlled partial prefix so the same historical fixture can exercise later phases.
mysql_query root "$root_password" commerce_db "
DROP TABLE eval_commerce_product_observation;
DROP TABLE eval_commerce_audit_legacy_watermark;
ALTER TABLE eval_commerce_audit_reference
  DROP COLUMN created_at_anchor,
  MODIFY COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
DELETE FROM commerce_schema_history WHERE version = '013';
"

# Prepare the exact barrier, then interrupt the grant client only after both table grants commit.
# The failed orchestration must use the phase-independent cleanup path rather than re-granting in
# AWAITING.
"${compose[@]}" run --rm -e MIGRATION_PREPARE_V013=true commerce-migrate
grant_interrupt_script="$tmp_dir/apply-mysql-grants-interrupted.sh"
awk '
  { print }
  /echo "migration-v013-grants=exact-prepared"/ { print "      sleep 60" }
' scripts/apply_mysql_grants.sh >"$grant_interrupt_script"
grant_interrupt_override="$tmp_dir/v013-grant-interrupt-compose.yaml"
printf '%s\n' \
  'services:' \
  '  mysql-grants:' \
  '    volumes:' \
  "      - $grant_interrupt_script:/opt/citybuddy/apply-grants.sh:ro" \
  >"$grant_interrupt_override"
fault_compose_command="docker compose --project-name $project --env-file $env_file --file compose.yaml --file $grant_interrupt_override"
set +e
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" COMPOSE="$fault_compose_command" \
  migrate-commerce >"$tmp_dir/v013-grant-interruption.log" 2>&1 &
grant_interruption_pid=$!
set -e
wait_for_mysql_value 2 \
  "SELECT COUNT(*) FROM mysql.tables_priv WHERE Db = 'commerce_db' AND User = 'commerce_migration' AND Table_name IN ('eval_commerce_audit_reference', 'eval_commerce_audit_legacy_watermark')" \
  "V013 exact grants are committed before the grant-client interruption"
grant_container_id="$(docker compose --project-name "$project" --env-file "$env_file" \
  --file compose.yaml --file "$grant_interrupt_override" ps --all --quiet mysql-grants)"
if [[ -z "$grant_container_id" ]]; then
  echo "Could not locate the controlled V013 grant client." >&2
  exit 1
fi
docker kill "$grant_container_id" >/dev/null
if wait "$grant_interruption_pid"; then
  echo "Expected the controlled V013 exact-grant interruption to fail." >&2
  exit 1
fi
grep -Fq 'migration-v013-grants=force-revoked' "$tmp_dir/v013-grant-interruption.log"
assert_v013_migration_grants_absent "AWAITING exact-grant client interruption cleanup"

# Continue from the unchanged AWAITING barrier, then prove a POPULATING interruption is cleaned up.
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
mysql_query root "$root_password" commerce_db \
  'LOCK TABLES eval_commerce_audit_reference WRITE; SELECT SLEEP(60) /* CB105_POPULATING_LOCK */' \
  >"$tmp_dir/v013-populating-lock.log" 2>&1 &
populating_lock_pid=$!
sleep 0.5
set +e
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce \
  >"$tmp_dir/v013-populating-migration.log" 2>&1 &
populating_migration_pid=$!
set -e
wait_for_mysql_value V013_COMMITMENT_POPULATING \
  "SELECT table_comment FROM information_schema.tables WHERE table_schema = 'commerce_db' AND table_name = 'eval_commerce_audit_legacy_watermark'" \
  "V013 commitment enters POPULATING before the controlled read block"
kill_active_commerce_migration
kill_mysql_sessions_matching \
  "USER = 'commerce_migration' AND DB = 'commerce_db' AND INFO LIKE 'INSERT INTO eval_commerce_audit_legacy_watermark%'"
kill_mysql_sessions_matching \
  "USER = 'root' AND INFO LIKE 'SELECT SLEEP(60) /* CB105_POPULATING_LOCK */%'"
kill "$populating_lock_pid" >/dev/null 2>&1 || true
if wait "$populating_migration_pid"; then
  echo "Expected the controlled POPULATING migration interruption to fail." >&2
  exit 1
fi
assert_v013_migration_grants_absent "POPULATING interruption cleanup"
if make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce \
  >"$tmp_dir/v013-populating-retry.log" 2>&1; then
  echo "Interrupted POPULATING migration resumed unexpectedly." >&2
  exit 1
fi
assert_v013_migration_grants_absent "POPULATING retry preflight cleanup"
wait_for_mysql_value 0 \
  "SELECT COUNT(*) FROM information_schema.processlist WHERE db = 'commerce_db' AND info LIKE 'INSERT INTO eval_commerce_audit_legacy_watermark%'" \
  "interrupted POPULATING server work is quiescent before fixture reset"
mysql_query root "$root_password" commerce_db "
DELETE FROM eval_commerce_audit_legacy_watermark WHERE watermark_key = 'V013';
ALTER TABLE eval_commerce_audit_legacy_watermark COMMENT='V013_AWAITING_COMMITMENT';
"

# An unknown phase must revoke before it fails, not preserve transient authority.
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE eval_commerce_audit_legacy_watermark COMMENT='V013_UNKNOWN_CORRUPTED_PHASE'"
if make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce \
  >"$tmp_dir/v013-unknown-phase.log" 2>&1; then
  echo "Unknown V013 phase was accepted unexpectedly." >&2
  exit 1
fi
assert_v013_migration_grants_absent "unknown phase cleanup"
mysql_query root "$root_password" commerce_db "
DELETE FROM eval_commerce_audit_legacy_watermark WHERE watermark_key = 'V013';
ALTER TABLE eval_commerce_audit_legacy_watermark COMMENT='V013_AWAITING_COMMITMENT';
"

# Delay only the history UPDATE so the process can be killed after SEALED but before success=true.
mysql_query root "$root_password" commerce_db \
  "CREATE TRIGGER cb105_v013_history_pause BEFORE UPDATE ON commerce_schema_history FOR EACH ROW DO SLEEP(60)"
set +e
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce \
  >"$tmp_dir/v013-sealed-history-migration.log" 2>&1 &
sealed_migration_pid=$!
set -e
if ! wait_for_mysql_value V013_COMMITMENT_SEALED \
  "SELECT table_comment FROM information_schema.tables WHERE table_schema = 'commerce_db' AND table_name = 'eval_commerce_audit_legacy_watermark'" \
  "V013 reaches SEALED before the controlled history interruption"; then
  cat "$tmp_dir/v013-sealed-history-migration.log" >&2
  exit 1
fi
kill_mysql_sessions_matching \
  "USER = 'root' AND DB = 'commerce_db' AND INFO = 'DO SLEEP(60)'"
if wait "$sealed_migration_pid"; then
  echo "Expected the controlled SEALED/history=false migration interruption to fail." >&2
  exit 1
fi
mysql_query root "$root_password" commerce_db 'DROP TRIGGER cb105_v013_history_pause'
assert_equal 0 "$(mysql_query root "$root_password" commerce_db \
  "SELECT success FROM commerce_schema_history WHERE version = '013'")" \
  "SEALED interruption keeps migration history incomplete"
assert_v013_migration_grants_absent "SEALED/history=false interruption cleanup"
mysql_query root "$root_password" commerce_db "
DELETE FROM eval_commerce_audit_legacy_watermark WHERE watermark_key = 'V013';
ALTER TABLE eval_commerce_audit_legacy_watermark COMMENT='V013_AWAITING_COMMITMENT';
"

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce
commerce_runtime_grant_output="$(
  make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
)"
grep -q 'runtime-grants=applied' <<<"$commerce_runtime_grant_output"

commerce_migration_grants="$(mysql_query commerce_migration "$commerce_migration_password" '' \
  'SHOW GRANTS FOR CURRENT_USER')"
if grep -Eq 'SELECT, INSERT.*ON `commerce_db`\.\*|ON `commerce_db`\.`eval_commerce_audit_(reference|legacy_watermark)`' \
  <<<"$commerce_migration_grants"; then
  echo "Commerce migration retained forbidden data access after V013 sealed." >&2
  exit 1
fi
assert_mysql_fails "commerce migration cannot read auth-private truth after V013" \
  mysql_query commerce_migration "$commerce_migration_password" commerce_db \
  'SELECT * FROM auth_eval_test_principal'
assert_mysql_fails "commerce migration cannot append audit truth after V013" \
  mysql_query commerce_migration "$commerce_migration_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES (REPEAT('f', 64), '$legacy_sandbox_id', '$legacy_session', '$legacy_trace', REPEAT('e', 64), 'PRODUCT_FIXTURE', '$legacy_product_id', 1, 'OBSERVED', CURRENT_TIMESTAMP(6), 'LEGACY_CUTOFF')"
assert_equal "1:V013_COMMITMENT_SEALED" \
  "$(mysql_query commerce_migration "$commerce_migration_password" commerce_db \
    "SELECT CONCAT(success, ':', (SELECT table_comment FROM information_schema.tables WHERE table_schema = 'commerce_db' AND table_name = 'eval_commerce_audit_legacy_watermark')) FROM commerce_schema_history WHERE version = '013'")" \
  "V013 exact-grant barrier sealed and history complete"

assert_equal "4:$legacy_other_reference_id_2:4:CITYBUDDY_EVAL_AUDIT_LEGACY_LPUTF8_SHA256_CHAIN_V1:64" \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(cutoff_sequence_id, ':', cutoff_audit_reference_id, ':', legacy_row_count, ':', commitment_format, ':', LENGTH(legacy_set_digest)) FROM eval_commerce_audit_legacy_watermark WHERE watermark_key = 'V013'")" \
  "V013 immutable complete-set legacy commitment"
assert_equal "$legacy_created_at:LEGACY_CUTOFF" \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'), ':', created_at_anchor) FROM eval_commerce_audit_reference WHERE audit_reference_id = '$legacy_reference_id'")" \
  "pre-V013 audit classification"

if [[ "$v013_only" == true ]]; then
  echo "CB-105 V013 interrupted-authority integration passed."
  exit 0
fi

mysql_query auth_app "$auth_app_password" commerce_db "
INSERT INTO auth_service_identity (service_id, client_id, credential_hash, state, allowed_scopes) VALUES
  ('00000000-0000-0000-0000-000000000101', 'commerce-service', '$commerce_service_hash', 'ACTIVE', 'eval:principal:manage'),
  ('00000000-0000-0000-0000-000000000102', 'evaluation-client', '$evaluator_hash', 'ACTIVE', 'eval:test-token:issue'),
  ('00000000-0000-0000-0000-000000000103', 'agent-service', '$agent_service_hash', 'ACTIVE', 'catalog:read refund:create');
INSERT INTO auth_signing_key_metadata (kid, state, activated_at, retire_after) VALUES
  ('current-key', 'CURRENT', CURRENT_TIMESTAMP(6), NULL),
  ('overlap-key', 'OVERLAP', CURRENT_TIMESTAMP(6), TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP(6)));
"
assert_mysql_fails "auth runtime cannot read sandbox registry" \
  mysql_query auth_app "$auth_app_password" commerce_db 'SELECT * FROM eval_sandbox'
assert_mysql_fails "agent runtime cannot read sandbox registry" \
  mysql_query agent_app "$agent_app_password" commerce_db 'SELECT * FROM eval_sandbox'
assert_mysql_fails "auth runtime cannot read commerce evaluation audit" \
  mysql_query auth_app "$auth_app_password" commerce_db \
  'SELECT * FROM eval_commerce_audit_reference'
assert_mysql_fails "agent runtime cannot read commerce evaluation audit" \
  mysql_query agent_app "$agent_app_password" commerce_db \
  'SELECT * FROM eval_commerce_audit_reference'
assert_mysql_fails "auth runtime cannot read commerce product observation truth" \
  mysql_query auth_app "$auth_app_password" commerce_db \
  'SELECT * FROM eval_commerce_product_observation'
assert_mysql_fails "agent runtime cannot read commerce product observation truth" \
  mysql_query agent_app "$agent_app_password" commerce_db \
  'SELECT * FROM eval_commerce_product_observation'
assert_mysql_fails "auth runtime cannot read agent evidence truth" \
  mysql_query auth_app "$auth_app_password" cs_db 'SELECT * FROM support_event'
assert_mysql_fails "commerce runtime cannot read agent evidence truth" \
  mysql_query commerce_app "$commerce_app_password" cs_db 'SELECT * FROM support_event'
assert_mysql_fails "commerce runtime cannot read auth provisioning truth" \
  mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT * FROM auth_eval_test_principal'
assert_mysql_fails "commerce runtime cannot execute DDL" \
  mysql_query commerce_app "$commerce_app_password" commerce_db 'CREATE TABLE forbidden_cb101 (id INT)'
assert_mysql_fails "agent runtime cannot execute DDL" \
  mysql_query agent_app "$agent_app_password" cs_db 'CREATE TABLE forbidden_cb103 (id INT)'
agent_grants="$(mysql_query agent_app "$agent_app_password" '' 'SHOW GRANTS FOR CURRENT_USER')"
for table in support_session support_conversation support_turn support_event support_feedback \
  retrieval_decision retrieval_evidence; do
  grep -Fq "\`cs_db\`.\`$table\`" <<<"$agent_grants"
done
if grep -Fq 'commerce_db' <<<"$agent_grants"; then
  echo "Agent runtime gained forbidden commerce_db access." >&2
  exit 1
fi
commerce_grants="$(mysql_query commerce_app "$commerce_app_password" '' 'SHOW GRANTS FOR CURRENT_USER')"
evaluation_grants="$(grep -F 'eval_' <<<"$commerce_grants")"
printf '%s\n' "$evaluation_grants"
grep -Fq 'GRANT SELECT, INSERT, UPDATE ON `commerce_db`.`eval_sandbox`' <<<"$commerce_grants"
grep -Fq 'GRANT SELECT, INSERT, UPDATE, DELETE ON `commerce_db`.`eval_sandbox_product_fixture`' <<<"$commerce_grants"
grep -Fq 'GRANT SELECT, INSERT ON `commerce_db`.`eval_sandbox_effect_stub`' <<<"$commerce_grants"
grep -Fq 'GRANT SELECT, INSERT ON `commerce_db`.`eval_commerce_audit_reference`' \
  <<<"$commerce_grants"
grep -Fq 'GRANT SELECT, INSERT ON `commerce_db`.`eval_commerce_product_observation`' \
  <<<"$commerce_grants"
grep -Fq 'GRANT SELECT ON `commerce_db`.`eval_commerce_audit_legacy_watermark`' \
  <<<"$commerce_grants"
assert_mysql_fails "commerce audit references are append-only" \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET outcome = 'OBSERVED' WHERE sequence_id = 1"
assert_mysql_fails "commerce audit references cannot be deleted by runtime" \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  'DELETE FROM eval_commerce_audit_reference WHERE sequence_id = 1'
assert_mysql_fails "commerce product observations are append-only" \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE eval_commerce_product_observation SET outcome = 'OBSERVED' WHERE observation_id = REPEAT('0', 64)"
assert_mysql_fails "commerce product observations cannot be deleted by runtime" \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  'DELETE FROM eval_commerce_product_observation WHERE observation_id = REPEAT("0", 64)'
assert_mysql_fails "commerce runtime cannot insert a legacy watermark" \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "INSERT INTO eval_commerce_audit_legacy_watermark (watermark_key, commitment_format, legacy_set_digest, cutoff_sequence_id, legacy_row_count, recorded_at) VALUES ('V013', 'CITYBUDDY_EVAL_AUDIT_LEGACY_LPUTF8_SHA256_CHAIN_V1', REPEAT('0', 64), 0, 0, CURRENT_TIMESTAMP(6))"
assert_mysql_fails "commerce runtime cannot update the legacy watermark" \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE eval_commerce_audit_legacy_watermark SET legacy_row_count = 0 WHERE watermark_key = 'V013'"
assert_mysql_fails "commerce runtime cannot delete the legacy watermark" \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "DELETE FROM eval_commerce_audit_legacy_watermark WHERE watermark_key = 'V013'"
explain_audit="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "EXPLAIN SELECT sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-main' AND support_session_id = 'session-main' AND sequence_id > 0 ORDER BY sequence_id LIMIT 21")"
grep -Fq 'ix_eval_audit_session_page' <<<"$explain_audit"
explain_cleanup="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "EXPLAIN SELECT sandbox_id, case_correlation, reset_idempotency_key, fixture_digest, fixture_count, test_user_label, requested_ttl_seconds, auth_provision_idempotency_key, auth_revoke_idempotency_key, opaque_handle, lifecycle_state, auth_invalidation_state, death_reason, completion_idempotency_key, cleanup_attempts, cleanup_due_at, provisioning_due_at, auth_expiry_upper_bound, expires_at, activated_at, dead_at, closed_at, version FROM eval_sandbox WHERE cleanup_due_at IS NOT NULL AND cleanup_due_at <= CURRENT_TIMESTAMP(6) ORDER BY cleanup_due_at, lifecycle_state, sandbox_id LIMIT 4 FOR UPDATE SKIP LOCKED")"
grep -Fq 'ix_eval_sandbox_cleanup' <<<"$explain_cleanup"
if grep -Fq 'Using filesort' <<<"$explain_cleanup"; then
  echo "Cleanup claim query does not use index ordering." >&2
  exit 1
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp_dir/current-private.pem" 2>/dev/null
openssl pkey -in "$tmp_dir/current-private.pem" -pubout -out "$tmp_dir/current-public.pem" 2>/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp_dir/overlap-private.pem" 2>/dev/null
openssl pkey -in "$tmp_dir/overlap-private.pem" -pubout -out "$tmp_dir/overlap-public.pem" 2>/dev/null
./mvnw -q -pl auth-service,commerce-service -am -DskipTests package

start_auth production
start_commerce production "http://127.0.0.1:$auth_port"
start_agent false
assert_status 404 "production profile omits agent evaluation evidence" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/00000000-0000-0000-0000-000000000103" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-production'
stop_process agent_pid "$agent_pid"
assert_status 404 "production profile omits reset" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: production-reset' \
  --header 'Content-Type: application/json' \
  --data "$(reset_body sandbox-production case-production forbidden)"
assert_status 404 "production profile omits completion" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-production/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: production-complete' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-production"}'
assert_status 404 "production profile omits evaluation state" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-production'
assert_status 404 "production profile omits evaluation audit" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/session-production" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-production'
assert_status 404 "production profile omits evaluation version" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/version" \
  --user "evaluation-manager:$management_password"
assert_status 404 "production profile omits liveness" \
  --request POST "http://127.0.0.1:$commerce_port/internal/eval/sandboxes/sandbox-production/liveness"
stop_process commerce_pid "$commerce_pid"
stop_process auth_pid "$auth_pid"

start_auth evaluation
start_commerce evaluation "http://127.0.0.1:$auth_port"
assert_status 200 "version exposes only fixed server identifiers" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/version" \
  --user "evaluation-manager:$management_password"
cp "$tmp_dir/http-response.json" "$tmp_dir/version.json"
uv run python scripts/check_evaluation_views.py version "$tmp_dir/version.json" \
  --build cb102-integration-build --schema commerce-evaluation-v1
assert_status 401 "version rejects substituted credential" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/version" \
  --user "evaluation-client:$invalid_management_password"
assert_status 400 "version rejects caller capability override" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/version?capability=all" \
  --user "evaluation-manager:$management_password"
curl --silent --show-error "http://127.0.0.1:$auth_port/auth/jwks" >"$tmp_dir/jwks.json"
assert_status 200 "provision migrated legacy sandbox identity through the real auth boundary" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: provision-legacy-upgrade' \
  --header 'Content-Type: application/json' \
  --data "{\"sandboxId\":\"$legacy_sandbox_id\",\"caseCorrelation\":\"$legacy_case\",\"testUserLabel\":\"legacy-upgrade-user\",\"ttlSeconds\":3600}"
legacy_handle="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" handle)"
legacy_subject="$(mysql_query auth_app "$auth_app_password" commerce_db \
  "SELECT subject FROM auth_eval_test_principal WHERE opaque_handle = '$legacy_handle'")"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_sandbox SET opaque_handle = '$legacy_handle' WHERE sandbox_id = '$legacy_sandbox_id'"
assert_status 200 "issue migrated legacy sandbox token" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header "X-Eval-Sandbox-Id: $legacy_sandbox_id" \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$legacy_handle\"}"
legacy_direct_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
assert_status 200 "exchange migrated legacy sandbox OBO token" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $legacy_direct_token" \
  --header "X-Eval-Sandbox-Id: $legacy_sandbox_id" \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$legacy_session\",\"userSubject\":\"$legacy_subject\",\"scope\":\"catalog:read\"}"
legacy_obo_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
assert_equal 1 \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT COUNT(*) FROM eval_sandbox WHERE sandbox_id = '$legacy_sandbox_id' AND lifecycle_state = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP(6)")" \
  "migrated legacy sandbox active truth"
assert_equal "$legacy_reference_id:$legacy_session:$legacy_trace:$legacy_operation:$legacy_product_id:1:OBSERVED:LEGACY_CUTOFF" \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(audit_reference_id, ':', support_session_id, ':', trace_id, ':', operation_id, ':', entity_id, ':', entity_version, ':', outcome, ':', created_at_anchor) FROM eval_commerce_audit_reference WHERE audit_reference_id = '$legacy_reference_id'")" \
  "migrated legacy audit identity"
assert_status 200 "pre-replay migrated legacy audit is accepted only under its watermark" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header "X-Eval-Sandbox-Id: $legacy_sandbox_id"
assert_status 200 "second sandbox multi-row legacy set is accepted under one commitment" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header "X-Eval-Sandbox-Id: $legacy_other_sandbox_id"

legacy_sequence_id="$(mysql_query root "$root_password" commerce_db \
  "SELECT sequence_id FROM eval_commerce_audit_reference WHERE audit_reference_id = '$legacy_reference_id'")"
legacy_created_at_2="$(mysql_query root "$root_password" commerce_db \
  "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM eval_commerce_audit_reference WHERE audit_reference_id = '$legacy_reference_id_2'")"
legacy_sequence_id_2="$(mysql_query root "$root_password" commerce_db \
  "SELECT sequence_id FROM eval_commerce_audit_reference WHERE audit_reference_id = '$legacy_reference_id_2'")"
assert_equal 1 "$legacy_sequence_id" "first legacy row is below the cutoff"
assert_equal 2 "$legacy_sequence_id_2" "second legacy row is below the cutoff"

forged_legacy_operation="$(printf '5%.0s' {1..64})"
forged_legacy_reference="$(evaluation_product_reference \
  "$legacy_sandbox_id" "$legacy_session" forged-legacy-trace "$forged_legacy_operation" \
  PRODUCT_FIXTURE forged-product 1 OBSERVED)"
mysql_query root "$root_password" commerce_db "
DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = '$legacy_reference_id';
INSERT INTO eval_commerce_audit_reference (
  sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id,
  entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor
) VALUES (
  $legacy_sequence_id, '$forged_legacy_reference', '$legacy_sandbox_id', '$legacy_session',
  'forged-legacy-trace', '$forged_legacy_operation', 'PRODUCT_FIXTURE', 'forged-product',
  1, 'OBSERVED', '$legacy_created_at', 'LEGACY_CUTOFF'
);"
assert_legacy_commitment_fails_closed \
  "lower-sequence deletion plus a digest-self-consistent replacement in the same hole"
mysql_query root "$root_password" commerce_db "
DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = '$forged_legacy_reference';
INSERT INTO eval_commerce_audit_reference (
  sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id,
  entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor
) VALUES (
  $legacy_sequence_id, '$legacy_reference_id', '$legacy_sandbox_id', '$legacy_session',
  '$legacy_trace', '$legacy_operation', 'PRODUCT_FIXTURE', '$legacy_product_id',
  1, 'OBSERVED', '$legacy_created_at', 'LEGACY_CUTOFF'
);"
assert_legacy_commitment_recovers "exact restoration after lower-sequence replacement"

tamper_legacy_column "legacy sequence_id mutation" \
  "UPDATE eval_commerce_audit_reference SET sequence_id = 20 WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET sequence_id = $legacy_sequence_id_2 WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy audit_reference_id mutation" \
  "UPDATE eval_commerce_audit_reference SET audit_reference_id = REPEAT('e', 64) WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET audit_reference_id = '$legacy_reference_id_2' WHERE audit_reference_id = REPEAT('e', 64)"
tamper_legacy_column "legacy sandbox_id cross-sandbox redistribution" \
  "UPDATE eval_commerce_audit_reference SET sandbox_id = '$legacy_other_sandbox_id' WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET sandbox_id = '$legacy_sandbox_id' WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy support_session_id mutation" \
  "UPDATE eval_commerce_audit_reference SET support_session_id = 'tampered-legacy-session' WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET support_session_id = '$legacy_session' WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy trace_id mutation" \
  "UPDATE eval_commerce_audit_reference SET trace_id = 'tampered-legacy-trace' WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET trace_id = '$legacy_trace_2' WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy operation_id mutation" \
  "UPDATE eval_commerce_audit_reference SET operation_id = REPEAT('6', 64) WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET operation_id = '$legacy_operation_2' WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy entity_type mutation" \
  "UPDATE eval_commerce_audit_reference SET entity_type = 'PAYMENT_CALLBACK' WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET entity_type = 'PRODUCT_FIXTURE' WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy entity_id mutation" \
  "UPDATE eval_commerce_audit_reference SET entity_id = 'tampered-legacy-product' WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET entity_id = '$legacy_product_id_2' WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy entity_version mutation" \
  "UPDATE eval_commerce_audit_reference SET entity_version = 2 WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET entity_version = 1 WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy outcome mutation" \
  "SET SESSION sql_mode = 'NO_ENGINE_SUBSTITUTION'; UPDATE eval_commerce_audit_reference SET outcome = 'FUTURE_OUTCOME' WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET outcome = 'OBSERVED' WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy created_at mutation" \
  "UPDATE eval_commerce_audit_reference SET created_at = TIMESTAMPADD(MICROSECOND, 1, created_at) WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET created_at = '$legacy_created_at_2' WHERE audit_reference_id = '$legacy_reference_id_2'"
tamper_legacy_column "legacy created_at_anchor mutation" \
  "UPDATE eval_commerce_audit_reference SET created_at_anchor = 'BUSINESS_EVENT' WHERE audit_reference_id = '$legacy_reference_id_2'" \
  "UPDATE eval_commerce_audit_reference SET created_at_anchor = 'LEGACY_CUTOFF' WHERE audit_reference_id = '$legacy_reference_id_2'"

assert_status 200 "other sandbox also recovers after cross-sandbox commitment matrix" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header "X-Eval-Sandbox-Id: $legacy_other_sandbox_id"
assert_status 200 "pre-V013 product operation replays from migrated audit truth" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $legacy_obo_token" \
  --header "X-Support-Session-Id: $legacy_session" \
  --header "X-Eval-Sandbox-Id: $legacy_sandbox_id" \
  --header "X-Agent-Trace-Id: $legacy_trace" \
  --header "X-Agent-Operation-Id: $legacy_operation" \
  --header 'Content-Type: application/json' \
  --data "{\"productId\":\"$legacy_product_id\"}"
assert_equal "$legacy_created_at" \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM eval_commerce_product_observation WHERE observation_id = '$legacy_reference_id'")" \
  "legacy replay observation time reconstructed from audit truth"
assert_status 200 "migrated legacy replay is idempotent" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $legacy_obo_token" \
  --header "X-Support-Session-Id: $legacy_session" \
  --header "X-Eval-Sandbox-Id: $legacy_sandbox_id" \
  --header "X-Agent-Trace-Id: $legacy_trace" \
  --header "X-Agent-Operation-Id: $legacy_operation" \
  --header 'Content-Type: application/json' \
  --data "{\"productId\":\"$legacy_product_id\"}"
assert_status 403 "migrated legacy operation rejects conflicting replay intent" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $legacy_obo_token" \
  --header "X-Support-Session-Id: $legacy_session" \
  --header "X-Eval-Sandbox-Id: $legacy_sandbox_id" \
  --header 'X-Agent-Trace-Id: conflicting-legacy-trace' \
  --header "X-Agent-Operation-Id: $legacy_operation" \
  --header 'Content-Type: application/json' \
  --data "{\"productId\":\"$legacy_product_id\"}"
assert_equal '1:1' \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT((SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE operation_id = '$legacy_operation'), ':', (SELECT COUNT(*) FROM eval_commerce_product_observation WHERE operation_id = '$legacy_operation'))")" \
  "legacy replay keeps one audit and one reconstructed truth"
assert_status 200 "migrated legacy sandbox remains reconcilable" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header "X-Eval-Sandbox-Id: $legacy_sandbox_id"
assert_status 200 "migrated legacy audit remains observable" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$legacy_session" \
  --user "evaluation-manager:$management_password" \
  --header "X-Eval-Sandbox-Id: $legacy_sandbox_id"
concurrent_pids=()
for index in {1..12}; do
  concurrent_operation="$(printf '%064x' "$((4096 + index))")"
  (
    request_status "$tmp_dir/concurrent-product-$index.json" \
      --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
      --header "Authorization: Bearer $legacy_obo_token" \
      --header "X-Support-Session-Id: $legacy_session" \
      --header "X-Eval-Sandbox-Id: $legacy_sandbox_id" \
      --header "X-Agent-Trace-Id: concurrent-trace-$index" \
      --header "X-Agent-Operation-Id: $concurrent_operation" \
      --header 'Content-Type: application/json' \
      --data "{\"productId\":\"$legacy_product_id\"}" \
      >"$tmp_dir/concurrent-product-$index.status"
  ) &
  concurrent_pids+=("$!")
done
for pid in "${concurrent_pids[@]}"; do
  wait "$pid"
done
for index in {1..12}; do
  assert_equal 200 "$(cat "$tmp_dir/concurrent-product-$index.status")" \
    "concurrent product observation $index"
done
assert_equal 0 \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT COUNT(*) FROM eval_commerce_audit_reference earlier JOIN eval_commerce_audit_reference later ON earlier.sandbox_id = later.sandbox_id AND earlier.sequence_id < later.sequence_id AND earlier.created_at > later.created_at WHERE earlier.sandbox_id = '$legacy_sandbox_id'")" \
  "sandbox-serialized audit sequence/time inversions after concurrent writes"
assert_status 200 "legal concurrent writes cannot leave a persistent inconsistent sandbox" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header "X-Eval-Sandbox-Id: $legacy_sandbox_id"
assert_status 401 "reset rejects substituted management credential" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-client:$invalid_management_password" \
  --header 'Idempotency-Key: reset-main' \
  --header 'Content-Type: application/json' \
  --data "$(reset_body sandbox-main case-main sandbox-product 3600)"
assert_status 400 "reset rejects unbounded fixture set" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: reset-main' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-main","caseCorrelation":"case-main","ttlSeconds":3600,"testUserLabel":"user-main","products":[]}'

reset_sandbox sandbox-main case-main reset-main sandbox-product 3600
cp "$tmp_dir/http-response.json" "$tmp_dir/reset-main.json"
main_handle="$(uv run python scripts/read_json_field.py "$tmp_dir/reset-main.json" testUserHandle)"
test "${#main_handle}" = 43
if grep -Eq 'password|credential|accessToken|subject|caseCorrelation|expiresAt' "$tmp_dir/reset-main.json"; then
  echo "Reset response leaked private lifecycle data." >&2
  exit 1
fi
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(lifecycle_state, ':', auth_invalidation_state, ':', fixture_count) FROM eval_sandbox WHERE sandbox_id = 'sandbox-main'")" = 'ACTIVE:PROVISIONED:1'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(name, ':', price_minor) FROM eval_sandbox_product_fixture WHERE sandbox_id = 'sandbox-main' AND product_id = 'product-1'")" = 'sandbox-product:900'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM eval_sandbox_effect_stub WHERE sandbox_id = 'sandbox-main' AND effect_type = 'SMS' AND outcome = 'SUPPRESSED'")" = 1
test "$(mysql_query auth_app "$auth_app_password" commerce_db \
  "SELECT COUNT(*) FROM auth_eval_test_principal WHERE opaque_handle = '$main_handle' AND sandbox_id = 'sandbox-main' AND case_correlation = 'case-main' AND state = 'PROVISIONED'")" = 1
state_truth_before="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(version, ':', UNIX_TIMESTAMP(expires_at), ':', UNIX_TIMESTAMP(updated_at)) FROM eval_sandbox WHERE sandbox_id = 'sandbox-main'")"
mysql_query commerce_app "$commerce_app_password" commerce_db \
  "INSERT INTO eval_sandbox_effect_stub (sandbox_id, effect_type, correlation_key, outcome, created_at) VALUES ('sandbox-main', 'SMS', '000-effect-order-first', 'SUPPRESSED', '2020-01-01 00:00:00.000000'), ('sandbox-main', 'SMS', 'tie-effect-a', 'SUPPRESSED', '2025-01-01 00:00:00.000000'), ('sandbox-main', 'SMS', 'tie-effect-b', 'SUPPRESSED', '2025-01-01 00:00:00.000000'), ('sandbox-main', 'SMS', 'zzz-effect-order-last', 'SUPPRESSED', '2030-01-01 00:00:00.000000')"
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT GROUP_CONCAT(correlation_key ORDER BY created_at, effect_type, correlation_key SEPARATOR ',') FROM eval_sandbox_effect_stub WHERE sandbox_id = 'sandbox-main' AND created_at = '2025-01-01 00:00:00.000000'")" = 'tie-effect-a,tie-effect-b'
assert_status 200 "active state is exact bounded commerce truth" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
cp "$tmp_dir/http-response.json" "$tmp_dir/state-active.json"
uv run python scripts/check_evaluation_views.py state "$tmp_dir/state-active.json" \
  --sandbox sandbox-main --lifecycle ACTIVE --product-count 1 \
  --effects-created-ascending
assert_status 200 "repeated state preserves stable total effect ordering" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
cmp "$tmp_dir/state-active.json" "$tmp_dir/http-response.json"
printf '%s\n' 'Verified stable multi-record effect ordering with an exercised equal-time tie key.'
assert_status 400 "state rejects caller-selected fields" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state?fields=sandbox" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 404 "state does not reveal a different sandbox" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-other'
assert_status 401 "sandbox header is not an authentication fallback" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_equal "$state_truth_before" "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(version, ':', UNIX_TIMESTAMP(expires_at), ':', UNIX_TIMESTAMP(updated_at)) FROM eval_sandbox WHERE sandbox_id = 'sandbox-main'")" \
  "state read has no lifecycle side effect"

reset_sandbox sandbox-main case-main reset-main sandbox-product 3600
cmp "$tmp_dir/reset-main.json" "$tmp_dir/http-response.json"
assert_status 409 "same reset key rejects fixture mutation" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: reset-main' \
  --header 'Content-Type: application/json' \
  --data "$(reset_body sandbox-main case-main changed-product 3600)"
assert_status 409 "case cannot bind a second sandbox" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: reset-other' \
  --header 'Content-Type: application/json' \
  --data "$(reset_body sandbox-other case-main other-product)"
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM eval_sandbox WHERE case_correlation = 'case-main'")" = 1

assert_status 200 "issue sandbox-bound direct token" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$main_handle\"}"
direct_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
printf '%s' "$direct_token" >"$tmp_dir/direct.jwt"
uv run python scripts/check_evaluation_token.py \
  --token-file "$tmp_dir/direct.jwt" --jwks-file "$tmp_dir/jwks.json" \
  --issuer https://identity.citybuddy.test --audience citybuddy-web \
  --token-type eval_direct_user --sandbox sandbox-main \
  --maximum-expiry "$(date -u -v+901S +%s 2>/dev/null || date -u -d '+901 seconds' +%s)" \
  --output "$tmp_dir/direct.json"
direct_subject="$(uv run python scripts/read_json_field.py "$tmp_dir/direct.json" subject)"

payment_order_id='00000000-0000-0000-0000-000000000105'
reset_payment_sandbox sandbox-payment case-payment reset-payment "$payment_order_id" 1200
payment_handle="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" testUserHandle)"
assert_status 200 "issue payment sandbox direct token" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$payment_handle\"}"
payment_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
printf '%s' "$payment_token" >"$tmp_dir/payment-direct.jwt"
uv run python scripts/check_evaluation_token.py \
  --token-file "$tmp_dir/payment-direct.jwt" --jwks-file "$tmp_dir/jwks.json" \
  --issuer https://identity.citybuddy.test --audience citybuddy-web \
  --token-type eval_direct_user --sandbox sandbox-payment \
  --maximum-expiry "$(date -u -v+1201S +%s 2>/dev/null || date -u -d '+1201 seconds' +%s)" \
  --output "$tmp_dir/payment-direct.json"
payment_subject="$(uv run python scripts/read_json_field.py "$tmp_dir/payment-direct.json" subject)"
assert_status 401 "evaluation token requires its exact sandbox header for payment" \
  --request POST "http://127.0.0.1:$commerce_port/api/orders/$payment_order_id/mock-payment" \
  --header "Authorization: Bearer $payment_token" \
  --header 'Idempotency-Key: payment-evaluation' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1800,"currency":"CNY"}'
unknown_payment_order='00000000-0000-0000-0000-000000000199'
assert_status 404 "active cross-sandbox payment order is concealed" \
  --request POST "http://127.0.0.1:$commerce_port/api/orders/$payment_order_id/mock-payment" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'Idempotency-Key: payment-cross-active-sandbox' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1800,"currency":"CNY"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/payment-cross-order-error.json"
assert_status 404 "unknown payment order uses the same concealed response" \
  --request POST "http://127.0.0.1:$commerce_port/api/orders/$unknown_payment_order/mock-payment" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'Idempotency-Key: payment-unknown-active-sandbox' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1800,"currency":"CNY"}'
cmp "$tmp_dir/payment-cross-order-error.json" "$tmp_dir/http-response.json"
assert_status 400 "payment start rejects a null-valued unknown field" \
  --request POST "http://127.0.0.1:$commerce_port/api/orders/$payment_order_id/mock-payment" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Idempotency-Key: payment-null-metadata' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1800,"currency":"CNY","metadata":null}'
assert_status 400 "payment start rejects a nested unknown field" \
  --request POST "http://127.0.0.1:$commerce_port/api/orders/$payment_order_id/mock-payment" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Idempotency-Key: payment-nested-metadata' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1800,"currency":"CNY","metadata":{"private":true}}'
assert_status 400 "payment start rejects an invalid known-field type" \
  --request POST "http://127.0.0.1:$commerce_port/api/orders/$payment_order_id/mock-payment" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Idempotency-Key: payment-invalid-amount-type' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":{"value":1800},"currency":"CNY"}'
assert_status 201 "evaluation payment attempt binds token sandbox and fixture order" \
  --request POST "http://127.0.0.1:$commerce_port/api/orders/$payment_order_id/mock-payment" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Idempotency-Key: payment-evaluation' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1800,"currency":"CNY"}'
payment_attempt_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" attemptId)"
payment_correlation_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" callbackCorrelationId)"
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(user_subject, ':', sandbox_id, ':', evaluation_owner_handle) FROM standard_order WHERE order_id = '$payment_order_id'")" = \
  "$payment_subject:sandbox-payment:$payment_handle"

payment_event_id='00000000-0000-0000-0000-000000000106'
payment_session='payment-session'
payment_trace='payment-trace'
payment_operation="$(openssl rand -hex 32)"
payment_callback_key='callback-evaluation'
payment_timestamp="$(date +%s)"
payment_signature="$(sign_payment_callback "$payment_timestamp" "$payment_callback_key" \
  "$payment_event_id" "$payment_correlation_id" "$payment_order_id" sandbox-payment \
  "$payment_session" "$payment_trace" "$payment_operation")"
payment_callback_body="{\"callbackEventId\":\"$payment_event_id\",\"callbackCorrelationId\":\"$payment_correlation_id\",\"orderId\":\"$payment_order_id\",\"amountMinor\":1800,\"currency\":\"CNY\",\"outcome\":\"SUCCEEDED\",\"sandboxId\":\"sandbox-payment\",\"supportSessionId\":\"$payment_session\",\"traceId\":\"$payment_trace\",\"operationId\":\"$payment_operation\"}"
assert_status 401 "management and direct credentials cannot replace callback signature" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --user "evaluation-manager:$management_password" \
  --header "Authorization: Bearer $payment_token" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$payment_callback_body"
assert_status 401 "substituted sandbox invalidates authenticated callback context" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_signature" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "${payment_callback_body/sandbox-payment/sandbox-other}"
payment_cross_signature="$(sign_payment_callback "$payment_timestamp" "$payment_callback_key" \
  "$payment_event_id" "$payment_correlation_id" "$payment_order_id" sandbox-other \
  "$payment_session" "$payment_trace" "$payment_operation")"
assert_status 403 "authenticated cross-sandbox callback reveals no correlation truth" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_cross_signature" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "${payment_callback_body/sandbox-payment/sandbox-other}"
payment_active_cross_signature="$(sign_payment_callback "$payment_timestamp" callback-active-cross \
  "$payment_event_id" "$payment_correlation_id" "$payment_order_id" sandbox-main \
  "$payment_session" "$payment_trace" "$payment_operation")"
assert_status 404 "active cross-sandbox callback correlation is concealed" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_active_cross_signature" \
  --header 'Idempotency-Key: callback-active-cross' \
  --header 'Content-Type: application/json' \
  --data "${payment_callback_body/sandbox-payment/sandbox-main}"
cp "$tmp_dir/http-response.json" "$tmp_dir/payment-cross-correlation-error.json"
unknown_payment_correlation='00000000-0000-0000-0000-000000000198'
payment_unknown_signature="$(sign_payment_callback "$payment_timestamp" callback-active-unknown \
  "$payment_event_id" "$unknown_payment_correlation" "$payment_order_id" sandbox-main \
  "$payment_session" "$payment_trace" "$payment_operation")"
payment_unknown_body="${payment_callback_body/sandbox-payment/sandbox-main}"
payment_unknown_body="${payment_unknown_body/$payment_correlation_id/$unknown_payment_correlation}"
assert_status 404 "unknown callback correlation uses the same concealed response" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_unknown_signature" \
  --header 'Idempotency-Key: callback-active-unknown' \
  --header 'Content-Type: application/json' \
  --data "$payment_unknown_body"
cmp "$tmp_dir/payment-cross-correlation-error.json" "$tmp_dir/http-response.json"
payment_partial_signature="$(sign_payment_callback "$payment_timestamp" callback-partial \
  "$payment_event_id" "$payment_correlation_id" "$payment_order_id" sandbox-payment \
  "$payment_session" '' "$payment_operation")"
assert_status 400 "authenticated partial evaluation callback context is invalid" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_partial_signature" \
  --header 'Idempotency-Key: callback-partial' \
  --header 'Content-Type: application/json' \
  --data "{\"callbackEventId\":\"$payment_event_id\",\"callbackCorrelationId\":\"$payment_correlation_id\",\"orderId\":\"$payment_order_id\",\"amountMinor\":1800,\"currency\":\"CNY\",\"outcome\":\"SUCCEEDED\",\"sandboxId\":\"sandbox-payment\",\"supportSessionId\":\"$payment_session\",\"operationId\":\"$payment_operation\"}"
payment_oversized_sandbox="$(printf 's%.0s' {1..65})"
payment_oversized_signature="$(sign_payment_callback "$payment_timestamp" callback-oversized \
  "$payment_event_id" "$payment_correlation_id" "$payment_order_id" \
  "$payment_oversized_sandbox" "$payment_session" "$payment_trace" "$payment_operation")"
assert_status 400 "authenticated callback rejects oversized sandbox identity" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_oversized_signature" \
  --header 'Idempotency-Key: callback-oversized' \
  --header 'Content-Type: application/json' \
  --data "${payment_callback_body/sandbox-payment/$payment_oversized_sandbox}"
payment_extra_body="${payment_callback_body%?},\"metadata\":\"cb105-private-callback-metadata\"}"
assert_status 400 "callback rejects arbitrary metadata" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_signature" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$payment_extra_body"
for payment_invalid_body in \
  "${payment_callback_body%?},\"metadata\":null}" \
  "${payment_callback_body%?},\"metadata\":{\"nested\":true}}" \
  "${payment_callback_body%?},\"metadata\":[\"nested\"]}" \
  "${payment_callback_body/\"amountMinor\":1800/\"amountMinor\":{\"value\":1800}}"; do
  assert_status 400 "callback malformed-input class fails closed" \
    --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
    --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
    --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
    --header "X-Mock-Payment-Signature: $payment_signature" \
    --header "Idempotency-Key: $payment_callback_key" \
    --header 'Content-Type: application/json' \
    --data "$payment_invalid_body"
done
mysql_query root "$root_password" '' "SET GLOBAL offline_mode = ON"
assert_status 503 "database disconnection is unavailable and never authorizes payment" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_signature" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$payment_callback_body"
mysql_query root "$root_password" '' "SET GLOBAL offline_mode = OFF"
wait_http \
  "http://127.0.0.1:$commerce_port/api/eval/state" \
  "$commerce_pid" \
  "$tmp_dir/commerce.log" \
  200 \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(status, ':', state_version) FROM standard_order WHERE order_id = '$payment_order_id'")" = 'UNPAID:1'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM mock_payment_callback WHERE attempt_id = '$payment_attempt_id'")" = 0
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM inventory_ledger WHERE business_event_key = 'mock-payment:$payment_attempt_id'")" = 0
assert_status 200 "signed exact evaluation callback commits atomic payment truth" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_signature" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$payment_callback_body"

assert_conflicting_payment_replay() {
  local description="$1"
  local callback_key="$2"
  local event_id="$3"
  local order_id="$4"
  local amount_minor="$5"
  local currency="$6"
  local outcome="$7"
  local session_id="$8"
  local trace_id="$9"
  local operation_id="${10}"
  local signature body
  signature="$(sign_payment_callback "$payment_timestamp" "$callback_key" "$event_id" \
    "$payment_correlation_id" "$order_id" sandbox-payment "$session_id" "$trace_id" \
    "$operation_id" "$amount_minor" "$currency" "$outcome")"
  body="{\"callbackEventId\":\"$event_id\",\"callbackCorrelationId\":\"$payment_correlation_id\",\"orderId\":\"$order_id\",\"amountMinor\":$amount_minor,\"currency\":\"$currency\",\"outcome\":\"$outcome\",\"sandboxId\":\"sandbox-payment\",\"supportSessionId\":\"$session_id\",\"traceId\":\"$trace_id\",\"operationId\":\"$operation_id\"}"
  assert_status 409 "$description" \
    --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
    --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
    --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
    --header "X-Mock-Payment-Signature: $signature" \
    --header "Idempotency-Key: $callback_key" \
    --header 'Content-Type: application/json' \
    --data "$body"
}

for repetition in {1..5}; do
  assert_conflicting_payment_replay \
    "replay rejects a different callback key (repetition $repetition)" \
    callback-evaluation-new-key "$payment_event_id" "$payment_order_id" 1800 CNY SUCCEEDED \
    "$payment_session" "$payment_trace" "$payment_operation"
done
assert_conflicting_payment_replay "replay rejects a different callback event" \
  "$payment_callback_key" 00000000-0000-0000-0000-000000000197 "$payment_order_id" \
  1800 CNY SUCCEEDED "$payment_session" "$payment_trace" "$payment_operation"
assert_conflicting_payment_replay "replay rejects a different order" \
  "$payment_callback_key" "$payment_event_id" 00000000-0000-0000-0000-000000000196 \
  1800 CNY SUCCEEDED "$payment_session" "$payment_trace" "$payment_operation"
assert_conflicting_payment_replay "replay rejects a different amount" \
  "$payment_callback_key" "$payment_event_id" "$payment_order_id" 1801 CNY SUCCEEDED \
  "$payment_session" "$payment_trace" "$payment_operation"
assert_conflicting_payment_replay "replay rejects a different currency" \
  "$payment_callback_key" "$payment_event_id" "$payment_order_id" 1800 AUD SUCCEEDED \
  "$payment_session" "$payment_trace" "$payment_operation"
assert_conflicting_payment_replay "replay rejects a different support session" \
  "$payment_callback_key" "$payment_event_id" "$payment_order_id" 1800 CNY SUCCEEDED \
  changed-payment-session "$payment_trace" "$payment_operation"
assert_conflicting_payment_replay "replay rejects a different trace" \
  "$payment_callback_key" "$payment_event_id" "$payment_order_id" 1800 CNY SUCCEEDED \
  "$payment_session" changed-payment-trace "$payment_operation"
assert_conflicting_payment_replay "replay rejects a different operation" \
  "$payment_callback_key" "$payment_event_id" "$payment_order_id" 1800 CNY SUCCEEDED \
  "$payment_session" "$payment_trace" bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(o.status, ':', a.state, ':', COUNT(DISTINCT c.callback_event_id), ':', COUNT(DISTINCT l.movement_id), ':', COUNT(DISTINCT r.audit_reference_id)) FROM standard_order o JOIN mock_payment_attempt a ON a.order_id = o.order_id LEFT JOIN mock_payment_callback c ON c.attempt_id = a.attempt_id LEFT JOIN inventory_ledger l ON l.business_event_key = CONCAT('mock-payment:', a.attempt_id) LEFT JOIN eval_commerce_audit_reference r ON r.entity_type = 'PAYMENT_CALLBACK' AND r.entity_id = c.callback_event_id WHERE o.order_id = '$payment_order_id' GROUP BY o.status, a.state")" = \
  'PAID:SUCCEEDED:1:1:1'
assert_status 200 "payment state exposes only authoritative sandbox-scoped locators" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
jq -e --arg attempt "$payment_attempt_id" \
  '.payments | length == 1 and .[0].attemptId == $attempt and .[0].state == "SUCCEEDED" and .[0].movementCount == 1' \
  "$tmp_dir/http-response.json" >/dev/null
assert_status 200 "payment audit remains a verified callback locator" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$payment_session" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
jq -e --arg event "$payment_event_id" \
  '.entries | length == 1 and .[0].entityType == "PAYMENT_CALLBACK" and .[0].entityId == $event' \
  "$tmp_dir/http-response.json" >/dev/null

refresh_payment_callback_signature() {
  payment_timestamp="$(date +%s)"
  payment_signature="$(sign_payment_callback "$payment_timestamp" "$payment_callback_key" \
    "$payment_event_id" "$payment_correlation_id" "$payment_order_id" sandbox-payment \
    "$payment_session" "$payment_trace" "$payment_operation")"
}

assert_payment_truth_fails_closed() {
  local description="$1"
  local callback_status state_status audit_status commerce_log_start=0
  refresh_payment_callback_signature
  if [[ -f "$tmp_dir/commerce.log" ]]; then
    commerce_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  fi
  callback_status="$(request_status "$tmp_dir/payment-callback-classification.json" \
    --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
    --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
    --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
    --header "X-Mock-Payment-Signature: $payment_signature" \
    --header "Idempotency-Key: $payment_callback_key" \
    --header 'Content-Type: application/json' \
    --data "$payment_callback_body")"
  state_status="$(request_status "$tmp_dir/payment-state-classification.json" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment')"
  audit_status="$(request_status "$tmp_dir/payment-audit-classification.json" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$payment_session" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment')"
  if [[ "$callback_status:$state_status:$audit_status" != '409:409:409' ]]; then
    echo "Cross-path classification mismatch for $description: callback=$callback_status state=$state_status audit=$audit_status" >&2
    for response in payment-callback-classification payment-state-classification payment-audit-classification; do
      echo "$response-response" >&2
      cat "$tmp_dir/$response.json" >&2
    done
    if [[ -f "$tmp_dir/commerce.log" ]]; then
      echo 'request-rejection-reasons' >&2
      tail -n "+$((commerce_log_start + 1))" "$tmp_dir/commerce.log" \
        | grep 'evaluation_request_rejected reason_code=' >&2 || true
    fi
    exit 1
  fi
  if [[ "$compact_output" == false ]]; then
    echo "Verified cross-path classification 409:409:409: $description"
  fi
}

assert_payment_audit_reconciliation_fails_closed() {
  local description="$1"
  assert_payment_truth_fails_closed "$description"
}

assert_unrelated_payment_audit_reconciliation_fails_closed() {
  local description="$1"
  refresh_payment_callback_signature
  assert_status 200 "$description does not contaminate exact committed callback replay" \
    --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
    --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
    --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
    --header "X-Mock-Payment-Signature: $payment_signature" \
    --header "Idempotency-Key: $payment_callback_key" \
    --header 'Content-Type: application/json' \
    --data "$payment_callback_body"
  assert_status 409 "$description rejects evaluation state" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment'
  assert_status 409 "$description rejects evaluation audit" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$payment_session" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment'
}

assert_audit_totality_fails_closed() {
  local sandbox_id="$1"
  local support_session_id="$2"
  local description="$3"
  assert_status 409 "$description rejects evaluation state" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
    --user "evaluation-manager:$management_password" \
    --header "X-Eval-Sandbox-Id: $sandbox_id"
  assert_status 409 "$description rejects evaluation audit" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$support_session_id" \
    --user "evaluation-manager:$management_password" \
    --header "X-Eval-Sandbox-Id: $sandbox_id"
}

payment_audit_reference_id="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT audit_reference_id FROM eval_commerce_audit_reference WHERE entity_type = 'PAYMENT_CALLBACK' AND entity_id = '$payment_event_id'")"
payment_audit_sequence_id="$(mysql_query root "$root_password" commerce_db \
  "SELECT sequence_id FROM eval_commerce_audit_reference WHERE audit_reference_id = '$payment_audit_reference_id'")"
payment_audit_created_at="$(mysql_query root "$root_password" commerce_db \
  "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM mock_payment_callback WHERE callback_event_id = '$payment_event_id'")"
payment_movement_id="$(mysql_query root "$root_password" commerce_db \
  "SELECT movement_id FROM inventory_ledger WHERE business_event_key = 'mock-payment:$payment_attempt_id'")"
payment_product_id="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT product_id FROM standard_order WHERE order_id = '$payment_order_id'")"
payment_product_version="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT product_version FROM standard_order WHERE order_id = '$payment_order_id'")"

payment_second_trace='payment-trace-second-operation'
payment_second_operation="$(openssl rand -hex 32)"
payment_second_reference_id="$(evaluation_product_reference \
  sandbox-payment "$payment_session" "$payment_second_trace" "$payment_second_operation" \
  PRODUCT_FIXTURE "$payment_product_id" "$payment_product_version" OBSERVED)"
payment_second_created_at="$(mysql_query root "$root_password" commerce_db \
  "SELECT DATE_FORMAT(TIMESTAMPADD(MICROSECOND, 1, MAX(created_at)), '%Y-%m-%d %H:%i:%s.%f') FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-payment'")"
mysql_query root "$root_password" commerce_db "
INSERT INTO eval_commerce_product_observation
  (observation_id, sandbox_id, support_session_id, trace_id, operation_id, product_id,
   product_version, outcome, created_at)
VALUES ('$payment_second_reference_id', 'sandbox-payment', '$payment_session',
  '$payment_second_trace', '$payment_second_operation', '$payment_product_id',
  $payment_product_version, 'OBSERVED', '$payment_second_created_at');
INSERT INTO eval_commerce_audit_reference
  (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type,
   entity_id, entity_version, outcome, created_at, created_at_anchor)
VALUES ('$payment_second_reference_id', 'sandbox-payment', '$payment_session',
  '$payment_second_trace', '$payment_second_operation', 'PRODUCT_FIXTURE',
  '$payment_product_id', $payment_product_version, 'OBSERVED', '$payment_second_created_at',
  'BUSINESS_EVENT');
"
assert_equal 2 "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(DISTINCT operation_id) FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-payment' AND support_session_id = '$payment_session'")" \
  "one support session carries multiple exact operations"
assert_status 200 "same-session second operation does not corrupt committed callback cardinality" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_signature" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$payment_callback_body"
assert_status 200 "same-session second operation preserves evaluation state" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
assert_status 200 "same-session second operation preserves evaluation audit" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$payment_session" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'

mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "missing payment audit reference"
mysql_query root "$root_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES ($payment_audit_sequence_id, '$payment_audit_reference_id', 'sandbox-payment', '$payment_session', '$payment_trace', '$payment_operation', 'PAYMENT_CALLBACK', '$payment_event_id', 2, 'OBSERVED', '$payment_audit_created_at', 'BUSINESS_EVENT')"

mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET audit_reference_id = REPEAT('f', 64) WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit reference identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET audit_reference_id = '$payment_audit_reference_id' WHERE audit_reference_id = REPEAT('f', 64)"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET sandbox_id = 'sandbox-main' WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit sandbox identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET sandbox_id = 'sandbox-payment' WHERE audit_reference_id = '$payment_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET support_session_id = 'tampered-payment-session' WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit session identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET support_session_id = '$payment_session' WHERE audit_reference_id = '$payment_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET trace_id = 'tampered-payment-trace' WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit trace identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET trace_id = '$payment_trace' WHERE audit_reference_id = '$payment_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET operation_id = REPEAT('b', 64) WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit operation identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET operation_id = '$payment_operation' WHERE audit_reference_id = '$payment_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_type = 'PRODUCT_FIXTURE' WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit entity-type identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_type = 'PAYMENT_CALLBACK' WHERE audit_reference_id = '$payment_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_id = '00000000-0000-0000-0000-000000000196' WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit entity identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_id = '$payment_event_id' WHERE audit_reference_id = '$payment_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_version = 3 WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit entity version"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_version = 2 WHERE audit_reference_id = '$payment_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "SET SESSION sql_mode = 'NO_ENGINE_SUBSTITUTION'; UPDATE eval_commerce_audit_reference SET outcome = 'CORRUPTED' WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit outcome"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET outcome = 'OBSERVED' WHERE audit_reference_id = '$payment_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET created_at = TIMESTAMPADD(SECOND, 1, created_at) WHERE audit_reference_id = '$payment_audit_reference_id'"
assert_payment_audit_reconciliation_fails_closed "corrupted payment audit business event time"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET created_at = '$payment_audit_created_at' WHERE audit_reference_id = '$payment_audit_reference_id'"

mysql_query root "$root_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES (REPEAT('d', 64), 'sandbox-payment', '$payment_session', '$payment_trace', REPEAT('e', 64), 'PAYMENT_CALLBACK', '$payment_event_id', 2, 'OBSERVED', CURRENT_TIMESTAMP(6), 'BUSINESS_EVENT')"
assert_payment_audit_reconciliation_fails_closed "duplicate payment audit reference"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = REPEAT('d', 64)"
# Keep the sequence/time invariant valid so this cell isolates only the orphan-face fault.
mysql_query root "$root_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) SELECT REPEAT('c', 64), 'sandbox-payment', '$payment_session', '$payment_trace', REPEAT('a', 64), 'PAYMENT_CALLBACK', '00000000-0000-0000-0000-000000000197', 2, 'OBSERVED', TIMESTAMPADD(MICROSECOND, 1, MAX(created_at)), 'BUSINESS_EVENT' FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-payment'"
assert_unrelated_payment_audit_reconciliation_fails_closed "orphan payment audit reference"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = REPEAT('c', 64)"
mysql_query root "$root_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES (REPEAT('9', 64), 'sandbox-payment', '$payment_session', '$payment_trace', REPEAT('8', 64), 'PRODUCT_FIXTURE', '$payment_event_id', 2, 'OBSERVED', CURRENT_TIMESTAMP(6), 'BUSINESS_EVENT')"
assert_payment_audit_reconciliation_fails_closed \
  "correct payment audit plus cross-type product pseudo-duplicate"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = REPEAT('9', 64)"
mysql_query root "$root_password" commerce_db \
  "SET SESSION sql_mode = 'NO_ENGINE_SUBSTITUTION'; INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES (REPEAT('7', 64), 'sandbox-payment', '$payment_session', '$payment_trace', REPEAT('6', 64), 'FUTURE_AUDIT_TYPE', '$payment_event_id', 2, 'OBSERVED', CURRENT_TIMESTAMP(6), 'BUSINESS_EVENT')"
assert_payment_audit_reconciliation_fails_closed "unknown audit entity type"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = REPEAT('7', 64)"

tampered_payment_correlation='00000000-0000-0000-0000-000000000107'
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET callback_correlation_id = '$tampered_payment_correlation' WHERE callback_event_id = '$payment_event_id'"
assert_payment_truth_fails_closed "corrupted callback correlation"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET callback_correlation_id = '$payment_correlation_id' WHERE callback_event_id = '$payment_event_id'"
tampered_payment_event='00000000-0000-0000-0000-000000000195'
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET callback_event_id = '$tampered_payment_event' WHERE callback_event_id = '$payment_event_id'"
assert_payment_truth_fails_closed "corrupted callback event"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET callback_event_id = '$payment_event_id' WHERE callback_event_id = '$tampered_payment_event'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET callback_idempotency_key = 'tampered-callback-key' WHERE callback_event_id = '$payment_event_id'"
assert_payment_truth_fails_closed "corrupted callback idempotency key"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET callback_idempotency_key = '$payment_callback_key' WHERE callback_event_id = '$payment_event_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET sandbox_id = 'sandbox-main' WHERE callback_event_id = '$payment_event_id'"
assert_payment_truth_fails_closed "corrupted callback sandbox"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET sandbox_id = 'sandbox-payment' WHERE callback_event_id = '$payment_event_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET support_session_id = 'tampered-callback-session' WHERE callback_event_id = '$payment_event_id'"
assert_payment_truth_fails_closed "corrupted callback support session"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET support_session_id = '$payment_session' WHERE callback_event_id = '$payment_event_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET trace_id = 'tampered-callback-trace' WHERE callback_event_id = '$payment_event_id'"
assert_payment_truth_fails_closed "corrupted callback trace"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET trace_id = '$payment_trace' WHERE callback_event_id = '$payment_event_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET operation_id = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' WHERE callback_event_id = '$payment_event_id'"
assert_payment_truth_fails_closed "corrupted callback operation"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET operation_id = '$payment_operation' WHERE callback_event_id = '$payment_event_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET intent_hash = REPEAT('f', 64) WHERE callback_event_id = '$payment_event_id'"
assert_payment_truth_fails_closed "corrupted callback intent hash"
payment_intent_hash="$(printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s' \
  "$payment_event_id" "$payment_correlation_id" "$payment_order_id" 1800 CNY SUCCEEDED \
  sandbox-payment "$payment_session" "$payment_trace" "$payment_operation" \
  "$payment_callback_key" | openssl dgst -sha256 -hex | awk '{print $NF}')"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET intent_hash = '$payment_intent_hash' WHERE callback_event_id = '$payment_event_id'"

mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = '$payment_audit_reference_id'; UPDATE mock_payment_callback SET intent_hash = REPEAT('f', 64) WHERE callback_event_id = '$payment_event_id'"
assert_payment_audit_reconciliation_fails_closed \
  "combined missing payment audit and corrupted callback intent"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET intent_hash = '$payment_intent_hash' WHERE callback_event_id = '$payment_event_id'; INSERT INTO eval_commerce_audit_reference (sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES ($payment_audit_sequence_id, '$payment_audit_reference_id', 'sandbox-payment', '$payment_session', '$payment_trace', '$payment_operation', 'PAYMENT_CALLBACK', '$payment_event_id', 2, 'OBSERVED', '$payment_audit_created_at', 'BUSINESS_EVENT')"

mysql_query root "$root_password" commerce_db \
  "DELETE FROM mock_payment_callback WHERE callback_event_id = '$payment_event_id'"
assert_payment_audit_reconciliation_fails_closed "missing succeeded callback truth"
mysql_query root "$root_password" commerce_db \
  "INSERT INTO mock_payment_callback (callback_event_id, callback_idempotency_key, attempt_id, callback_correlation_id, sandbox_id, support_session_id, trace_id, operation_id, intent_hash, requested_outcome, result_state, created_at) VALUES ('$payment_event_id', '$payment_callback_key', '$payment_attempt_id', '$payment_correlation_id', 'sandbox-payment', '$payment_session', '$payment_trace', '$payment_operation', '$payment_intent_hash', 'SUCCEEDED', 'APPLIED', '$payment_audit_created_at')"

mysql_query root "$root_password" commerce_db \
  "DELETE FROM inventory_ledger WHERE movement_id = '$payment_movement_id'"
assert_payment_audit_reconciliation_fails_closed "missing payment ledger truth"
mysql_query root "$root_password" commerce_db \
  "INSERT INTO inventory_ledger (movement_id, business_event_key, movement_type, order_id, reservation_id, activity_id, product_id, sandbox_id, inventory_delta, activity_quota_delta, payment_amount_minor, payment_currency) VALUES ('$payment_movement_id', 'mock-payment:$payment_attempt_id', 'STANDARD_PAYMENT', '$payment_order_id', NULL, NULL, '$payment_product_id', 'sandbox-payment', 0, 0, 1800, 'CNY')"

payment_attempt_succeeded_at="$(mysql_query root "$root_password" commerce_db \
  "SELECT DATE_FORMAT(succeeded_at, '%Y-%m-%d %H:%i:%s.%f') FROM mock_payment_attempt WHERE attempt_id = '$payment_attempt_id'")"
payment_attempt_intent_hash="$(mysql_query root "$root_password" commerce_db \
  "SELECT intent_hash FROM mock_payment_attempt WHERE attempt_id = '$payment_attempt_id'")"
payment_audit_tampered_sequence_id="$(mysql_query root "$root_password" commerce_db \
  "SELECT MAX(sequence_id) + 1 FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-payment'")"
tampered_callback_event_id='00000000-0000-0000-0000-000000000181'
tampered_callback_attempt_id='00000000-0000-0000-0000-000000000182'
tampered_attempt_id='00000000-0000-0000-0000-000000000183'
tampered_attempt_order_id='00000000-0000-0000-0000-000000000184'
tampered_order_id='00000000-0000-0000-0000-000000000185'

restore_complete_payment_truth() {
  mysql_query root "$root_password" commerce_db "
UPDATE mock_payment_attempt SET attempt_id = '$payment_attempt_id'
  WHERE attempt_id = '$tampered_attempt_id';
UPDATE standard_order SET order_id = '$payment_order_id'
  WHERE order_id = '$tampered_order_id';
UPDATE standard_order SET user_subject = '$payment_subject', product_id = '$payment_product_id',
  total_price_minor = 1800, currency = 'CNY', status = 'PAID', state_version = 2,
  sandbox_id = 'sandbox-payment' WHERE order_id = '$payment_order_id';
UPDATE mock_payment_attempt SET callback_correlation_id = '$payment_correlation_id',
  user_subject = '$payment_subject', order_id = '$payment_order_id', order_kind = 'STANDARD',
  sandbox_id = 'sandbox-payment', intent_hash = '$payment_attempt_intent_hash',
  amount_minor = 1800, refunded_amount_minor = 0, currency = 'CNY',
  state = 'SUCCEEDED', state_version = 2, succeeded_at = '$payment_attempt_succeeded_at'
  WHERE attempt_id = '$payment_attempt_id';
DELETE FROM mock_payment_callback
  WHERE callback_event_id IN ('$payment_event_id', '$tampered_callback_event_id')
     OR callback_idempotency_key IN ('$payment_callback_key', 'tampered-callback-key')
     OR attempt_id IN ('$payment_attempt_id', '$tampered_callback_attempt_id');
INSERT INTO mock_payment_callback (callback_event_id, callback_idempotency_key, attempt_id,
  callback_correlation_id, sandbox_id, support_session_id, trace_id, operation_id, intent_hash,
  requested_outcome, result_state, created_at)
VALUES ('$payment_event_id', '$payment_callback_key', '$payment_attempt_id',
  '$payment_correlation_id', 'sandbox-payment', '$payment_session', '$payment_trace',
  '$payment_operation', '$payment_intent_hash', 'SUCCEEDED', 'APPLIED', '$payment_audit_created_at');
INSERT INTO inventory_ledger (movement_id, business_event_key, movement_type, order_id,
  reservation_id, activity_id, product_id, sandbox_id, inventory_delta, activity_quota_delta,
  payment_amount_minor, payment_currency)
VALUES ('$payment_movement_id', 'mock-payment:$payment_attempt_id', 'STANDARD_PAYMENT',
  '$payment_order_id', NULL, NULL, '$payment_product_id', 'sandbox-payment', 0, 0, 1800, 'CNY')
ON DUPLICATE KEY UPDATE business_event_key = VALUES(business_event_key),
  movement_type = VALUES(movement_type), order_id = VALUES(order_id),
  reservation_id = VALUES(reservation_id), activity_id = VALUES(activity_id),
  product_id = VALUES(product_id), sandbox_id = VALUES(sandbox_id),
  inventory_delta = VALUES(inventory_delta), activity_quota_delta = VALUES(activity_quota_delta),
  payment_amount_minor = VALUES(payment_amount_minor), payment_currency = VALUES(payment_currency);
INSERT INTO eval_commerce_audit_reference (sequence_id, audit_reference_id, sandbox_id,
  support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome,
  created_at, created_at_anchor)
VALUES ($payment_audit_sequence_id, '$payment_audit_reference_id', 'sandbox-payment',
  '$payment_session', '$payment_trace', '$payment_operation', 'PAYMENT_CALLBACK',
  '$payment_event_id', 2, 'OBSERVED', '$payment_audit_created_at', 'BUSINESS_EVENT')
ON DUPLICATE KEY UPDATE sandbox_id = VALUES(sandbox_id),
  sequence_id = VALUES(sequence_id),
  support_session_id = VALUES(support_session_id), trace_id = VALUES(trace_id),
  operation_id = VALUES(operation_id), entity_type = VALUES(entity_type),
  entity_id = VALUES(entity_id), entity_version = VALUES(entity_version),
  outcome = VALUES(outcome), created_at = VALUES(created_at),
  created_at_anchor = VALUES(created_at_anchor);
"
}

assert_status 200 "one-row committed faces replay the existing payment result" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_signature" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$payment_callback_body"

duplicate_callback_event_id='00000000-0000-0000-0000-000000000201'
duplicate_callback_attempt_id='00000000-0000-0000-0000-000000000202'
mysql_query root "$root_password" commerce_db "
INSERT INTO mock_payment_callback (callback_event_id, callback_idempotency_key, attempt_id,
  callback_correlation_id, sandbox_id, support_session_id, trace_id, operation_id, intent_hash,
  requested_outcome, result_state, created_at)
SELECT '$duplicate_callback_event_id', 'duplicate-cardinality-callback',
  '$duplicate_callback_attempt_id', callback_correlation_id, 'sandbox-main', support_session_id,
  trace_id, operation_id, intent_hash, requested_outcome, result_state, created_at
FROM mock_payment_callback WHERE callback_event_id = '$payment_event_id'
"
assert_payment_truth_fails_closed "duplicate callback correlation cardinality"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM mock_payment_callback WHERE callback_event_id = '$duplicate_callback_event_id'"

duplicate_attempt_id='00000000-0000-0000-0000-000000000203'
duplicate_attempt_order_id='00000000-0000-0000-0000-000000000204'
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE mock_payment_attempt DROP INDEX uq_mock_payment_callback_correlation"
mysql_query root "$root_password" commerce_db "
INSERT INTO mock_payment_attempt (attempt_id, callback_correlation_id, user_subject, order_id,
  order_kind, sandbox_id, request_idempotency_key, intent_hash, amount_minor, refunded_amount_minor,
  currency, state, state_version, succeeded_at, created_at)
SELECT '$duplicate_attempt_id', callback_correlation_id, user_subject,
  '$duplicate_attempt_order_id', order_kind, 'sandbox-main', 'duplicate-cardinality-attempt',
  intent_hash, amount_minor, refunded_amount_minor, currency, state, state_version, succeeded_at,
  created_at FROM mock_payment_attempt WHERE attempt_id = '$payment_attempt_id'
"
assert_payment_truth_fails_closed "duplicate attempt correlation cardinality"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM mock_payment_attempt WHERE attempt_id = '$duplicate_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE mock_payment_attempt ADD CONSTRAINT uq_mock_payment_callback_correlation UNIQUE (callback_correlation_id)"

mysql_query root "$root_password" commerce_db \
  "ALTER TABLE standard_order DROP PRIMARY KEY"
mysql_query root "$root_password" commerce_db "
INSERT INTO standard_order (order_id, user_subject, sandbox_id, evaluation_owner_handle,
  product_id, product_name, unit_price_minor, currency, quantity, total_price_minor,
  product_version, status, state_version, created_at)
SELECT order_id, user_subject, 'sandbox-main', evaluation_owner_handle, product_id,
  CONCAT(product_name, ' duplicate-cardinality'), unit_price_minor, currency, quantity,
  total_price_minor, product_version, status, state_version, created_at
FROM standard_order WHERE order_id = '$payment_order_id' LIMIT 1
"
assert_payment_truth_fails_closed "duplicate order stable-key cardinality"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM standard_order WHERE order_id = '$payment_order_id' AND product_name LIKE '% duplicate-cardinality'"
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE standard_order ADD PRIMARY KEY (order_id)"

cross_type_reservation_id='00000000-0000-0000-0000-000000000211'
cross_type_transaction_id='00000000-0000-0000-0000-000000000212'
cross_type_timeout_id='00000000-0000-0000-0000-000000000213'
mysql_query root "$root_password" commerce_db "
INSERT INTO seckill_order
  (order_id, reservation_id, transaction_event_id, timeout_event_id, user_subject, activity_id,
   product_id, product_name, unit_price_minor, currency, quantity, total_price_minor, status,
   state_version, unpaid_deadline, created_at)
SELECT order_id, '$cross_type_reservation_id', '$cross_type_transaction_id',
  '$cross_type_timeout_id', user_subject, 'cross-type-order-cardinality', product_id,
  CONCAT(product_name, ' cross-type-cardinality'), unit_price_minor, currency, quantity,
  total_price_minor, 'PAID', 2, TIMESTAMPADD(MINUTE, 5, created_at), created_at
FROM standard_order WHERE order_id = '$payment_order_id'
"
assert_payment_truth_fails_closed "cross-type order stable-key cardinality"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM seckill_order WHERE order_id = '$payment_order_id'"

duplicate_ledger_movement_id='00000000-0000-0000-0000-000000000205'
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE inventory_ledger DROP INDEX uq_inventory_ledger_single_movement"
mysql_query root "$root_password" commerce_db "
INSERT INTO inventory_ledger (movement_id, business_event_key, movement_type, order_id,
  reservation_id, activity_id, product_id, sandbox_id, inventory_delta, activity_quota_delta,
  payment_amount_minor, payment_currency, created_at)
SELECT '$duplicate_ledger_movement_id', 'duplicate-cardinality-ledger', movement_type, order_id,
  reservation_id, activity_id, product_id, 'sandbox-main', inventory_delta, activity_quota_delta,
  payment_amount_minor, payment_currency, created_at
FROM inventory_ledger WHERE movement_id = '$payment_movement_id'
"
assert_payment_truth_fails_closed "duplicate ledger stable-key cardinality"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM inventory_ledger WHERE movement_id = '$duplicate_ledger_movement_id'"
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE inventory_ledger ADD CONSTRAINT uq_inventory_ledger_single_movement UNIQUE (order_id, single_movement_type)"

duplicate_audit_reference_id="$(printf '5%.0s' {1..64})"
duplicate_audit_operation_id="$(printf '4%.0s' {1..64})"
mysql_query root "$root_password" commerce_db "
INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id,
  trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at,
  created_at_anchor)
SELECT '$duplicate_audit_reference_id', 'sandbox-main', support_session_id, trace_id,
  '$duplicate_audit_operation_id', entity_type, entity_id, entity_version, outcome, created_at,
  created_at_anchor FROM eval_commerce_audit_reference
WHERE audit_reference_id = '$payment_audit_reference_id'
"
assert_payment_truth_fails_closed "duplicate audit stable-identity cardinality"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = '$duplicate_audit_reference_id'"

mysql_query root "$root_password" commerce_db \
  "ALTER TABLE inventory_ledger DROP CHECK chk_inventory_ledger_movement"
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE mock_payment_attempt DROP CHECK chk_mock_payment_attempt_state"
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE standard_order DROP CHECK chk_standard_order_payment_state"

payment_callback_fault_locator="callback_event_id IN ('$payment_event_id', '$tampered_callback_event_id') OR callback_idempotency_key IN ('$payment_callback_key', 'tampered-callback-key') OR attempt_id IN ('$payment_attempt_id', '$tampered_callback_attempt_id')"
payment_attempt_fault_locator="request_idempotency_key = 'payment-evaluation'"
payment_order_fault_locator="order_id IN ('$payment_order_id', '$tampered_order_id')"

payment_predicate_labels=(
  audit-row audit-sequence audit-anchor callback-row ledger-row
  callback-event callback-idempotency-key callback-attempt callback-correlation callback-sandbox
  callback-session callback-trace callback-operation callback-intent callback-outcome callback-result
  callback-created-at
  attempt-id attempt-correlation attempt-owner attempt-order attempt-order-kind attempt-sandbox
  attempt-intent attempt-amount attempt-refunded-amount attempt-currency attempt-state
  attempt-state-version attempt-succeeded-at
  order-id order-sandbox order-owner order-product order-amount order-currency order-status
  order-state-version
  ledger-key ledger-sandbox ledger-movement ledger-order ledger-product ledger-reservation
  ledger-activity ledger-inventory-delta ledger-activity-delta ledger-amount ledger-currency
)
payment_predicate_mutations=(
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = '$payment_audit_reference_id'"
  "UPDATE eval_commerce_audit_reference SET sequence_id = $payment_audit_tampered_sequence_id WHERE audit_reference_id = '$payment_audit_reference_id'"
  "SET SESSION sql_mode = 'NO_ENGINE_SUBSTITUTION'; UPDATE eval_commerce_audit_reference SET created_at_anchor = 'CORRUPTED' WHERE audit_reference_id = '$payment_audit_reference_id'"
  "DELETE FROM mock_payment_callback WHERE $payment_callback_fault_locator"
  "DELETE FROM inventory_ledger WHERE movement_id = '$payment_movement_id'"
  "UPDATE mock_payment_callback SET callback_event_id = '$tampered_callback_event_id' WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_callback SET callback_idempotency_key = 'tampered-callback-key' WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_callback SET attempt_id = '$tampered_callback_attempt_id' WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_callback SET callback_correlation_id = '00000000-0000-0000-0000-000000000107' WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_callback SET sandbox_id = 'sandbox-main' WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_callback SET support_session_id = 'tampered-payment-session' WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_callback SET trace_id = '00000000-0000-0000-0000-000000000186' WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_callback SET operation_id = REPEAT('f', 64) WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_callback SET intent_hash = REPEAT('f', 64) WHERE $payment_callback_fault_locator"
  "SET SESSION sql_mode = 'NO_ENGINE_SUBSTITUTION'; UPDATE mock_payment_callback SET requested_outcome = 'CORRUPTED' WHERE $payment_callback_fault_locator"
  "SET SESSION sql_mode = 'NO_ENGINE_SUBSTITUTION'; UPDATE mock_payment_callback SET result_state = 'CORRUPTED' WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_callback SET created_at = TIMESTAMPADD(MICROSECOND, 1, created_at) WHERE $payment_callback_fault_locator"
  "UPDATE mock_payment_attempt SET attempt_id = '$tampered_attempt_id' WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET callback_correlation_id = '00000000-0000-0000-0000-000000000194' WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET user_subject = 'tampered-attempt-owner' WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET order_id = '$tampered_attempt_order_id' WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET order_kind = 'SECKILL' WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET sandbox_id = 'sandbox-main' WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET intent_hash = REPEAT('f', 64) WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET amount_minor = 1801 WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET refunded_amount_minor = 1 WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET currency = 'AUD' WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET state = 'FAILED' WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET state_version = 3 WHERE $payment_attempt_fault_locator"
  "UPDATE mock_payment_attempt SET succeeded_at = TIMESTAMPADD(MICROSECOND, 1, succeeded_at) WHERE $payment_attempt_fault_locator"
  "UPDATE standard_order SET order_id = '$tampered_order_id' WHERE $payment_order_fault_locator"
  "UPDATE standard_order SET sandbox_id = 'sandbox-main' WHERE $payment_order_fault_locator"
  "UPDATE standard_order SET user_subject = 'tampered-order-user' WHERE $payment_order_fault_locator"
  "UPDATE standard_order SET product_id = 'tampered-order-product' WHERE $payment_order_fault_locator"
  "UPDATE standard_order SET total_price_minor = 1801 WHERE $payment_order_fault_locator"
  "UPDATE standard_order SET currency = 'AUD' WHERE $payment_order_fault_locator"
  "UPDATE standard_order SET status = 'UNPAID' WHERE $payment_order_fault_locator"
  "UPDATE standard_order SET state_version = 1 WHERE $payment_order_fault_locator"
  "UPDATE inventory_ledger SET business_event_key = 'tampered-payment-event-key' WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET sandbox_id = 'sandbox-main' WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET movement_type = 'STANDARD_REFUND' WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET order_id = '00000000-0000-0000-0000-000000000108' WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET product_id = 'tampered-ledger-product' WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET reservation_id = '00000000-0000-0000-0000-000000000187' WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET activity_id = 'tampered-activity' WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET inventory_delta = 1 WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET activity_quota_delta = 1 WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET payment_amount_minor = 1801 WHERE movement_id = '$payment_movement_id'"
  "UPDATE inventory_ledger SET payment_currency = 'AUD' WHERE movement_id = '$payment_movement_id'"
)

assert_equal 49 "${#payment_predicate_labels[@]}" \
  "complete physical JOIN/WHERE corruption label matrix"
assert_equal "${#payment_predicate_labels[@]}" "${#payment_predicate_mutations[@]}" \
  "physical JOIN/WHERE corruption labels and mutations stay aligned"

is_committed_payment_face_index() {
  local index="$1"
  [[ "$index" == 0 || "$index" == 3 || "$index" == 4 || "$index" == 17 || "$index" == 29 ]]
}

for ((predicate_index = 0; predicate_index < ${#payment_predicate_mutations[@]}; predicate_index++)); do
  mutation_count="$(mysql_query root "$root_password" commerce_db \
    "${payment_predicate_mutations[$predicate_index]}; SELECT ROW_COUNT()")"
  assert_equal 1 "$mutation_count" \
    "single consistency fault injection changed exactly one row: ${payment_predicate_labels[$predicate_index]}"
  assert_payment_truth_fails_closed \
    "single committed-face content corruption ${payment_predicate_labels[$predicate_index]}"
  restore_complete_payment_truth
done

for ((left_index = 0; left_index < ${#payment_predicate_mutations[@]}; left_index++)); do
  for ((right_index = left_index + 1; right_index < ${#payment_predicate_mutations[@]}; right_index++)); do
    left_mutation="${payment_predicate_mutations[$left_index]}"
    right_mutation="${payment_predicate_mutations[$right_index]}"
    if [[ "${payment_predicate_labels[$left_index]}" == audit-row \
        && "${payment_predicate_labels[$right_index]}" == audit-* ]] \
      || [[ "${payment_predicate_labels[$left_index]}" == callback-row \
        && "${payment_predicate_labels[$right_index]}" == callback-* ]] \
      || [[ "${payment_predicate_labels[$left_index]}" == ledger-row \
        && "${payment_predicate_labels[$right_index]}" == ledger-* ]]; then
      first_mutation="$right_mutation"
      second_mutation="$left_mutation"
    else
      first_mutation="$left_mutation"
      second_mutation="$right_mutation"
    fi
    mutation_counts="$(mysql_query root "$root_password" commerce_db \
      "$first_mutation; SELECT ROW_COUNT(); $second_mutation; SELECT ROW_COUNT()")"
    assert_equal $'1\n1' "$mutation_counts" \
      "paired consistency fault injection changed one row per fault: ${payment_predicate_labels[$left_index]} + ${payment_predicate_labels[$right_index]}"
    if is_committed_payment_face_index "$left_index" \
      && is_committed_payment_face_index "$right_index"; then
      assert_payment_truth_fails_closed \
        "paired committed-face corruption ${payment_predicate_labels[$left_index]} + ${payment_predicate_labels[$right_index]}"
    else
      assert_payment_audit_reconciliation_fails_closed \
        "paired enumerator predicate corruption ${payment_predicate_labels[$left_index]} + ${payment_predicate_labels[$right_index]}"
    fi
    restore_complete_payment_truth
  done
done

mysql_query root "$root_password" commerce_db "
ALTER TABLE standard_order ADD CONSTRAINT chk_standard_order_payment_state CHECK (
  (status = 'UNPAID' AND state_version = 1)
  OR (status = 'PAID' AND state_version = 2)
);
ALTER TABLE mock_payment_attempt ADD CONSTRAINT chk_mock_payment_attempt_state CHECK (
  refunded_amount_minor <= amount_minor
  AND (
    (state = 'PENDING' AND state_version = 1 AND succeeded_at IS NULL
      AND refunded_amount_minor = 0)
    OR (state = 'SUCCEEDED' AND state_version = 2 AND succeeded_at IS NOT NULL)
    OR (state = 'FAILED' AND state_version = 2 AND succeeded_at IS NULL
      AND refunded_amount_minor = 0)
  )
);
ALTER TABLE inventory_ledger ADD CONSTRAINT chk_inventory_ledger_movement CHECK (
  (movement_type = 'SECKILL_ORDER_CREATE'
    AND reservation_id IS NOT NULL AND activity_id IS NOT NULL
    AND inventory_delta < 0 AND activity_quota_delta = inventory_delta
    AND payment_amount_minor IS NULL AND payment_currency IS NULL)
  OR
  (movement_type = 'SECKILL_UNPAID_CANCEL'
    AND reservation_id IS NOT NULL AND activity_id IS NOT NULL
    AND inventory_delta > 0 AND activity_quota_delta = inventory_delta
    AND payment_amount_minor IS NULL AND payment_currency IS NULL)
  OR
  (movement_type IN ('STANDARD_PAYMENT', 'STANDARD_REFUND')
    AND reservation_id IS NULL AND activity_id IS NULL
    AND inventory_delta = 0 AND activity_quota_delta = 0
    AND payment_amount_minor IS NOT NULL AND payment_amount_minor > 0
    AND payment_currency IS NOT NULL)
  OR
  (movement_type IN ('SECKILL_PAYMENT', 'SECKILL_REFUND')
    AND reservation_id IS NOT NULL AND activity_id IS NOT NULL
    AND inventory_delta = 0 AND activity_quota_delta = 0
    AND payment_amount_minor IS NOT NULL AND payment_amount_minor > 0
    AND payment_currency IS NOT NULL)
);
"

mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET callback_correlation_id = '00000000-0000-0000-0000-000000000194' WHERE attempt_id = '$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted attempt correlation"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET callback_correlation_id = '$payment_correlation_id' WHERE attempt_id = '$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET user_subject = 'tampered-payment-user' WHERE attempt_id = '$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted attempt owner"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET user_subject = '$payment_subject' WHERE attempt_id = '$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET sandbox_id = 'sandbox-main' WHERE attempt_id = '$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted attempt sandbox"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET sandbox_id = 'sandbox-payment' WHERE attempt_id = '$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET order_id = '00000000-0000-0000-0000-000000000193' WHERE attempt_id = '$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted attempt order"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET order_id = '$payment_order_id' WHERE attempt_id = '$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET amount_minor = 1801 WHERE attempt_id = '$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted attempt amount"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET amount_minor = 1800 WHERE attempt_id = '$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET currency = 'AUD' WHERE attempt_id = '$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted attempt currency"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET currency = 'CNY' WHERE attempt_id = '$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET user_subject = 'tampered-order-user' WHERE order_id = '$payment_order_id'"
assert_payment_truth_fails_closed "corrupted order owner"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET user_subject = '$payment_subject' WHERE order_id = '$payment_order_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET sandbox_id = 'sandbox-main' WHERE order_id = '$payment_order_id'"
assert_payment_truth_fails_closed "corrupted order sandbox"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET sandbox_id = 'sandbox-payment' WHERE order_id = '$payment_order_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET product_id = 'tampered-order-product' WHERE order_id = '$payment_order_id'"
assert_payment_truth_fails_closed "corrupted order product"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET product_id = '$payment_product_id' WHERE order_id = '$payment_order_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET total_price_minor = 1801 WHERE order_id = '$payment_order_id'"
assert_payment_truth_fails_closed "corrupted order amount"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET total_price_minor = 1800 WHERE order_id = '$payment_order_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET currency = 'AUD' WHERE order_id = '$payment_order_id'"
assert_payment_truth_fails_closed "corrupted order currency"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET currency = 'CNY' WHERE order_id = '$payment_order_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET status = 'UNPAID', state_version = 1 WHERE order_id = '$payment_order_id'"
assert_payment_truth_fails_closed "corrupted order terminal state"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET status = 'PAID', state_version = 2 WHERE order_id = '$payment_order_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET business_event_key = 'tampered-payment-event-key' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted ledger business key"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET business_event_key = 'mock-payment:$payment_attempt_id' WHERE business_event_key = 'tampered-payment-event-key'"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET movement_type = 'STANDARD_REFUND' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted ledger movement type"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET movement_type = 'STANDARD_PAYMENT' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
tampered_payment_order='00000000-0000-0000-0000-000000000108'
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET order_id = '$tampered_payment_order' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted ledger order"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET order_id = '$payment_order_id' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET product_id = 'tampered-ledger-product' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted ledger product"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET product_id = '$payment_product_id' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET sandbox_id = 'sandbox-main' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted ledger sandbox"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET sandbox_id = 'sandbox-payment' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET payment_amount_minor = 1801 WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted ledger amount"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET payment_amount_minor = 1800 WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET payment_currency = 'AUD' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_payment_truth_fails_closed "corrupted ledger currency"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET payment_currency = 'CNY' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_status 200 "payment audit recovers after every locator is restored" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$payment_session" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
assert_status 200 "payment state recovers after every authoritative row is restored" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'

stop_process commerce_pid "$commerce_pid"
start_commerce evaluation "http://127.0.0.1:$auth_port"
payment_replay_timestamp="$(date +%s)"
payment_replay_signature="$(sign_payment_callback "$payment_replay_timestamp" \
  "$payment_callback_key" "$payment_event_id" "$payment_correlation_id" "$payment_order_id" \
  sandbox-payment "$payment_session" "$payment_trace" "$payment_operation")"
assert_status 200 "restart replay converges to the one durable callback result" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_replay_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_replay_signature" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$payment_callback_body"
jq -e '.replayed == true and .state == "SUCCEEDED"' "$tmp_dir/http-response.json" >/dev/null
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM inventory_ledger WHERE business_event_key = 'mock-payment:$payment_attempt_id'")" = 1
action_session='payment-action-session'
assert_status 200 "exchange exact sandbox-bound refund action scope" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$action_session\",\"userSubject\":\"$payment_subject\",\"scope\":\"refund:create\"}"
payment_action_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
action_turn_confirm='00000000-0000-0000-0000-000000000120'
assert_status 201 "active sandbox prepares one exact refund action" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/actions/prepare" \
  --header "Authorization: Bearer $payment_action_token" \
  --header "X-Support-Session-Id: $action_session" \
  --header 'X-Agent-Trace-Id: payment-action-trace' \
  --header "X-Agent-Turn-Id: $action_turn_confirm" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Content-Type: application/json' \
  --data "{\"actionType\":\"REFUND_REQUEST\",\"arguments\":{\"orderId\":\"$payment_order_id\",\"amountMinor\":500,\"currency\":\"CNY\"}}"
payment_pending_confirm="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" pendingActionId)"
action_refund_outbox_before="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND'")"
assert_action_sandbox_fault_has_no_effects() {
  local label="$1"
  local actual
  actual="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(
       (SELECT state FROM pending_action WHERE pending_action_id = '$payment_pending_confirm'), ':',
       (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM inventory_ledger
          WHERE order_id = '$payment_order_id'
            AND movement_type IN ('STANDARD_REFUND', 'SECKILL_REFUND')), ':',
       (SELECT COUNT(*) FROM action_receipt
          WHERE pending_action_id = '$payment_pending_confirm'), ':',
       (SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND'))")"
  if [[ "$actual" != "PREPARED:0:0:0:$action_refund_outbox_before" ]]; then
    echo "Action sandbox fault wrote durable effects for $label: $actual" >&2
    exit 1
  fi
}
assert_action_sandbox_fault_rejected() {
  local label="$1"
  assert_status 409 "$label rejects without action effects" \
    --request POST "http://127.0.0.1:$commerce_port/internal/tools/actions/$payment_pending_confirm/confirm" \
    --header "Authorization: Bearer $payment_action_token" \
    --header "X-Support-Session-Id: $action_session" \
    --header 'X-Agent-Trace-Id: payment-action-trace' \
    --header "X-Agent-Turn-Id: $action_turn_confirm" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment'
  assert_action_sandbox_fault_has_no_effects "$label"
}
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET sandbox_id = 'sandbox-main' WHERE order_id = '$payment_order_id'"
assert_action_sandbox_fault_rejected "cross-sandbox order truth"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET sandbox_id = 'sandbox-payment' WHERE order_id = '$payment_order_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET sandbox_id = 'sandbox-main' WHERE attempt_id = '$payment_attempt_id'"
assert_action_sandbox_fault_rejected "cross-sandbox payment attempt truth"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_attempt SET sandbox_id = 'sandbox-payment' WHERE attempt_id = '$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET sandbox_id = 'sandbox-main' WHERE attempt_id = '$payment_attempt_id'"
assert_action_sandbox_fault_rejected "cross-sandbox callback truth"
mysql_query root "$root_password" commerce_db \
  "UPDATE mock_payment_callback SET sandbox_id = 'sandbox-payment' WHERE attempt_id = '$payment_attempt_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET sandbox_id = 'sandbox-main' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_action_sandbox_fault_rejected "cross-sandbox payment ledger truth"
mysql_query root "$root_password" commerce_db \
  "UPDATE inventory_ledger SET sandbox_id = 'sandbox-payment' WHERE business_event_key = 'mock-payment:$payment_attempt_id'"
assert_status 403 "sandbox claim and header substitution fail closed for actions" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/actions/prepare" \
  --header "Authorization: Bearer $payment_action_token" \
  --header "X-Support-Session-Id: $action_session" \
  --header 'X-Agent-Trace-Id: payment-action-trace' \
  --header 'X-Agent-Turn-Id: 00000000-0000-0000-0000-000000000122' \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'Content-Type: application/json' \
  --data "{\"actionType\":\"REFUND_REQUEST\",\"arguments\":{\"orderId\":\"$payment_order_id\",\"amountMinor\":500,\"currency\":\"CNY\"}}"
assert_status 200 "active sandbox confirmation commits one durable receipt" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/actions/$payment_pending_confirm/confirm" \
  --header "Authorization: Bearer $payment_action_token" \
  --header "X-Support-Session-Id: $action_session" \
  --header 'X-Agent-Trace-Id: payment-action-trace' \
  --header "X-Agent-Turn-Id: $action_turn_confirm" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
jq -e '.status == "REQUESTED" and .replayed == false' "$tmp_dir/http-response.json" >/dev/null
action_turn_dead='00000000-0000-0000-0000-000000000121'
assert_status 201 "active sandbox may prepare a second unconsumed action" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/actions/prepare" \
  --header "Authorization: Bearer $payment_action_token" \
  --header "X-Support-Session-Id: $action_session" \
  --header 'X-Agent-Trace-Id: payment-action-dead-trace' \
  --header "X-Agent-Turn-Id: $action_turn_dead" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Content-Type: application/json' \
  --data "{\"actionType\":\"REFUND_REQUEST\",\"arguments\":{\"orderId\":\"$payment_order_id\",\"amountMinor\":400,\"currency\":\"CNY\"}}"
payment_pending_dead="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" pendingActionId)"
assert_status 200 "payment-first completion serializes after the committed callback" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-payment/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: complete-payment' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-payment"}'
assert_status 403 "inactive sandbox cannot confirm a prepared action" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/actions/$payment_pending_dead/confirm" \
  --header "Authorization: Bearer $payment_action_token" \
  --header "X-Support-Session-Id: $action_session" \
  --header 'X-Agent-Trace-Id: payment-action-dead-trace' \
  --header "X-Agent-Turn-Id: $action_turn_dead" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT((SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':', (SELECT COUNT(*) FROM action_receipt WHERE order_id = '$payment_order_id'), ':', (SELECT state FROM pending_action WHERE pending_action_id = '$payment_pending_dead'))")" = '1:1:PREPARED'
payment_dead_timestamp="$(date +%s)"
payment_dead_signature="$(sign_payment_callback "$payment_dead_timestamp" \
  "$payment_callback_key" "$payment_event_id" "$payment_correlation_id" "$payment_order_id" \
  sandbox-payment "$payment_session" "$payment_trace" "$payment_operation")"
assert_status 200 "dead sandbox returns the authenticated durable callback result without mutation" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $payment_dead_timestamp" \
  --header "X-Mock-Payment-Signature: $payment_dead_signature" \
  --header "Idempotency-Key: $payment_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$payment_callback_body"
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(status, ':', state_version) FROM standard_order WHERE order_id = '$payment_order_id'")" = 'PAID:2'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM inventory_ledger WHERE business_event_key = 'mock-payment:$payment_attempt_id'")" = 1

dead_order_id='00000000-0000-0000-0000-000000000107'
reset_payment_sandbox sandbox-dead-payment case-dead-payment reset-dead-payment "$dead_order_id"
dead_handle="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" testUserHandle)"
assert_status 200 "issue completion-first payment token" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-dead-payment' \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$dead_handle\"}"
dead_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
assert_status 201 "create completion-first pending attempt" \
  --request POST "http://127.0.0.1:$commerce_port/api/orders/$dead_order_id/mock-payment" \
  --header "Authorization: Bearer $dead_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-dead-payment' \
  --header 'Idempotency-Key: payment-dead-first' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1800,"currency":"CNY"}'
dead_attempt_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" attemptId)"
dead_correlation_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" callbackCorrelationId)"
assert_status 200 "completion wins before callback delivery" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-dead-payment/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: complete-dead-payment' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-dead-payment"}'
dead_event_id='00000000-0000-0000-0000-000000000108'
dead_operation="$(openssl rand -hex 32)"
dead_timestamp="$(date +%s)"
dead_signature="$(sign_payment_callback "$dead_timestamp" callback-dead-first \
  "$dead_event_id" "$dead_correlation_id" "$dead_order_id" sandbox-dead-payment \
  payment-dead-session payment-dead-trace "$dead_operation")"
assert_status 403 "completion-first callback performs no payment mutation" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $dead_timestamp" \
  --header "X-Mock-Payment-Signature: $dead_signature" \
  --header 'Idempotency-Key: callback-dead-first' \
  --header 'Content-Type: application/json' \
  --data "{\"callbackEventId\":\"$dead_event_id\",\"callbackCorrelationId\":\"$dead_correlation_id\",\"orderId\":\"$dead_order_id\",\"amountMinor\":1800,\"currency\":\"CNY\",\"outcome\":\"SUCCEEDED\",\"sandboxId\":\"sandbox-dead-payment\",\"supportSessionId\":\"payment-dead-session\",\"traceId\":\"payment-dead-trace\",\"operationId\":\"$dead_operation\"}"
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(o.status, ':', a.state, ':', (SELECT COUNT(*) FROM mock_payment_callback c WHERE c.attempt_id = a.attempt_id), ':', (SELECT COUNT(*) FROM inventory_ledger l WHERE l.business_event_key = CONCAT('mock-payment:', a.attempt_id))) FROM standard_order o JOIN mock_payment_attempt a ON a.order_id = o.order_id WHERE o.order_id = '$dead_order_id'")" = \
  'UNPAID:PENDING:0:0'

assert_status 204 "token header path and registry liveness agree" \
  --request POST "http://127.0.0.1:$commerce_port/internal/eval/sandboxes/sandbox-main/liveness" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'

uv run python scripts/fake_litellm_server.py --port 0 >>"$tmp_dir/model.log" 2>&1 &
model_pid=$!
process_bound_port proxy_port uvicorn "$model_pid" "$tmp_dir/model.log" 0
wait_http "http://127.0.0.1:$proxy_port/fixture/counts" "$model_pid" "$tmp_dir/model.log"
start_agent true
assert_status 201 "evaluation support session binds subject and sandbox" \
  --request POST "http://127.0.0.1:$agent_port/api/sessions" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'Content-Type: application/json' \
  --data '{}'
session_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" sessionId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db \
  "SELECT CONCAT(user_subject, ':', sandbox_id) FROM support_session WHERE session_id = '$session_id'")" = "$direct_subject:sandbox-main"
assert_status 200 "JIT exchange preserves the exact sandbox" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$session_id\",\"userSubject\":\"$direct_subject\",\"scope\":\"catalog:read\"}"
obo_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
sleep 1.1
jwks_fault_log_start="$(wc -l <"$tmp_dir/commerce.log")"
stop_process auth_pid "$auth_pid"
jwks_liveness_status="$(request_status "$tmp_dir/jwks-liveness-unavailable.json" \
  --request POST "http://127.0.0.1:$commerce_port/internal/eval/sandboxes/sandbox-main/liveness" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main')"
jwks_tool_operation="$(openssl rand -hex 32)"
jwks_tool_status="$(request_status "$tmp_dir/jwks-tool-unavailable.json" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'X-Agent-Trace-Id: jwks-unavailable-trace' \
  --header "X-Agent-Operation-Id: $jwks_tool_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}')"
echo 'jwks-unavailability-rejection-reasons'
jwks_rejection_reasons="$(tail -n "+$((jwks_fault_log_start + 1))" "$tmp_dir/commerce.log" \
  | sed -n 's/.*evaluation_request_rejected reason_code=\([^ ]*\).*/\1/p' \
  | sort)"
echo "$jwks_rejection_reasons"
start_auth evaluation
stop_process agent_pid "$agent_pid"
stop_process commerce_pid "$commerce_pid"
start_commerce evaluation "http://127.0.0.1:$auth_port"
start_agent true
assert_equal $'LIVENESS_DIRECT_USER_JWKS_UNAVAILABLE\nTOOL_OBO_JWKS_UNAVAILABLE' \
  "$jwks_rejection_reasons" \
  "JWKS outage reaches exactly the two attributed unavailable producers"
assert_equal '503:503' "$jwks_liveness_status:$jwks_tool_status" \
  "JWKS unavailability is never classified as authorization or inactive"
assert_equal 'Service unavailable' \
  "$(uv run python scripts/read_json_field.py "$tmp_dir/jwks-liveness-unavailable.json" error)" \
  "liveness JWKS outage exposes only the fixed unavailable response"
assert_equal 'Service unavailable' \
  "$(uv run python scripts/read_json_field.py "$tmp_dir/jwks-tool-unavailable.json" error)" \
  "tool JWKS outage exposes only the fixed unavailable response"
direct_trace="direct-trace-$(openssl rand -hex 8)"
direct_operation="$(openssl rand -hex 32)"
failed_operation="$(openssl rand -hex 32)"
audit_denials_before="$(mysql_query root "$root_password" performance_schema \
  "SELECT COALESCE(SUM(sum_error_raised), 0) FROM events_errors_summary_by_account_by_error WHERE user = 'commerce_app' AND error_number = 1142")"
mysql_query root "$root_password" '' \
  "REVOKE INSERT ON commerce_db.eval_commerce_audit_reference FROM 'commerce_app'@'%'"
assert_status 503 "tool read cannot report success when audit persistence fails" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header "X-Agent-Trace-Id: $direct_trace" \
  --header "X-Agent-Operation-Id: $failed_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
test "$(mysql_query root "$root_password" commerce_db \
  "SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE operation_id = '$failed_operation'")" = 0
test "$(mysql_query root "$root_password" commerce_db \
  "SELECT COUNT(*) FROM eval_commerce_product_observation WHERE operation_id = '$failed_operation'")" = 0

audit_fault_pids=()
audit_fault_operations=()
liveness_pids=()
for index in {1..16}; do
  fault_operation="$(openssl rand -hex 32)"
  audit_fault_operations+=("$fault_operation")
  (
    if ! request_status "$tmp_dir/audit-unavailable-$index.json" \
      --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
      --header "Authorization: Bearer $obo_token" \
      --header "X-Support-Session-Id: $session_id" \
      --header 'X-Eval-Sandbox-Id: sandbox-main' \
      --header "X-Agent-Trace-Id: audit-unavailable-trace-$index" \
      --header "X-Agent-Operation-Id: $fault_operation" \
      --header 'Content-Type: application/json' \
      --data '{"productId":"product-1"}' \
      >"$tmp_dir/audit-unavailable-$index.status"; then
      printf '000' >"$tmp_dir/audit-unavailable-$index.status"
    fi
  ) &
  audit_fault_pids+=("$!")
  (
    if ! request_status "$tmp_dir/audit-liveness-$index.json" \
      --request POST "http://127.0.0.1:$commerce_port/internal/eval/sandboxes/sandbox-main/liveness" \
      --header "Authorization: Bearer $direct_token" \
      --header 'X-Eval-Sandbox-Id: sandbox-main' \
      >"$tmp_dir/audit-liveness-$index.status"; then
      printf '000' >"$tmp_dir/audit-liveness-$index.status"
    fi
  ) &
  liveness_pids+=("$!")
done
for pid in "${audit_fault_pids[@]}" "${liveness_pids[@]}"; do
  wait "$pid"
done
for index in {1..16}; do
  if [[ "$(cat "$tmp_dir/audit-unavailable-$index.status")" != 503 ]]; then
    report_audit_unavailability_misclassification \
      "concurrent audit write $index" \
      "$tmp_dir/audit-unavailable-$index.status" \
      "$tmp_dir/audit-unavailable-$index.json"
  fi
  assert_equal 'Service unavailable' \
    "$(uv run python scripts/read_json_field.py "$tmp_dir/audit-unavailable-$index.json" error)" \
    "concurrent audit write $index exposes only the fixed unavailable response"
  if [[ "$(cat "$tmp_dir/audit-liveness-$index.status")" != 204 ]]; then
    report_audit_unavailability_misclassification \
      "concurrent liveness read $index" \
      "$tmp_dir/audit-liveness-$index.status" \
      "$tmp_dir/audit-liveness-$index.json"
  fi
done
quoted_fault_operations="$(printf "'%s'," "${audit_fault_operations[@]}")"
quoted_fault_operations="${quoted_fault_operations%,}"
assert_equal 'ACTIVE:1' \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT(lifecycle_state, ':', expires_at > CURRENT_TIMESTAMP(6)) FROM eval_sandbox WHERE sandbox_id = 'sandbox-main'")" \
  "audit-unavailability pressure preserves authoritative sandbox liveness"
assert_equal 0 \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE operation_id IN ($quoted_fault_operations)")" \
  "audit-unavailability pressure leaves no audit reference"
assert_equal 0 \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT COUNT(*) FROM eval_commerce_product_observation WHERE operation_id IN ($quoted_fault_operations)")" \
  "audit-unavailability pressure rolls back every product observation"
audit_denials_after="$(mysql_query root "$root_password" performance_schema \
  "SELECT COALESCE(SUM(sum_error_raised), 0) FROM events_errors_summary_by_account_by_error WHERE user = 'commerce_app' AND error_number = 1142")"
assert_equal 17 "$((audit_denials_after - audit_denials_before))" \
  "every unavailable tool request reached the revoked audit INSERT boundary"
echo 'Verified 16 concurrent audit-persistence failures remained 503 while 16 liveness reads remained 204.'
mysql_query root "$root_password" '' \
  "GRANT INSERT ON commerce_db.eval_commerce_audit_reference TO 'commerce_app'@'%'"
assert_status 200 "OBO tool reads only the exact sandbox fixture" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header "X-Agent-Trace-Id: $direct_trace" \
  --header "X-Agent-Operation-Id: $direct_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
test "$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" name)" = sandbox-product
assert_status 200 "same evaluation operation replays one audit identity" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header "X-Agent-Trace-Id: $direct_trace" \
  --header "X-Agent-Operation-Id: $direct_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE operation_id = '$direct_operation'")" = 1
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM eval_commerce_product_observation WHERE operation_id = '$direct_operation'")" = 1
assert_status 403 "same operation rejects conflicting trace reuse" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'X-Agent-Trace-Id: conflicting-trace' \
  --header "X-Agent-Operation-Id: $direct_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
assert_status 403 "OBO tool rejects sandbox substitution" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-other' \
  --header "X-Agent-Trace-Id: $direct_trace" \
  --header "X-Agent-Operation-Id: $direct_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
assert_status 200 "evaluation chat executes sandbox-bound OBO tool" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header "X-Session-Id: $session_id" \
  --header 'Idempotency-Key: cb101-tool-turn' \
  --header 'Content-Type: application/json' \
  --data '{"message":"tool-success cb103-private-user-text"}'
trace_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
mysql_query root "$root_password" cs_db \
  "INSERT INTO support_feedback (feedback_id, session_id, user_subject, trace_id, idempotency_key, request_fingerprint, rating, comment_text) VALUES (UUID(), '$session_id', '$direct_subject', '$trace_id', 'cb103-feedback-fixture', REPEAT('f', 64), 'POSITIVE', 'cb103-private-feedback-comment')"
test "$(mysql_query agent_app "$agent_app_password" cs_db \
  "SELECT GROUP_CONCAT(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.state')) ORDER BY sequence SEPARATOR ',') FROM support_event WHERE trace_id = '$trace_id' AND event_type = 'TOOL_LIFECYCLE'")" = 'requested,succeeded'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-main' AND support_session_id = '$session_id'")" = 2
assert_status 401 "agent evidence rejects missing management credential" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 401 "direct-user token is not agent evidence authentication" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 401 "agent evidence rejects substituted management credential" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --user "evaluation-manager:$invalid_management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'

assert_agent_evidence_credential_401() {
  local description="$1"
  local authorization_value="$2"
  assert_status 401 "$description" \
    --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
    --header "Authorization: $authorization_value" \
    --header 'X-Eval-Sandbox-Id: sandbox-main'
  if ! grep -Fxq '{"detail":"Unauthorized"}' "$tmp_dir/http-response.json"; then
    echo "Malformed evaluation credential exposed a non-public response." >&2
    exit 1
  fi
}

assert_agent_evidence_credential_401 \
  "agent evidence rejects a raw non-ASCII Basic token" 'Basic é'
assert_agent_evidence_credential_401 \
  "agent evidence rejects invalid Base64" 'Basic !!!'
invalid_utf8_basic="$(printf '\377:x' | openssl base64 -A)"
assert_agent_evidence_credential_401 \
  "agent evidence rejects decoded non-UTF-8 bytes" "Basic $invalid_utf8_basic"
missing_colon_basic="$(printf 'missing-colon' | openssl base64 -A)"
assert_agent_evidence_credential_401 \
  "agent evidence rejects Basic credentials without a colon" "Basic $missing_colon_basic"
empty_basic="$(printf ':' | openssl base64 -A)"
assert_agent_evidence_credential_401 \
  "agent evidence rejects empty Basic credentials" "Basic $empty_basic"
assert_agent_evidence_credential_401 \
  "agent evidence rejects a non-Basic scheme" 'Bearer evaluator-token'
oversized_basic="$(printf '%2048s' '' | tr ' ' A)"
assert_agent_evidence_credential_401 \
  "agent evidence rejects an oversized Basic header" "Basic $oversized_basic"
control_basic="$(printf 'evaluation-manager:x\001' | openssl base64 -A)"
assert_agent_evidence_credential_401 \
  "agent evidence rejects decoded control characters" "Basic $control_basic"
nul_basic="$(printf 'evaluation-manager:x\000' | openssl base64 -A)"
assert_agent_evidence_credential_401 \
  "agent evidence rejects decoded NUL bytes" "Basic $nul_basic"
non_ascii_client_basic="$(printf '\303\251valuation-manager:x' | openssl base64 -A)"
assert_agent_evidence_credential_401 \
  "agent evidence rejects non-ASCII management client id" "Basic $non_ascii_client_basic"
non_ascii_secret_basic="$(printf 'evaluation-manager:x\303\251' | openssl base64 -A)"
assert_agent_evidence_credential_401 \
  "agent evidence rejects non-ASCII management secret" "Basic $non_ascii_secret_basic"
assert_status 422 "agent evidence rejects caller-selected fields" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id?fields=all" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 422 "agent evidence rejects malformed trace" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/not-a-trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 422 "agent evidence rejects a request body" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'Content-Type: application/json' \
  --data '{}'
assert_status 404 "agent evidence conceals cross-sandbox trace ownership" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-other'
agent_truth_before="$(mysql_query agent_app "$agent_app_password" cs_db \
  "SELECT CONCAT((SELECT state FROM support_turn WHERE trace_id = '$trace_id'), ':', (SELECT COUNT(*) FROM support_event WHERE trace_id = '$trace_id'), ':', (SELECT COUNT(*) FROM support_feedback WHERE trace_id = '$trace_id'))")"
curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" >"$tmp_dir/model-counts-before-evidence.json"
assert_status 200 "agent evidence projects complete bounded durable truth" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
cp "$tmp_dir/http-response.json" "$tmp_dir/agent-evidence.json"
uv run python scripts/check_agent_evaluation_evidence.py "$tmp_dir/agent-evidence.json" \
  --trace "$trace_id" --session "$session_id" --outcome completed \
  --require-event ROUTING_DECISION --require-event TOOL_LIFECYCLE \
  --require-event BUDGET_CHARGED --feedback-count 1 \
  --forbid-marker cb103-private-user-text \
  --forbid-marker cb103-private-feedback-comment \
  --forbid-marker support-standard-primary \
  --forbid-marker sandbox-product
assert_status 200 "repeated agent evidence read is byte-for-byte deterministic" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
cmp "$tmp_dir/agent-evidence.json" "$tmp_dir/http-response.json"
assert_equal "$agent_truth_before" "$(mysql_query agent_app "$agent_app_password" cs_db \
  "SELECT CONCAT((SELECT state FROM support_turn WHERE trace_id = '$trace_id'), ':', (SELECT COUNT(*) FROM support_event WHERE trace_id = '$trace_id'), ':', (SELECT COUNT(*) FROM support_feedback WHERE trace_id = '$trace_id'))")" \
  "agent evidence reads do not mutate durable support truth"
curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" >"$tmp_dir/model-counts-after-evidence.json"
cmp "$tmp_dir/model-counts-before-evidence.json" "$tmp_dir/model-counts-after-evidence.json"
mysql_query root "$root_password" cs_db \
  "UPDATE support_event SET payload_json = JSON_SET(payload_json, '$.outcome', 'provider_denied') WHERE trace_id = '$trace_id' AND event_type = 'AGENT_OUTCOME'"
assert_status 409 "agent evidence rejects a terminal outcome conflicting with turn truth" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
mysql_query root "$root_password" cs_db \
  "UPDATE support_event SET payload_json = JSON_SET(payload_json, '$.outcome', 'completed') WHERE trace_id = '$trace_id' AND event_type = 'AGENT_OUTCOME'"
mysql_query root "$root_password" cs_db \
  "UPDATE support_event SET event_type = 'TURN_FAILED', payload_json = JSON_OBJECT('code', 'tampered_intermediate_terminal') WHERE trace_id = '$trace_id' AND event_type = 'AGENT_OUTCOME'"
assert_status 409 "agent evidence rejects an intermediate terminal boundary" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
mysql_query root "$root_password" cs_db \
  "UPDATE support_event SET event_type = 'AGENT_OUTCOME', payload_json = JSON_OBJECT('outcome', 'completed') WHERE trace_id = '$trace_id' AND event_type = 'TURN_FAILED' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.code')) = 'tampered_intermediate_terminal'"
turn_id="$(mysql_query agent_app "$agent_app_password" cs_db \
  "SELECT turn_id FROM support_turn WHERE trace_id = '$trace_id'")"
turn_last_sequence="$(mysql_query agent_app "$agent_app_password" cs_db \
  "SELECT MAX(sequence) FROM support_event WHERE trace_id = '$trace_id'")"
assert_mysql_integrity_fails "duplicate trace sequence is rejected by MySQL" \
  mysql_query agent_app "$agent_app_password" cs_db \
  "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES (UUID(), '$turn_id', '$trace_id', '$session_id', '$direct_subject', 1, 'USER_INPUT', JSON_OBJECT('accepted', true))"
assert_mysql_integrity_fails "conflicting turn and session association is rejected by MySQL" \
  mysql_query agent_app "$agent_app_password" cs_db \
  "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES (UUID(), '$turn_id', '$trace_id', 'cross-session', '$direct_subject', $((turn_last_sequence + 1)), 'AGENT_OUTCOME', JSON_OBJECT('outcome', 'completed'))"

conversation_id="$(mysql_query agent_app "$agent_app_password" cs_db \
  "SELECT conversation_id FROM support_conversation WHERE session_id = '$session_id'")"
partial_trace='00000000-0000-0000-0000-000000000131'
partial_turn='00000000-0000-0000-0000-000000000132'
mysql_query agent_app "$agent_app_password" cs_db \
  "INSERT INTO support_turn (turn_id, conversation_id, session_id, user_subject, trace_id, turn_sequence, correlation_key, request_fingerprint, input_text, response_text, outcome, state, processing_deadline_at, completed_at) VALUES ('$partial_turn', '$conversation_id', '$session_id', '$direct_subject', '$partial_trace', 131, 'cb103-partial', REPEAT('1', 64), 'cb103-private-partial-input', 'partial response', 'completed', 'COMPLETED', NULL, CURRENT_TIMESTAMP(6)); INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES (UUID(), '$partial_turn', '$partial_trace', '$session_id', '$direct_subject', 1, 'USER_INPUT', JSON_OBJECT('accepted', true));"
assert_status 409 "agent evidence rejects partial history without terminal boundary" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$partial_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'

retrieval_trace='00000000-0000-0000-0000-000000000133'
retrieval_turn='00000000-0000-0000-0000-000000000134'
retrieval_decision='00000000-0000-0000-0000-000000000135'
mysql_query agent_app "$agent_app_password" cs_db \
  "INSERT INTO support_turn (turn_id, conversation_id, session_id, user_subject, trace_id, turn_sequence, correlation_key, request_fingerprint, input_text, response_text, outcome, state, processing_deadline_at, completed_at) VALUES ('$retrieval_turn', '$conversation_id', '$session_id', '$direct_subject', '$retrieval_trace', 133, 'cb103-retrieval', REPEAT('2', 64), 'cb103-private-retrieval-input', 'retrieval denied', 'retrieval_denied', 'COMPLETED', NULL, CURRENT_TIMESTAMP(6)); INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES (UUID(), '$retrieval_turn', '$retrieval_trace', '$session_id', '$direct_subject', 1, 'USER_INPUT', JSON_OBJECT('accepted', true)), (UUID(), '$retrieval_turn', '$retrieval_trace', '$session_id', '$direct_subject', 2, 'RETRIEVAL_DECISION', JSON_OBJECT('indexVersion', 'knowledge_docs_v1', 'calibrationVersion', 'cb091-calibration-v1', 'outcome', 'INSUFFICIENT', 'reason', 'below_threshold', 'candidateCount', 2, 'evidenceCount', 0)), (UUID(), '$retrieval_turn', '$retrieval_trace', '$session_id', '$direct_subject', 3, 'AGENT_OUTCOME', JSON_OBJECT('outcome', 'retrieval_denied')), (UUID(), '$retrieval_turn', '$retrieval_trace', '$session_id', '$direct_subject', 4, 'ASSISTANT_RESPONSE', JSON_OBJECT('outcome', 'retrieval_denied')), (UUID(), '$retrieval_turn', '$retrieval_trace', '$session_id', '$direct_subject', 5, 'TURN_COMPLETED', JSON_OBJECT('outcome', 'retrieval_denied')); INSERT INTO retrieval_decision (decision_id, turn_id, trace_id, session_id, user_subject, index_version, calibration_version, sufficiency_outcome, reason_code, candidate_count, evidence_count, top_score, top_margin) VALUES ('$retrieval_decision', '$retrieval_turn', '$retrieval_trace', '$session_id', '$direct_subject', 'knowledge_docs_v1', 'cb091-calibration-v1', 'INSUFFICIENT', 'below_threshold', 2, 0, 0.40, 0.05);"
assert_status 200 "agent evidence projects persisted insufficient retrieval decision" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$retrieval_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
uv run python scripts/check_agent_evaluation_evidence.py "$tmp_dir/http-response.json" \
  --trace "$retrieval_trace" --session "$session_id" --outcome retrieval_denied \
  --require-event RETRIEVAL_DECISION --retrieval-outcome INSUFFICIENT \
  --forbid-marker cb103-private-retrieval-input
mysql_query root "$root_password" cs_db \
  "UPDATE support_event SET payload_json = JSON_SET(payload_json, '$.outcome', 'SUFFICIENT') WHERE trace_id = '$retrieval_trace' AND event_type = 'RETRIEVAL_DECISION'"
assert_status 409 "agent evidence rejects conflicting retrieval facts" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$retrieval_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
mysql_query root "$root_password" cs_db \
  "UPDATE support_event SET payload_json = JSON_SET(payload_json, '$.outcome', 'INSUFFICIENT') WHERE trace_id = '$retrieval_trace' AND event_type = 'RETRIEVAL_DECISION'"

sufficient_trace='00000000-0000-0000-0000-000000000138'
sufficient_turn='00000000-0000-0000-0000-000000000139'
sufficient_decision='00000000-0000-0000-0000-000000000140'
mysql_query agent_app "$agent_app_password" cs_db \
  "INSERT INTO support_turn (turn_id, conversation_id, session_id, user_subject, trace_id, turn_sequence, correlation_key, request_fingerprint, input_text, response_text, outcome, state, processing_deadline_at, completed_at) VALUES ('$sufficient_turn', '$conversation_id', '$session_id', '$direct_subject', '$sufficient_trace', 138, 'cb103-sufficient', REPEAT('4', 64), 'cb103-private-sufficient-input', 'grounded answer', 'completed', 'COMPLETED', NULL, CURRENT_TIMESTAMP(6)); INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES (UUID(), '$sufficient_turn', '$sufficient_trace', '$session_id', '$direct_subject', 1, 'USER_INPUT', JSON_OBJECT('accepted', true)), (UUID(), '$sufficient_turn', '$sufficient_trace', '$session_id', '$direct_subject', 2, 'RETRIEVAL_DECISION', JSON_OBJECT('indexVersion', 'knowledge_docs_v2', 'calibrationVersion', 'cb091-calibration-v1', 'outcome', 'SUFFICIENT', 'reason', 'sufficient', 'candidateCount', 1, 'evidenceCount', 1)), (UUID(), '$sufficient_turn', '$sufficient_trace', '$session_id', '$direct_subject', 3, 'AGENT_OUTCOME', JSON_OBJECT('outcome', 'completed')), (UUID(), '$sufficient_turn', '$sufficient_trace', '$session_id', '$direct_subject', 4, 'ASSISTANT_RESPONSE', JSON_OBJECT('outcome', 'completed')), (UUID(), '$sufficient_turn', '$sufficient_trace', '$session_id', '$direct_subject', 5, 'TURN_COMPLETED', JSON_OBJECT('outcome', 'completed')); INSERT INTO retrieval_decision (decision_id, turn_id, trace_id, session_id, user_subject, index_version, calibration_version, sufficiency_outcome, reason_code, candidate_count, evidence_count, top_score, top_margin) VALUES ('$sufficient_decision', '$sufficient_turn', '$sufficient_trace', '$session_id', '$direct_subject', 'knowledge_docs_v2', 'cb091-calibration-v1', 'SUFFICIENT', 'sufficient', 1, 1, 0.90, 0.80); INSERT INTO retrieval_evidence (evidence_id, decision_id, evidence_rank, source_id, chunk_id, source_version, doc_type, title, excerpt, rerank_score) VALUES (UUID(), '$sufficient_decision', 1, 'public-source-1', 'public-chunk-1', 7, 'faq', 'cb103-private-source-title', 'cb103-private-source-excerpt', 0.90);"
assert_status 200 "agent evidence projects only safe public retrieval references" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$sufficient_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
uv run python scripts/check_agent_evaluation_evidence.py "$tmp_dir/http-response.json" \
  --trace "$sufficient_trace" --session "$session_id" --outcome completed \
  --require-event RETRIEVAL_DECISION --retrieval-outcome SUFFICIENT \
  --forbid-marker cb103-private-sufficient-input \
  --forbid-marker cb103-private-source-title \
  --forbid-marker cb103-private-source-excerpt

assert_status 200 "evaluation chat persists bounded provider denial" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header "X-Session-Id: $session_id" \
  --header 'Idempotency-Key: cb103-provider-denied' \
  --header 'Content-Type: application/json' \
  --data '{"message":"provider-failure cb103-private-provider-input"}'
provider_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
assert_status 200 "agent evidence projects bounded provider denial without provider identity" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$provider_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
uv run python scripts/check_agent_evaluation_evidence.py "$tmp_dir/http-response.json" \
  --trace "$provider_trace" --session "$session_id" --outcome provider_denied \
  --require-event MODEL_OUTCOME --require-event AGENT_OUTCOME \
  --forbid-marker cb103-private-provider-input \
  --forbid-marker support-standard-primary

oversized_trace='00000000-0000-0000-0000-000000000136'
oversized_turn='00000000-0000-0000-0000-000000000137'
mysql_query agent_app "$agent_app_password" cs_db \
  "INSERT INTO support_turn (turn_id, conversation_id, session_id, user_subject, trace_id, turn_sequence, correlation_key, request_fingerprint, input_text, response_text, outcome, state, processing_deadline_at, completed_at) VALUES ('$oversized_turn', '$conversation_id', '$session_id', '$direct_subject', '$oversized_trace', 136, 'cb103-oversized', REPEAT('3', 64), 'oversized input', 'oversized response', 'completed', 'COMPLETED', NULL, CURRENT_TIMESTAMP(6))"
oversized_values="(UUID(), '$oversized_turn', '$oversized_trace', '$session_id', '$direct_subject', 1, 'USER_INPUT', JSON_OBJECT('accepted', true))"
for sequence in $(seq 2 48); do
  oversized_values+=", (UUID(), '$oversized_turn', '$oversized_trace', '$session_id', '$direct_subject', $sequence, 'BUDGET_CHARGED', JSON_OBJECT('attempt', 1, 'limit', 8, 'kind', 'model_http', 'target', 'private-provider'))"
done
oversized_values+=", (UUID(), '$oversized_turn', '$oversized_trace', '$session_id', '$direct_subject', 49, 'TURN_COMPLETED', JSON_OBJECT('outcome', 'completed'))"
mysql_query agent_app "$agent_app_password" cs_db \
  "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES $oversized_values"
assert_status 409 "agent evidence rejects histories beyond the server event bound" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$oversized_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 200 "audit returns only the exact sandbox and support session" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
cp "$tmp_dir/http-response.json" "$tmp_dir/audit.json"
uv run python scripts/check_evaluation_views.py audit "$tmp_dir/audit.json" \
  --sandbox sandbox-main --session "$session_id" --count 2 --trace "$trace_id"
assert_status 200 "audit first page is bounded and has a stable cursor" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id?limit=1" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
uv run python scripts/check_evaluation_views.py audit "$tmp_dir/http-response.json" \
  --sandbox sandbox-main --session "$session_id" --count 1 --next-cursor
first_sequence="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT MIN(sequence_id) FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-main' AND support_session_id = '$session_id'")"
assert_status 200 "audit cursor advances deterministically" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id?limit=1&after=$first_sequence" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
uv run python scripts/check_evaluation_views.py audit "$tmp_dir/http-response.json" \
  --sandbox sandbox-main --session "$session_id" --count 1
assert_status 400 "audit rejects unbounded limit" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id?limit=51" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 404 "audit rejects an unassociated support session" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/session-other" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 404 "audit rejects cross-sandbox lookup" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-other'

stop_process model_pid "$model_pid"
stop_process commerce_pid "$commerce_pid"
stop_process agent_pid "$agent_pid"
start_agent true
assert_status 200 "agent evidence survives restart without model or commerce availability" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$trace_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
cmp "$tmp_dir/agent-evidence.json" "$tmp_dir/http-response.json"
start_commerce evaluation "http://127.0.0.1:$auth_port"
stop_process agent_pid "$agent_pid"
start_agent true
assert_status 200 "state persists across commerce restart" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
cmp "$tmp_dir/state-active.json" "$tmp_dir/http-response.json"
assert_status 200 "audit references persist across commerce restart" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
cmp "$tmp_dir/audit.json" "$tmp_dir/http-response.json"
assert_status 200 "version identifiers persist across commerce restart" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/version" \
  --user "evaluation-manager:$management_password"
cmp "$tmp_dir/version.json" "$tmp_dir/http-response.json"
product_audit_reference_id="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT audit_reference_id FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-main' AND operation_id = '$direct_operation'")"
product_audit_sequence_id="$(mysql_query root "$root_password" commerce_db \
  "SELECT sequence_id FROM eval_commerce_audit_reference WHERE audit_reference_id = '$product_audit_reference_id'")"
product_audit_created_at="$(mysql_query root "$root_password" commerce_db \
  "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM eval_commerce_product_observation WHERE observation_id = '$product_audit_reference_id'")"

mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" "missing product audit reference"
mysql_query root "$root_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES ($product_audit_sequence_id, '$product_audit_reference_id', 'sandbox-main', '$session_id', '$direct_trace', '$direct_operation', 'PRODUCT_FIXTURE', 'product-1', 1, 'OBSERVED', '$product_audit_created_at', 'BUSINESS_EVENT')"

mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET audit_reference_id = REPEAT('f', 64) WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit reference identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET audit_reference_id = '$product_audit_reference_id' WHERE audit_reference_id = REPEAT('f', 64)"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET sandbox_id = 'sandbox-payment' WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit sandbox identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET sandbox_id = 'sandbox-main' WHERE audit_reference_id = '$product_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET support_session_id = 'tampered-product-session' WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit session identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET support_session_id = '$session_id' WHERE audit_reference_id = '$product_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET trace_id = 'tampered-product-trace' WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit trace identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET trace_id = '$direct_trace' WHERE audit_reference_id = '$product_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET operation_id = REPEAT('5', 64) WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit operation identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET operation_id = '$direct_operation' WHERE audit_reference_id = '$product_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_type = 'PAYMENT_CALLBACK' WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit entity-type identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_type = 'PRODUCT_FIXTURE' WHERE audit_reference_id = '$product_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_id = 'missing-product' WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit entity identity"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_id = 'product-1' WHERE audit_reference_id = '$product_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_version = 2 WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit entity version"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_version = 1 WHERE audit_reference_id = '$product_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "SET SESSION sql_mode = 'NO_ENGINE_SUBSTITUTION'; UPDATE eval_commerce_audit_reference SET outcome = 'CORRUPTED' WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit outcome"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET outcome = 'OBSERVED' WHERE audit_reference_id = '$product_audit_reference_id'"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET created_at = TIMESTAMPADD(SECOND, 1, created_at) WHERE audit_reference_id = '$product_audit_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "corrupted product audit business event time"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET created_at = '$product_audit_created_at' WHERE audit_reference_id = '$product_audit_reference_id'"

mysql_query root "$root_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES (REPEAT('4', 64), 'sandbox-main', '$session_id', '$direct_trace', REPEAT('3', 64), 'PRODUCT_FIXTURE', 'product-1', 1, 'OBSERVED', CURRENT_TIMESTAMP(6), 'BUSINESS_EVENT')"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "orphan product audit pseudo-duplicate"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = REPEAT('4', 64)"
mysql_query root "$root_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES (REPEAT('2', 64), 'sandbox-main', '$session_id', '$direct_trace', REPEAT('1', 64), 'PAYMENT_CALLBACK', 'product-1', 1, 'OBSERVED', CURRENT_TIMESTAMP(6), 'BUSINESS_EVENT')"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "correct product audit plus cross-type payment pseudo-duplicate"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = REPEAT('2', 64)"
mysql_query root "$root_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES (REPEAT('0', 64), 'sandbox-main', '$session_id', '$direct_trace', REPEAT('9', 64), 'PRODUCT_FIXTURE', 'missing-product', 1, 'OBSERVED', CURRENT_TIMESTAMP(6), 'BUSINESS_EVENT')"
assert_audit_totality_fails_closed sandbox-main "$session_id" "orphan product audit reference"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = REPEAT('0', 64)"
fake_legacy_operation="$(printf '7%.0s' {1..64})"
fake_legacy_reference_id="$(uv run python -c '
import hashlib
import sys

digest = hashlib.sha256()
for value in sys.argv[1:]:
    encoded = value.encode()
    digest.update(str(len(encoded)).encode())
    digest.update(b":")
    digest.update(encoded)
    digest.update(b";")
print(digest.hexdigest())
' sandbox-main "$session_id" "$direct_trace" "$fake_legacy_operation" \
  PRODUCT_FIXTURE missing-product 1 OBSERVED)"
mysql_query root "$root_password" commerce_db \
  "INSERT INTO eval_commerce_audit_reference (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor) VALUES ('$fake_legacy_reference_id', 'sandbox-main', '$session_id', '$direct_trace', '$fake_legacy_operation', 'PRODUCT_FIXTURE', 'missing-product', 1, 'OBSERVED', CURRENT_TIMESTAMP(6), 'LEGACY_CUTOFF')"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "post-V013 self-declared legacy orphan beyond the immutable watermark"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = '$fake_legacy_reference_id'"
sequence_source_reference_id="$(mysql_query root "$root_password" commerce_db \
  "SELECT audit_reference_id FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-main' AND created_at < (SELECT MAX(created_at) FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-main') ORDER BY created_at, sequence_id LIMIT 1")"
if [[ -z "$sequence_source_reference_id" ]]; then
  echo "Sequence-order corruption fixture requires two distinct anchored audit times." >&2
  exit 1
fi
sequence_original="$(mysql_query root "$root_password" commerce_db \
  "SELECT sequence_id FROM eval_commerce_audit_reference WHERE audit_reference_id = '$sequence_source_reference_id'")"
sequence_max="$(mysql_query root "$root_password" commerce_db \
  "SELECT MAX(sequence_id) FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-main'")"
sequence_tampered="$((sequence_max + 1000))"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET sequence_id = $sequence_tampered WHERE audit_reference_id = '$sequence_source_reference_id'"
assert_audit_totality_fails_closed sandbox-main "$session_id" \
  "audit sequence contradicts anchored business event time"
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET sequence_id = $sequence_original WHERE audit_reference_id = '$sequence_source_reference_id'"
assert_status 200 "state recovers after the complete product audit matrix" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 200 "audit recovers after the complete product audit matrix" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 401 "evaluation chat rejects sandbox header substitution" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-other' \
  --header "X-Session-Id: $session_id" \
  --header 'Idempotency-Key: cb101-cross-sandbox' \
  --header 'Content-Type: application/json' \
  --data '{"message":"tool-success"}'

assert_status 404 "completion hides cross-case sandbox" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-main/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: complete-main' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-other"}'
assert_status 200 "normal completion revokes identity before success" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-main/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: complete-main' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-main"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/complete-main.json"
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(lifecycle_state, ':', auth_invalidation_state, ':', closed_at IS NOT NULL) FROM eval_sandbox WHERE sandbox_id = 'sandbox-main'")" = 'DEAD:REVOKED:1'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM eval_sandbox_product_fixture WHERE sandbox_id = 'sandbox-main'")" = 0
test "$(mysql_query auth_app "$auth_app_password" commerce_db \
  "SELECT state FROM auth_eval_test_principal WHERE opaque_handle = '$main_handle'")" = REVOKED
assert_status 200 "dead sandbox remains bounded historical state" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
uv run python scripts/check_evaluation_views.py state "$tmp_dir/http-response.json" \
  --sandbox sandbox-main --lifecycle DEAD --product-count 0
assert_status 200 "normal completion replay" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-main/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: complete-main' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-main"}'
cmp "$tmp_dir/complete-main.json" "$tmp_dir/http-response.json"
assert_status 409 "completion rejects conflicting idempotency" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-main/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: complete-conflict' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-main"}'
assert_status 409 "dead sandbox cannot be reset or reused" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: reset-main' \
  --header 'Content-Type: application/json' \
  --data "$(reset_body sandbox-main case-main sandbox-product 3600)"
assert_status 403 "completion immediately blocks commerce liveness" \
  --request POST "http://127.0.0.1:$commerce_port/internal/eval/sandboxes/sandbox-main/liveness" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
assert_status 403 "completion immediately blocks new agent work" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header "X-Session-Id: $session_id" \
  --header 'Idempotency-Key: cb101-after-complete' \
  --header 'Content-Type: application/json' \
  --data '{"message":"tool-success"}'

# The runtime loses fixture INSERT only after the registry write; compensation must close safely.
mysql_query root "$root_password" commerce_db \
  "REVOKE INSERT ON commerce_db.eval_sandbox_product_fixture FROM 'commerce_app'@'%'"
assert_status 503 "fixture closure failure stays dead and compensated" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: reset-fixture-failure' \
  --header 'Content-Type: application/json' \
  --data "$(reset_body sandbox-fixture-failure case-fixture-failure never-active)"
mysql_query root "$root_password" '' \
  "GRANT INSERT ON commerce_db.eval_sandbox_product_fixture TO 'commerce_app'@'%'"
assert_equal 'DEAD:REVOKED:1' "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(lifecycle_state, ':', auth_invalidation_state, ':', closed_at IS NOT NULL) FROM eval_sandbox WHERE sandbox_id = 'sandbox-fixture-failure'")" \
  "fixture failure compensation state"
assert_equal 1 "$(mysql_query auth_app "$auth_app_password" commerce_db \
  "SELECT COUNT(*) FROM auth_eval_test_principal WHERE sandbox_id = 'sandbox-fixture-failure' AND state = 'REVOKED'")" \
  "fixture failure revoked identity count"

# Payment-order creation and activation are one transaction; a denied insert leaves no order.
activation_failure_order_id='00000000-0000-0000-0000-000000000109'
mysql_query root "$root_password" commerce_db \
  "REVOKE INSERT ON commerce_db.standard_order FROM 'commerce_app'@'%'"
assert_status 503 "payment fixture activation failure rolls back and compensates" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: reset-payment-activation-failure' \
  --header 'Content-Type: application/json' \
  --data "$(payment_reset_body sandbox-payment-activation-failure \
    case-payment-activation-failure "$activation_failure_order_id")"
mysql_query root "$root_password" '' \
  "GRANT INSERT ON commerce_db.standard_order TO 'commerce_app'@'%'"
assert_equal 'DEAD:REVOKED:1' "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(lifecycle_state, ':', auth_invalidation_state, ':', closed_at IS NOT NULL) FROM eval_sandbox WHERE sandbox_id = 'sandbox-payment-activation-failure'")" \
  "payment activation failure compensation state"
assert_equal 0 "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM standard_order WHERE order_id = '$activation_failure_order_id'")" \
  "payment activation failure leaves no order"

# Auth commits provisioning but every response is lost. A commerce restart must recover by key.
uv run python scripts/drop_response_proxy.py \
  --port 0 --upstream "http://127.0.0.1:$auth_port" \
  --path-prefix /internal/eval/test-principals/provision --drop-count 20 \
  >>"$tmp_dir/drop-proxy.log" 2>&1 &
drop_proxy_pid=$!
process_bound_port drop_proxy_port proxy "$drop_proxy_pid" "$tmp_dir/drop-proxy.log" 0
wait_http "http://127.0.0.1:$drop_proxy_port/auth/jwks" "$drop_proxy_pid" "$tmp_dir/drop-proxy.log"
stop_process commerce_pid "$commerce_pid"
start_commerce evaluation "http://127.0.0.1:$drop_proxy_port"
assert_status 502 "lost provisioning response cannot activate sandbox" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: reset-response-loss' \
  --header 'Content-Type: application/json' \
  --data "$(reset_body sandbox-response-loss case-response-loss never-active)"
assert_equal 1 "$(mysql_query auth_app "$auth_app_password" commerce_db \
  "SELECT COUNT(*) FROM auth_eval_test_principal WHERE sandbox_id = 'sandbox-response-loss' AND state = 'PROVISIONED'")" \
  "lost response leaves one provisioned auth identity"
assert_equal 'DEAD:UNPROVISIONED:1' "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(lifecycle_state, ':', auth_invalidation_state, ':', closed_at IS NULL) FROM eval_sandbox WHERE sandbox_id = 'sandbox-response-loss'")" \
  "lost response leaves a fail-closed registry pending durable handle recovery"
stop_process commerce_pid "$commerce_pid"
stop_process drop_proxy_pid "$drop_proxy_pid"
start_commerce evaluation "http://127.0.0.1:$auth_port"
for _ in {1..15}; do
  response_loss_state="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(auth_invalidation_state, ':', closed_at IS NOT NULL) FROM eval_sandbox WHERE sandbox_id = 'sandbox-response-loss'")"
  [[ "$response_loss_state" == 'REVOKED:1' ]] && break
  sleep 1
done
test "$response_loss_state" = 'REVOKED:1'
test "$(mysql_query auth_app "$auth_app_password" commerce_db \
  "SELECT state FROM auth_eval_test_principal WHERE sandbox_id = 'sandbox-response-loss'")" = REVOKED

# Completion becomes DEAD immediately but cannot claim safe success while auth is unavailable.
reset_sandbox sandbox-revoke-retry case-revoke-retry reset-revoke-retry retry-product
retry_handle="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" testUserHandle)"
stop_process auth_pid "$auth_pid"
assert_status 503 "completion revocation outage cannot report success" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-revoke-retry/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: complete-revoke-retry' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-revoke-retry"}'
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(lifecycle_state, ':', closed_at IS NULL) FROM eval_sandbox WHERE sandbox_id = 'sandbox-revoke-retry'")" = 'DEAD:1'
start_auth evaluation
stop_process commerce_pid "$commerce_pid"
start_commerce evaluation "http://127.0.0.1:$auth_port"
sleep 2
assert_status 200 "completion retry converges after auth recovery" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-revoke-retry/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: complete-revoke-retry' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-revoke-retry"}'
test "$(mysql_query auth_app "$auth_app_password" commerce_db \
  "SELECT state FROM auth_eval_test_principal WHERE opaque_handle = '$retry_handle'")" = REVOKED

# Force only the persisted due/expiry clocks, restart commerce, and let the bounded janitor close it.
reset_sandbox sandbox-expiry case-expiry reset-expiry expiry-product
expiry_handle="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" testUserHandle)"
mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE eval_sandbox SET expires_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)), cleanup_due_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE sandbox_id = 'sandbox-expiry'"
stop_process commerce_pid "$commerce_pid"
start_commerce evaluation "http://127.0.0.1:$auth_port"
for _ in {1..15}; do
  expiry_state="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(lifecycle_state, ':', auth_invalidation_state, ':', closed_at IS NOT NULL) FROM eval_sandbox WHERE sandbox_id = 'sandbox-expiry'")"
  [[ "$expiry_state" == 'DEAD:REVOKED:1' ]] && break
  sleep 1
done
test "$expiry_state" = 'DEAD:REVOKED:1'
test "$(mysql_query auth_app "$auth_app_password" commerce_db \
  "SELECT state FROM auth_eval_test_principal WHERE opaque_handle = '$expiry_handle'")" = REVOKED
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT COUNT(*) FROM eval_sandbox_product_fixture WHERE sandbox_id = 'sandbox-expiry'")" = 0

for private_value in \
  "$management_password" "$commerce_service_password" "$evaluator_password" \
  "$agent_service_password" "$mock_payment_secret" "$payment_token" "$payment_action_token" \
  "$payment_signature"; do
  if grep -Fq "$private_value" "$tmp_dir/auth.log" "$tmp_dir/commerce.log" "$tmp_dir/agent.log"; then
    echo "Private evaluation credential leaked into service logs." >&2
    exit 1
  fi
done
for private_marker in \
  cb103-private-user-text cb103-private-feedback-comment cb103-private-partial-input \
  cb103-private-retrieval-input cb103-private-sufficient-input cb103-private-source-title \
  cb103-private-source-excerpt cb103-private-provider-input private-provider \
  cb105-private-callback-metadata; do
  if grep -Fq "$private_marker" \
    "$tmp_dir/auth.log" "$tmp_dir/commerce.log" "$tmp_dir/agent.log" "$tmp_dir/model.log"; then
    echo "Private CB-103 evidence marker leaked into service logs." >&2
    exit 1
  fi
done
if grep -Eq 'string argument should contain only ASCII|Traceback.*authorize_evaluator' \
  "$tmp_dir/agent.log"; then
  echo "Basic credential parser leaked internal exception text into service logs." >&2
  exit 1
fi

echo "CB-101 through CB-105 evaluation lifecycle, evidence, payment, profile, grant, restart, liveness, and redaction integration passed."
