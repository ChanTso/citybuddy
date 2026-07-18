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
    ${profile_argument[@]+"${profile_argument[@]}"} \
    >>"$tmp_dir/commerce.log" 2>&1 &
  commerce_pid=$!
  wait_http "http://127.0.0.1:$commerce_port/api/products" "$commerce_pid" "$tmp_dir/commerce.log"
}

start_agent() {
  AGENT_PORT="$agent_port" \
  AGENT_IDENTITY_ENABLED=true \
  AGENT_EVALUATION_ENABLED=true \
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
assert_mysql_fails "commerce runtime cannot read auth provisioning truth" \
  mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT * FROM auth_eval_test_principal'
assert_mysql_fails "commerce runtime cannot execute DDL" \
  mysql_query commerce_app "$commerce_app_password" commerce_db 'CREATE TABLE forbidden_cb101 (id INT)'
commerce_grants="$(mysql_query commerce_app "$commerce_app_password" '' 'SHOW GRANTS FOR CURRENT_USER')"
grep -Fq 'GRANT SELECT, INSERT, UPDATE ON `commerce_db`.`eval_sandbox`' <<<"$commerce_grants"
grep -Fq 'GRANT SELECT, INSERT, UPDATE, DELETE ON `commerce_db`.`eval_sandbox_product_fixture`' <<<"$commerce_grants"
grep -Fq 'GRANT SELECT, INSERT ON `commerce_db`.`eval_sandbox_effect_stub`' <<<"$commerce_grants"
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
assert_status 404 "production profile omits liveness" \
  --request POST "http://127.0.0.1:$commerce_port/internal/eval/sandboxes/sandbox-production/liveness"
stop_process commerce_pid "$commerce_pid"
stop_process auth_pid "$auth_pid"

start_auth evaluation
start_commerce evaluation "http://127.0.0.1:$auth_port"
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
start_agent
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
assert_status 200 "OBO tool reads only the exact sandbox fixture" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
test "$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" name)" = sandbox-product
assert_status 403 "OBO tool rejects sandbox substitution" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'X-Eval-Sandbox-Id: sandbox-other' \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
assert_status 200 "evaluation chat executes sandbox-bound OBO tool" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-main' \
  --header "X-Session-Id: $session_id" \
  --header 'Idempotency-Key: cb101-tool-turn' \
  --header 'Content-Type: application/json' \
  --data '{"message":"tool-success"}'
trace_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db \
  "SELECT GROUP_CONCAT(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.state')) ORDER BY sequence SEPARATOR ',') FROM support_event WHERE trace_id = '$trace_id' AND event_type = 'TOOL_LIFECYCLE'")" = 'requested,succeeded'
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

echo "CB-101 evaluation sandbox reset, compensation, lifecycle, SQL, RS256/OBO, agent liveness, profile, grant, restart, janitor, effect-stub, and redaction integration passed."
