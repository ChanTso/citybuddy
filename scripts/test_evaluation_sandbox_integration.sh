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
  echo "Verified value: $label"
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
        | grep -E 'evaluation_request_rejected .*reason_code=' >&2 || true
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

assert_status_reason() {
  local expected="$1"
  local reason="$2"
  local public_error="$3"
  local label="$4"
  shift 4
  local status
  local commerce_log_start
  local request_logs
  local reason_count
  commerce_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  status="$(request_status "$tmp_dir/http-response.json" "$@")"
  request_logs="$(
    tail -n "+$((commerce_log_start + 1))" "$tmp_dir/commerce.log" \
      | grep 'evaluation_request_rejected' || true
  )"
  if [[ "$status" != "$expected" ]]; then
    echo "Unexpected HTTP status for $label: $status" >&2
    cat "$tmp_dir/http-response.json" >&2
    printf '%s\n' "$request_logs" >&2
    exit 1
  fi
  assert_equal "$public_error" \
    "$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" error)" \
    "$label exposes only its fixed public response"
  reason_count="$(printf '%s\n' "$request_logs" | grep -c "reason_code=$reason" || true)"
  assert_equal 1 "$reason_count" "$label has one exact server-only attribution"
  if grep -Fq "$reason" "$tmp_dir/http-response.json"; then
    echo "$label leaked its server-only attribution." >&2
    exit 1
  fi
  echo "Verified HTTP $expected with reason $reason: $label"
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
  local action_pending_ttl="${3:-15m}"
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
      --citybuddy.refund.lock-wait-timeout-seconds=1
      --citybuddy.refund.maximum-observation-attempts=2
      --citybuddy.refund.observation-backoff=25ms
      --citybuddy.actions.enabled=true
      --citybuddy.actions.required-scope=refund:create
      --citybuddy.actions.pending-ttl="$action_pending_ttl"
      --citybuddy.actions.lock-wait-timeout-seconds=1
      --citybuddy.actions.maximum-observation-attempts=2
      --citybuddy.actions.observation-backoff=25ms
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
  local tools_url="${2:-http://127.0.0.1:$commerce_port}"
  local log_offset
  port_log_offset log_offset "$tmp_dir/agent.log"
  AGENT_PORT=0 \
  AGENT_IDENTITY_ENABLED=true \
  AGENT_EVALUATION_ENABLED="$evaluation_enabled" \
  AGENT_EVALUATION_CLIENT_ID=evaluation-manager \
  AGENT_EVALUATION_CLIENT_SECRET="$management_password" \
  CITYBUDDY_METRICS_ENABLED=true \
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
  AGENT_EXCHANGE_SCOPES='catalog:read refund:create' \
  AGENT_MODEL_PROXY_URL="http://127.0.0.1:$proxy_port" \
  AGENT_COMMERCE_TOOLS_URL="$tools_url" \
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
  printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s' \
    "$mock_payment_key" "$timestamp" "$idempotency_key" "$event_id" "$correlation_id" \
    "$order_id" "$amount_minor" "$currency" "$outcome" "$sandbox_id" "$session_id" "$trace_id" \
    "$operation_id" \
    | openssl dgst -sha256 -hmac "$mock_payment_secret" -hex \
    | awk '{print $NF}'
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
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access

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
  retrieval_decision retrieval_evidence pending_action_reference; do
  grep -Fq "\`cs_db\`.\`$table\`" <<<"$agent_grants"
done
assert_equal 'INSERT,SELECT' \
  "$(mysql_query root "$root_password" information_schema \
    "SELECT GROUP_CONCAT(privilege_type ORDER BY privilege_type) FROM table_privileges WHERE grantee = \"'agent_app'@'%'\" AND table_schema = 'cs_db' AND table_name = 'pending_action_reference'")" \
  "agent PendingAction table privileges are exact"
assert_equal 'resolution_trace_id:UPDATE,resolution_turn_id:UPDATE,resolved_at:UPDATE,state:UPDATE' \
  "$(mysql_query root "$root_password" information_schema \
    "SELECT GROUP_CONCAT(CONCAT(column_name, ':', privilege_type) ORDER BY column_name, privilege_type) FROM column_privileges WHERE grantee = \"'agent_app'@'%'\" AND table_schema = 'cs_db' AND table_name = 'pending_action_reference'")" \
  "agent PendingAction mutable-column privileges are exact"
assert_mysql_fails "agent runtime cannot mutate immutable PendingAction content" \
  mysql_query agent_app "$agent_app_password" cs_db \
  "UPDATE pending_action_reference SET argument_commitment = REPEAT('f', 64) WHERE pending_action_id = '00000000-0000-0000-0000-000000000000'"
assert_mysql_fails "agent runtime cannot delete PendingAction references" \
  mysql_query agent_app "$agent_app_password" cs_db \
  "DELETE FROM pending_action_reference WHERE pending_action_id = '00000000-0000-0000-0000-000000000000'"
assert_mysql_fails "agent runtime cannot update immutable support events" \
  mysql_query agent_app "$agent_app_password" cs_db \
  "UPDATE support_event SET event_type = 'TURN_FAILED' WHERE event_id = '00000000-0000-0000-0000-000000000000'"
assert_mysql_fails "agent runtime cannot delete immutable support events" \
  mysql_query agent_app "$agent_app_password" cs_db \
  "DELETE FROM support_event WHERE event_id = '00000000-0000-0000-0000-000000000000'"
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
payment_fault_inventory="$tmp_dir/payment-fault-inventory.tsv"
java -cp commerce-service/target/test-classes:commerce-service/target/classes \
  io.citybuddy.commerce.payment.EvaluationPaymentFaultInventoryCommand \
  >"$payment_fault_inventory"
if [[ ! -s "$payment_fault_inventory" ]]; then
  echo "Committed-payment fault inventory is empty." >&2
  exit 1
fi

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
assert_status_reason 409 TOOL_AUDIT_OPERATION_CONFLICT Conflict \
  "migrated legacy operation rejects conflicting replay intent" \
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
reset_payment_sandbox sandbox-payment case-payment reset-payment "$payment_order_id" 3600
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
  --maximum-expiry "$(date -u -v+3601S +%s 2>/dev/null || date -u -d '+3601 seconds' +%s)" \
  --output "$tmp_dir/payment-direct.json"
payment_subject="$(uv run python scripts/read_json_field.py "$tmp_dir/payment-direct.json" subject)"
payment_observer_credentials_issued_at="$(date +%s)"

