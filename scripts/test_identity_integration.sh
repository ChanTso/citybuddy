#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
source "$repo_root/scripts/test_dynamic_ports.sh"
source "$repo_root/scripts/surefire_evidence.sh"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb020-test-$$"
auth_port=""
agent_port=""
commerce_port=""
proxy_port=""
timeout_agent_port=""
MYSQL_PORT=""
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
auth_pid=""
agent_pid=""
commerce_pid=""
proxy_pid=""
timeout_agent_pid=""

cleanup() {
  local status=$?
  local resource_stop_status=0
  if [[ -n "$agent_pid" ]]; then
    kill "$agent_pid" >/dev/null 2>&1 || true
    wait "$agent_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$auth_pid" ]]; then
    kill "$auth_pid" >/dev/null 2>&1 || true
    wait "$auth_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$commerce_pid" ]]; then
    kill "$commerce_pid" >/dev/null 2>&1 || true
    wait "$commerce_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$proxy_pid" ]]; then
    kill "$proxy_pid" >/dev/null 2>&1 || true
    wait "$proxy_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$timeout_agent_pid" ]]; then
    kill "$timeout_agent_pid" >/dev/null 2>&1 || true
    wait "$timeout_agent_pid" >/dev/null 2>&1 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || resource_stop_status=$?
  rm -rf "$tmp_dir"
  finish_test_cleanup "$status" "$resource_stop_status"
}

