#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb020-test-$$"
auth_port="$((44000 + ($$ % 500)))"
agent_port="$((45000 + ($$ % 500)))"
export MYSQL_PORT="$((33060 + ($$ % 500)))"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
auth_pid=""
agent_pid=""

cleanup() {
  if [[ -n "$agent_pid" ]]; then
    kill "$agent_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$auth_pid" ]]; then
    kill "$auth_pid" >/dev/null 2>&1 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
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
  shift
  if "$@" >"$tmp_dir/mysql-rejection.log" 2>&1; then
    echo "Expected MySQL rejection succeeded: $label" >&2
    exit 1
  fi
  grep -Eq 'Access denied|command denied' "$tmp_dir/mysql-rejection.log"
  echo "Verified permission rejection: $label"
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
agent_app_password="$(read_value MYSQL_AGENT_APP_PASSWORD)"
user_password="$(openssl rand -hex 24)"
other_password="$(openssl rand -hex 24)"
disabled_password="$(openssl rand -hex 24)"
service_password="$(openssl rand -hex 24)"
user_hash="$(uv run python scripts/hash_test_credential.py "$user_password")"
other_hash="$(uv run python scripts/hash_test_credential.py "$other_password")"
disabled_hash="$(uv run python scripts/hash_test_credential.py "$disabled_password")"
service_hash="$(uv run python scripts/hash_test_credential.py "$service_password")"

"${compose[@]}" up --detach --wait --wait-timeout 60 mysql
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-auth migrate-commerce migrate-agent
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access

mysql_query auth_app "$auth_app_password" commerce_db "
INSERT INTO auth_user_principal (principal_id, subject, login_identifier, state, permissions) VALUES
  ('00000000-0000-0000-0000-000000000020', 'user-integration', 'integration-user', 'ACTIVE', 'support:session:create'),
  ('00000000-0000-0000-0000-000000000021', 'other-user', 'other-user', 'ACTIVE', 'support:session:create'),
  ('00000000-0000-0000-0000-000000000022', 'disabled-user', 'disabled-user', 'DISABLED', 'support:session:create');
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

test "$(mysql_query auth_app "$auth_app_password" commerce_db 'SELECT COUNT(*) FROM auth_user_principal')" = 3
test "$(mysql_query agent_app "$agent_app_password" cs_db 'SELECT COUNT(*) FROM support_session')" = 0
assert_mysql_fails "auth_app cannot read commerce migration history" \
  mysql_query auth_app "$auth_app_password" commerce_db 'SELECT * FROM commerce_schema_history'
assert_mysql_fails "auth_app cannot access cs_db" \
  mysql_query auth_app "$auth_app_password" cs_db 'SELECT * FROM support_session'
assert_mysql_fails "auth_app cannot execute DDL" \
  mysql_query auth_app "$auth_app_password" commerce_db 'CREATE TABLE forbidden_auth_ddl (id INT)'
assert_mysql_fails "agent_app cannot access auth tables" \
  mysql_query agent_app "$agent_app_password" commerce_db 'SELECT * FROM auth_user_principal'
assert_mysql_fails "agent_app cannot execute DDL" \
  mysql_query agent_app "$agent_app_password" cs_db 'CREATE TABLE forbidden_agent_ddl (id INT)'

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp_dir/current-private.pem" 2>/dev/null
openssl pkey -in "$tmp_dir/current-private.pem" -pubout -out "$tmp_dir/current-public.pem" 2>/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp_dir/overlap-private.pem" 2>/dev/null
openssl pkey -in "$tmp_dir/overlap-private.pem" -pubout -out "$tmp_dir/overlap-public.pem" 2>/dev/null

./mvnw -q -pl auth-service -am -DskipTests package
SPRING_DATASOURCE_PASSWORD="$auth_app_password" java -jar auth-service/target/auth-service-0.0.1-SNAPSHOT.jar \
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
  >"$tmp_dir/auth.log" 2>&1 &
auth_pid=$!
wait_http "http://127.0.0.1:$auth_port/auth/jwks" "$auth_pid" "$tmp_dir/auth.log"

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

AGENT_PORT="$agent_port" \
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
uv run citybuddy-agent >"$tmp_dir/agent.log" 2>&1 &
agent_pid=$!
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

IDENTITY_PROBE_OBO="$obo_token" \
IDENTITY_PROBE_DIRECT="$direct_token" \
IDENTITY_JWKS_URL="http://127.0.0.1:$auth_port/auth/jwks" \
SUPPORT_SESSION_ID="$session_id" \
./mvnw -q -pl commerce-service -Dtest=OboChainIntegrationTest test

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

echo "CB-020 login -> session -> JIT exchange -> commerce authorization integration passed."