refresh_payment_observer_credentials() {
  local now
  now="$(date +%s)"
  if ((now - payment_observer_credentials_issued_at < 300)); then
    return
  fi
  assert_status 200 "refresh main sandbox direct token" \
    --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
    --user "evaluation-client:$evaluator_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-main' \
    --header 'Content-Type: application/json' \
    --data "{\"handle\":\"$main_handle\"}"
  direct_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
  assert_status 200 "refresh payment observer evaluation token" \
    --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
    --user "evaluation-client:$evaluator_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment' \
    --header 'Content-Type: application/json' \
    --data "{\"handle\":\"$payment_handle\"}"
  payment_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
  payment_observer_credentials_issued_at="$now"
}

payment_start_visibility_fields=(
  order_id
  sandbox_id
  user_subject
  evaluation_owner_handle
)
payment_start_visibility_order_id='00000000-0000-0000-0000-000000000220'
payment_start_visibility_moved_order_id='00000000-0000-0000-0000-000000000221'
payment_start_visibility_other_order_id='00000000-0000-0000-0000-000000000222'
payment_start_visibility_mismatch_order_id='00000000-0000-0000-0000-000000000223'
payment_start_visibility_direct_order_id='00000000-0000-0000-0000-000000000224'
payment_start_visibility_unknown_order_id='00000000-0000-0000-0000-000000000225'
payment_start_visibility_fault_sql=(
  "order_id = '$payment_start_visibility_moved_order_id'"
  "sandbox_id = 'sandbox-main'"
  "user_subject = 'visibility-damaged-owner'"
  "evaluation_owner_handle = 'short'"
)
assert_equal 4 "${#payment_start_visibility_fields[@]}" \
  "payment-start visibility metadata has four finite persistent inputs"
assert_equal 4 "${#payment_start_visibility_fault_sql[@]}" \
  "payment-start visibility injection metadata covers every input"

create_payment_start_visibility_order() {
  local order_id="$1"
  local owner_sql="$2"
  local sandbox_sql="$3"
  local handle_sql="$4"
  mysql_query root "$root_password" commerce_db "
    DELETE FROM standard_order
    WHERE order_id IN ('$order_id', '$payment_start_visibility_moved_order_id');
    INSERT INTO standard_order
      (order_id, user_subject, sandbox_id, evaluation_owner_handle, product_id, product_name,
       unit_price_minor, currency, quantity, total_price_minor, product_version, status,
       state_version, created_at)
    SELECT '$order_id', $owner_sql, $sandbox_sql, $handle_sql, product_id,
      CONCAT(product_name, ' start-visibility'), unit_price_minor, currency, quantity,
      total_price_minor, product_version, status, state_version, created_at
    FROM standard_order WHERE order_id = '$payment_order_id';
  "
}

payment_start_visibility_effects() {
  mysql_query root "$root_password" commerce_db "
    SELECT CONCAT(
      (SELECT COUNT(*) FROM mock_payment_attempt
        WHERE order_id IN ('$payment_start_visibility_order_id',
          '$payment_start_visibility_moved_order_id',
          '$payment_start_visibility_other_order_id',
          '$payment_start_visibility_mismatch_order_id',
          '$payment_start_visibility_direct_order_id')), ':',
      (SELECT COUNT(*) FROM mock_payment_callback c JOIN mock_payment_attempt a
        ON a.attempt_id = c.attempt_id
        WHERE a.order_id IN ('$payment_start_visibility_order_id',
          '$payment_start_visibility_moved_order_id',
          '$payment_start_visibility_other_order_id',
          '$payment_start_visibility_mismatch_order_id',
          '$payment_start_visibility_direct_order_id')), ':',
      (SELECT COUNT(*) FROM inventory_ledger
        WHERE order_id IN ('$payment_start_visibility_order_id',
          '$payment_start_visibility_moved_order_id',
          '$payment_start_visibility_other_order_id',
          '$payment_start_visibility_mismatch_order_id',
          '$payment_start_visibility_direct_order_id')), ':',
      (SELECT COUNT(*) FROM mock_refund
        WHERE order_id IN ('$payment_start_visibility_order_id',
          '$payment_start_visibility_moved_order_id',
          '$payment_start_visibility_other_order_id',
          '$payment_start_visibility_mismatch_order_id',
          '$payment_start_visibility_direct_order_id')), ':',
      (SELECT COUNT(*) FROM commerce_outbox))
  "
}

payment_start_visibility_rows() {
  mysql_query root "$root_password" commerce_db "
    SELECT COALESCE(GROUP_CONCAT(CONCAT_WS('|', order_id, user_subject, sandbox_id,
      COALESCE(evaluation_owner_handle, '<NULL>'), status, state_version)
      ORDER BY order_id SEPARATOR ';'), '-')
    FROM standard_order
    WHERE order_id IN ('$payment_start_visibility_order_id',
      '$payment_start_visibility_moved_order_id',
      '$payment_start_visibility_other_order_id',
      '$payment_start_visibility_mismatch_order_id',
      '$payment_start_visibility_direct_order_id')
  "
}

payment_start_reason_since() {
  local log_start="$1"
  local log_end="$2"
  sed -n "$((log_start + 1)),${log_end}p" "$tmp_dir/commerce.log" 2>/dev/null \
    | sed -n 's/.*mock_payment_request_rejected reason_code=\([^ ]*\).*/\1/p' \
    | tail -n 1
}

evaluation_reasons_since() {
  local log_start="$1"
  local log_end="$2"
  sed -n "$((log_start + 1)),${log_end}p" "$tmp_dir/commerce.log" 2>/dev/null \
    | sed -n 's/.*evaluation_request_rejected reason_code=\([^ ]*\).*/\1/p'
}

state_payment_integrity_reason='STATE_COMMITTED_PAYMENT_TRUTH_INCONSISTENT'
audit_payment_integrity_reason='AUDIT_COMMITTED_PAYMENT_TRUTH_INCONSISTENT'
state_audit_integrity_reason='STATE_EVALUATION_AUDIT_TRUTH_INCONSISTENT'
audit_audit_integrity_reason='AUDIT_EVALUATION_AUDIT_TRUTH_INCONSISTENT'

evaluation_payment_origin_reason() {
  local route="$1"
  local origin
  origin="$(mysql_query root "$root_password" commerce_db "
SELECT CASE
  WHEN COUNT(*) = 0 THEN 'PAYMENT_CALLBACK'
  WHEN SUM(CASE WHEN product_root = payment_root OR payment_root = 0 THEN 1 ELSE 0 END) > 0
    THEN 'AMBIGUOUS_OR_ORPHAN'
  ELSE 'PAYMENT_CALLBACK'
END
FROM (
  SELECT
    EXISTS (
      SELECT 1
      FROM eval_commerce_product_observation product_root
      WHERE product_root.observation_id = audit.audit_reference_id
         OR product_root.product_id = audit.entity_id
         OR (
           product_root.sandbox_id <=> audit.sandbox_id
           AND product_root.support_session_id <=> audit.support_session_id
           AND product_root.trace_id <=> audit.trace_id
           AND product_root.operation_id <=> audit.operation_id
         )
    ) AS product_root,
    EXISTS (
      SELECT 1
      FROM mock_payment_callback callback_root
      LEFT JOIN mock_payment_attempt attempt_root
        ON attempt_root.attempt_id = callback_root.attempt_id
      WHERE audit.audit_reference_id = SHA2(CONCAT(
              callback_root.sandbox_id, CHAR(10),
              callback_root.support_session_id, CHAR(10),
              callback_root.trace_id, CHAR(10),
              callback_root.operation_id, CHAR(10),
              callback_root.callback_event_id, CHAR(10),
              attempt_root.state_version), 256)
         OR audit.entity_id = callback_root.callback_event_id
         OR (
           audit.sandbox_id <=> callback_root.sandbox_id
           AND audit.support_session_id <=> callback_root.support_session_id
           AND audit.trace_id <=> callback_root.trace_id
           AND audit.operation_id <=> callback_root.operation_id
         )
    ) AS payment_root
  FROM eval_commerce_audit_reference audit
  WHERE audit.audit_reference_id = '$payment_audit_reference_id'
     OR audit.entity_id = '$payment_event_id'
     OR (
       audit.sandbox_id = 'sandbox-payment'
       AND audit.support_session_id = '$payment_session'
       AND audit.trace_id = '$payment_trace'
       AND audit.operation_id = '$payment_operation'
     )
) authoritative_origin
")"
  if [[ "$route:$origin" == STATE:PAYMENT_CALLBACK ]]; then
    printf '%s' "$state_payment_integrity_reason"
  elif [[ "$route:$origin" == AUDIT:PAYMENT_CALLBACK ]]; then
    printf '%s' "$audit_payment_integrity_reason"
  elif [[ "$route" == STATE ]]; then
    printf '%s' "$state_audit_integrity_reason"
  else
    printf '%s' "$audit_audit_integrity_reason"
  fi
}

assert_no_evaluation_integrity_reason_since() {
  local log_start="$1"
  local log_end="$2"
  local description="$3"
  if evaluation_reasons_since "$log_start" "$log_end" \
    | grep -Eq '^(STATE|AUDIT)_(COMMITTED_PAYMENT|EVALUATION_AUDIT)_TRUTH_INCONSISTENT$'; then
    echo "Successful evaluation request acquired integrity attribution: $description" >&2
    evaluation_reasons_since "$log_start" "$log_end" >&2
    exit 1
  fi
}

assert_payment_start_concealed() {
  local description="$1"
  local order_id="$2"
  local idempotency_key="$3"
  local response_file="$4"
  local effects_before rows_before log_start log_end status reason effects_after rows_after
  effects_before="$(payment_start_visibility_effects)"
  rows_before="$(payment_start_visibility_rows)"
  log_start="$(wc -l <"$tmp_dir/commerce.log")"
  status="$(request_status "$response_file" \
    --request POST "http://127.0.0.1:$commerce_port/api/orders/$order_id/mock-payment" \
    --header "Authorization: Bearer $payment_token" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment' \
    --header "Idempotency-Key: $idempotency_key" \
    --header 'Content-Type: application/json' \
    --data '{"amountMinor":1800,"currency":"CNY"}')"
  log_end="$(wc -l <"$tmp_dir/commerce.log")"
  reason="$(payment_start_reason_since "$log_start" "$log_end")"
  effects_after="$(payment_start_visibility_effects)"
  rows_after="$(payment_start_visibility_rows)"
  assert_equal 404 "$status" "$description is concealed"
  assert_equal CONCEALED_NOT_FOUND "$reason" "$description has concealment attribution"
  cmp "$tmp_dir/payment-start-visibility-unknown.json" "$response_file"
  assert_equal "$effects_before" "$effects_after" "$description creates no durable effects"
  assert_equal "$rows_before" "$rows_after" "$description does not bind or mutate the order"
  if sed -n "$((log_start + 1)),${log_end}p" "$tmp_dir/commerce.log" \
    | grep -Eq 'SANDBOX_NOT_ACTIVE|PAYMENT_SANDBOX_NOT_ACTIVE|evaluation_owner_handle|short'; then
    echo "Payment-start concealment leaked an internal reason for $description." >&2
    exit 1
  fi
}

visibility_unknown_effects_before="$(payment_start_visibility_effects)"
visibility_unknown_log_start="$(wc -l <"$tmp_dir/commerce.log")"
payment_start_visibility_unknown_status="$(request_status \
  "$tmp_dir/payment-start-visibility-unknown.json" \
  --request POST \
  "http://127.0.0.1:$commerce_port/api/orders/$payment_start_visibility_unknown_order_id/mock-payment" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Idempotency-Key: visibility-unknown' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1800,"currency":"CNY"}')"
visibility_unknown_log_end="$(wc -l <"$tmp_dir/commerce.log")"
assert_equal 404 "$payment_start_visibility_unknown_status" \
  "payment-start unknown visibility baseline"
assert_equal CONCEALED_NOT_FOUND \
  "$(payment_start_reason_since "$visibility_unknown_log_start" "$visibility_unknown_log_end")" \
  "payment-start unknown visibility attribution"
assert_equal "$visibility_unknown_effects_before" "$(payment_start_visibility_effects)" \
  "payment-start unknown candidate creates no durable effects"
jq -e \
  '. == {"category":"NOT_FOUND","message":"Payment order is missing or not owned"}' \
  "$tmp_dir/payment-start-visibility-unknown.json" >/dev/null

create_payment_start_visibility_order "$payment_start_visibility_other_order_id" \
  "'visibility-other-owner'" "'sandbox-payment'" "'$payment_handle'"
assert_payment_start_concealed "payment-start true other owner" \
  "$payment_start_visibility_other_order_id" visibility-other \
  "$tmp_dir/payment-start-visibility-other.json"

create_payment_start_visibility_order "$payment_start_visibility_order_id" \
  "'eval-handle:$payment_handle'" "'sandbox-payment'" "'$payment_handle'"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET evaluation_owner_handle = 'short' WHERE order_id = '$payment_start_visibility_order_id'"
assert_payment_start_concealed "payment-start malformed fixture handle" \
  "$payment_start_visibility_order_id" visibility-malformed \
  "$tmp_dir/payment-start-visibility-malformed.json"

create_payment_start_visibility_order "$payment_start_visibility_mismatch_order_id" \
  "'eval-handle:$payment_handle'" "'sandbox-payment'" "REPEAT('B', 43)"
assert_payment_start_concealed "payment-start valid handle with mismatched fixture subject" \
  "$payment_start_visibility_mismatch_order_id" visibility-mismatch \
  "$tmp_dir/payment-start-visibility-mismatch.json"

payment_start_visibility_cell_count=0
for ((left = 0; left < ${#payment_start_visibility_fields[@]}; left++)); do
  create_payment_start_visibility_order "$payment_start_visibility_order_id" \
    "'eval-handle:$payment_handle'" "'sandbox-payment'" "'$payment_handle'"
  mysql_query root "$root_password" commerce_db "
    UPDATE standard_order SET ${payment_start_visibility_fault_sql[$left]}
    WHERE order_id = '$payment_start_visibility_order_id';
  "
  assert_payment_start_concealed \
    "payment-start visibility single ${payment_start_visibility_fields[$left]}" \
    "$payment_start_visibility_order_id" "visibility-single-$left" \
    "$tmp_dir/payment-start-visibility-single-$left.json"
  payment_start_visibility_cell_count=$((payment_start_visibility_cell_count + 1))
done
for ((left = 0; left < ${#payment_start_visibility_fields[@]}; left++)); do
  for ((right = left + 1; right < ${#payment_start_visibility_fields[@]}; right++)); do
    create_payment_start_visibility_order "$payment_start_visibility_order_id" \
      "'eval-handle:$payment_handle'" "'sandbox-payment'" "'$payment_handle'"
    mysql_query root "$root_password" commerce_db "
      UPDATE standard_order SET ${payment_start_visibility_fault_sql[$left]},
        ${payment_start_visibility_fault_sql[$right]}
      WHERE order_id = '$payment_start_visibility_order_id';
    "
    assert_payment_start_concealed \
      "payment-start visibility pair ${payment_start_visibility_fields[$left]} + ${payment_start_visibility_fields[$right]}" \
      "$payment_start_visibility_order_id" "visibility-pair-$left-$right" \
      "$tmp_dir/payment-start-visibility-pair-$left-$right.json"
    payment_start_visibility_cell_count=$((payment_start_visibility_cell_count + 1))
  done
done
assert_equal 10 "$payment_start_visibility_cell_count" \
  "payment-start visibility matrix covers four singles and six pairs"

create_payment_start_visibility_order "$payment_start_visibility_order_id" \
  "'eval-handle:$payment_handle'" "'sandbox-payment'" "'$payment_handle'"
assert_status 409 "valid unbound fixture owner is visible before binding" \
  --request POST \
  "http://127.0.0.1:$commerce_port/api/orders/$payment_start_visibility_order_id/mock-payment" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Idempotency-Key: visibility-valid-unbound' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1801,"currency":"CNY"}'
test "$(mysql_query root "$root_password" commerce_db \
  "SELECT user_subject FROM standard_order WHERE order_id = '$payment_start_visibility_order_id'")" = \
  "eval-handle:$payment_handle"

create_payment_start_visibility_order "$payment_start_visibility_direct_order_id" \
  "'$payment_subject'" "'sandbox-payment'" "'short'"
assert_status 409 "direct owner visibility does not parse malformed fixture provenance" \
  --request POST \
  "http://127.0.0.1:$commerce_port/api/orders/$payment_start_visibility_direct_order_id/mock-payment" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Idempotency-Key: visibility-direct-owner' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1801,"currency":"CNY"}'

mysql_query root "$root_password" commerce_db "
  DELETE FROM standard_order WHERE order_id IN (
    '$payment_start_visibility_order_id',
    '$payment_start_visibility_moved_order_id',
    '$payment_start_visibility_other_order_id',
    '$payment_start_visibility_mismatch_order_id',
    '$payment_start_visibility_direct_order_id');
"
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
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET evaluation_owner_handle = 'short' WHERE order_id = '$payment_order_id'"
assert_status 200 "visible attempt replay does not reclassify malformed fixture provenance" \
  --request POST "http://127.0.0.1:$commerce_port/api/orders/$payment_order_id/mock-payment" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Idempotency-Key: payment-evaluation' \
  --header 'Content-Type: application/json' \
  --data '{"amountMinor":1800,"currency":"CNY"}'
assert_equal "$payment_attempt_id" \
  "$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" attemptId)" \
  "visible attempt replay returns the existing attempt"
mysql_query root "$root_password" commerce_db \
  "UPDATE standard_order SET evaluation_owner_handle = '$payment_handle' WHERE order_id = '$payment_order_id'"

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

payment_caller_names=(
  start
  production-callback
  evaluation-callback
  state
  audit
)
payment_caller_trust_boundaries=(
  OWNER_CONCEALING_PUBLIC
  AUTHENTICATED_CALLBACK
  AUTHENTICATED_CALLBACK
  SANDBOX_WIDE_INTERNAL_EVALUATOR
  SANDBOX_WIDE_INTERNAL_EVALUATOR
)
payment_caller_visibility_locators=(
  'attempt.user_subject+request_idempotency_key|order.order_id+user_subject+sandbox_id'
  'verified_signature+production_callback_request_locators'
  'verified_signature+callback_request_locators'
  'management_credential+sandbox_id'
  'management_credential+sandbox_id+support_session_id'
)
payment_caller_visibility_sql=(
  "SELECT CONCAT(
    EXISTS(SELECT 1 FROM mock_payment_attempt
      WHERE user_subject = '$payment_subject'
        AND request_idempotency_key = 'payment-evaluation'), ':',
    EXISTS(SELECT 1 FROM standard_order
      WHERE order_id = '$payment_order_id' AND user_subject = '$payment_subject'
        AND sandbox_id = 'sandbox-payment'))"
  "SELECT '1'"
  "SELECT '1'"
  "SELECT '1'"
  "SELECT '1'"
)
assert_equal 5 "${#payment_caller_names[@]}" "CB-116 active payment caller name inventory"
assert_equal 5 "${#payment_caller_trust_boundaries[@]}" \
  "CB-116 active payment caller trust-boundary inventory"
assert_equal 5 "${#payment_caller_visibility_locators[@]}" \
  "CB-116 active payment caller visibility-locator inventory"
assert_equal 5 "${#payment_caller_visibility_sql[@]}" \
  "CB-116 active payment caller visibility oracle inventory"
payment_start_caller_index=0

refresh_payment_callback_signature() {
  payment_timestamp="$(date +%s)"
  payment_signature="$(sign_payment_callback "$payment_timestamp" "$payment_callback_key" \
    "$payment_event_id" "$payment_correlation_id" "$payment_order_id" sandbox-payment \
    "$payment_session" "$payment_trace" "$payment_operation")"
}

assert_payment_truth_fails_closed() {
  local description="$1"
  local callback_status start_status state_status audit_status
  local start_visibility start_expected=409
  local durable_before durable_after commerce_log_start=0 start_log_end=0 callback_log_end=0
  local state_log_start=0 state_log_end=0 audit_log_start=0 audit_log_end=0
  local start_reason callback_reason state_reason audit_reason
  local expected_state_reason expected_audit_reason
  refresh_payment_observer_credentials
  refresh_payment_callback_signature
  durable_before="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM mock_payment_attempt WHERE order_id = '$payment_order_id'), ':',
       (SELECT COALESCE(GROUP_CONCAT(CONCAT(status, '/', state_version)
          ORDER BY status, state_version SEPARATOR ','), '-')
          FROM standard_order WHERE order_id = '$payment_order_id'), ':',
       (SELECT COALESCE(GROUP_CONCAT(CONCAT(state, '/', state_version)
          ORDER BY state, state_version SEPARATOR ','), '-')
          FROM mock_payment_attempt
          WHERE request_idempotency_key = 'payment-evaluation'), ':',
       (SELECT COUNT(*) FROM mock_payment_callback
          WHERE callback_idempotency_key = '$payment_callback_key'), ':',
       (SELECT COUNT(*) FROM inventory_ledger WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM commerce_outbox))")"
  if [[ -f "$tmp_dir/commerce.log" ]]; then
    commerce_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  fi
  start_status="$(request_status "$tmp_dir/payment-start-classification.json" \
    --request POST "http://127.0.0.1:$commerce_port/api/orders/$payment_order_id/mock-payment" \
    --header "Authorization: Bearer $payment_token" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment' \
    --header 'Idempotency-Key: payment-evaluation' \
    --header 'Content-Type: application/json' \
    --data '{"amountMinor":1800,"currency":"CNY"}')"
  if [[ -f "$tmp_dir/commerce.log" ]]; then
    start_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  fi
  start_reason="$(payment_start_reason_since "$commerce_log_start" "$start_log_end")"
  callback_status="$(request_status "$tmp_dir/payment-callback-classification.json" \
    --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
    --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
    --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
    --header "X-Mock-Payment-Signature: $payment_signature" \
    --header "Idempotency-Key: $payment_callback_key" \
    --header 'Content-Type: application/json' \
    --data "$payment_callback_body")"
  if [[ -f "$tmp_dir/commerce.log" ]]; then
    callback_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  fi
  callback_reason="$(payment_start_reason_since "$start_log_end" "$callback_log_end")"
  state_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  state_status="$(request_status "$tmp_dir/payment-state-classification.json" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment')"
  state_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  state_reason="$(evaluation_reasons_since "$state_log_start" "$state_log_end")"
  audit_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  audit_status="$(request_status "$tmp_dir/payment-audit-classification.json" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$payment_session" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment')"
  audit_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  audit_reason="$(evaluation_reasons_since "$audit_log_start" "$audit_log_end")"
  expected_state_reason="$(evaluation_payment_origin_reason STATE)"
  expected_audit_reason="$(evaluation_payment_origin_reason AUDIT)"
  durable_after="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM mock_payment_attempt WHERE order_id = '$payment_order_id'), ':',
       (SELECT COALESCE(GROUP_CONCAT(CONCAT(status, '/', state_version)
          ORDER BY status, state_version SEPARATOR ','), '-')
          FROM standard_order WHERE order_id = '$payment_order_id'), ':',
       (SELECT COALESCE(GROUP_CONCAT(CONCAT(state, '/', state_version)
          ORDER BY state, state_version SEPARATOR ','), '-')
          FROM mock_payment_attempt
          WHERE request_idempotency_key = 'payment-evaluation'), ':',
       (SELECT COUNT(*) FROM mock_payment_callback
          WHERE callback_idempotency_key = '$payment_callback_key'), ':',
       (SELECT COUNT(*) FROM inventory_ledger WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM commerce_outbox))")"
  assert_equal "$durable_before" "$durable_after" \
    "committed-payment observers create zero durable effects: $description"
  start_visibility="$(mysql_query root "$root_password" commerce_db \
    "${payment_caller_visibility_sql[$payment_start_caller_index]}")"
  if [[ "$start_visibility" != *1* ]]; then
    start_expected=404
    cmp "$tmp_dir/payment-start-visibility-unknown.json" \
      "$tmp_dir/payment-start-classification.json"
  fi
  if [[ "$start_status:$callback_status:$state_status:$audit_status" != \
      "$start_expected:409:409:409" ]]; then
    echo "Cross-path visibility/integrity mismatch for $description: start=$start_status/$start_visibility callback=$callback_status/1 state=$state_status/1 audit=$audit_status/1" >&2
    for response in payment-start-classification payment-callback-classification \
      payment-state-classification payment-audit-classification; do
      echo "$response-response" >&2
      cat "$tmp_dir/$response.json" >&2
    done
    if [[ -f "$tmp_dir/commerce.log" ]]; then
      echo 'request-rejection-reasons' >&2
      tail -n "+$((commerce_log_start + 1))" "$tmp_dir/commerce.log" \
        | grep -E 'evaluation_request_rejected .*reason_code=' >&2 || true
    fi
    exit 1
  fi
  if [[ "$start_expected" == 409 ]]; then
    assert_equal COMMITTED_PAYMENT_TRUTH_INCONSISTENT "$start_reason" \
      "visible payment-start damage has durable-integrity attribution: $description"
  else
    assert_equal CONCEALED_NOT_FOUND "$start_reason" \
      "concealed payment-start damage has concealment attribution: $description"
    echo "Verified CONCEALED_BY_AUTHORIZATION: caller=start anchors=${payment_caller_visibility_locators[$payment_start_caller_index]} evidence=$start_visibility fault=$description"
  fi
  assert_equal COMMITTED_PAYMENT_TRUTH_INCONSISTENT "$callback_reason" \
    "callback damage has durable-integrity attribution: $description"
  assert_equal "$expected_state_reason" "$state_reason" \
    "state damage has authoritative-origin request-local attribution: $description"
  assert_equal "$expected_audit_reason" "$audit_reason" \
    "audit damage has authoritative-origin request-local attribution: $description"
  for response in payment-start-classification payment-callback-classification \
    payment-state-classification payment-audit-classification; do
    if grep -Eq 'COMMITTED_PAYMENT_TRUTH_INCONSISTENT|CONCEALED_NOT_FOUND' \
      "$tmp_dir/$response.json"; then
      echo "$response leaked a server-only payment reason for $description." >&2
      exit 1
    fi
  done
  echo "Verified visibility × integrity classification $start_expected:409:409:409: $description"
}

assert_payment_audit_reconciliation_fails_closed() {
  local description="$1"
  assert_payment_truth_fails_closed "$description"
}

assert_payment_truth_equivalence_preserving() {
  local description="$1"
  local durable_before durable_after state_log_start state_log_end audit_log_start audit_log_end
  refresh_payment_observer_credentials
  refresh_payment_callback_signature
  durable_before="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM mock_payment_attempt WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM mock_payment_callback
          WHERE callback_idempotency_key = '$payment_callback_key'), ':',
       (SELECT COUNT(*) FROM inventory_ledger WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM eval_commerce_audit_reference
          WHERE entity_type = 'PAYMENT_CALLBACK' AND entity_id = '$payment_event_id'), ':',
       (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM commerce_outbox))")"
  assert_status 200 "$description preserves payment-start replay" \
    --request POST "http://127.0.0.1:$commerce_port/api/orders/$payment_order_id/mock-payment" \
    --header "Authorization: Bearer $payment_token" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment' \
    --header 'Idempotency-Key: payment-evaluation' \
    --header 'Content-Type: application/json' \
    --data '{"amountMinor":1800,"currency":"CNY"}'
  assert_status 200 "$description preserves callback replay" \
    --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
    --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
    --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
    --header "X-Mock-Payment-Signature: $payment_signature" \
    --header "Idempotency-Key: $payment_callback_key" \
    --header 'Content-Type: application/json' \
    --data "$payment_callback_body"
  state_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  assert_status 200 "$description preserves evaluation state" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment'
  state_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  assert_no_evaluation_integrity_reason_since "$state_log_start" "$state_log_end" \
    "$description restored state"
  audit_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  assert_status 200 "$description preserves evaluation audit" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$payment_session" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment'
  audit_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  assert_no_evaluation_integrity_reason_since "$audit_log_start" "$audit_log_end" \
    "$description restored audit"
  durable_after="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM mock_payment_attempt WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM mock_payment_callback
          WHERE callback_idempotency_key = '$payment_callback_key'), ':',
       (SELECT COUNT(*) FROM inventory_ledger WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM eval_commerce_audit_reference
          WHERE entity_type = 'PAYMENT_CALLBACK' AND entity_id = '$payment_event_id'), ':',
       (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':',
       (SELECT COUNT(*) FROM commerce_outbox))")"
  assert_equal "$durable_before" "$durable_after" \
    "$description creates zero durable effects"
  echo "Verified EQUIVALENCE_PRESERVING evaluation transformation: $description"
}

assert_unrelated_payment_audit_reconciliation_fails_closed() {
  local description="$1"
  local state_log_start state_log_end audit_log_start audit_log_end
  refresh_payment_callback_signature
  assert_status 200 "$description does not contaminate exact committed callback replay" \
    --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
    --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
    --header "X-Mock-Payment-Timestamp: $payment_timestamp" \
    --header "X-Mock-Payment-Signature: $payment_signature" \
    --header "Idempotency-Key: $payment_callback_key" \
    --header 'Content-Type: application/json' \
    --data "$payment_callback_body"
  state_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  assert_status 409 "$description rejects evaluation state" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment'
  state_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  assert_equal STATE_EVALUATION_AUDIT_TRUTH_INCONSISTENT \
    "$(evaluation_reasons_since "$state_log_start" "$state_log_end")" \
    "$description state has request-local non-payment audit-integrity attribution"
  audit_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  assert_status 409 "$description rejects evaluation audit" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$payment_session" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment'
  audit_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  assert_equal AUDIT_EVALUATION_AUDIT_TRUTH_INCONSISTENT \
    "$(evaluation_reasons_since "$audit_log_start" "$audit_log_end")" \
    "$description audit has request-local non-payment audit-integrity attribution"
}

assert_audit_totality_fails_closed() {
  local sandbox_id="$1"
  local support_session_id="$2"
  local description="$3"
  local state_log_start state_log_end audit_log_start audit_log_end
  state_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  assert_status 409 "$description rejects evaluation state" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/state" \
    --user "evaluation-manager:$management_password" \
    --header "X-Eval-Sandbox-Id: $sandbox_id"
  state_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  assert_equal STATE_EVALUATION_AUDIT_TRUTH_INCONSISTENT \
    "$(evaluation_reasons_since "$state_log_start" "$state_log_end")" \
    "$description state has request-local non-payment audit-integrity attribution"
  audit_log_start="$(wc -l <"$tmp_dir/commerce.log")"
  assert_status 409 "$description rejects evaluation audit" \
    --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$support_session_id" \
    --user "evaluation-manager:$management_password" \
    --header "X-Eval-Sandbox-Id: $sandbox_id"
  audit_log_end="$(wc -l <"$tmp_dir/commerce.log")"
  assert_equal AUDIT_EVALUATION_AUDIT_TRUTH_INCONSISTENT \
    "$(evaluation_reasons_since "$audit_log_start" "$audit_log_end")" \
    "$description audit has request-local non-payment audit-integrity attribution"
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
payment_attempt_created_at="$(mysql_query root "$root_password" commerce_db \
  "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM mock_payment_attempt WHERE attempt_id = '$payment_attempt_id'")"
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
  quantity = 2, product_version = 1, unit_price_minor = 900,
  total_price_minor = 1800, currency = 'CNY',
  status = 'PAID', state_version = 2,
  sandbox_id = 'sandbox-payment' WHERE order_id = '$payment_order_id';
INSERT INTO mock_payment_attempt (attempt_id, callback_correlation_id, user_subject, order_id,
  order_kind, sandbox_id, request_idempotency_key, intent_hash, amount_minor,
  refunded_amount_minor, currency, state, state_version, succeeded_at, created_at)
VALUES ('$payment_attempt_id', '$payment_correlation_id', '$payment_subject', '$payment_order_id',
  'STANDARD', 'sandbox-payment', 'payment-evaluation', '$payment_attempt_intent_hash', 1800,
  0, 'CNY', 'SUCCEEDED', 2, '$payment_attempt_succeeded_at', '$payment_attempt_created_at')
ON DUPLICATE KEY UPDATE attempt_id = VALUES(attempt_id);
UPDATE mock_payment_attempt SET callback_correlation_id = '$payment_correlation_id',
  user_subject = '$payment_subject', order_id = '$payment_order_id', order_kind = 'STANDARD',
  sandbox_id = 'sandbox-payment', request_idempotency_key = 'payment-evaluation',
  intent_hash = '$payment_attempt_intent_hash',
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

production_order_id='00000000-0000-0000-0000-000000000301'
production_attempt_id='00000000-0000-0000-0000-000000000302'
production_correlation_id='00000000-0000-0000-0000-000000000303'
production_event_id='00000000-0000-0000-0000-000000000304'
production_movement_id='00000000-0000-0000-0000-000000000305'
production_start_key='cb116-production-start'
production_callback_key='cb116-production-callback'
production_subject='cb116-production-owner'
production_created_at="$(mysql_query root "$root_password" commerce_db \
  "SELECT DATE_FORMAT(TIMESTAMPADD(MICROSECOND, 10, '$payment_audit_created_at'), '%Y-%m-%d %H:%i:%s.%f')")"
production_attempt_intent_hash="$(printf '%s\n%s\n%s\n%s\n%s' \
  "$production_order_id" "$production_start_key" 1800 CNY '' \
  | openssl dgst -sha256 -hex | awk '{print $NF}')"
production_order_intent_hash="$(printf '%s:%s:%s:%s' \
  "${#payment_product_id}" "$payment_product_id" 1 1 \
  | openssl dgst -sha256 -hex | awk '{print $NF}')"
production_callback_intent_hash="$(printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s' \
  "$production_event_id" "$production_correlation_id" "$production_order_id" 1800 CNY SUCCEEDED \
  '' '' '' '' "$production_callback_key" \
  | openssl dgst -sha256 -hex | awk '{print $NF}')"
production_callback_body="{\"callbackEventId\":\"$production_event_id\",\"callbackCorrelationId\":\"$production_correlation_id\",\"orderId\":\"$production_order_id\",\"amountMinor\":1800,\"currency\":\"CNY\",\"outcome\":\"SUCCEEDED\"}"

refresh_production_callback_signature() {
  production_timestamp="$(date +%s)"
  production_signature="$(sign_payment_callback "$production_timestamp" \
    "$production_callback_key" "$production_event_id" "$production_correlation_id" \
    "$production_order_id" '' '' '' '')"
}

restore_complete_production_payment_truth() {
  mysql_query root "$root_password" commerce_db "
DELETE FROM order_idempotency
  WHERE order_id IN ('$production_order_id', '$tampered_order_id')
     OR idempotency_key = 'cb116-production-order-origin';
UPDATE mock_payment_attempt SET attempt_id = '$production_attempt_id'
  WHERE attempt_id = '$tampered_attempt_id';
UPDATE standard_order SET order_id = '$production_order_id'
  WHERE order_id = '$tampered_order_id';
INSERT INTO standard_order (order_id, user_subject, product_id, product_name, unit_price_minor,
  currency, quantity, total_price_minor, product_version, status, state_version, created_at)
VALUES ('$production_order_id', '$production_subject', '$payment_product_id',
  'CB-116 production callback matrix', 1800, 'CNY', 1, 1800, 1, 'PAID', 2,
  '$production_created_at')
ON DUPLICATE KEY UPDATE user_subject = VALUES(user_subject), sandbox_id = NULL,
  evaluation_owner_handle = NULL, product_id = VALUES(product_id),
  quantity = VALUES(quantity), product_version = VALUES(product_version),
  unit_price_minor = VALUES(unit_price_minor),
  total_price_minor = VALUES(total_price_minor), currency = VALUES(currency),
  status = VALUES(status), state_version = VALUES(state_version);
INSERT INTO order_idempotency (user_subject, idempotency_key, intent_hash, order_id)
VALUES ('$production_subject', 'cb116-production-order-origin',
  '$production_order_intent_hash', '$production_order_id');
INSERT INTO mock_payment_attempt (attempt_id, callback_correlation_id, user_subject, order_id,
  order_kind, sandbox_id, request_idempotency_key, intent_hash, amount_minor,
  refunded_amount_minor, currency, state, state_version, succeeded_at, created_at)
VALUES ('$production_attempt_id', '$production_correlation_id', '$production_subject',
  '$production_order_id', 'STANDARD', NULL, '$production_start_key',
  '$production_attempt_intent_hash', 1800, 0, 'CNY', 'SUCCEEDED', 2,
  '$production_created_at', '$production_created_at')
ON DUPLICATE KEY UPDATE attempt_id = VALUES(attempt_id);
UPDATE mock_payment_attempt SET callback_correlation_id = '$production_correlation_id',
  user_subject = '$production_subject', order_id = '$production_order_id',
  order_kind = 'STANDARD', sandbox_id = NULL,
  request_idempotency_key = '$production_start_key',
  intent_hash = '$production_attempt_intent_hash', amount_minor = 1800,
  refunded_amount_minor = 0, currency = 'CNY', state = 'SUCCEEDED', state_version = 2,
  succeeded_at = '$production_created_at'
  WHERE attempt_id = '$production_attempt_id';
DELETE FROM mock_payment_callback
  WHERE callback_event_id IN ('$production_event_id', '$tampered_callback_event_id')
     OR callback_idempotency_key IN ('$production_callback_key', 'tampered-callback-key')
     OR attempt_id IN ('$production_attempt_id', '$tampered_callback_attempt_id');
INSERT INTO mock_payment_callback (callback_event_id, callback_idempotency_key, attempt_id,
  callback_correlation_id, sandbox_id, support_session_id, trace_id, operation_id, intent_hash,
  requested_outcome, result_state, created_at)
VALUES ('$production_event_id', '$production_callback_key', '$production_attempt_id',
  '$production_correlation_id', NULL, NULL, NULL, NULL, '$production_callback_intent_hash',
  'SUCCEEDED', 'APPLIED', '$production_created_at');
INSERT INTO inventory_ledger (movement_id, business_event_key, movement_type, order_id,
  reservation_id, activity_id, product_id, sandbox_id, inventory_delta, activity_quota_delta,
  payment_amount_minor, payment_currency)
VALUES ('$production_movement_id', 'mock-payment:$production_attempt_id', 'STANDARD_PAYMENT',
  '$production_order_id', NULL, NULL, '$payment_product_id', NULL, 0, 0, 1800, 'CNY')
ON DUPLICATE KEY UPDATE business_event_key = VALUES(business_event_key),
  movement_type = VALUES(movement_type), order_id = VALUES(order_id),
  reservation_id = VALUES(reservation_id), activity_id = VALUES(activity_id),
  product_id = VALUES(product_id), sandbox_id = VALUES(sandbox_id),
  inventory_delta = VALUES(inventory_delta), activity_quota_delta = VALUES(activity_quota_delta),
  payment_amount_minor = VALUES(payment_amount_minor), payment_currency = VALUES(payment_currency);
"
}

assert_production_callback_truth_fails_closed() {
  local description="$1"
  local durable_before durable_after log_start log_end reason status
  refresh_production_callback_signature
  durable_before="$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM mock_payment_attempt
          WHERE attempt_id IN ('$production_attempt_id', '$tampered_attempt_id')), ':',
       (SELECT COUNT(*) FROM mock_payment_callback
          WHERE callback_event_id IN ('$production_event_id', '$tampered_callback_event_id')
             OR callback_idempotency_key IN ('$production_callback_key', 'tampered-callback-key')
             OR attempt_id IN ('$production_attempt_id', '$tampered_callback_attempt_id')), ':',
       (SELECT COUNT(*) FROM standard_order
          WHERE order_id IN ('$production_order_id', '$tampered_order_id')), ':',
       (SELECT COUNT(*) FROM inventory_ledger
          WHERE movement_id = '$production_movement_id'), ':',
       (SELECT COUNT(*) FROM order_idempotency
          WHERE order_id IN ('$production_order_id', '$tampered_order_id')), ':',
       (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$production_order_id'), ':',
       (SELECT COUNT(*) FROM commerce_outbox))")"
  log_start="$(wc -l <"$tmp_dir/commerce.log")"
  status="$(request_status "$tmp_dir/production-callback-classification.json" \
    --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
    --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
    --header "X-Mock-Payment-Timestamp: $production_timestamp" \
    --header "X-Mock-Payment-Signature: $production_signature" \
    --header "Idempotency-Key: $production_callback_key" \
    --header 'Content-Type: application/json' \
    --data "$production_callback_body")"
  log_end="$(wc -l <"$tmp_dir/commerce.log")"
  reason="$(payment_start_reason_since "$log_start" "$log_end")"
  durable_after="$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM mock_payment_attempt
          WHERE attempt_id IN ('$production_attempt_id', '$tampered_attempt_id')), ':',
       (SELECT COUNT(*) FROM mock_payment_callback
          WHERE callback_event_id IN ('$production_event_id', '$tampered_callback_event_id')
             OR callback_idempotency_key IN ('$production_callback_key', 'tampered-callback-key')
             OR attempt_id IN ('$production_attempt_id', '$tampered_callback_attempt_id')), ':',
       (SELECT COUNT(*) FROM standard_order
          WHERE order_id IN ('$production_order_id', '$tampered_order_id')), ':',
       (SELECT COUNT(*) FROM inventory_ledger
          WHERE movement_id = '$production_movement_id'), ':',
       (SELECT COUNT(*) FROM order_idempotency
          WHERE order_id IN ('$production_order_id', '$tampered_order_id')), ':',
       (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$production_order_id'), ':',
       (SELECT COUNT(*) FROM commerce_outbox))")"
  assert_equal 409 "$status" "production callback rejects $description"
  assert_equal COMMITTED_PAYMENT_TRUTH_INCONSISTENT "$reason" \
    "production callback damage has durable-integrity attribution: $description"
  assert_equal "$durable_before" "$durable_after" \
    "production callback creates zero durable effects: $description"
  if grep -Eq 'COMMITTED_PAYMENT_TRUTH_INCONSISTENT|DEPENDENCY_OBSERVATION_INDETERMINATE' \
    "$tmp_dir/production-callback-classification.json"; then
    echo "Production callback leaked a server-only reason for $description." >&2
    exit 1
  fi
  echo "Verified production callback durable closure 409: $description"
}

assert_production_callback_truth_equivalence_preserving() {
  local description="$1"
  local durable_before durable_after
  refresh_production_callback_signature
  durable_before="$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM mock_payment_attempt
          WHERE attempt_id IN ('$production_attempt_id', '$tampered_attempt_id')), ':',
       (SELECT COUNT(*) FROM mock_payment_callback
          WHERE callback_event_id IN ('$production_event_id', '$tampered_callback_event_id')
             OR callback_idempotency_key IN ('$production_callback_key', 'tampered-callback-key')
             OR attempt_id IN ('$production_attempt_id', '$tampered_callback_attempt_id')), ':',
       (SELECT COUNT(*) FROM standard_order
          WHERE order_id IN ('$production_order_id', '$tampered_order_id')), ':',
       (SELECT COUNT(*) FROM inventory_ledger
          WHERE movement_id = '$production_movement_id'), ':',
       (SELECT COUNT(*) FROM order_idempotency
          WHERE order_id IN ('$production_order_id', '$tampered_order_id')), ':',
       (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$production_order_id'), ':',
       (SELECT COUNT(*) FROM commerce_outbox))")"
  assert_status 200 "$description preserves production callback replay" \
    --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
    --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
    --header "X-Mock-Payment-Timestamp: $production_timestamp" \
    --header "X-Mock-Payment-Signature: $production_signature" \
    --header "Idempotency-Key: $production_callback_key" \
    --header 'Content-Type: application/json' \
    --data "$production_callback_body"
  durable_after="$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM mock_payment_attempt
          WHERE attempt_id IN ('$production_attempt_id', '$tampered_attempt_id')), ':',
       (SELECT COUNT(*) FROM mock_payment_callback
          WHERE callback_event_id IN ('$production_event_id', '$tampered_callback_event_id')
             OR callback_idempotency_key IN ('$production_callback_key', 'tampered-callback-key')
             OR attempt_id IN ('$production_attempt_id', '$tampered_callback_attempt_id')), ':',
       (SELECT COUNT(*) FROM standard_order
          WHERE order_id IN ('$production_order_id', '$tampered_order_id')), ':',
       (SELECT COUNT(*) FROM inventory_ledger
          WHERE movement_id = '$production_movement_id'), ':',
       (SELECT COUNT(*) FROM order_idempotency
          WHERE order_id IN ('$production_order_id', '$tampered_order_id')), ':',
       (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$production_order_id'), ':',
       (SELECT COUNT(*) FROM commerce_outbox))")"
  assert_equal "$durable_before" "$durable_after" \
    "$description creates zero durable effects"
  echo "Verified EQUIVALENCE_PRESERVING production transformation: $description"
}