wait_port() {
  local url="$1"
  local pid="$2"
  local log="$3"
  for _ in {1..60}; do
    if [[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "$url")" != 000 ]]; then
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

assert_mysql_rejects() {
  local label="$1"
  local pattern="$2"
  shift 2
  if "$@" >"$tmp_dir/mysql-rejection.log" 2>&1; then
    echo "Expected MySQL rejection succeeded: $label" >&2
    exit 1
  fi
  if ! grep -Eq "$pattern" "$tmp_dir/mysql-rejection.log"; then
    echo "MySQL rejection used an unexpected error for $label:" >&2
    cat "$tmp_dir/mysql-rejection.log" >&2
    exit 1
  fi
  echo "Verified MySQL rejection: $label"
}

assert_mysql_fails() {
  local label="$1"
  shift
  assert_mysql_rejects "$label" 'Access denied|command denied' "$@"
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
    exit 1
  fi
  echo "Verified HTTP $expected: $label"
}

wait_http() {
  local url="$1"
  local pid="$2"
  local log="$3"
  for _ in {1..60}; do
    if curl --silent --fail "$url" >/dev/null 2>&1; then
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

ENV_FILE="$env_file" ./scripts/init_local.sh
auth_app_password="$(read_value MYSQL_AUTH_APP_PASSWORD)"
bootstrap_password="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
commerce_app_password="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
commerce_migration_password="$(read_value MYSQL_COMMERCE_MIGRATION_PASSWORD)"
agent_app_password="$(read_value MYSQL_AGENT_APP_PASSWORD)"
agent_migration_password="$(read_value MYSQL_AGENT_MIGRATION_PASSWORD)"
user_password="$(openssl rand -hex 24)"
other_password="$(openssl rand -hex 24)"
disabled_password="$(openssl rand -hex 24)"
service_password="$(openssl rand -hex 24)"
user_hash="$(uv run python scripts/hash_test_credential.py "$user_password")"
other_hash="$(uv run python scripts/hash_test_credential.py "$other_password")"
disabled_hash="$(uv run python scripts/hash_test_credential.py "$disabled_password")"
service_hash="$(uv run python scripts/hash_test_credential.py "$service_password")"

"${compose[@]}" up --detach --wait --wait-timeout 60 mysql
compose_host_port MYSQL_PORT mysql 3306
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-auth

# A complete-looking evaluation table chain without the commerce runtime schema must not be
# normalized into the auth-only state or receive any optional commerce/migration authority.
mysql_query root "$bootstrap_password" commerce_db "
CREATE TABLE eval_sandbox (id INT);
CREATE TABLE eval_sandbox_effect_stub (id INT);
CREATE TABLE eval_sandbox_product_fixture (id INT);
CREATE TABLE eval_commerce_audit_reference (id INT);
CREATE TABLE eval_commerce_product_observation (id INT);
CREATE TABLE eval_commerce_audit_legacy_watermark (id INT) COMMENT='V013_AWAITING_COMMITMENT';
"
assert_mysql_rejects "auth-only schema rejects commerce evaluation table chain" \
  'rejects commerce evaluation tables without the commerce runtime schema' \
  make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
commerce_grants_after_schema_rejection="$(mysql_query commerce_app "$commerce_app_password" '' \
  'SHOW GRANTS FOR CURRENT_USER')"
if grep -Eq 'eval_(sandbox|commerce)' <<<"$commerce_grants_after_schema_rejection"; then
  echo "Auth-only schema rejection granted commerce evaluation authority." >&2
  exit 1
fi
migration_grants_after_schema_rejection="$(mysql_query commerce_migration \
  "$commerce_migration_password" '' 'SHOW GRANTS FOR CURRENT_USER')"
if grep -Eq 'eval_commerce_audit_(reference|legacy_watermark)' \
  <<<"$migration_grants_after_schema_rejection"; then
  echo "Auth-only schema rejection granted V013 migration authority." >&2
  exit 1
fi
mysql_query root "$bootstrap_password" commerce_db "
DROP TABLE eval_commerce_audit_legacy_watermark;
DROP TABLE eval_commerce_product_observation;
DROP TABLE eval_commerce_audit_reference;
DROP TABLE eval_sandbox_product_fixture;
DROP TABLE eval_sandbox_effect_stub;
DROP TABLE eval_sandbox;
"

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce migrate-agent
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access

mysql_query auth_app "$auth_app_password" commerce_db "
INSERT INTO auth_user_principal (principal_id, subject, login_identifier, state, permissions) VALUES
  ('00000000-0000-0000-0000-000000000020', 'user-integration', 'integration-user', 'ACTIVE', 'support:session:create support:chat'),
  ('00000000-0000-0000-0000-000000000021', 'other-user', 'other-user', 'ACTIVE', 'support:session:create support:chat'),
  ('00000000-0000-0000-0000-000000000022', 'disabled-user', 'disabled-user', 'DISABLED', 'support:session:create support:chat');
INSERT INTO auth_login_credential (principal_id, password_hash) VALUES
  ('00000000-0000-0000-0000-000000000020', '$user_hash'),
  ('00000000-0000-0000-0000-000000000021', '$other_hash'),
  ('00000000-0000-0000-0000-000000000022', '$disabled_hash');
INSERT INTO auth_service_identity (service_id, client_id, credential_hash, state, allowed_scopes) VALUES
  ('00000000-0000-0000-0000-000000000023', 'agent-service', '$service_hash', 'ACTIVE', 'catalog:read');
INSERT INTO auth_signing_key_metadata (kid, state, activated_at, retire_after) VALUES
  ('current-key', 'CURRENT', CURRENT_TIMESTAMP(6), NULL),
  ('overlap-key', 'OVERLAP', CURRENT_TIMESTAMP(6), TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP(6)));
"

mysql_query commerce_app "$commerce_app_password" commerce_db "
INSERT INTO product (
  product_id, name, description, price_minor, currency, stock_quantity,
  available, publication_state, publication_version
) VALUES ('product-1', 'Integration tea', 'private description', 500, 'CNY', 10, TRUE, 'PUBLISHED', 1);
"

test "$(mysql_query auth_app "$auth_app_password" commerce_db 'SELECT COUNT(*) FROM auth_user_principal')" = 3
test "$(mysql_query agent_app "$agent_app_password" cs_db 'SELECT COUNT(*) FROM support_session')" = 0
assert_mysql_fails "auth_app cannot read commerce migration history" \
  mysql_query auth_app "$auth_app_password" commerce_db 'SELECT * FROM commerce_schema_history'
assert_mysql_fails "auth_app cannot access cs_db" \
  mysql_query auth_app "$auth_app_password" cs_db 'SELECT * FROM support_session'
assert_mysql_fails "auth_app cannot read support conversation truth" \
  mysql_query auth_app "$auth_app_password" cs_db 'SELECT * FROM support_conversation'
assert_mysql_fails "commerce_app cannot read support evidence" \
  mysql_query commerce_app "$(read_value MYSQL_COMMERCE_APP_PASSWORD)" cs_db 'SELECT * FROM support_event'
assert_mysql_fails "auth_app cannot read support feedback" \
  mysql_query auth_app "$auth_app_password" cs_db 'SELECT * FROM support_feedback'
assert_mysql_fails "commerce_app cannot read support feedback" \
  mysql_query commerce_app "$commerce_app_password" cs_db 'SELECT * FROM support_feedback'
assert_mysql_fails "auth_app cannot execute DDL" \
  mysql_query auth_app "$auth_app_password" commerce_db 'CREATE TABLE forbidden_auth_ddl (id INT)'
assert_mysql_fails "agent_app cannot access auth tables" \
  mysql_query agent_app "$agent_app_password" commerce_db 'SELECT * FROM auth_user_principal'
assert_mysql_fails "agent_app cannot execute DDL" \
  mysql_query agent_app "$agent_app_password" cs_db 'CREATE TABLE forbidden_agent_ddl (id INT)'
assert_mysql_fails "agent_app cannot mutate append-only evidence" \
  mysql_query agent_app "$agent_app_password" cs_db \
    "UPDATE support_event SET event_type = 'TURN_FAILED' WHERE 1 = 0"
assert_mysql_fails "agent_app cannot update append-only feedback" \
  mysql_query agent_app "$agent_app_password" cs_db \
    "UPDATE support_feedback SET rating = 'NEGATIVE' WHERE 1 = 0"
assert_mysql_fails "agent_app cannot delete append-only feedback" \
  mysql_query agent_app "$agent_app_password" cs_db \
    "DELETE FROM support_feedback WHERE 1 = 0"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp_dir/current-private.pem" 2>/dev/null
openssl pkey -in "$tmp_dir/current-private.pem" -pubout -out "$tmp_dir/current-public.pem" 2>/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp_dir/overlap-private.pem" 2>/dev/null
openssl pkey -in "$tmp_dir/overlap-private.pem" -pubout -out "$tmp_dir/overlap-public.pem" 2>/dev/null

./mvnw -q -pl auth-service,commerce-service -am -DskipTests package
SPRING_DATASOURCE_PASSWORD="$auth_app_password" java -jar auth-service/target/auth-service-0.0.1-SNAPSHOT.jar \
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
  >"$tmp_dir/auth.log" 2>&1 &
auth_pid=$!
process_bound_port auth_port spring "$auth_pid" "$tmp_dir/auth.log" 0
wait_http "http://127.0.0.1:$auth_port/auth/jwks" "$auth_pid" "$tmp_dir/auth.log"

SPRING_DATASOURCE_PASSWORD="$commerce_app_password" \
java -jar commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar \
  --server.port=0 \
  --spring.datasource.url="jdbc:mysql://127.0.0.1:$MYSQL_PORT/commerce_db?useSSL=false&allowPublicKeyRetrieval=true" \
  --spring.datasource.username=commerce_app \
  --citybuddy.obo.enabled=true \
  --citybuddy.obo.issuer=https://identity.citybuddy.test \
  --citybuddy.obo.jwks-url="http://127.0.0.1:$auth_port/auth/jwks" \
  --citybuddy.agent-tools.enabled=true \
  >"$tmp_dir/commerce.log" 2>&1 &
commerce_pid=$!
process_bound_port commerce_port spring "$commerce_pid" "$tmp_dir/commerce.log" 0
wait_port \
  "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  "$commerce_pid" \
  "$tmp_dir/commerce.log"

uv run python scripts/fake_litellm_server.py --port 0 \
  >"$tmp_dir/proxy.log" 2>&1 &
proxy_pid=$!
process_bound_port proxy_port uvicorn "$proxy_pid" "$tmp_dir/proxy.log" 0
wait_http "http://127.0.0.1:$proxy_port/fixture/counts" "$proxy_pid" "$tmp_dir/proxy.log"

curl --silent --show-error "http://127.0.0.1:$auth_port/auth/jwks" >"$tmp_dir/jwks.json"
uv run python scripts/check_identity_jwks.py "$tmp_dir/jwks.json" current-key overlap-key
echo "Verified JWKS current/overlap publication with no private RSA fields."
assert_status 401 "auth JWKS rejects production evaluation header" \
  --request GET "http://127.0.0.1:$auth_port/auth/jwks" \
  --header 'X-Eval-Sandbox-Id: forbidden-production-context'

assert_status 401 "invalid login hides credential detail" \
  --request POST "http://127.0.0.1:$auth_port/auth/login" \
  --header 'Content-Type: application/json' \
  --data '{"loginIdentifier":"integration-user","password":"wrong"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/invalid-login.json"
assert_status 401 "auth login rejects production evaluation header" \
  --request POST "http://127.0.0.1:$auth_port/auth/login" \
  --header 'X-Eval-Sandbox-Id: forbidden-production-context' \
  --header 'Content-Type: application/json' \
  --data "{\"loginIdentifier\":\"integration-user\",\"password\":\"$user_password\"}"
assert_status 401 "disabled principal hides state detail" \
  --request POST "http://127.0.0.1:$auth_port/auth/login" \
  --header 'Content-Type: application/json' \
  --data "{\"loginIdentifier\":\"disabled-user\",\"password\":\"$disabled_password\"}"
cmp "$tmp_dir/invalid-login.json" "$tmp_dir/http-response.json"

assert_status 200 "active principal login" \
  --request POST "http://127.0.0.1:$auth_port/auth/login" \
  --header 'Content-Type: application/json' \
  --data "{\"loginIdentifier\":\"integration-user\",\"password\":\"$user_password\"}"
direct_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
assert_status 200 "second active principal login" \
  --request POST "http://127.0.0.1:$auth_port/auth/login" \
  --header 'Content-Type: application/json' \
  --data "{\"loginIdentifier\":\"other-user\",\"password\":\"$other_password\"}"
other_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"

AGENT_PORT=0 \
AGENT_IDENTITY_ENABLED=true \
CITYBUDDY_ENVIRONMENT=integration \
IDENTITY_ISSUER=https://identity.citybuddy.test \
IDENTITY_USER_AUDIENCE=citybuddy-web \
IDENTITY_JWKS_URL="http://127.0.0.1:$auth_port/auth/jwks" \
IDENTITY_EXCHANGE_URL="http://127.0.0.1:$auth_port/auth/token/exchange" \
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT="$MYSQL_PORT" \
MYSQL_AGENT_APP_PASSWORD="$agent_app_password" \
AGENT_SERVICE_CLIENT_ID=agent-service \
AGENT_SERVICE_CLIENT_SECRET="$service_password" \
AGENT_EXCHANGE_SCOPES=catalog:read \
AGENT_MODEL_PROXY_URL="http://127.0.0.1:$proxy_port" \
AGENT_COMMERCE_TOOLS_URL="http://127.0.0.1:$commerce_port" \
AGENT_CIRCUIT_OPEN_SECONDS=3 \
uv run citybuddy-agent >"$tmp_dir/agent.log" 2>&1 &
agent_pid=$!
process_bound_port agent_port uvicorn "$agent_pid" "$tmp_dir/agent.log" 0
for _ in {1..60}; do
  if kill -0 "$agent_pid" >/dev/null 2>&1 && \
      [[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:$agent_port/api/sessions")" != 000 ]]; then
    break
  fi
  sleep 1
done
kill -0 "$agent_pid" >/dev/null 2>&1 || { cat "$tmp_dir/agent.log" >&2; exit 1; }

assert_status 201 "server-owned support-session creation" \
  --request POST "http://127.0.0.1:$agent_port/api/sessions" \
  --header "Authorization: Bearer $direct_token" \
  --header 'Content-Type: application/json' \
  --data '{}'
session_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" sessionId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT user_subject FROM support_session WHERE session_id = '$session_id'")" = user-integration
assert_status 422 "client-selected session owner" \
  --request POST "http://127.0.0.1:$agent_port/api/sessions" \
  --header "Authorization: Bearer $direct_token" \
  --header 'Content-Type: application/json' \
  --data '{"userSubject":"other-user"}'
assert_status 401 "production evaluation header" \
  --request POST "http://127.0.0.1:$agent_port/api/sessions" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: forbidden-production-context' \
  --header 'Content-Type: application/json' \
  --data '{}'

chat_headers=(
  --header "Authorization: Bearer $direct_token"
  --header "X-Session-Id: $session_id"
  --header 'Content-Type: application/json'
)
assert_status 200 "durable bounded support turn" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb080-first-turn' \
  --data '{"message":"Where is my order?"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/first-chat.json"
conversation_id="$(uv run python scripts/read_json_field.py "$tmp_dir/first-chat.json" conversationId)"
trace_id="$(uv run python scripts/read_json_field.py "$tmp_dir/first-chat.json" traceId)"
turn_id="$(uv run python scripts/read_json_field.py "$tmp_dir/first-chat.json" turnId)"
test "$(uv run python scripts/read_json_field.py "$tmp_dir/first-chat.json" outcome)" = completed
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_conversation WHERE conversation_id = '$conversation_id' AND session_id = '$session_id' AND user_subject = 'user-integration'")" = 1
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT CONCAT(state, ':', turn_sequence) FROM support_turn WHERE turn_id = '$turn_id' AND trace_id = '$trace_id'")" = COMPLETED:1
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT GROUP_CONCAT(CONCAT(sequence, ':', event_type) ORDER BY sequence SEPARATOR ',') FROM support_event WHERE trace_id = '$trace_id'")" = '1:USER_INPUT,2:ROUTING_DECISION,3:BUDGET_CHARGED,4:CIRCUIT_OUTCOME,5:MODEL_OUTCOME,6:AGENT_OUTCOME,7:ASSISTANT_RESPONSE,8:TURN_COMPLETED'

assert_status 200 "same-intent durable replay" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb080-first-turn' \
  --data '{"message":"Where is my order?"}'
cmp "$tmp_dir/first-chat.json" "$tmp_dir/http-response.json"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_turn WHERE session_id = '$session_id'")" = 1
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$trace_id'")" = 8

assert_status 200 "filtered SSE safe text" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-safe-stream' \
  --data '{"message":"safe stream"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/safe-stream.sse"
uv run python scripts/check_sse_stream.py "$tmp_dir/safe-stream.sse" \
  --terminal done --expected-text 'The bounded support route completed safely.'
stream_trace="$(mysql_query agent_app "$agent_app_password" cs_db "SELECT trace_id FROM support_turn WHERE session_id = '$session_id' AND correlation_key = 'cb082-safe-stream'")"
test -n "$stream_trace"
assert_status 200 "filtered SSE durable replay" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-safe-stream' \
  --data '{"message":"safe stream"}'
cmp "$tmp_dir/safe-stream.sse" "$tmp_dir/http-response.json"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_turn WHERE session_id = '$session_id' AND correlation_key = 'cb082-safe-stream'")" = 1
assert_status 409 "filtered SSE conflicting idempotency reuse" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-safe-stream' \
  --data '{"message":"different stream intent"}'
assert_status 403 "filtered SSE forged support session" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Session-Id: forged-session' \
  --header 'Idempotency-Key: cb082-forged-stream' \
  --header 'Content-Type: application/json' \
  --data '{"message":"hello"}'
assert_status 403 "filtered SSE cross-user support session" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  --header "Authorization: Bearer $other_token" \
  --header "X-Session-Id: $session_id" \
  --header 'Idempotency-Key: cb082-cross-user-stream' \
  --header 'Content-Type: application/json' \
  --data '{"message":"hello"}'
assert_status 401 "filtered SSE rejects production evaluation header" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-eval-stream' \
  --header 'X-Eval-Sandbox-Id: forbidden-production-context' \
  --data '{"message":"hello"}'
assert_status 401 "filtered SSE requires direct-user bearer" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  --header "X-Session-Id: $session_id" \
  --header 'Idempotency-Key: cb082-missing-auth-stream' \
  --header 'Content-Type: application/json' \
  --data '{"message":"hello"}'

assert_status 200 "filtered SSE ToolSpec success" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-tool-stream' \
  --data '{"message":"tool-success product-1"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/tool-stream.sse"
uv run python scripts/check_sse_stream.py "$tmp_dir/tool-stream.sse" \
  --terminal done --expected-text 'The requested information is available.'
tool_stream_trace="$(mysql_query agent_app "$agent_app_password" cs_db "SELECT trace_id FROM support_turn WHERE session_id = '$session_id' AND correlation_key = 'cb082-tool-stream'")"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$tool_stream_trace' AND event_type = 'TOOL_LIFECYCLE'")" = 2
assert_status 200 "filtered SSE tool replay does not re-execute" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-tool-stream' \
  --data '{"message":"tool-success product-1"}'
cmp "$tmp_dir/tool-stream.sse" "$tmp_dir/http-response.json"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$tool_stream_trace' AND event_type = 'TOOL_LIFECYCLE'")" = 2

assert_status 200 "filtered SSE structured tool denial" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-denied-stream' \
  --data '{"message":"tool-malformed"}'
uv run python scripts/check_sse_stream.py "$tmp_dir/http-response.json" \
  --terminal done --expected-text 'The requested information is available.'
denied_stream_trace="$(mysql_query agent_app "$agent_app_password" cs_db "SELECT trace_id FROM support_turn WHERE session_id = '$session_id' AND correlation_key = 'cb082-denied-stream'")"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.reason')) FROM support_event WHERE trace_id = '$denied_stream_trace' AND event_type = 'TOOL_DENIED'")" = invalid_arguments

assert_status 200 "provider failure is one bounded public SSE error" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-provider-failure' \
  --data '{"message":"provider-failure"}'
uv run python scripts/check_sse_stream.py "$tmp_dir/http-response.json" \
  --terminal error --error-code provider_unavailable
assert_status 200 "budget exhaustion is one bounded public SSE error" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-budget-failure' \
  --data '{"message":"budget-exhaustion"}'
uv run python scripts/check_sse_stream.py "$tmp_dir/http-response.json" \
  --terminal error --error-code attempt_budget_exhausted
assert_status 200 "action claim without receipt is withheld" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-unsafe-action' \
  --data '{"message":"unsafe-action-claim"}'
uv run python scripts/check_sse_stream.py "$tmp_dir/http-response.json" \
  --terminal error --error-code unsafe_output
if grep -qi refund "$tmp_dir/http-response.json"; then
  echo "Action-success language escaped without receipt truth." >&2
  exit 1
fi

set +e
curl --silent --show-error --max-time 0.05 \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-disconnect' \
  --data '{"message":"disconnect-slow"}' \
  >"$tmp_dir/disconnected-stream.sse"
disconnect_curl_status=$?
set -e
test "$disconnect_curl_status" -ne 0
sleep 1
assert_status 200 "authorized replay after disconnect uses durable turn" \
  --request POST "http://127.0.0.1:$agent_port/api/chat/stream" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb082-disconnect' \
  --data '{"message":"disconnect-slow"}'
uv run python scripts/check_sse_stream.py "$tmp_dir/http-response.json" \
  --terminal done --expected-text 'The bounded response completed safely.'
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_turn WHERE session_id = '$session_id' AND correlation_key = 'cb082-disconnect'")" = 1
echo "Verified bounded filtered SSE, terminal ordering, replay, failure, and disconnect recovery."

feedback_headers=(
  --header "Authorization: Bearer $direct_token"
  --header "X-Session-Id: $session_id"
  --header 'Content-Type: application/json'
)
assert_status 201 "owner-scoped append-only feedback" \
  --request POST "http://127.0.0.1:$agent_port/api/feedback" \
  "${feedback_headers[@]}" \
  --header 'Idempotency-Key: cb082-feedback' \
  --data "{\"traceId\":\"$stream_trace\",\"rating\":\"POSITIVE\",\"comment\":\"Helpful\"}"
cp "$tmp_dir/http-response.json" "$tmp_dir/first-feedback.json"
feedback_id="$(uv run python scripts/read_json_field.py "$tmp_dir/first-feedback.json" feedbackId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT CONCAT(session_id, ':', user_subject, ':', trace_id, ':', rating) FROM support_feedback WHERE feedback_id = '$feedback_id'")" = "$session_id:user-integration:$stream_trace:POSITIVE"
assert_status 201 "same-intent feedback retry" \
  --request POST "http://127.0.0.1:$agent_port/api/feedback" \
  "${feedback_headers[@]}" \
  --header 'Idempotency-Key: cb082-feedback' \
  --data "{\"traceId\":\"$stream_trace\",\"rating\":\"POSITIVE\",\"comment\":\"Helpful\"}"
cmp "$tmp_dir/first-feedback.json" "$tmp_dir/http-response.json"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_feedback WHERE session_id = '$session_id' AND idempotency_key = 'cb082-feedback'")" = 1
feedback_pids=()
for index in 1 2 3 4; do
  (
    status="$(curl --silent --show-error \
      --output "$tmp_dir/feedback-concurrent-$index.json" --write-out '%{http_code}' \
      --request POST "http://127.0.0.1:$agent_port/api/feedback" \
      "${feedback_headers[@]}" \
      --header 'Idempotency-Key: cb082-feedback-concurrent' \
      --data "{\"traceId\":\"$stream_trace\",\"rating\":\"NEGATIVE\"}")"
    test "$status" = 201
  ) &
  feedback_pids+=("$!")
done
for feedback_pid in "${feedback_pids[@]}"; do
  wait "$feedback_pid"
done
for index in 2 3 4; do
  cmp "$tmp_dir/feedback-concurrent-1.json" "$tmp_dir/feedback-concurrent-$index.json"
done
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_feedback WHERE session_id = '$session_id' AND idempotency_key = 'cb082-feedback-concurrent'")" = 1
echo "Verified concurrent same-intent feedback converges to one append-only signal."
assert_status 409 "conflicting feedback key reuse" \
  --request POST "http://127.0.0.1:$agent_port/api/feedback" \
  "${feedback_headers[@]}" \
  --header 'Idempotency-Key: cb082-feedback' \
  --data "{\"traceId\":\"$stream_trace\",\"rating\":\"NEGATIVE\"}"
assert_status 403 "unknown feedback trace" \
  --request POST "http://127.0.0.1:$agent_port/api/feedback" \
  "${feedback_headers[@]}" \
  --header 'Idempotency-Key: cb082-unknown-feedback' \
  --data '{"traceId":"00000000-0000-0000-0000-000000000999","rating":"POSITIVE"}'
assert_status 403 "cross-user feedback ownership" \
  --request POST "http://127.0.0.1:$agent_port/api/feedback" \
  --header "Authorization: Bearer $other_token" \
  --header "X-Session-Id: $session_id" \
  --header 'Idempotency-Key: cb082-cross-user-feedback' \
  --header 'Content-Type: application/json' \
  --data "{\"traceId\":\"$stream_trace\",\"rating\":\"POSITIVE\"}"
assert_status 422 "feedback body identity substitution" \
  --request POST "http://127.0.0.1:$agent_port/api/feedback" \
  "${feedback_headers[@]}" \
  --header 'Idempotency-Key: cb082-body-owner-feedback' \
  --data "{\"traceId\":\"$stream_trace\",\"rating\":\"POSITIVE\",\"userSubject\":\"other-user\"}"
assert_status 401 "feedback rejects production evaluation header" \
  --request POST "http://127.0.0.1:$agent_port/api/feedback" \
  "${feedback_headers[@]}" \
  --header 'X-Eval-Sandbox-Id: forbidden-production-context' \
  --header 'Idempotency-Key: cb082-eval-feedback' \
  --data "{\"traceId\":\"$stream_trace\",\"rating\":\"POSITIVE\"}"

mysql_query agent_migration "$agent_migration_password" cs_db \
  "ALTER TABLE support_feedback ADD CONSTRAINT chk_cb082_feedback_rollback CHECK (comment_text <> 'rollback-trigger')"
before_feedback_rollback="$(mysql_query agent_app "$agent_app_password" cs_db 'SELECT COUNT(*) FROM support_feedback')"
assert_status 503 "feedback transaction rollback is bounded" \
  --request POST "http://127.0.0.1:$agent_port/api/feedback" \
  "${feedback_headers[@]}" \
  --header 'Idempotency-Key: cb082-feedback-rollback' \
  --data "{\"traceId\":\"$stream_trace\",\"rating\":\"NEGATIVE\",\"comment\":\"rollback-trigger\"}"
mysql_query agent_migration "$agent_migration_password" cs_db \
  'ALTER TABLE support_feedback DROP CHECK chk_cb082_feedback_rollback'
test "$(mysql_query agent_app "$agent_app_password" cs_db 'SELECT COUNT(*) FROM support_feedback')" = "$before_feedback_rollback"
echo "Verified feedback ownership, idempotency, append-only grants, and transaction rollback."

crash_turn_id='00000000-0000-0000-0000-000000000810'
crash_trace_id='00000000-0000-0000-0000-000000000811'
crash_turn_sequence="$(mysql_query agent_app "$agent_app_password" cs_db "SELECT next_turn_sequence + 1 FROM support_conversation WHERE conversation_id = '$conversation_id'")"
mysql_query agent_app "$agent_app_password" cs_db \
  "START TRANSACTION; UPDATE support_conversation SET next_turn_sequence = $crash_turn_sequence WHERE conversation_id = '$conversation_id'; INSERT INTO support_turn (turn_id, conversation_id, session_id, user_subject, trace_id, turn_sequence, correlation_key, request_fingerprint, input_text, state, processing_deadline_at) VALUES ('$crash_turn_id', '$conversation_id', '$session_id', 'user-integration', '$crash_trace_id', $crash_turn_sequence, 'cb081-crash-window', 'ae2067cc156a9372a6a96c0741e0b1884a23067d63c264e73766e4a25c6a7459', 'crash-window', 'PROCESSING', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)); INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES ('00000000-0000-0000-0000-000000000812', '$crash_turn_id', '$crash_trace_id', '$session_id', 'user-integration', 1, 'USER_INPUT', JSON_OBJECT('accepted', TRUE)); COMMIT"
assert_status 503 "expired crash-window turn converges without re-execution" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-crash-window' \
  --data '{"message":"crash-window"}'
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT CONCAT(state, ':', failure_code, ':', processing_deadline_at IS NULL) FROM support_turn WHERE turn_id = '$crash_turn_id'")" = 'FAILED:processing_deadline_expired:1'
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT GROUP_CONCAT(CONCAT(sequence, ':', event_type) ORDER BY sequence SEPARATOR ',') FROM support_event WHERE trace_id = '$crash_trace_id'")" = '1:USER_INPUT,2:TURN_FAILED'
echo "Verified a committed PROCESSING crash window converges once to durable failure without agent or tool re-execution."

assert_status 409 "conflicting idempotency reuse" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb080-first-turn' \
  --data '{"message":"Different intent"}'
assert_status 422 "client-selected authoritative trace" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb080-client-id' \
  --data '{"message":"hello","traceId":"client-selected"}'
assert_status 422 "missing support session header" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $direct_token" \
  --header 'Idempotency-Key: cb080-missing-session' \
  --header 'Content-Type: application/json' \
  --data '{"message":"hello"}'
assert_status 401 "chat rejects production evaluation header" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb080-eval' \
  --header 'X-Eval-Sandbox-Id: forbidden-production-context' \
  --data '{"message":"hello"}'

assert_status 403 "unknown support session does not disclose existence" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $direct_token" \
  --header 'X-Session-Id: unknown-session' \
  --header 'Idempotency-Key: cb080-unknown' \
  --header 'Content-Type: application/json' \
  --data '{"message":"hello"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/forbidden-chat.json"
assert_status 403 "cross-user support session does not disclose existence" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  --header "Authorization: Bearer $other_token" \
  --header "X-Session-Id: $session_id" \
  --header 'Idempotency-Key: cb080-cross-user' \
  --header 'Content-Type: application/json' \
  --data '{"message":"hello"}'
cmp "$tmp_dir/forbidden-chat.json" "$tmp_dir/http-response.json"

concurrent_pids=()
for index in 1 2 3 4; do
  (
    status="$(curl --silent --show-error \
      --output "$tmp_dir/concurrent-$index.json" --write-out '%{http_code}' \
      --request POST "http://127.0.0.1:$agent_port/api/chat" \
      "${chat_headers[@]}" \
      --header 'Idempotency-Key: cb080-concurrent' \
      --data '{"message":"one concurrent intent"}')"
    test "$status" = 200
  ) &
  concurrent_pids+=("$!")
done
for concurrent_pid in "${concurrent_pids[@]}"; do
  wait "$concurrent_pid"
done
for index in 2 3 4; do
  cmp "$tmp_dir/concurrent-1.json" "$tmp_dir/concurrent-$index.json"
done
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_turn WHERE session_id = '$session_id' AND correlation_key = 'cb080-concurrent'")" = 1
concurrent_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/concurrent-1.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$concurrent_trace'")" = 8
echo "Verified concurrent same-intent requests converge to one durable turn and sequence."

assert_status 200 "real JIT OBO and commerce ToolSpec success" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-tool-success' \
  --data '{"message":"tool-success product-1"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/tool-success.json"
tool_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/tool-success.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$tool_trace' AND event_type = 'TOOL_LIFECYCLE'")" = 2
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT GROUP_CONCAT(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.kind')) ORDER BY sequence SEPARATOR ',') FROM support_event WHERE trace_id = '$tool_trace' AND event_type = 'BUDGET_CHARGED'")" = 'model_http,identity_http,tool_http,model_http'
assert_status 200 "tool turn replay does not re-execute agent or tool" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-tool-success' \
  --data '{"message":"tool-success product-1"}'
cmp "$tmp_dir/tool-success.json" "$tmp_dir/http-response.json"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$tool_trace' AND event_type = 'TOOL_LIFECYCLE'")" = 2

assert_status 200 "malformed model arguments deny with feedback" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-malformed' \
  --data '{"message":"tool-malformed"}'
malformed_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.reason')) FROM support_event WHERE trace_id = '$malformed_trace' AND event_type = 'TOOL_DENIED'")" = invalid_arguments
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$malformed_trace' AND event_type = 'BUDGET_CHARGED' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.kind')) IN ('identity_http', 'tool_http')")" = 0

assert_status 200 "unknown model-selected tool denies before authority or I/O" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-unknown-tool' \
  --data '{"message":"tool-unknown"}'
unknown_tool_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.reason')) FROM support_event WHERE trace_id = '$unknown_tool_trace' AND event_type = 'TOOL_DENIED'")" = unknown_tool

mysql_query auth_app "$auth_app_password" commerce_db \
  "UPDATE auth_service_identity SET state = 'REVOKED' WHERE client_id = 'agent-service'"
assert_status 200 "OBO exchange denial is structured model feedback" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: test-denial' \
  --data '{"message":"tool-success with denied exchange"}'
obo_denial_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.reason')) FROM support_event WHERE trace_id = '$obo_denial_trace' AND event_type = 'TOOL_DENIED'")" = identity_denied
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$obo_denial_trace' AND event_type = 'BUDGET_CHARGED' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.kind')) = 'tool_http'")" = 0
mysql_query auth_app "$auth_app_password" commerce_db \
  "UPDATE auth_service_identity SET state = 'ACTIVE' WHERE client_id = 'agent-service'"

