#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb101-test-$$"
auth_port="$((44900 + ($$ % 200)))"
commerce_port="$((45100 + ($$ % 200)))"
agent_port="$((45300 + ($$ % 200)))"
proxy_port="$((45500 + ($$ % 200)))"
drop_proxy_port="$((45700 + ($$ % 200)))"
export MYSQL_PORT="$((33900 + ($$ % 200)))"
export REDIS_COMMERCE_PORT="$((6390 + ($$ % 200)))"
export REDIS_SUPPORT_PORT="$((6590 + ($$ % 200)))"
export ELASTICSEARCH_PORT="$((9290 + ($$ % 200)))"
export ROCKETMQ_NAMESRV_PORT="$((9990 + ($$ % 200)))"
export ROCKETMQ_BROKER_PORT="$((11190 + ($$ % 200)))"
export ROCKETMQ_PROXY_PORT="$((8290 + ($$ % 200)))"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
auth_pid=""
commerce_pid=""
agent_pid=""
model_pid=""
drop_proxy_pid=""

cleanup() {
  for pid in "$agent_pid" "$commerce_pid" "$auth_pid" "$model_pid" "$drop_proxy_pid"; do
    if [[ -n "$pid" ]]; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
  done
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
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
  status="$(request_status "$tmp_dir/http-response.json" "$@")"
  if [[ "$status" != "$expected" ]]; then
    echo "Unexpected HTTP status for $label: $status" >&2
    cat "$tmp_dir/http-response.json" >&2
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

wait_http() {
  local url="$1"
  local pid="$2"
  local log="$3"
  for _ in {1..90}; do
    if [[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "$url" 2>/dev/null)" != 000 ]]; then
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
  if [[ "$profile" == evaluation ]]; then
    profile_argument=(--spring.profiles.active=evaluation)
  fi
  SPRING_DATASOURCE_PASSWORD="$auth_app_password" \
    java -jar auth-service/target/auth-service-0.0.1-SNAPSHOT.jar \
    --server.port="$auth_port" \
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
    ${profile_argument[@]+"${profile_argument[@]}"} \
    >>"$tmp_dir/auth.log" 2>&1 &
  auth_pid=$!
  wait_http "http://127.0.0.1:$auth_port/auth/jwks" "$auth_pid" "$tmp_dir/auth.log"
}

start_commerce() {
  local profile="$1"
  local auth_base="$2"
  local -a profile_argument=()
  if [[ "$profile" == evaluation ]]; then
    profile_argument=(--spring.profiles.active=evaluation)
  fi
  SPRING_DATASOURCE_PASSWORD="$commerce_app_password" \
    java -jar commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar \
    --server.port="$commerce_port" \
    --spring.datasource.url="jdbc:mysql://127.0.0.1:$MYSQL_PORT/commerce_db?useSSL=false&allowPublicKeyRetrieval=true" \
    --spring.datasource.username=commerce_app \
    --citybuddy.obo.enabled=true \
    --citybuddy.obo.issuer=https://identity.citybuddy.test \
    --citybuddy.obo.jwks-url="http://127.0.0.1:$auth_port/auth/jwks" \
    --citybuddy.agent-tools.enabled=true \
    --citybuddy.evaluation.management-client-id=evaluation-manager \
    --citybuddy.evaluation.management-client-secret="$management_password" \
    --citybuddy.evaluation.auth-base-url="$auth_base" \
    --citybuddy.evaluation.auth-client-id=commerce-service \
    --citybuddy.evaluation.auth-client-secret="$commerce_service_password" \
    --citybuddy.evaluation.identity-issuer=https://identity.citybuddy.test \
    --citybuddy.evaluation.user-audience=citybuddy-web \
    --citybuddy.evaluation.jwks-url="http://127.0.0.1:$auth_port/auth/jwks" \
    --citybuddy.evaluation.provisioning-timeout=10s \
    --citybuddy.evaluation.auth-expiry-safety=2s \
    --citybuddy.evaluation.cleanup-retry=1s \
    --citybuddy.evaluation.janitor-interval=5s \
    --citybuddy.evaluation.max-cleanup-attempts=5 \
    --citybuddy.evaluation.janitor-batch-size=4 \
    --citybuddy.evaluation.build-id=cb102-integration-build \
    --citybuddy.evaluation.schema-compatibility=commerce-evaluation-v1 \
    ${profile_argument[@]+"${profile_argument[@]}"} \
    >>"$tmp_dir/commerce.log" 2>&1 &
  commerce_pid=$!
  wait_http "http://127.0.0.1:$commerce_port/api/products" "$commerce_pid" "$tmp_dir/commerce.log"
}

start_agent() {
  local evaluation_enabled="$1"
  AGENT_PORT="$agent_port" \
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
  wait_http "http://127.0.0.1:$agent_port/api/sessions" "$agent_pid" "$tmp_dir/agent.log"
}

reset_body() {
  local sandbox="$1"
  local case_id="$2"
  local product_name="$3"
  printf '{"sandboxId":"%s","caseCorrelation":"%s","ttlSeconds":60,"testUserLabel":"user-%s","products":[{"productId":"product-1","name":"%s","description":"sandbox fixture","priceMinor":900,"currency":"CNY","stockQuantity":3,"available":true}]}' \
    "$sandbox" "$case_id" "$sandbox" "$product_name"
}

reset_sandbox() {
  local sandbox="$1"
  local case_id="$2"
  local key="$3"
  local product_name="$4"
  assert_status 200 "reset $sandbox" \
    --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
    --user "evaluation-manager:$management_password" \
    --header "Idempotency-Key: $key" \
    --header 'Content-Type: application/json' \
    --data "$(reset_body "$sandbox" "$case_id" "$product_name")"
}

ENV_FILE="$env_file" ./scripts/init_local.sh
auth_app_password="$(read_value MYSQL_AUTH_APP_PASSWORD)"
commerce_app_password="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
agent_app_password="$(read_value MYSQL_AGENT_APP_PASSWORD)"
root_password="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
commerce_service_password="$(openssl rand -hex 24)"
evaluator_password="$(openssl rand -hex 24)"
agent_service_password="$(openssl rand -hex 24)"
management_password="$(openssl rand -hex 24)"
invalid_management_password="$(openssl rand -hex 24)"
commerce_service_hash="$(uv run python scripts/hash_test_credential.py "$commerce_service_password")"
evaluator_hash="$(uv run python scripts/hash_test_credential.py "$evaluator_password")"
agent_service_hash="$(uv run python scripts/hash_test_credential.py "$agent_service_password")"

"${compose[@]}" up --detach --wait --wait-timeout 60 mysql
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" \
  migrate-auth migrate-commerce migrate-agent
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access

mysql_query auth_app "$auth_app_password" commerce_db "
INSERT INTO auth_service_identity (service_id, client_id, credential_hash, state, allowed_scopes) VALUES
  ('00000000-0000-0000-0000-000000000101', 'commerce-service', '$commerce_service_hash', 'ACTIVE', 'eval:principal:manage'),
  ('00000000-0000-0000-0000-000000000102', 'evaluation-client', '$evaluator_hash', 'ACTIVE', 'eval:test-token:issue'),
  ('00000000-0000-0000-0000-000000000103', 'agent-service', '$agent_service_hash', 'ACTIVE', 'catalog:read');
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
assert_mysql_fails "commerce audit references are append-only" \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET outcome = 'OBSERVED' WHERE sequence_id = 1"
assert_mysql_fails "commerce audit references cannot be deleted by runtime" \
  mysql_query commerce_app "$commerce_app_password" commerce_db \
  'DELETE FROM eval_commerce_audit_reference WHERE sequence_id = 1'
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
assert_status 401 "reset rejects substituted management credential" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-client:$invalid_management_password" \
  --header 'Idempotency-Key: reset-main' \
  --header 'Content-Type: application/json' \
  --data "$(reset_body sandbox-main case-main sandbox-product)"
assert_status 400 "reset rejects unbounded fixture set" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: reset-main' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-main","caseCorrelation":"case-main","ttlSeconds":60,"testUserLabel":"user-main","products":[]}'

reset_sandbox sandbox-main case-main reset-main sandbox-product
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

reset_sandbox sandbox-main case-main reset-main sandbox-product
cmp "$tmp_dir/reset-main.json" "$tmp_dir/http-response.json"
assert_status 409 "same reset key rejects fixture mutation" \
  --request POST "http://127.0.0.1:$commerce_port/api/eval/reset" \
  --user "evaluation-manager:$management_password" \
  --header 'Idempotency-Key: reset-main' \
  --header 'Content-Type: application/json' \
  --data "$(reset_body sandbox-main case-main changed-product)"
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
  --maximum-expiry "$(date -u -v+61S +%s 2>/dev/null || date -u -d '+61 seconds' +%s)" \
  --output "$tmp_dir/direct.json"
direct_subject="$(uv run python scripts/read_json_field.py "$tmp_dir/direct.json" subject)"

assert_status 204 "token header path and registry liveness agree" \
  --request POST "http://127.0.0.1:$commerce_port/internal/eval/sandboxes/sandbox-main/liveness" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'

uv run python scripts/fake_litellm_server.py --port "$proxy_port" >>"$tmp_dir/model.log" 2>&1 &
model_pid=$!
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
direct_trace="direct-trace-$(openssl rand -hex 8)"
direct_operation="$(openssl rand -hex 32)"
failed_operation="$(openssl rand -hex 32)"
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
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_version = 2 WHERE sandbox_id = 'sandbox-main' AND support_session_id = '$session_id' LIMIT 1"
assert_status 409 "audit fails closed on mismatched authoritative version" \
  --request GET "http://127.0.0.1:$commerce_port/api/eval/audit/$session_id" \
  --user "evaluation-manager:$management_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-main'
mysql_query root "$root_password" commerce_db \
  "UPDATE eval_commerce_audit_reference SET entity_version = 1 WHERE sandbox_id = 'sandbox-main' AND support_session_id = '$session_id'"
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
  --data "$(reset_body sandbox-main case-main sandbox-product)"
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
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access >/dev/null
test "$(mysql_query commerce_app "$commerce_app_password" commerce_db \
  "SELECT CONCAT(lifecycle_state, ':', auth_invalidation_state, ':', closed_at IS NOT NULL) FROM eval_sandbox WHERE sandbox_id = 'sandbox-fixture-failure'")" = 'DEAD:REVOKED:1'
test "$(mysql_query auth_app "$auth_app_password" commerce_db \
  "SELECT COUNT(*) FROM auth_eval_test_principal WHERE sandbox_id = 'sandbox-fixture-failure' AND state = 'REVOKED'")" = 1

# Auth commits provisioning but every response is lost. A commerce restart must recover by key.
uv run python scripts/drop_response_proxy.py \
  --port "$drop_proxy_port" --upstream "http://127.0.0.1:$auth_port" \
  --path-prefix /internal/eval/test-principals/provision --drop-count 20 \
  >>"$tmp_dir/drop-proxy.log" 2>&1 &
drop_proxy_pid=$!
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
  "$agent_service_password"; do
  if grep -Fq "$private_value" "$tmp_dir/auth.log" "$tmp_dir/commerce.log" "$tmp_dir/agent.log"; then
    echo "Private evaluation credential leaked into service logs." >&2
    exit 1
  fi
done

echo "CB-101/CB-102 evaluation lifecycle, state, audit, version, profile, grant, restart, liveness, and redaction integration passed."