restore_complete_production_payment_truth
refresh_production_callback_signature
assert_status 200 "complete production callback fixture replays before fault injection" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $production_timestamp" \
  --header "X-Mock-Payment-Signature: $production_signature" \
  --header "Idempotency-Key: $production_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$production_callback_body"

mysql_query root "$root_password" commerce_db \
  "ALTER TABLE inventory_ledger DROP CHECK chk_inventory_ledger_movement"
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE mock_payment_attempt DROP CHECK chk_mock_payment_attempt_state"
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE standard_order DROP CHECK chk_standard_order_payment_state"
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE mock_payment_callback DROP CHECK chk_mock_payment_callback_eval_context"
mysql_query root "$root_password" commerce_db \
  "ALTER TABLE standard_order DROP CHECK chk_standard_order_eval_binding"

payment_callback_fault_locator="callback_event_id IN ('$payment_event_id', '$tampered_callback_event_id') OR callback_idempotency_key IN ('$payment_callback_key', 'tampered-callback-key') OR attempt_id IN ('$payment_attempt_id', '$tampered_callback_attempt_id')"
payment_attempt_fault_locator="attempt_id IN ('$payment_attempt_id', '$tampered_attempt_id')"
payment_order_fault_locator="order_id IN ('$payment_order_id', '$tampered_order_id')"