assert_status 200 "one transient provider retry" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: test-retry' \
  --data '{"message":"transient-retry"}'
transient_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT GROUP_CONCAT(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.result')) ORDER BY sequence SEPARATOR ',') FROM support_event WHERE trace_id = '$transient_trace' AND event_type = 'MODEL_OUTCOME'")" = 'transient,ok'

assert_status 200 "same-tier fallback after the one retry" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-fallback' \
  --data '{"message":"same-tier-fallback"}'
fallback_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT GROUP_CONCAT(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.provider')) ORDER BY sequence SEPARATOR ',') FROM support_event WHERE trace_id = '$fallback_trace' AND event_type = 'MODEL_OUTCOME'")" = 'primary,primary,fallback'
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$fallback_trace' AND event_type = 'CIRCUIT_OUTCOME' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.state')) = 'opened'")" = 1

assert_status 200 "open primary circuit is charged, recorded, and stays in tier" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-circuit-open' \
  --data '{"message":"circuit-open"}'
circuit_open_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$circuit_open_trace' AND event_type = 'CIRCUIT_OUTCOME' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.state')) = 'open'")" = 1
sleep 4
assert_status 200 "bounded half-open probe recovers the primary provider" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-circuit-recover' \
  --data '{"message":"circuit-recover"}'
circuit_recover_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT GROUP_CONCAT(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.state')) ORDER BY sequence SEPARATOR ',') FROM support_event WHERE trace_id = '$circuit_recover_trace' AND event_type = 'CIRCUIT_OUTCOME'")" = 'half-open,closed'

assert_status 200 "shared attempt budget exhausts deterministically" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-budget' \
  --data '{"message":"budget-exhaustion"}'
test "$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" outcome)" = budget_exhausted
budget_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT COUNT(*) FROM support_event WHERE trace_id = '$budget_trace' AND event_type = 'BUDGET_CHARGED'")" = 8

AGENT_PORT=0 \
AGENT_IDENTITY_ENABLED=true \
CITYBUDDY_ENVIRONMENT=integration \
IDENTITY_ISSUER=https://identity.citybuddy.test \
IDENTITY_USER_AUDIENCE=citybuddy-web \
IDENTITY_JWKS_URL="http://127.0.0.1:$auth_port/auth/jwks" \
IDENTITY_EXCHANGE_URL="http://127.0.0.1:$auth_port/auth/token/exchange" \
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT="$MYSQL_PORT" \
MYSQL_AGENT_APP_PASSWORD="$agent_app_password" \
AGENT_SERVICE_CLIENT_ID=agent-service \
AGENT_SERVICE_CLIENT_SECRET="$service_password" \
AGENT_EXCHANGE_SCOPES=catalog:read \
AGENT_MODEL_PROXY_URL="http://127.0.0.1:$proxy_port" \
AGENT_COMMERCE_TOOLS_URL="http://127.0.0.1:$proxy_port" \
uv run citybuddy-agent >"$tmp_dir/timeout-agent.log" 2>&1 &
timeout_agent_pid=$!
process_bound_port timeout_agent_port uvicorn "$timeout_agent_pid" "$tmp_dir/timeout-agent.log" 0
wait_port "http://127.0.0.1:$timeout_agent_port/api/sessions" \
  "$timeout_agent_pid" "$tmp_dir/timeout-agent.log"