payment_inventory_targets=()
payment_inventory_dispositions=()
payment_inventory_groups=()
payment_inventory_scopes=()
payment_inventory_canonicalizers=()
payment_inventory_anchors=()
while IFS=$'\t' read -r inventory_face inventory_table inventory_column \
  inventory_disposition inventory_group inventory_scopes inventory_canonicalizer \
  inventory_anchors; do
  payment_inventory_targets+=("$inventory_table.$inventory_column")
  payment_inventory_dispositions+=("$inventory_disposition")
  payment_inventory_groups+=("$inventory_group")
  payment_inventory_scopes+=("$inventory_scopes")
  payment_inventory_canonicalizers+=("$inventory_canonicalizer")
  payment_inventory_anchors+=("$inventory_anchors")
done <"$payment_fault_inventory"
assert_equal 85 "${#payment_inventory_targets[@]}" \
  "committed-payment metadata declares every current physical content column"

payment_inventory_index() {
  local target="$1"
  local inventory_index
  for ((inventory_index = 0;
        inventory_index < ${#payment_inventory_targets[@]};
        inventory_index++)); do
    if [[ "${payment_inventory_targets[$inventory_index]}" == "$target" ]]; then
      printf '%s' "$inventory_index"
      return 0
    fi
  done
  return 1
}

payment_transformation_classification() {
  local scope="$1"
  shift
  local target target_index group='' required_target
  local required_count=0 matched_count=0
  for target in "$@"; do
    if [[ "$target" == row:* ]]; then
      printf '%s' INTEGRITY_DAMAGE
      return 0
    fi
    target_index="$(payment_inventory_index "$target")" || {
      echo "Fault target is absent from committed-payment metadata: $target" >&2
      return 1
    }
    if [[ "${payment_inventory_dispositions[$target_index]}" != CORRELATED_GROUP ]]; then
      printf '%s' INTEGRITY_DAMAGE
      return 0
    fi
    if [[ -z "$group" ]]; then
      group="${payment_inventory_groups[$target_index]}"
    elif [[ "${payment_inventory_groups[$target_index]}" != "$group" ]]; then
      printf '%s' INTEGRITY_DAMAGE
      return 0
    fi
  done
  for ((target_index = 0;
        target_index < ${#payment_inventory_targets[@]};
        target_index++)); do
    if [[ "${payment_inventory_groups[$target_index]}" == "$group" \
        && ",${payment_inventory_scopes[$target_index]}," == *",$scope,"* ]]; then
      required_count=$((required_count + 1))
      required_target="${payment_inventory_targets[$target_index]}"
      for target in "$@"; do
        if [[ "$target" == "$required_target" ]]; then
          matched_count=$((matched_count + 1))
          break
        fi
      done
    fi
  done
  if [[ "$required_count" -gt 0 && "$matched_count" -eq "$required_count" \
      && "$#" -eq "$required_count" ]]; then
    printf '%s' EQUIVALENCE_PRESERVING
  else
    printf '%s' INTEGRITY_DAMAGE
  fi
}

payment_observed_transformation_classification() {
  local scope="$1"
  local relative_order_consistent="$2"
  shift 2
  local classification
  classification="$(payment_transformation_classification "$scope" "$@")"
  if [[ "$classification" == EQUIVALENCE_PRESERVING \
      && "$scope" == EVALUATION \
      && "$relative_order_consistent" != 1 ]]; then
    printf '%s' INTEGRITY_DAMAGE
    return 0
  fi
  printf '%s' "$classification"
}

payment_predicate_labels=(
  audit-row audit-sequence audit-anchor audit-created-at callback-row ledger-row attempt-row
  callback-event callback-idempotency-key callback-attempt callback-correlation callback-sandbox
  callback-session callback-trace callback-operation callback-intent callback-outcome callback-result
  callback-created-at
  attempt-id attempt-correlation attempt-owner attempt-order attempt-order-kind attempt-sandbox
  attempt-request-key attempt-intent attempt-amount attempt-refunded-amount attempt-currency attempt-state
  attempt-state-version attempt-succeeded-at
  order-id order-sandbox order-owner order-product order-quantity order-product-version
  order-unit-price order-amount order-currency order-status
  order-state-version
  ledger-key ledger-sandbox ledger-movement ledger-order ledger-product ledger-reservation
  ledger-activity ledger-inventory-delta ledger-activity-delta ledger-amount ledger-currency
)
payment_predicate_targets=(
  row:audit
  eval_commerce_audit_reference.sequence_id
  eval_commerce_audit_reference.created_at_anchor
  eval_commerce_audit_reference.created_at
  row:callback row:ledger row:attempt
  mock_payment_callback.callback_event_id
  mock_payment_callback.callback_idempotency_key
  mock_payment_callback.attempt_id
  mock_payment_callback.callback_correlation_id
  mock_payment_callback.sandbox_id
  mock_payment_callback.support_session_id
  mock_payment_callback.trace_id
  mock_payment_callback.operation_id
  mock_payment_callback.intent_hash
  mock_payment_callback.requested_outcome
  mock_payment_callback.result_state
  mock_payment_callback.created_at
  mock_payment_attempt.attempt_id
  mock_payment_attempt.callback_correlation_id
  mock_payment_attempt.user_subject
  mock_payment_attempt.order_id
  mock_payment_attempt.order_kind
  mock_payment_attempt.sandbox_id
  mock_payment_attempt.request_idempotency_key
  mock_payment_attempt.intent_hash
  mock_payment_attempt.amount_minor
  mock_payment_attempt.refunded_amount_minor
  mock_payment_attempt.currency
  mock_payment_attempt.state
  mock_payment_attempt.state_version
  mock_payment_attempt.succeeded_at
  standard_order.order_id
  standard_order.sandbox_id
  standard_order.user_subject
  standard_order.product_id
  standard_order.quantity
  standard_order.product_version
  standard_order.unit_price_minor
  standard_order.total_price_minor
  standard_order.currency
  standard_order.status
  standard_order.state_version
  inventory_ledger.business_event_key
  inventory_ledger.sandbox_id
  inventory_ledger.movement_type
  inventory_ledger.order_id
  inventory_ledger.product_id
  inventory_ledger.reservation_id
  inventory_ledger.activity_id
  inventory_ledger.inventory_delta
  inventory_ledger.activity_quota_delta
  inventory_ledger.payment_amount_minor
  inventory_ledger.payment_currency
)
payment_predicate_mutations=(
  "DELETE FROM eval_commerce_audit_reference WHERE audit_reference_id = '$payment_audit_reference_id'"
  "UPDATE eval_commerce_audit_reference SET sequence_id = $payment_audit_tampered_sequence_id WHERE audit_reference_id = '$payment_audit_reference_id'"
  "SET SESSION sql_mode = 'NO_ENGINE_SUBSTITUTION'; UPDATE eval_commerce_audit_reference SET created_at_anchor = 'CORRUPTED' WHERE audit_reference_id = '$payment_audit_reference_id'"
  "UPDATE eval_commerce_audit_reference SET created_at = TIMESTAMPADD(MICROSECOND, 1, created_at) WHERE audit_reference_id = '$payment_audit_reference_id'"
  "DELETE FROM mock_payment_callback WHERE $payment_callback_fault_locator"
  "DELETE FROM inventory_ledger WHERE movement_id = '$payment_movement_id'"
  "DELETE FROM mock_payment_attempt WHERE $payment_attempt_fault_locator"
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
  "UPDATE mock_payment_attempt SET request_idempotency_key = 'tampered-payment-start-key' WHERE $payment_attempt_fault_locator"
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
  "UPDATE standard_order SET quantity = 3 WHERE $payment_order_fault_locator"
  "UPDATE standard_order SET product_version = 2 WHERE $payment_order_fault_locator"
  "UPDATE standard_order SET unit_price_minor = 901 WHERE $payment_order_fault_locator"
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

assert_equal 55 "${#payment_predicate_labels[@]}" \
  "complete physical JOIN/WHERE corruption label matrix"
assert_equal "${#payment_predicate_labels[@]}" "${#payment_predicate_mutations[@]}" \
  "physical JOIN/WHERE corruption labels and mutations stay aligned"
assert_equal "${#payment_predicate_labels[@]}" "${#payment_predicate_targets[@]}" \
  "physical fault labels and metadata targets stay aligned"

is_committed_payment_face_index() {
  local label="${payment_predicate_labels[$1]}"
  [[ "$label" == audit-row || "$label" == callback-row || "$label" == ledger-row \
    || "$label" == attempt-row || "$label" == attempt-id || "$label" == order-id ]]
}

evaluation_integrity_damage_cells=0
evaluation_equivalence_preserving_transformations=0
for ((predicate_index = 0; predicate_index < ${#payment_predicate_mutations[@]}; predicate_index++)); do
  classification="$(payment_transformation_classification \
    EVALUATION "${payment_predicate_targets[$predicate_index]}")"
  assert_equal INTEGRITY_DAMAGE "$classification" \
    "single-column oracle rejects ${payment_predicate_labels[$predicate_index]}"
  mutation_count="$(mysql_query root "$root_password" commerce_db \
    "${payment_predicate_mutations[$predicate_index]}; SELECT ROW_COUNT()")"
  assert_equal 1 "$mutation_count" \
    "single consistency fault injection changed exactly one row: ${payment_predicate_labels[$predicate_index]}"
  assert_payment_truth_fails_closed \
    "single committed-face content corruption ${payment_predicate_labels[$predicate_index]}"
  restore_complete_payment_truth
  evaluation_integrity_damage_cells=$((evaluation_integrity_damage_cells + 1))
done

for ((left_index = 0; left_index < ${#payment_predicate_mutations[@]}; left_index++)); do
  for ((right_index = left_index + 1; right_index < ${#payment_predicate_mutations[@]}; right_index++)); do
    left_mutation="${payment_predicate_mutations[$left_index]}"
    right_mutation="${payment_predicate_mutations[$right_index]}"
    if [[ "${payment_predicate_labels[$left_index]}" == audit-row \
        && "${payment_predicate_labels[$right_index]}" == audit-* ]] \
      || [[ "${payment_predicate_labels[$left_index]}" == callback-row \
        && "${payment_predicate_labels[$right_index]}" == callback-* ]] \
      || [[ "${payment_predicate_labels[$left_index]}" == attempt-row \
        && "${payment_predicate_labels[$right_index]}" == attempt-* ]] \
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
    classification="$(payment_transformation_classification \
      EVALUATION "${payment_predicate_targets[$left_index]}" \
      "${payment_predicate_targets[$right_index]}")"
    assert_equal INTEGRITY_DAMAGE "$classification" \
      "evaluation strict-subset oracle rejects ${payment_predicate_labels[$left_index]} + ${payment_predicate_labels[$right_index]}"
    if is_committed_payment_face_index "$left_index" \
      && is_committed_payment_face_index "$right_index"; then
      assert_payment_truth_fails_closed \
        "paired committed-face corruption ${payment_predicate_labels[$left_index]} + ${payment_predicate_labels[$right_index]}"
    else
      assert_payment_audit_reconciliation_fails_closed \
        "paired enumerator predicate corruption ${payment_predicate_labels[$left_index]} + ${payment_predicate_labels[$right_index]}"
    fi
    restore_complete_payment_truth
    evaluation_integrity_damage_cells=$((evaluation_integrity_damage_cells + 1))
  done
done

evaluation_time_group_targets=()
for ((target_index = 0;
      target_index < ${#payment_inventory_targets[@]};
      target_index++)); do
  if [[ "${payment_inventory_groups[$target_index]}" == PAYMENT_EVENT_TIME \
      && ",${payment_inventory_scopes[$target_index]}," == *,EVALUATION,* ]]; then
    evaluation_time_group_targets+=("${payment_inventory_targets[$target_index]}")
  fi
done
assert_equal 3 "${#evaluation_time_group_targets[@]}" \
  "evaluation payment event-time group is derived from production metadata"
classification="$(payment_transformation_classification \
  EVALUATION "${evaluation_time_group_targets[@]}")"
assert_equal EQUIVALENCE_PRESERVING "$classification" \
  "full evaluation payment event-time group is equivalence preserving"
mysql_query root "$root_password" commerce_db "
UPDATE mock_payment_callback
  SET created_at = TIMESTAMPADD(MICROSECOND, 1, created_at)
  WHERE $payment_callback_fault_locator;
UPDATE mock_payment_attempt
  SET succeeded_at = TIMESTAMPADD(MICROSECOND, 1, succeeded_at)
  WHERE $payment_attempt_fault_locator;
UPDATE eval_commerce_audit_reference
  SET created_at = TIMESTAMPADD(MICROSECOND, 1, created_at)
  WHERE audit_reference_id = '$payment_audit_reference_id';
"
assert_equal 0 "$(mysql_query root "$root_password" commerce_db \
  "SELECT COUNT(*) FROM (
     SELECT created_at, LAG(created_at) OVER (ORDER BY sequence_id) AS previous_created_at
     FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-payment'
   ) ordered_audit
   WHERE previous_created_at IS NOT NULL AND created_at < previous_created_at")" \
  "equivalent evaluation event-time shift preserves audit relative order"
assert_payment_truth_equivalence_preserving \
  "full PAYMENT_EVENT_TIME group shift"
restore_complete_payment_truth
evaluation_equivalence_preserving_transformations=$((evaluation_equivalence_preserving_transformations + 1))

payment_order_breaking_time="$(mysql_query root "$root_password" commerce_db \
  "SELECT DATE_FORMAT(TIMESTAMPADD(MICROSECOND, 1, created_at), '%Y-%m-%d %H:%i:%s.%f')
   FROM eval_commerce_audit_reference
   WHERE sandbox_id = 'sandbox-payment'
     AND sequence_id > $payment_audit_sequence_id
   ORDER BY sequence_id
   LIMIT 1")"
test -n "$payment_order_breaking_time"
mysql_query root "$root_password" commerce_db "
UPDATE mock_payment_callback
  SET created_at = '$payment_order_breaking_time'
  WHERE $payment_callback_fault_locator;
UPDATE mock_payment_attempt
  SET succeeded_at = '$payment_order_breaking_time'
  WHERE $payment_attempt_fault_locator;
UPDATE eval_commerce_audit_reference
  SET created_at = '$payment_order_breaking_time'
  WHERE audit_reference_id = '$payment_audit_reference_id';
"
payment_relative_order_consistent="$(
  mysql_query root "$root_password" commerce_db \
    "SELECT IF(COUNT(*) = 0, 1, 0) FROM (
       SELECT created_at, LAG(created_at) OVER (ORDER BY sequence_id) AS previous_created_at
       FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-payment'
     ) ordered_audit
     WHERE previous_created_at IS NOT NULL AND created_at < previous_created_at"
)"
classification="$(payment_observed_transformation_classification \
  EVALUATION "$payment_relative_order_consistent" "${evaluation_time_group_targets[@]}")"
assert_equal INTEGRITY_DAMAGE "$classification" \
  "full event-time group that breaks audit relative order remains integrity damage"
assert_payment_truth_fails_closed \
  "full PAYMENT_EVENT_TIME group shift that breaks audit relative order"
restore_complete_payment_truth
evaluation_integrity_damage_cells=$((evaluation_integrity_damage_cells + 1))
echo "Evaluation payment matrix totals: integrity-damage=$evaluation_integrity_damage_cells equivalence-preserving=$evaluation_equivalence_preserving_transformations"
echo "Payment-start visibility matrix totals: concealed-authorization=$payment_start_visibility_cell_count"

production_predicate_labels=("${payment_predicate_labels[@]:4}")
production_predicate_targets=("${payment_predicate_targets[@]:4}")
production_predicate_mutations=()
for ((predicate_index = 4; predicate_index < ${#payment_predicate_mutations[@]}; predicate_index++)); do
  production_mutation="${payment_predicate_mutations[$predicate_index]}"
  production_mutation="${production_mutation//$payment_event_id/$production_event_id}"
  production_mutation="${production_mutation//$payment_callback_key/$production_callback_key}"
  production_mutation="${production_mutation//$payment_attempt_id/$production_attempt_id}"
  production_mutation="${production_mutation//$payment_order_id/$production_order_id}"
  production_mutation="${production_mutation//$payment_movement_id/$production_movement_id}"
  production_predicate_mutations+=("$production_mutation")
done
production_predicate_labels+=(
  order-origin-row order-origin-owner order-origin-intent order-origin-order
)
production_predicate_targets+=(
  row:standard-order-origin
  order_idempotency.user_subject
  order_idempotency.intent_hash
  order_idempotency.order_id
)
production_predicate_mutations+=(
  "DELETE FROM order_idempotency WHERE order_id IN ('$production_order_id', '$tampered_order_id')"
  "UPDATE order_idempotency SET user_subject = 'tampered-origin-owner' WHERE order_id = '$production_order_id'"
  "UPDATE order_idempotency SET intent_hash = REPEAT('f', 64) WHERE order_id = '$production_order_id'"
  "UPDATE order_idempotency SET order_id = '$tampered_order_id' WHERE order_id = '$production_order_id'"
)
assert_equal 55 "${#production_predicate_labels[@]}" \
  "production callback four-face physical corruption label matrix"
assert_equal "${#production_predicate_labels[@]}" "${#production_predicate_mutations[@]}" \
  "production callback labels and mutations stay aligned"
assert_equal "${#production_predicate_labels[@]}" "${#production_predicate_targets[@]}" \
  "production callback targets stay aligned with metadata"

classification="$(payment_transformation_classification \
  PRODUCTION standard_order.product_id inventory_ledger.product_id)"
assert_equal INTEGRITY_DAMAGE "$classification" \
  "coordinated product replica rewrite remains metadata-classified integrity damage"
mysql_query root "$root_password" commerce_db "
UPDATE standard_order SET product_id = 'coordinated-product-rewrite'
  WHERE order_id = '$production_order_id';
UPDATE inventory_ledger SET product_id = 'coordinated-product-rewrite'
  WHERE order_id = '$production_order_id';
"
assert_production_callback_truth_fails_closed \
  "coordinated standard order/payment-ledger product rewrite"
restore_complete_production_payment_truth

production_integrity_damage_cells=0
production_equivalence_preserving_transformations=0
for ((predicate_index = 0;
      predicate_index < ${#production_predicate_mutations[@]};
      predicate_index++)); do
  classification="$(payment_transformation_classification \
    PRODUCTION "${production_predicate_targets[$predicate_index]}")"
  assert_equal INTEGRITY_DAMAGE "$classification" \
    "production single-column oracle rejects ${production_predicate_labels[$predicate_index]}"
  mutation_count="$(mysql_query root "$root_password" commerce_db \
    "${production_predicate_mutations[$predicate_index]}; SELECT ROW_COUNT()")"
  assert_equal 1 "$mutation_count" \
    "production callback single fault changed exactly one row: ${production_predicate_labels[$predicate_index]}"
  assert_production_callback_truth_fails_closed \
    "single ${production_predicate_labels[$predicate_index]}"
  restore_complete_production_payment_truth
  production_integrity_damage_cells=$((production_integrity_damage_cells + 1))
done

production_pair_count=0
for ((left_index = 0; left_index < ${#production_predicate_mutations[@]}; left_index++)); do
  for ((right_index = left_index + 1;
        right_index < ${#production_predicate_mutations[@]};
        right_index++)); do
    left_mutation="${production_predicate_mutations[$left_index]}"
    right_mutation="${production_predicate_mutations[$right_index]}"
    if [[ "${production_predicate_labels[$left_index]}" == callback-row \
        && "${production_predicate_labels[$right_index]}" == callback-* ]] \
      || [[ "${production_predicate_labels[$left_index]}" == attempt-row \
        && "${production_predicate_labels[$right_index]}" == attempt-* ]] \
      || [[ "${production_predicate_labels[$left_index]}" == ledger-row \
        && "${production_predicate_labels[$right_index]}" == ledger-* ]] \
      || [[ "${production_predicate_labels[$left_index]}" == order-origin-row \
        && "${production_predicate_labels[$right_index]}" == order-origin-* ]]; then
      first_mutation="$right_mutation"
      second_mutation="$left_mutation"
    else
      first_mutation="$left_mutation"
      second_mutation="$right_mutation"
    fi
    mutation_counts="$(mysql_query root "$root_password" commerce_db \
      "$first_mutation; SELECT ROW_COUNT(); $second_mutation; SELECT ROW_COUNT()")"
    assert_equal $'1\n1' "$mutation_counts" \
      "production callback pair changed one row per fault: ${production_predicate_labels[$left_index]} + ${production_predicate_labels[$right_index]}"
    classification="$(payment_transformation_classification \
      PRODUCTION "${production_predicate_targets[$left_index]}" \
      "${production_predicate_targets[$right_index]}")"
    if [[ "$classification" == EQUIVALENCE_PRESERVING ]]; then
      assert_production_callback_truth_equivalence_preserving \
        "pair ${production_predicate_labels[$left_index]} + ${production_predicate_labels[$right_index]}"
      production_equivalence_preserving_transformations=$((production_equivalence_preserving_transformations + 1))
    else
      assert_equal INTEGRITY_DAMAGE "$classification" \
        "production pair oracle classifies integrity damage"
      assert_production_callback_truth_fails_closed \
        "pair ${production_predicate_labels[$left_index]} + ${production_predicate_labels[$right_index]}"
      production_integrity_damage_cells=$((production_integrity_damage_cells + 1))
    fi
    restore_complete_production_payment_truth
    production_pair_count=$((production_pair_count + 1))
  done
done
assert_equal 1485 "$production_pair_count" \
  "production callback covers every two-way four-face corruption pair"
assert_equal 1 "$production_equivalence_preserving_transformations" \
  "production matrix contains one metadata-derived equivalence transformation"
assert_equal 1539 "$production_integrity_damage_cells" \
  "production matrix keeps every independently anchored single/pair as damage"
echo "Production payment matrix totals: integrity-damage=$production_integrity_damage_cells equivalence-preserving=$production_equivalence_preserving_transformations"

evaluation_ledger_bound_prefix='cb116-evaluation-ledger-bound-'
mysql_query root "$root_password" commerce_db "
SET SESSION cte_max_recursion_depth = 1100;
INSERT INTO inventory_ledger (movement_id, business_event_key, movement_type, order_id,
  reservation_id, activity_id, product_id, sandbox_id, inventory_delta, activity_quota_delta,
  payment_amount_minor, payment_currency)
WITH RECURSIVE sequence_number(value) AS (
  SELECT 0
  UNION ALL
  SELECT value + 1 FROM sequence_number WHERE value < 1024
)
SELECT UUID(), CONCAT('$evaluation_ledger_bound_prefix', value), 'STANDARD_REFUND',
  '$payment_order_id', NULL, NULL, '$payment_product_id', 'sandbox-payment', 0, 0, 1, 'CNY'
FROM sequence_number;
"
assert_equal 1025 "$(mysql_query root "$root_password" commerce_db \
  "SELECT COUNT(*) FROM inventory_ledger WHERE business_event_key LIKE '$evaluation_ledger_bound_prefix%'")" \
  "evaluation ledger overflow fixture exceeds the bounded acquisition contract"
assert_payment_truth_fails_closed \
  "evaluation state/audit ledger closure exceeds the physical acquisition bound"
mysql_query root "$root_password" commerce_db \
  "DELETE FROM inventory_ledger WHERE business_event_key LIKE '$evaluation_ledger_bound_prefix%'"
restore_complete_payment_truth

refresh_production_callback_signature
assert_status 200 "restored production callback succeeds after complete 55/1485 matrix" \
  --request POST "http://127.0.0.1:$commerce_port/internal/mock-payments/callback" \
  --header "X-Mock-Payment-Key-Id: $mock_payment_key" \
  --header "X-Mock-Payment-Timestamp: $production_timestamp" \
  --header "X-Mock-Payment-Signature: $production_signature" \
  --header "Idempotency-Key: $production_callback_key" \
  --header 'Content-Type: application/json' \
  --data "$production_callback_body"

mysql_query root "$root_password" commerce_db "
ALTER TABLE standard_order
  ADD CONSTRAINT chk_standard_order_eval_binding CHECK (
    (sandbox_id IS NULL AND evaluation_owner_handle IS NULL)
    OR (sandbox_id IS NOT NULL AND evaluation_owner_handle IS NOT NULL)
  );
ALTER TABLE mock_payment_callback
  ADD CONSTRAINT chk_mock_payment_callback_eval_context CHECK (
    (sandbox_id IS NULL AND support_session_id IS NULL AND trace_id IS NULL
      AND operation_id IS NULL)
    OR
    (sandbox_id IS NOT NULL AND support_session_id IS NOT NULL AND trace_id IS NOT NULL
      AND operation_id IS NOT NULL)
  );
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

action_session='action-payment-session'
action_trace='action-payment-trace'
action_turn='00000000-0000-0000-0000-000000000401'
assert_status 200 "exchange sandbox-bound Action OBO token" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$action_session\",\"userSubject\":\"$payment_subject\",\"scope\":\"refund:create\"}"
action_obo_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
assert_status 201 "prepare sandbox-bound refund Action" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/actions/prepare" \
  --header "Authorization: Bearer $action_obo_token" \
  --header "X-Support-Session-Id: $action_session" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Agent-Trace-Id: $action_trace" \
  --header "X-Agent-Turn-Id: $action_turn" \
  --header 'Content-Type: application/json' \
  --data "{\"actionType\":\"REFUND_REQUEST\",\"arguments\":{\"orderId\":\"$payment_order_id\",\"amountMinor\":500,\"currency\":\"CNY\"}}"
action_pending_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" pendingActionId)"
assert_status 200 "confirm sandbox-bound refund Action atomically" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/actions/$action_pending_id/confirm" \
  --header "Authorization: Bearer $action_obo_token" \
  --header "X-Support-Session-Id: $action_session" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Agent-Trace-Id: $action_trace" \
  --header "X-Agent-Turn-Id: $action_turn"
action_receipt_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" receiptId)"
action_refund_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" refundId)"
assert_equal 'CONSUMED:2:1' \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT(state, ':', state_version, ':', consumed_at IS NOT NULL) FROM pending_action WHERE pending_action_id = '$action_pending_id'")" \
  "Action confirm consumes exactly one PendingAction"
assert_equal '1:1:1' \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT((SELECT COUNT(*) FROM action_receipt WHERE receipt_id = '$action_receipt_id'), ':', (SELECT COUNT(*) FROM mock_refund WHERE refund_id = '$action_refund_id'), ':', (SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND' AND aggregate_id = '$action_refund_id' AND event_type = 'REFUND_REQUESTED'))")" \
  "Action confirm commits one receipt, refund, and Outbox row"

uv run python scripts/fake_litellm_server.py --port 0 \
  --commerce-base-url "http://127.0.0.1:$commerce_port" \
  >>"$tmp_dir/cb122-model.log" 2>&1 &
model_pid=$!
process_bound_port proxy_port uvicorn "$model_pid" "$tmp_dir/cb122-model.log" 0
wait_http "http://127.0.0.1:$proxy_port/fixture/counts" \
  "$model_pid" "$tmp_dir/cb122-model.log"
start_agent true "http://127.0.0.1:$proxy_port"
assert_status 201 "CB-122 session binds the payment principal and sandbox" \
  --request POST "http://127.0.0.1:$agent_port/api/sessions" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Content-Type: application/json' \
  --data '{}'
cb122_session="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" sessionId)"
cb122_commerce_before="$(mysql_query root "$root_password" commerce_db \
  "SELECT CONCAT((SELECT COUNT(*) FROM pending_action WHERE order_id = '$payment_order_id'), ':', (SELECT COUNT(*) FROM action_receipt WHERE order_id = '$payment_order_id'), ':', (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':', (SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND'))")"
assert_status 200 "CB-122 response-loss prepare converges through one bounded same-intent replay" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-response-loss' \
  --header 'Content-Type: application/json' \
  --data '{"message":"action-prepare"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/cb122-prepared.json"
jq -e '.outcome == "action_pending"' "$tmp_dir/cb122-prepared.json" >/dev/null
cb122_prepare_trace="$(uv run python scripts/read_json_field.py \
  "$tmp_dir/cb122-prepared.json" traceId)"
cb122_prepare_turn="$(uv run python scripts/read_json_field.py \
  "$tmp_dir/cb122-prepared.json" turnId)"
cb122_pending_id="$(mysql_query root "$root_password" cs_db \
  "SELECT pending_action_id FROM pending_action_reference WHERE source_turn_id = '$cb122_prepare_turn'")"
assert_equal 1 \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT reference.target_version = JSON_EXTRACT(event_record.payload_json, '$.targetVersion') AND reference.target_version = (SELECT target_order_version FROM commerce_db.pending_action WHERE pending_action_id = reference.pending_action_id) FROM pending_action_reference reference JOIN support_event event_record ON event_record.turn_id = reference.source_turn_id AND event_record.event_type = 'ACTION_PREPARED' WHERE reference.pending_action_id = '$cb122_pending_id'")" \
  "CB-122 stores the Commerce target version only as matching local reference and event evidence"
assert_equal 'PENDING:action_pending:1:1' \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT(reference.state, ':', turn_record.outcome, ':', (SELECT COUNT(*) FROM support_event WHERE turn_id = turn_record.turn_id AND event_type = 'ACTION_PREPARED'), ':', (SELECT COUNT(*) FROM pending_action_reference WHERE source_turn_id = turn_record.turn_id)) FROM pending_action_reference reference JOIN support_turn turn_record ON turn_record.turn_id = reference.source_turn_id WHERE reference.pending_action_id = '$cb122_pending_id'")" \
  "CB-122 prepare commits one local reference and one preparation event"
assert_equal 2 \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')" \
  "response-loss fixture observes one upstream commit and one exact replay"
assert_equal 1 \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT COUNT(*) FROM pending_action WHERE support_session_id = '$cb122_session' AND turn_id = '$cb122_prepare_turn'")" \
  "bounded response-loss replay creates one commerce PendingAction"
cb122_proxy_calls_before_replay="$(curl --silent --show-error \
  "http://127.0.0.1:$proxy_port/fixture/counts" \
  | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')"
cb122_model_calls_before_replay="$(curl --silent --show-error \
  "http://127.0.0.1:$proxy_port/fixture/counts" \
  | jq -r '.["action-prepare:total"]')"
assert_status 200 "complete local action closure replays before model and commerce" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-response-loss' \
  --header 'Content-Type: application/json' \
  --data '{"message":"action-prepare"}'
cmp "$tmp_dir/cb122-prepared.json" "$tmp_dir/http-response.json"
assert_equal "$cb122_proxy_calls_before_replay" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')" \
  "local replay does not call commerce prepare again"
assert_equal "$cb122_model_calls_before_replay" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r '.["action-prepare:total"]')" \
  "local replay does not call the model again"

for duplicate in 1 2; do
  request_status "$tmp_dir/cb122-concurrent-$duplicate.json" \
    --request POST "http://127.0.0.1:$agent_port/api/chat" \
    --header "Authorization: Bearer $payment_token" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment' \
    --header "X-Session-Id: $cb122_session" \
    --header 'Idempotency-Key: cb122-response-loss' \
    --header 'Content-Type: application/json' \
    --data '{"message":"action-prepare"}' \
    >"$tmp_dir/cb122-concurrent-$duplicate.status" &
  cb122_concurrent_pids[$duplicate]=$!
done
for duplicate in 1 2; do
  wait "${cb122_concurrent_pids[$duplicate]}"
  assert_equal 200 "$(cat "$tmp_dir/cb122-concurrent-$duplicate.status")" \
    "concurrent stored-closure replay $duplicate status"
  cmp "$tmp_dir/cb122-prepared.json" "$tmp_dir/cb122-concurrent-$duplicate.json"
done
assert_equal "$cb122_proxy_calls_before_replay" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')" \
  "concurrent stored-closure replay does not call commerce"
assert_equal "$cb122_model_calls_before_replay" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r '.["action-prepare:total"]')" \
  "concurrent stored-closure replay does not call the model"

assert_status 409 "changed message cannot reuse the completed action key" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-response-loss' \
  --header 'Content-Type: application/json' \
  --data '{"message":"action-prepare changed"}'
assert_equal "$cb122_proxy_calls_before_replay" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')" \
  "changed replay is rejected before commerce"
assert_equal "$cb122_model_calls_before_replay" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r '.["action-prepare:total"]')" \
  "changed replay is rejected before the model"

cb122_cross_trace='00000000-0000-0000-0000-000000000922'
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE support_event SET trace_id = '$cb122_cross_trace' WHERE turn_id = '$cb122_prepare_turn' AND event_type = 'ACTION_PREPARED'; SET FOREIGN_KEY_CHECKS = 1"
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 409 "conversation replay rejects a cross-trace ACTION_PREPARED row" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-response-loss' \
  --header 'Content-Type: application/json' \
  --data '{"message":"action-prepare"}'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_DURABLE_TRUTH_INCONSISTENT'
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 409 "evaluation evidence rejects the same cross-trace ACTION_PREPARED row" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$cb122_prepare_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT'
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE support_event SET trace_id = '$cb122_prepare_trace' WHERE turn_id = '$cb122_prepare_turn' AND event_type = 'ACTION_PREPARED'; SET FOREIGN_KEY_CHECKS = 1"
assert_status 200 "conversation replay recovers after cross-trace damage is restored" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-response-loss' \
  --header 'Content-Type: application/json' \
  --data '{"message":"action-prepare"}'
cmp "$tmp_dir/cb122-prepared.json" "$tmp_dir/http-response.json"
assert_cb122_action_closure_damage() {
  local label="$1"
  local closure_before
  local log_start
  closure_before="$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT((SELECT COUNT(*) FROM support_turn WHERE session_id = '$cb122_session'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_session'), ':', (SELECT COUNT(*) FROM pending_action_reference WHERE session_id = '$cb122_session'))")"
  log_start="$(wc -l <"$tmp_dir/agent.log")"
  assert_status 409 "conversation rejects $label" \
    --request POST "http://127.0.0.1:$agent_port/api/chat" \
    --header "Authorization: Bearer $payment_token" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment' \
    --header "X-Session-Id: $cb122_session" \
    --header 'Idempotency-Key: cb122-response-loss' \
    --header 'Content-Type: application/json' \
    --data '{"message":"action-prepare"}'
  tail -n "+$((log_start + 1))" "$tmp_dir/agent.log" \
    | grep -Fq 'reason_code=ACTION_DURABLE_TRUTH_INCONSISTENT'
  log_start="$(wc -l <"$tmp_dir/agent.log")"
  assert_status 409 "evaluation rejects $label" \
    --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$cb122_prepare_trace" \
    --user "evaluation-manager:$management_password" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment'
  tail -n "+$((log_start + 1))" "$tmp_dir/agent.log" \
    | grep -Fq 'reason_code=ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT'
  assert_equal "$cb122_proxy_calls_before_replay" \
    "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
      | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')" \
    "$label does not call commerce"
  assert_equal "$cb122_model_calls_before_replay" \
    "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
      | jq -r '.["action-prepare:total"]')" \
    "$label does not call the model"
  assert_equal "$closure_before" \
    "$(mysql_query root "$root_password" cs_db \
      "SELECT CONCAT((SELECT COUNT(*) FROM support_turn WHERE session_id = '$cb122_session'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_session'), ':', (SELECT COUNT(*) FROM pending_action_reference WHERE session_id = '$cb122_session'))")" \
    "$label creates zero local durable effects"
}

cb122_prepared_event_id="$(mysql_query root "$root_password" cs_db \
  "SELECT event_id FROM support_event WHERE turn_id = '$cb122_prepare_turn' AND event_type = 'ACTION_PREPARED'")"
mysql_query root "$root_password" cs_db \
  "UPDATE support_event SET event_id = '00000000-0000-0000-0000-000000000ABC' WHERE event_id = '$cb122_prepared_event_id'"
assert_cb122_action_closure_damage "a non-canonical ACTION_PREPARED event identity"
mysql_query root "$root_password" cs_db \
  "UPDATE support_event SET event_id = '$cb122_prepared_event_id' WHERE event_id = '00000000-0000-0000-0000-000000000ABC'"

cb122_reference_conversation="$(mysql_query root "$root_password" cs_db \
  "SELECT conversation_id FROM pending_action_reference WHERE pending_action_id = '$cb122_pending_id'")"
mysql_query root "$root_password" cs_db \
  "UPDATE pending_action_reference SET conversation_id = '00000000-0000-0000-0000-000000000924' WHERE pending_action_id = '$cb122_pending_id'"
assert_cb122_action_closure_damage "a cross-conversation PendingAction reference"
mysql_query root "$root_password" cs_db \
  "UPDATE pending_action_reference SET conversation_id = '$cb122_reference_conversation' WHERE pending_action_id = '$cb122_pending_id'"

mysql_query root "$root_password" cs_db \
  "UPDATE support_turn SET outcome = 'completed' WHERE turn_id = '$cb122_prepare_turn'"
assert_cb122_action_closure_damage "a source turn without action_pending outcome"
mysql_query root "$root_password" cs_db \
  "UPDATE support_turn SET outcome = 'action_pending' WHERE turn_id = '$cb122_prepare_turn'"

cb122_source_user="$(mysql_query root "$root_password" cs_db \
  "SELECT user_subject FROM support_turn WHERE turn_id = '$cb122_prepare_turn'")"
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE support_turn SET user_subject = 'corrupted-owner' WHERE turn_id = '$cb122_prepare_turn'; SET FOREIGN_KEY_CHECKS = 1"
assert_cb122_action_closure_damage "a source turn with contradictory owner binding"
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE support_turn SET user_subject = '$cb122_source_user' WHERE turn_id = '$cb122_prepare_turn'; SET FOREIGN_KEY_CHECKS = 1"

cb122_duplicate_sequence="$(mysql_query root "$root_password" cs_db \
  "SELECT MAX(sequence) + 1 FROM support_event WHERE turn_id = '$cb122_prepare_turn'")"
mysql_query root "$root_password" cs_db \
  "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json, created_at) SELECT '00000000-0000-0000-0000-000000000925', turn_id, trace_id, session_id, user_subject, $cb122_duplicate_sequence, event_type, payload_json, created_at FROM support_event WHERE turn_id = '$cb122_prepare_turn' AND event_type = 'ACTION_PREPARED' LIMIT 1"
assert_cb122_action_closure_damage "duplicate ACTION_PREPARED evidence"
mysql_query root "$root_password" cs_db \
  "DELETE FROM support_event WHERE event_id = '00000000-0000-0000-0000-000000000925'"

cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
cb122_proxy_calls_before_confirmation="$(curl --silent --show-error \
  "http://127.0.0.1:$proxy_port/fixture/counts" \
  | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')"
cb122_model_calls_before_confirmation="$(curl --silent --show-error \
  "http://127.0.0.1:$proxy_port/fixture/counts" \
  | jq -r '.["confirm:total"] // 0')"
assert_status 409 "CB-122 exact confirmation is deliberately unavailable" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-confirm-unavailable' \
  --header 'Content-Type: application/json' \
  --data '{"message":"confirm"}'
assert_equal 'Action confirmation unavailable' \
  "$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" detail)" \
  "confirmation response is bounded and contains no internal reason"
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_CONFIRMATION_UNAVAILABLE'
assert_equal 'PENDING:0' \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT(state, ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_session' AND event_type IN ('ACTION_DECLINED', 'ACTION_EXPIRED', 'ACTION_RECEIPT'))) FROM pending_action_reference WHERE pending_action_id = '$cb122_pending_id'")" \
  "unavailable confirmation has zero local action effect"
assert_equal "$cb122_proxy_calls_before_confirmation" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')" \
  "unavailable confirmation performs no commerce action call"
assert_equal "$cb122_model_calls_before_confirmation" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r '.["confirm:total"] // 0')" \
  "unavailable confirmation performs no model call"

assert_status 200 "ambiguous action input produces one local clarification" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-note-1' \
  --header 'Content-Type: application/json' \
  --data '{"message":"maybe change it"}'
jq -e '.outcome == "action_clarification"' "$tmp_dir/http-response.json" >/dev/null
assert_equal PENDING \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT state FROM pending_action_reference WHERE pending_action_id = '$cb122_pending_id'")" \
  "clarification does not change PendingAction state"
cb122_clarification_turn="$(mysql_query root "$root_password" cs_db \
    "SELECT turn_id FROM support_turn WHERE session_id = '$cb122_session' AND correlation_key = 'cb122-note-1'")"
cb122_clarification_trace="$(mysql_query root "$root_password" cs_db \
  "SELECT trace_id FROM support_turn WHERE turn_id = '$cb122_clarification_turn'")"
mysql_query root "$root_password" cs_db \
  "INSERT INTO pending_action_reference (pending_action_id, source_turn_id, source_trace_id, conversation_id, session_id, user_subject, sandbox_id, action_type, argument_commitment, order_id, target_version, amount_minor, currency, state, expires_at, resolved_at, resolution_turn_id, resolution_trace_id) SELECT '00000000-0000-0000-0000-000000000926', turn_record.turn_id, turn_record.trace_id, turn_record.conversation_id, turn_record.session_id, turn_record.user_subject, reference.sandbox_id, reference.action_type, reference.argument_commitment, reference.order_id, reference.target_version, reference.amount_minor, reference.currency, 'DECLINED', reference.expires_at, CURRENT_TIMESTAMP(6), '$cb122_prepare_turn', '$cb122_prepare_trace' FROM support_turn turn_record JOIN pending_action_reference reference ON reference.pending_action_id = '$cb122_pending_id' WHERE turn_record.turn_id = '$cb122_clarification_turn'"
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 409 "clarification replay rejects an orphan PendingAction reference" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-note-1' \
  --header 'Content-Type: application/json' \
  --data '{"message":"maybe change it"}'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_DURABLE_TRUTH_INCONSISTENT'
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 409 "evaluation rejects the same clarification orphan reference" \
  --request GET \
  "http://127.0.0.1:$agent_port/api/eval/evidence/$cb122_clarification_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT'
mysql_query root "$root_password" cs_db \
  "DELETE FROM pending_action_reference WHERE pending_action_id = '00000000-0000-0000-0000-000000000926'"

mysql_query root "$root_password" '' \
  "REVOKE UPDATE (state, resolved_at, resolution_turn_id, resolution_trace_id) ON cs_db.pending_action_reference FROM 'agent_app'@'%'"
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 503 "decline persistence denial is attributed and rolls back the local decision" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-decline-denied' \
  --header 'Content-Type: application/json' \
  --data '{"message":"decline"}'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_DECLINE_PERSISTENCE_UNAVAILABLE'
assert_equal 'PENDING:0:FAILED' \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT((SELECT state FROM pending_action_reference WHERE pending_action_id = '$cb122_pending_id'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_session' AND event_type = 'ACTION_DECLINED'), ':', (SELECT state FROM support_turn WHERE session_id = '$cb122_session' AND correlation_key = 'cb122-decline-denied'))")" \
  "denied decline commits no action transition or action event"
mysql_query root "$root_password" '' \
  "GRANT UPDATE (state, resolved_at, resolution_turn_id, resolution_trace_id) ON cs_db.pending_action_reference TO 'agent_app'@'%'"
assert_equal 'resolution_trace_id:UPDATE,resolution_turn_id:UPDATE,resolved_at:UPDATE,state:UPDATE' \
  "$(mysql_query root "$root_password" information_schema \
    "SELECT GROUP_CONCAT(CONCAT(column_name, ':', privilege_type) ORDER BY column_name, privilege_type) FROM column_privileges WHERE grantee = \"'agent_app'@'%'\" AND table_schema = 'cs_db' AND table_name = 'pending_action_reference'")" \
  "decline failure fixture restores the exact PendingAction column privileges"

mysql_query root "$root_password" '' \
  "CREATE TRIGGER cs_db.cb122_fail_decline_event BEFORE INSERT ON cs_db.support_event FOR EACH ROW SET NEW.sequence = IF(NEW.event_type = 'ACTION_DECLINED', 0, NEW.sequence)"
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 503 "ACTION_DECLINED insert failure rolls back the complete local decision" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-decline-event-rollback' \
  --header 'Content-Type: application/json' \
  --data '{"message":"decline"}'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_DECLINE_PERSISTENCE_UNAVAILABLE'
assert_equal 'PENDING:0:FAILED' \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT((SELECT state FROM pending_action_reference WHERE pending_action_id = '$cb122_pending_id'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_session' AND event_type = 'ACTION_DECLINED'), ':', (SELECT state FROM support_turn WHERE session_id = '$cb122_session' AND correlation_key = 'cb122-decline-event-rollback'))")" \
  "ACTION_DECLINED insertion failure leaves no partial local action truth"
mysql_query root "$root_password" '' \
  'DROP TRIGGER cs_db.cb122_fail_decline_event'

for cb122_event_failure in \
  'AGENT_OUTCOME:agent-outcome' \
  'ASSISTANT_RESPONSE:assistant-response' \
  'TURN_COMPLETED:turn-completed'; do
  cb122_event_type="${cb122_event_failure%%:*}"
  cb122_event_suffix="${cb122_event_failure#*:}"
  mysql_query root "$root_password" '' \
    "CREATE TRIGGER cs_db.cb122_fail_${cb122_event_suffix//-/_} BEFORE INSERT ON cs_db.support_event FOR EACH ROW SET NEW.sequence = IF(NEW.event_type = '$cb122_event_type', 0, NEW.sequence)"
  cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
  assert_status 503 "$cb122_event_type insert failure rolls back the complete local decision" \
    --request POST "http://127.0.0.1:$agent_port/api/chat" \
    --header "Authorization: Bearer $payment_token" \
    --header 'X-Eval-Sandbox-Id: sandbox-payment' \
    --header "X-Session-Id: $cb122_session" \
    --header "Idempotency-Key: cb122-decline-$cb122_event_suffix-rollback" \
    --header 'Content-Type: application/json' \
    --data '{"message":"decline"}'
  tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
    | grep -Fq 'reason_code=ACTION_DECLINE_PERSISTENCE_UNAVAILABLE'
  assert_equal 'PENDING:0:FAILED' \
    "$(mysql_query root "$root_password" cs_db \
      "SELECT CONCAT((SELECT state FROM pending_action_reference WHERE pending_action_id = '$cb122_pending_id'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_session' AND event_type = 'ACTION_DECLINED'), ':', (SELECT state FROM support_turn WHERE session_id = '$cb122_session' AND correlation_key = 'cb122-decline-$cb122_event_suffix-rollback'))")" \
    "$cb122_event_type insertion failure leaves no partial local action truth"
  mysql_query root "$root_password" '' \
    "DROP TRIGGER cs_db.cb122_fail_${cb122_event_suffix//-/_}"
done

mysql_query root "$root_password" '' \
  "CREATE TRIGGER cs_db.cb122_fail_decline_turn BEFORE UPDATE ON cs_db.support_turn FOR EACH ROW SET NEW.state = IF(NEW.outcome = 'action_declined', 'PROCESSING', NEW.state)"
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 503 "terminal turn update failure rolls back the complete local decision" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-decline-turn-rollback' \
  --header 'Content-Type: application/json' \
  --data '{"message":"decline"}'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_DECLINE_PERSISTENCE_UNAVAILABLE'
assert_equal 'PENDING:0:FAILED' \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT((SELECT state FROM pending_action_reference WHERE pending_action_id = '$cb122_pending_id'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_session' AND event_type = 'ACTION_DECLINED'), ':', (SELECT state FROM support_turn WHERE session_id = '$cb122_session' AND correlation_key = 'cb122-decline-turn-rollback'))")" \
  "terminal turn update failure leaves no partial local action truth"
mysql_query root "$root_password" '' \
  'DROP TRIGGER cs_db.cb122_fail_decline_turn'

assert_status 200 "exact decline commits only the local decision" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-decline' \
  --header 'Content-Type: application/json' \
  --data '{"message":"decline"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/cb122-declined.sse"
grep -Fq 'event: done' "$tmp_dir/cb122-declined.sse"
grep -Fq '"outcome":"action_declined"' "$tmp_dir/cb122-declined.sse"
if grep -Fq 'event: action_receipt' "$tmp_dir/cb122-declined.sse"; then
  echo "CB-122 decline emitted a forbidden ActionReceipt." >&2
  exit 1
fi
cb122_decline_turn="$(mysql_query root "$root_password" cs_db \
  "SELECT turn_id FROM support_turn WHERE session_id = '$cb122_session' AND correlation_key = 'cb122-decline'")"
cb122_decline_trace="$(mysql_query root "$root_password" cs_db \
  "SELECT trace_id FROM support_turn WHERE turn_id = '$cb122_decline_turn'")"
assert_equal 'DECLINED:action_declined:1:1' \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT(reference.state, ':', turn_record.outcome, ':', (SELECT COUNT(*) FROM support_event WHERE turn_id = turn_record.turn_id AND event_type = 'ACTION_DECLINED'), ':', (SELECT COUNT(*) FROM support_event WHERE turn_id = turn_record.turn_id AND event_type = 'TURN_COMPLETED')) FROM pending_action_reference reference JOIN support_turn turn_record ON turn_record.turn_id = '$cb122_decline_turn' WHERE reference.pending_action_id = '$cb122_pending_id'")" \
  "decline atomically closes reference, evidence, and terminal turn"
assert_equal "$cb122_commerce_before" \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT((SELECT COUNT(*) - 1 FROM pending_action WHERE order_id = '$payment_order_id'), ':', (SELECT COUNT(*) FROM action_receipt WHERE order_id = '$payment_order_id'), ':', (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':', (SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND'))")" \
  "CB-122 local decisions add only the one prepared commerce reference and no business mutation"

assert_status 200 "evaluation evidence validates CB-122 preparation closure" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$cb122_prepare_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
uv run python scripts/check_agent_evaluation_evidence.py "$tmp_dir/http-response.json" \
  --trace "$cb122_prepare_trace" --session="$cb122_session" --outcome action_pending \
  --require-event ACTION_PREPARED
assert_status 200 "evaluation evidence validates CB-122 decline closure" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$cb122_decline_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
uv run python scripts/check_agent_evaluation_evidence.py "$tmp_dir/http-response.json" \
  --trace "$cb122_decline_trace" --session="$cb122_session" --outcome action_declined \
  --require-event ACTION_DECLINED
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE pending_action_reference SET resolution_trace_id = '00000000-0000-0000-0000-000000000927' WHERE pending_action_id = '$cb122_pending_id'; SET FOREIGN_KEY_CHECKS = 1"
assert_cb122_action_closure_damage "a contradictory resolution trace binding"
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE pending_action_reference SET resolution_trace_id = '$cb122_decline_trace' WHERE pending_action_id = '$cb122_pending_id'; SET FOREIGN_KEY_CHECKS = 1"
cb122_clarification_trace="$(mysql_query root "$root_password" cs_db \
  "SELECT trace_id FROM support_turn WHERE turn_id = '$cb122_clarification_turn'")"
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE pending_action_reference SET resolution_turn_id = '$cb122_clarification_turn', resolution_trace_id = '$cb122_clarification_trace' WHERE pending_action_id = '$cb122_pending_id'; SET FOREIGN_KEY_CHECKS = 1"
assert_cb122_action_closure_damage "a resolution pointer to a non-decision turn"
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE pending_action_reference SET resolution_turn_id = '$cb122_decline_turn', resolution_trace_id = '$cb122_decline_trace' WHERE pending_action_id = '$cb122_pending_id'; SET FOREIGN_KEY_CHECKS = 1"
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE support_event SET trace_id = '00000000-0000-0000-0000-000000000928' WHERE turn_id = '$cb122_decline_turn' AND event_type = 'ACTION_DECLINED'; SET FOREIGN_KEY_CHECKS = 1"
assert_cb122_action_closure_damage "a cross-trace terminal action event"
mysql_query root "$root_password" cs_db \
  "SET FOREIGN_KEY_CHECKS = 0; UPDATE support_event SET trace_id = '$cb122_decline_trace' WHERE turn_id = '$cb122_decline_turn' AND event_type = 'ACTION_DECLINED'; SET FOREIGN_KEY_CHECKS = 1"
mysql_query root "$root_password" cs_db \
  "UPDATE support_turn SET outcome = 'completed' WHERE turn_id = '$cb122_decline_turn'; UPDATE support_event SET event_type = 'MODEL_OUTCOME', payload_json = JSON_OBJECT('result', 'success') WHERE turn_id = '$cb122_decline_turn' AND event_type = 'ACTION_DECLINED'"
cb122_resolution_scope_before="$(mysql_query root "$root_password" cs_db \
  "SELECT CONCAT((SELECT COUNT(*) FROM support_turn WHERE session_id = '$cb122_session'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_session'), ':', (SELECT COUNT(*) FROM pending_action_reference WHERE session_id = '$cb122_session'))")"
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 409 "conversation uses the resolution reference root after outcome and event-type damage" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-decline' \
  --header 'Content-Type: application/json' \
  --data '{"message":"decline"}'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_DURABLE_TRUTH_INCONSISTENT'
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 409 "evaluation uses the resolution reference root after outcome and event-type damage" \
  --request GET "http://127.0.0.1:$agent_port/api/eval/evidence/$cb122_decline_trace" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT'
assert_equal "$cb122_resolution_scope_before" \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT((SELECT COUNT(*) FROM support_turn WHERE session_id = '$cb122_session'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_session'), ':', (SELECT COUNT(*) FROM pending_action_reference WHERE session_id = '$cb122_session'))")" \
  "resolution-root classification creates zero local durable effects"
assert_equal "$cb122_proxy_calls_before_replay" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')" \
  "resolution-root classification does not call commerce"
assert_equal "$cb122_model_calls_before_replay" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r '.["action-prepare:total"]')" \
  "resolution-root classification does not call the model"
mysql_query root "$root_password" cs_db \
  "UPDATE support_turn SET outcome = 'action_declined' WHERE turn_id = '$cb122_decline_turn'; UPDATE support_event SET event_type = 'ACTION_DECLINED', payload_json = JSON_OBJECT('pendingActionId', '$cb122_pending_id', 'outcome', 'declined') WHERE turn_id = '$cb122_decline_turn' AND event_type = 'MODEL_OUTCOME'"

stop_process agent_pid "$agent_pid"
start_agent true "http://127.0.0.1:$proxy_port"
assert_status 200 "CB-122 restart replays the durable preparation closure" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_session" \
  --header 'Idempotency-Key: cb122-response-loss' \
  --header 'Content-Type: application/json' \
  --data '{"message":"action-prepare"}'
cmp "$tmp_dir/cb122-prepared.json" "$tmp_dir/http-response.json"
assert_equal "$cb122_proxy_calls_before_replay" \
  "$(curl --silent --show-error "http://127.0.0.1:$proxy_port/fixture/counts" \
    | jq -r --arg session "$cb122_session" '.["action-proxy:" + $session]')" \
  "restart replay performs no model or commerce prepare"
assert_status 201 "CB-122 failed-local-commit session is isolated" \
  --request POST "http://127.0.0.1:$agent_port/api/sessions" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Content-Type: application/json' \
  --data '{}'
cb122_failed_commit_session="$(uv run python scripts/read_json_field.py \
  "$tmp_dir/http-response.json" sessionId)"
mysql_query root "$root_password" '' \
  "CREATE TRIGGER cs_db.cb122_fail_reference_insert BEFORE INSERT ON cs_db.pending_action_reference FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'controlled pending reference failure'"
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 503 "PendingAction reference insert failure rolls back preparation evidence" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_failed_commit_session" \
  --header 'Idempotency-Key: cb122-reference-insert-rollback' \
  --header 'Content-Type: application/json' \
  --data '{"message":"action-prepare"}'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE'
assert_equal '0:0:FAILED' \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT((SELECT COUNT(*) FROM pending_action_reference WHERE session_id = '$cb122_failed_commit_session'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_failed_commit_session' AND event_type = 'ACTION_PREPARED'), ':', (SELECT state FROM support_turn WHERE session_id = '$cb122_failed_commit_session' AND correlation_key = 'cb122-reference-insert-rollback'))")" \
  "failed reference insertion leaves no partial local preparation closure"
mysql_query root "$root_password" '' \
  'DROP TRIGGER cs_db.cb122_fail_reference_insert'
stop_process agent_pid "$agent_pid"
stop_process model_pid "$model_pid"
stop_process commerce_pid "$commerce_pid"
start_commerce evaluation "http://127.0.0.1:$auth_port" 1m
uv run python scripts/fake_litellm_server.py --port 0 \
  --commerce-base-url "http://127.0.0.1:$commerce_port" \
  >>"$tmp_dir/cb122-expiry-model.log" 2>&1 &
model_pid=$!
process_bound_port proxy_port uvicorn "$model_pid" "$tmp_dir/cb122-expiry-model.log" 0
wait_http "http://127.0.0.1:$proxy_port/fixture/counts" \
  "$model_pid" "$tmp_dir/cb122-expiry-model.log"
start_agent true "http://127.0.0.1:$proxy_port"
assert_status 201 "CB-122 expiry session binds the payment principal and sandbox" \
  --request POST "http://127.0.0.1:$agent_port/api/sessions" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header 'Content-Type: application/json' \
  --data '{}'
cb122_expiry_session="$(uv run python scripts/read_json_field.py \
  "$tmp_dir/http-response.json" sessionId)"
assert_status 200 "CB-122 prepares a short-lived action for deterministic expiry" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_expiry_session" \
  --header 'Idempotency-Key: cb122-expiry-prepare' \
  --header 'Content-Type: application/json' \
  --data '{"message":"action-prepare"}'
cb122_expiry_prepare_turn="$(uv run python scripts/read_json_field.py \
  "$tmp_dir/http-response.json" turnId)"
cb122_expiry_pending_id="$(mysql_query root "$root_password" cs_db \
  "SELECT pending_action_id FROM pending_action_reference WHERE source_turn_id = '$cb122_expiry_prepare_turn'")"
for _ in $(seq 1 750); do
  if [[ "$(mysql_query root "$root_password" cs_db \
    "SELECT expires_at <= CURRENT_TIMESTAMP(6) FROM pending_action_reference WHERE pending_action_id = '$cb122_expiry_pending_id'")" == 1 ]]; then
    break
  fi
  sleep 0.1
done
assert_equal 1 \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT expires_at <= CURRENT_TIMESTAMP(6) FROM pending_action_reference WHERE pending_action_id = '$cb122_expiry_pending_id'")" \
  "expiry fixture waits for the recorded database deadline"
mysql_query root "$root_password" '' \
  "CREATE TRIGGER cs_db.cb122_fail_expiry_event BEFORE INSERT ON cs_db.support_event FOR EACH ROW SET NEW.sequence = IF(NEW.event_type = 'ACTION_EXPIRED', 0, NEW.sequence)"
cb122_agent_log_start="$(wc -l <"$tmp_dir/agent.log")"
assert_status 503 "ACTION_EXPIRED insert failure rolls back the complete local decision" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_expiry_session" \
  --header 'Idempotency-Key: cb122-expiry-event-rollback' \
  --header 'Content-Type: application/json' \
  --data '{"message":"expire"}'
tail -n "+$((cb122_agent_log_start + 1))" "$tmp_dir/agent.log" \
  | grep -Fq 'reason_code=ACTION_EXPIRY_PERSISTENCE_UNAVAILABLE'
assert_equal 'PENDING:0:FAILED' \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT((SELECT state FROM pending_action_reference WHERE pending_action_id = '$cb122_expiry_pending_id'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$cb122_expiry_session' AND event_type = 'ACTION_EXPIRED'), ':', (SELECT state FROM support_turn WHERE session_id = '$cb122_expiry_session' AND correlation_key = 'cb122-expiry-event-rollback'))")" \
  "ACTION_EXPIRED insertion failure leaves no partial local action truth"
mysql_query root "$root_password" '' \
  'DROP TRIGGER cs_db.cb122_fail_expiry_event'
assert_status 200 "CB-122 expiry commits only the local expired decision" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $payment_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Session-Id: $cb122_expiry_session" \
  --header 'Idempotency-Key: cb122-end-1' \
  --header 'Content-Type: application/json' \
  --data '{"message":"anything"}'
jq -e '.outcome == "action_expired"' "$tmp_dir/http-response.json" >/dev/null
assert_equal 'EXPIRED:1:0' \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT(reference.state, ':', (SELECT COUNT(*) FROM support_event event_record JOIN support_turn turn_record ON turn_record.turn_id = event_record.turn_id WHERE turn_record.session_id = '$cb122_expiry_session' AND event_record.event_type = 'ACTION_EXPIRED'), ':', (SELECT COUNT(*) FROM support_event event_record JOIN support_turn turn_record ON turn_record.turn_id = event_record.turn_id WHERE turn_record.session_id = '$cb122_expiry_session' AND event_record.event_type = 'ACTION_RECEIPT')) FROM pending_action_reference reference WHERE reference.pending_action_id = '$cb122_expiry_pending_id'")" \
  "expiry writes one exact local event and no receipt"
curl --fail --silent --show-error \
  "http://127.0.0.1:$agent_port/internal/metrics/prometheus" \
  >"$tmp_dir/cb150-agent-metrics.txt"
for expected_metric in \
  'citybuddy_agent_operation_requests_total{operation="pending_action_prepare",outcome="success"} 1.0' \
  'citybuddy_agent_operation_requests_total{operation="pending_action_expiry",outcome="unavailable"} 1.0' \
  'citybuddy_agent_operation_requests_total{operation="pending_action_expiry",outcome="expired"} 1.0' \
  'citybuddy_agent_operation_requests_total{operation="chat_turn",outcome="pending"} 1.0' \
  'citybuddy_agent_operation_requests_total{operation="chat_turn",outcome="unavailable"} 1.0' \
  'citybuddy_agent_operation_requests_total{operation="chat_turn",outcome="expired"} 1.0'; do
  grep -Fq "$expected_metric" "$tmp_dir/cb150-agent-metrics.txt"
done
if grep -Eq 'confirmation|traceId|sessionId|turnId|pendingActionId|python_gc|process_' \
  "$tmp_dir/cb150-agent-metrics.txt"; then
  echo "Agent metrics exposed an out-of-scope operation, identifier, or default collector." >&2
  exit 1
fi
stop_process agent_pid "$agent_pid"
stop_process model_pid "$model_pid"
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
assert_status 200 "payment-first completion serializes after the committed callback" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/sandboxes/sandbox-payment/complete" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: complete-payment' \
  --header 'Content-Type: application/json' \
  --data '{"caseCorrelation":"case-payment"}'
action_effects_before_dead_replay="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT((SELECT COUNT(*) FROM action_receipt WHERE pending_action_id = '$action_pending_id'), ':', (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':', (SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND' AND aggregate_id = '$action_refund_id'))")"
assert_status 200 "completed sandbox replays committed ActionReceipt before mutable liveness" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/actions/$action_pending_id/confirm" \
  --header "Authorization: Bearer $action_obo_token" \
  --header "X-Support-Session-Id: $action_session" \
  --header 'X-Eval-Sandbox-Id: sandbox-payment' \
  --header "X-Agent-Trace-Id: $action_trace" \
  --header "X-Agent-Turn-Id: $action_turn"
assert_equal true \
  "$(jq -r '.replayed' "$tmp_dir/http-response.json")" \
  "committed Action replay is explicitly durable"
assert_equal "$action_effects_before_dead_replay" \
  "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
    "SELECT CONCAT((SELECT COUNT(*) FROM action_receipt WHERE pending_action_id = '$action_pending_id'), ':', (SELECT COUNT(*) FROM mock_refund WHERE order_id = '$payment_order_id'), ':', (SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND' AND aggregate_id = '$action_refund_id'))")" \
  "completed-sandbox Action replay creates zero effects"
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
invalid_obo_operation="$(openssl rand -hex 32)"
assert_status_reason 403 TOOL_OBO_AUTHORIZATION_REJECTED Forbidden \
  "direct token cannot replace OBO authorization" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $direct_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'X-Agent-Trace-Id: invalid-obo-trace' \
  --header "X-Agent-Operation-Id: $invalid_obo_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
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
  | sed -n 's/.*evaluation_request_rejected .*reason_code=\([^ ]*\).*/\1/p' \
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
assert_equal 'ACTIVE:1' \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT(lifecycle_state, ':', expires_at > CURRENT_TIMESTAMP(6)) FROM eval_sandbox WHERE sandbox_id = 'sandbox-main'")" \
  "audit-persistence probe begins with an active unexpired sandbox"
assert_equal 1 \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT COUNT(*) FROM eval_sandbox_product_fixture WHERE sandbox_id = 'sandbox-main' AND product_id = 'product-1'")" \
  "audit-persistence probe begins with one visible product fixture"
assert_equal "$direct_subject:sandbox-main" \
  "$(mysql_query root "$root_password" cs_db \
    "SELECT CONCAT(user_subject, ':', sandbox_id) FROM support_session WHERE session_id = '$session_id'")" \
  "audit-persistence probe preserves support-session sandbox binding"
assert_equal '0:0' \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT((SELECT COUNT(*) FROM eval_commerce_product_observation WHERE operation_id = '$failed_operation'), ':', (SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE operation_id = '$failed_operation'))")" \
  "audit-persistence probe uses a fresh operation"
audit_denials_before="$(mysql_query root "$root_password" performance_schema \
  "SELECT COALESCE(SUM(sum_error_raised), 0) FROM events_errors_summary_by_account_by_error WHERE user = 'commerce_app' AND error_number = 1142")"
mysql_query root "$root_password" '' \
  "REVOKE INSERT ON commerce_db.eval_commerce_audit_reference FROM 'commerce_app'@'%'"
commerce_log_start="$(wc -l <"$tmp_dir/commerce.log")"
failed_status="$(request_status "$tmp_dir/audit-unavailable-single.json" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header "X-Agent-Trace-Id: $direct_trace" \
  --header "X-Agent-Operation-Id: $failed_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}')"
failed_request_logs="$(
  tail -n "+$((commerce_log_start + 1))" "$tmp_dir/commerce.log" \
    | grep -E 'evaluation_(audit_failure|request_rejected)' || true
)"
audit_denials_after_single="$(mysql_query root "$root_password" performance_schema \
  "SELECT COALESCE(SUM(sum_error_raised), 0) FROM events_errors_summary_by_account_by_error WHERE user = 'commerce_app' AND error_number = 1142")"
single_observation_residue="$(mysql_query root "$root_password" commerce_db \
  "SELECT COUNT(*) FROM eval_commerce_product_observation WHERE operation_id = '$failed_operation'")"
single_audit_residue="$(mysql_query root "$root_password" commerce_db \
  "SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE operation_id = '$failed_operation'")"
echo "audit-persistence-probe status=$failed_status account_1142_delta=$((audit_denials_after_single - audit_denials_before)) observation_residue=$single_observation_residue audit_residue=$single_audit_residue"
printf '%s\n' "$failed_request_logs"
if [[ "$failed_status" != 503 ]]; then
  printf '%s' "$failed_status" >"$tmp_dir/audit-unavailable-single.status"
  report_audit_unavailability_misclassification \
    "single audit persistence request" \
    "$tmp_dir/audit-unavailable-single.status" \
    "$tmp_dir/audit-unavailable-single.json"
fi
assert_equal 'Service unavailable' \
  "$(uv run python scripts/read_json_field.py "$tmp_dir/audit-unavailable-single.json" error)" \
  "audit-persistence failure exposes only the fixed unavailable response"
assert_equal 1 "$((audit_denials_after_single - audit_denials_before))" \
  "single unavailable tool request reaches the revoked audit INSERT boundary"
assert_equal 0 "$single_observation_residue" \
  "single unavailable tool request rolls back product observation"
assert_equal 0 "$single_audit_residue" \
  "single unavailable tool request leaves no audit reference"
assert_equal 1 \
  "$(printf '%s\n' "$failed_request_logs" | grep -c \
    'producer_boundary=AUDIT_REFERENCE_INSERT reason_code=TOOL_AUDIT_PERSISTENCE_UNAVAILABLE product_fixture_read=true product_observation_insert=true audit_reference_insert=true transaction_rollback_required=true')" \
  "audit-persistence failure records its exact producer and reached phases"
assert_equal 1 \
  "$(printf '%s\n' "$failed_request_logs" | grep -c \
    'producer_boundary=EVALUATION_SANDBOX_EXCEPTION original_status=503 reason_code=TOOL_AUDIT_PERSISTENCE_UNAVAILABLE')" \
  "audit-persistence failure records its original status and attribution"

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
missing_product_operation="$(openssl rand -hex 32)"
assert_status_reason 404 TOOL_PRODUCT_NOT_FOUND 'Not found' \
  "missing evaluation product keeps its not-found family" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'X-Agent-Trace-Id: missing-product-trace' \
  --header "X-Agent-Operation-Id: $missing_product_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"missing-product"}'
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
assert_status_reason 409 TOOL_AUDIT_OPERATION_CONFLICT Conflict \
  "same operation rejects conflicting trace reuse" \
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
  --trace "$trace_id" --session="$session_id" --outcome completed \
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
  --trace "$retrieval_trace" --session="$session_id" --outcome retrieval_denied \
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
  --trace "$sufficient_trace" --session="$session_id" --outcome completed \
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
  --trace "$provider_trace" --session="$session_id" --outcome provider_denied \
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
  --sandbox sandbox-main --session="$session_id" --count 2 --trace "$trace_id"
assert_status 200 "audit first page is bounded and has a stable cursor" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id?limit=1" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
uv run python scripts/check_evaluation_views.py audit "$tmp_dir/http-response.json" \
  --sandbox sandbox-main --session="$session_id" --count 1 --next-cursor
first_sequence="$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT MIN(sequence_id) FROM eval_commerce_audit_reference WHERE sandbox_id = 'sandbox-main' AND support_session_id = '$session_id'")"
assert_status 200 "audit cursor advances deterministically" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id?limit=1&after=$first_sequence" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
uv run python scripts/check_evaluation_views.py audit "$tmp_dir/http-response.json" \
  --sandbox sandbox-main --session="$session_id" --count 1
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
inactive_tool_operation="$(openssl rand -hex 32)"
assert_status_reason 403 TOOL_SANDBOX_NOT_ACTIVE Forbidden \
  "completion immediately blocks the evaluation product tool" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'X-Agent-Trace-Id: inactive-tool-trace' \
  --header "X-Agent-Operation-Id: $inactive_tool_operation" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
assert_equal '0:0' \
  "$(mysql_query root "$root_password" commerce_db \
    "SELECT CONCAT((SELECT COUNT(*) FROM eval_commerce_product_observation WHERE operation_id = '$inactive_tool_operation'), ':', (SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE operation_id = '$inactive_tool_operation'))")" \
  "inactive evaluation product tool leaves no durable residue"
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
  "$agent_service_password" "$mock_payment_secret" "$payment_token" "$payment_signature"; do
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