assert_status 200 "bounded commerce tool timeout becomes feedback" \
  --request POST "http://127.0.0.1:$timeout_agent_port/api/chat" \
  "${chat_headers[@]}" \
  --header 'Idempotency-Key: cb081-tool-timeout' \
  --data '{"message":"tool-timeout"}'
timeout_trace="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" traceId)"
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.reason')) FROM support_event WHERE trace_id = '$timeout_trace' AND event_type = 'TOOL_DENIED'")" = timeout
echo "CB-081 fake-provider, shared-budget, circuit, JIT OBO, and ToolSpec evidence passed."

assert_status 201 "isolated rollback-drill session creation" \
  --request POST "http://127.0.0.1:$agent_port/api/sessions" \
  --header "Authorization: Bearer $direct_token" \
  --header 'Content-Type: application/json' \
  --data '{}'
rollback_session_id="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" sessionId)"
rollback_chat_headers=(
  --header "Authorization: Bearer $direct_token"
  --header "X-Session-Id: $rollback_session_id"
  --header 'Content-Type: application/json'
)
before_failure="$(mysql_query agent_app "$agent_app_password" cs_db "SELECT CONCAT((SELECT next_turn_sequence FROM support_conversation WHERE session_id = '$rollback_session_id'), ':', (SELECT COUNT(*) FROM support_turn WHERE session_id = '$rollback_session_id'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$rollback_session_id'))")"
test "$before_failure" = '0:0:0'
mysql_query agent_migration "$agent_migration_password" cs_db \
  "ALTER TABLE support_event ADD CONSTRAINT chk_cb081_controlled_failure CHECK (session_id <> '$rollback_session_id' OR event_type <> 'MODEL_OUTCOME')"
assert_status 503 "accepted-turn database failure is bounded" \
  --request POST "http://127.0.0.1:$agent_port/api/chat" \
  "${rollback_chat_headers[@]}" \
  --header 'Idempotency-Key: cb080-rollback' \
  --data '{"message":"must roll back"}'
if grep -q 'chk_cb081_controlled_failure' "$tmp_dir/http-response.json"; then
  echo "Database failure detail leaked through the public response." >&2
  exit 1
fi
mysql_query agent_migration "$agent_migration_password" cs_db \
  'ALTER TABLE support_event DROP CHECK chk_cb081_controlled_failure'
after_failure="$(mysql_query agent_app "$agent_app_password" cs_db "SELECT CONCAT((SELECT next_turn_sequence FROM support_conversation WHERE session_id = '$rollback_session_id'), ':', (SELECT COUNT(*) FROM support_turn WHERE session_id = '$rollback_session_id'), ':', (SELECT COUNT(*) FROM support_event WHERE session_id = '$rollback_session_id'))")"
test "$after_failure" = '1:1:2'
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT state FROM support_turn WHERE session_id = '$rollback_session_id'")" = FAILED
test "$(mysql_query agent_app "$agent_app_password" cs_db "SELECT GROUP_CONCAT(CONCAT(sequence, ':', event_type) ORDER BY sequence SEPARATOR ',') FROM support_event WHERE session_id = '$rollback_session_id'")" = '1:USER_INPUT,2:TURN_FAILED'
echo "Verified partial agent evidence rolls back while the accepted durable turn converges to FAILED."

assert_mysql_rejects "duplicate evidence sequence" 'Duplicate entry' \
  mysql_query agent_app "$agent_app_password" cs_db \
    "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES ('00000000-0000-0000-0000-000000000080', '$turn_id', '$trace_id', '$session_id', 'user-integration', 1, 'USER_INPUT', JSON_OBJECT())"
assert_mysql_rejects "non-positive evidence sequence" 'Check constraint.*violated' \
  mysql_query agent_app "$agent_app_password" cs_db \
    "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES ('00000000-0000-0000-0000-000000000081', '$turn_id', '$trace_id', '$session_id', 'user-integration', 0, 'TURN_COMPLETED', JSON_OBJECT())"
ordering_turn_id='00000000-0000-0000-0000-000000000085'
ordering_trace_id='00000000-0000-0000-0000-000000000086'
mysql_query agent_app "$agent_app_password" cs_db \
  "INSERT INTO support_turn (turn_id, conversation_id, session_id, user_subject, trace_id, turn_sequence, correlation_key, request_fingerprint, input_text, state, processing_deadline_at) VALUES ('$ordering_turn_id', '$conversation_id', '$session_id', 'user-integration', '$ordering_trace_id', 999, 'cb081-ordering-rejection', '042e57b4fc029d06e20d970d92b4c5bc916c321f9d96561c79843b23b705e496', 'ordering-rejection', 'PROCESSING', DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND))"
assert_mysql_rejects "terminal evidence cannot precede accepted input" 'Check constraint.*violated' \
  mysql_query agent_app "$agent_app_password" cs_db \
    "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES ('00000000-0000-0000-0000-000000000087', '$ordering_turn_id', '$ordering_trace_id', '$session_id', 'user-integration', 1, 'TURN_FAILED', JSON_OBJECT())"
mysql_query agent_app "$agent_app_password" cs_db \
  "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES ('00000000-0000-0000-0000-000000000088', '$ordering_turn_id', '$ordering_trace_id', '$session_id', 'user-integration', 1, 'USER_INPUT', JSON_OBJECT())"
assert_mysql_rejects "accepted input cannot be repeated after sequence one" 'Check constraint.*violated' \
  mysql_query agent_app "$agent_app_password" cs_db \
    "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES ('00000000-0000-0000-0000-000000000089', '$ordering_turn_id', '$ordering_trace_id', '$session_id', 'user-integration', 2, 'USER_INPUT', JSON_OBJECT())"
assert_mysql_rejects "cross-session evidence association" 'foreign key constraint fails' \
  mysql_query agent_app "$agent_app_password" cs_db \
    "INSERT INTO support_event (event_id, turn_id, trace_id, session_id, user_subject, sequence, event_type, payload_json) VALUES ('00000000-0000-0000-0000-000000000082', '00000000-0000-0000-0000-000000000083', '00000000-0000-0000-0000-000000000084', 'forged-session', 'user-integration', 1, 'USER_INPUT', JSON_OBJECT())"
assert_mysql_fails "append-only evidence cannot be deleted" \
  mysql_query agent_app "$agent_app_password" cs_db \
    "DELETE FROM support_event WHERE event_id = '00000000-0000-0000-0000-000000000080'"
echo "Verified evidence uniqueness, accepted-input-first ordering, association, and append-only grants."

common_probe_env=(
  IDENTITY_ISSUER=https://identity.citybuddy.test
  IDENTITY_USER_AUDIENCE=citybuddy-web
  IDENTITY_JWKS_URL="http://127.0.0.1:$auth_port/auth/jwks"
  IDENTITY_EXCHANGE_URL="http://127.0.0.1:$auth_port/auth/token/exchange"
  MYSQL_PORT="$MYSQL_PORT"
  MYSQL_AGENT_APP_PASSWORD="$agent_app_password"
  AGENT_SERVICE_CLIENT_SECRET="$service_password"
  SUPPORT_SESSION_ID="$session_id"
)
env "${common_probe_env[@]}" DIRECT_TOKEN="$direct_token" \
  uv run python scripts/identity_chain_probe.py --output "$tmp_dir/obo.jwt"
obo_token="$(tr -d '\n' <"$tmp_dir/obo.jwt")"
assert_status 403 "direct-user token rejected on commerce tool route" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $direct_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
assert_status 403 "OBO support-session substitution rejected" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header 'X-Support-Session-Id: forged-session' \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
assert_status 400 "body identity substitution rejected" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1","userSubject":"other-user"}'
assert_status 200 "exact OBO tool request returns bounded product view" \
  --request POST "http://127.0.0.1:$commerce_port/internal/tools/catalog.product.get" \
  --header "Authorization: Bearer $obo_token" \
  --header "X-Support-Session-Id: $session_id" \
  --header 'Content-Type: application/json' \
  --data '{"productId":"product-1"}'
test "$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" productId)" = product-1
if grep -Eq '"(description|stockQuantity)"' "$tmp_dir/http-response.json"; then
  echo "Private product fields leaked through the bounded tool response." >&2
  exit 1
fi
if env "${common_probe_env[@]}" DIRECT_TOKEN="$other_token" \
    uv run python scripts/identity_chain_probe.py --output "$tmp_dir/forged-obo.jwt" \
    >"$tmp_dir/cross-user.log" 2>&1; then
  echo "Cross-user support session unexpectedly exchanged." >&2
  exit 1
fi
grep -q '403: Forbidden' "$tmp_dir/cross-user.log"
echo "Verified every JIT exchange rechecks persisted session ownership."

assert_status 401 "OBO rejected on direct-user session route" \
  --request POST "http://127.0.0.1:$agent_port/api/sessions" \
  --header "Authorization: Bearer $obo_token" \
  --header 'Content-Type: application/json' \
  --data '{}'
assert_status 401 "invalid independent service credential" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user agent-service:wrong \
  --header "X-User-Authorization: Bearer $direct_token" \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$session_id\",\"userSubject\":\"user-integration\",\"scope\":\"catalog:read\"}"
assert_status 403 "scope widening" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$service_password" \
  --header "X-User-Authorization: Bearer $direct_token" \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$session_id\",\"userSubject\":\"user-integration\",\"scope\":\"catalog:read catalog:write\"}"
assert_status 403 "wildcard scope" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$service_password" \
  --header "X-User-Authorization: Bearer $direct_token" \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$session_id\",\"userSubject\":\"user-integration\",\"scope\":\"catalog:*\"}"
assert_status 403 "forged session user assertion" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$service_password" \
  --header "X-User-Authorization: Bearer $direct_token" \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$session_id\",\"userSubject\":\"other-user\",\"scope\":\"catalog:read\"}"
assert_status 403 "empty support-session assertion" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$service_password" \
  --header "X-User-Authorization: Bearer $direct_token" \
  --header 'Content-Type: application/json' \
  --data '{"sessionId":"","userSubject":"user-integration","scope":"catalog:read"}'
assert_status 401 "auth exchange rejects production evaluation header" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$service_password" \
  --header "X-User-Authorization: Bearer $direct_token" \
  --header 'X-Eval-Sandbox-Id: forbidden-production-context' \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$session_id\",\"userSubject\":\"user-integration\",\"scope\":\"catalog:read\"}"

required_surefire_classes=(io.citybuddy.commerce.identity.OboChainIntegrationTest)
clear_surefire_reports \
  "$repo_root/commerce-service/target/surefire-reports" \
  "${required_surefire_classes[@]}"
IDENTITY_PROBE_OBO="$obo_token" \
IDENTITY_PROBE_DIRECT="$direct_token" \
IDENTITY_JWKS_URL="http://127.0.0.1:$auth_port/auth/jwks" \
SUPPORT_SESSION_ID="$session_id" \
./mvnw -q -pl commerce-service -Dtest=OboChainIntegrationTest test
assert_surefire_classes_executed \
  "$repo_root/commerce-service/target/surefire-reports" \
  "${required_surefire_classes[@]}"

mysql_query auth_app "$auth_app_password" commerce_db \
  "UPDATE auth_user_principal SET state = 'DISABLED' WHERE subject = 'user-integration'"
assert_status 403 "token exchange rechecks current principal state" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$service_password" \
  --header "X-User-Authorization: Bearer $direct_token" \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"$session_id\",\"userSubject\":\"user-integration\",\"scope\":\"catalog:read\"}"

if rg -l 'BEGIN (RSA )?PRIVATE KEY' auth-service/target commerce-service/target agent-service/dist 2>/dev/null; then
  echo "Private signing material leaked into a build artifact." >&2
  exit 1
fi

echo "CB-020 identity, CB-080/CB-081 control, and CB-082 SSE/feedback integration passed."
