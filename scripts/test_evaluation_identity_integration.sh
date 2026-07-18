#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
project="citybuddy-cb100-test-$$"
auth_port="$((44500 + ($$ % 400)))"
export MYSQL_PORT="$((33500 + ($$ % 400)))"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)
auth_pid=""

cleanup() {
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
  echo "Verified MySQL rejection: $label"
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
  for _ in {1..60}; do
    if curl --silent --fail "http://127.0.0.1:$auth_port/auth/jwks" >/dev/null 2>&1; then
      return
    fi
    if ! kill -0 "$auth_pid" >/dev/null 2>&1; then
      cat "$tmp_dir/auth.log" >&2
      exit 1
    fi
    sleep 1
  done
  cat "$tmp_dir/auth.log" >&2
  echo "Timed out waiting for auth-service" >&2
  exit 1
}

stop_auth() {
  kill "$auth_pid"
  wait "$auth_pid" || true
  auth_pid=""
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
  wait_http
}

ENV_FILE="$env_file" ./scripts/init_local.sh
auth_app_password="$(read_value MYSQL_AUTH_APP_PASSWORD)"
commerce_app_password="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
agent_app_password="$(read_value MYSQL_AGENT_APP_PASSWORD)"
commerce_service_password="$(openssl rand -hex 24)"
evaluator_password="$(openssl rand -hex 24)"
agent_service_password="$(openssl rand -hex 24)"
production_user_password="$(openssl rand -hex 24)"
commerce_service_hash="$(uv run python scripts/hash_test_credential.py "$commerce_service_password")"
evaluator_hash="$(uv run python scripts/hash_test_credential.py "$evaluator_password")"
agent_service_hash="$(uv run python scripts/hash_test_credential.py "$agent_service_password")"
production_user_hash="$(uv run python scripts/hash_test_credential.py "$production_user_password")"

"${compose[@]}" up --detach --wait --wait-timeout 60 mysql
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-auth migrate-commerce migrate-agent
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" grant-access

mysql_query auth_app "$auth_app_password" commerce_db "
INSERT INTO auth_user_principal (principal_id, subject, login_identifier, state, permissions)
VALUES ('00000000-0000-0000-0000-000000000100', 'production-user', 'production-user', 'ACTIVE', 'support:session:create');
INSERT INTO auth_login_credential (principal_id, password_hash)
VALUES ('00000000-0000-0000-0000-000000000100', '$production_user_hash');
INSERT INTO auth_service_identity (service_id, client_id, credential_hash, state, allowed_scopes) VALUES
  ('00000000-0000-0000-0000-000000000101', 'commerce-service', '$commerce_service_hash', 'ACTIVE', 'eval:principal:manage'),
  ('00000000-0000-0000-0000-000000000102', 'evaluation-client', '$evaluator_hash', 'ACTIVE', 'eval:test-token:issue'),
  ('00000000-0000-0000-0000-000000000103', 'agent-service', '$agent_service_hash', 'ACTIVE', 'catalog:read');
INSERT INTO auth_signing_key_metadata (kid, state, activated_at, retire_after) VALUES
  ('current-key', 'CURRENT', CURRENT_TIMESTAMP(6), NULL),
  ('overlap-key', 'OVERLAP', CURRENT_TIMESTAMP(6), TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP(6)));
"

test "$(mysql_query auth_app "$auth_app_password" commerce_db 'SELECT COUNT(*) FROM auth_eval_test_principal')" = 0
assert_mysql_fails "commerce runtime cannot read auth evaluation principal truth" \
  mysql_query commerce_app "$commerce_app_password" commerce_db 'SELECT * FROM auth_eval_test_principal'
assert_mysql_fails "agent runtime cannot read auth evaluation principal truth" \
  mysql_query agent_app "$agent_app_password" commerce_db 'SELECT * FROM auth_eval_test_principal'
assert_mysql_fails "auth runtime cannot execute DDL" \
  mysql_query auth_app "$auth_app_password" commerce_db 'CREATE TABLE forbidden_eval_ddl (id INT)'
auth_grants="$(mysql_query auth_app "$auth_app_password" '' 'SHOW GRANTS FOR CURRENT_USER')"
grep -Fq 'GRANT SELECT, INSERT, UPDATE ON `commerce_db`.`auth_eval_test_principal`' <<<"$auth_grants"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp_dir/current-private.pem" 2>/dev/null
openssl pkey -in "$tmp_dir/current-private.pem" -pubout -out "$tmp_dir/current-public.pem" 2>/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp_dir/overlap-private.pem" 2>/dev/null
openssl pkey -in "$tmp_dir/overlap-private.pem" -pubout -out "$tmp_dir/overlap-public.pem" 2>/dev/null
./mvnw -q -pl auth-service -am -DskipTests package

start_auth production
assert_status 404 "production profile omits provisioning route" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: production-forbidden' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-1","caseCorrelation":"case-1","testUserLabel":"user-1","ttlSeconds":300}'
assert_status 404 "production profile omits revocation route" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/opaque/revoke" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: production-forbidden' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-1","caseCorrelation":"case-1"}'
assert_status 404 "production profile omits test-token route" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-1' \
  --header 'Content-Type: application/json' \
  --data '{"handle":"opaque"}'
assert_status 200 "production direct-user login" \
  --request POST "http://127.0.0.1:$auth_port/auth/login" \
  --header 'Content-Type: application/json' \
  --data "{\"loginIdentifier\":\"production-user\",\"password\":\"$production_user_password\"}"
production_direct_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
stop_auth

start_auth evaluation
curl --silent --show-error "http://127.0.0.1:$auth_port/auth/jwks" >"$tmp_dir/jwks.json"
uv run python scripts/check_identity_jwks.py "$tmp_dir/jwks.json" current-key overlap-key

provision_request='{"sandboxId":"sandbox-1","caseCorrelation":"case-1","testUserLabel":"test-user-1","ttlSeconds":300}'
assert_status 401 "evaluator credential cannot provision" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "evaluation-client:$evaluator_password" \
  --header 'Idempotency-Key: provision-1' \
  --header 'Content-Type: application/json' \
  --data "$provision_request"
assert_status 401 "wrong commerce credential cannot provision" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user 'commerce-service:wrong' \
  --header 'Idempotency-Key: provision-1' \
  --header 'Content-Type: application/json' \
  --data "$provision_request"
test "$(mysql_query auth_app "$auth_app_password" commerce_db 'SELECT COUNT(*) FROM auth_eval_test_principal')" = 0

assert_status 200 "evaluation test-principal provision" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: provision-1' \
  --header 'Content-Type: application/json' \
  --data "$provision_request"
cp "$tmp_dir/http-response.json" "$tmp_dir/provision.json"
handle="$(uv run python scripts/read_json_field.py "$tmp_dir/provision.json" handle)"
test "${#handle}" = 43
if grep -Eq 'subject|caseCorrelation|testUserLabel|idempotency|credential|password' "$tmp_dir/provision.json"; then
  echo "Private provisioning metadata leaked in response." >&2
  exit 1
fi
assert_status 200 "same-intent provisioning replay" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: provision-1' \
  --header 'Content-Type: application/json' \
  --data "$provision_request"
cmp "$tmp_dir/provision.json" "$tmp_dir/http-response.json"
test "$(mysql_query auth_app "$auth_app_password" commerce_db "SELECT COUNT(*) FROM auth_eval_test_principal WHERE opaque_handle = '$handle' AND sandbox_id = 'sandbox-1' AND case_correlation = 'case-1' AND state = 'PROVISIONED'")" = 1

assert_status 409 "provisioning idempotency intent conflict" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: provision-1' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-1","caseCorrelation":"case-1","testUserLabel":"different-user","ttlSeconds":300}'
assert_status 409 "sandbox and case cannot bind a second handle" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: provision-2' \
  --header 'Content-Type: application/json' \
  --data "$provision_request"
assert_status 400 "unbounded evaluation TTL" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: invalid-ttl' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-2","caseCorrelation":"case-2","testUserLabel":"test-user-2","ttlSeconds":3601}'
assert_status 400 "malformed test-principal attribute" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: invalid-attribute' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-2","caseCorrelation":"case-2","testUserLabel":"contains space","ttlSeconds":300}'

assert_status 401 "unknown handle token issuance" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-1' \
  --header 'Content-Type: application/json' \
  --data '{"handle":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/private-handle-error.json"
assert_status 401 "cross-sandbox handle token issuance" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-2' \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$handle\"}"
cmp "$tmp_dir/private-handle-error.json" "$tmp_dir/http-response.json"
assert_status 401 "commerce credential cannot issue test token" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "commerce-service:$commerce_service_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-1' \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$handle\"}"

assert_status 200 "sandbox-bound evaluation token issuance" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-1' \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$handle\"}"
evaluation_direct_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
printf '%s' "$evaluation_direct_token" >"$tmp_dir/evaluation-direct.jwt"
record_expiry="$(mysql_query auth_app "$auth_app_password" commerce_db "SELECT FLOOR(UNIX_TIMESTAMP(expires_at)) FROM auth_eval_test_principal WHERE opaque_handle = '$handle'")"
uv run python scripts/check_evaluation_token.py \
  --token-file "$tmp_dir/evaluation-direct.jwt" \
  --jwks-file "$tmp_dir/jwks.json" \
  --issuer https://identity.citybuddy.test \
  --audience citybuddy-web \
  --token-type eval_direct_user \
  --sandbox sandbox-1 \
  --maximum-expiry "$record_expiry" \
  --output "$tmp_dir/evaluation-direct.json"
evaluation_subject="$(uv run python scripts/read_json_field.py "$tmp_dir/evaluation-direct.json" subject)"
direct_expiry="$(uv run python scripts/read_json_field.py "$tmp_dir/evaluation-direct.json" expiresAt)"

assert_status 401 "evaluation token cannot drop sandbox header" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $evaluation_direct_token" \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"eval-session-1\",\"userSubject\":\"$evaluation_subject\",\"scope\":\"catalog:read\"}"
assert_status 401 "evaluation token cannot substitute sandbox header" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $evaluation_direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-2' \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"eval-session-1\",\"userSubject\":\"$evaluation_subject\",\"scope\":\"catalog:read\"}"
assert_status 401 "production token cannot acquire sandbox" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $production_direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-1' \
  --header 'Content-Type: application/json' \
  --data '{"sessionId":"eval-session-1","userSubject":"production-user","scope":"catalog:read"}'
assert_status 403 "evaluation exchange rejects scope widening" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $evaluation_direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-1' \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"eval-session-1\",\"userSubject\":\"$evaluation_subject\",\"scope\":\"catalog:read catalog:write\"}"
assert_status 200 "evaluation exchange preserves exact sandbox" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $evaluation_direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-1' \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"eval-session-1\",\"userSubject\":\"$evaluation_subject\",\"scope\":\"catalog:read\"}"
evaluation_obo_token="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" accessToken)"
printf '%s' "$evaluation_obo_token" >"$tmp_dir/evaluation-obo.jwt"
uv run python scripts/check_evaluation_token.py \
  --token-file "$tmp_dir/evaluation-obo.jwt" \
  --jwks-file "$tmp_dir/jwks.json" \
  --issuer https://identity.citybuddy.test \
  --audience commerce-service \
  --token-type agent_obo \
  --sandbox sandbox-1 \
  --maximum-expiry "$direct_expiry" \
  --output "$tmp_dir/evaluation-obo.json"
test "$(uv run python scripts/read_json_field.py "$tmp_dir/evaluation-obo.json" subject)" = "$evaluation_subject"

assert_status 404 "revocation hides cross-case binding" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/$handle/revoke" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: revoke-1' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-1","caseCorrelation":"case-other"}'
assert_status 200 "evaluation principal revocation" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/$handle/revoke" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: revoke-1' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-1","caseCorrelation":"case-1"}'
cp "$tmp_dir/http-response.json" "$tmp_dir/revoked.json"
assert_status 200 "same-intent revocation replay" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/$handle/revoke" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: revoke-1' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-1","caseCorrelation":"case-1"}'
cmp "$tmp_dir/revoked.json" "$tmp_dir/http-response.json"
assert_status 409 "revocation idempotency conflict" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/$handle/revoke" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: revoke-other' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-1","caseCorrelation":"case-1"}'
assert_status 401 "revoked handle cannot issue another token" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-1' \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$handle\"}"
assert_status 403 "revocation invalidates existing evaluation token exchange" \
  --request POST "http://127.0.0.1:$auth_port/auth/token/exchange" \
  --user "agent-service:$agent_service_password" \
  --header "X-User-Authorization: Bearer $evaluation_direct_token" \
  --header 'X-Eval-Sandbox-Id: sandbox-1' \
  --header 'Content-Type: application/json' \
  --data "{\"sessionId\":\"eval-session-1\",\"userSubject\":\"$evaluation_subject\",\"scope\":\"catalog:read\"}"

assert_status 200 "provision lifecycle record for restart evidence" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: restart-provision' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-restart","caseCorrelation":"case-restart","testUserLabel":"user-restart","ttlSeconds":300}'
restart_handle="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" handle)"
stop_auth
start_auth evaluation
assert_status 200 "restart preserves token-issuance lifecycle truth" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-restart' \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$restart_handle\"}"

assert_status 200 "provision expiry evidence record" \
  --request POST "http://127.0.0.1:$auth_port/internal/eval/test-principals/provision" \
  --user "commerce-service:$commerce_service_password" \
  --header 'Idempotency-Key: expiry-provision' \
  --header 'Content-Type: application/json' \
  --data '{"sandboxId":"sandbox-expiry","caseCorrelation":"case-expiry","testUserLabel":"user-expiry","ttlSeconds":60}'
expiry_handle="$(uv run python scripts/read_json_field.py "$tmp_dir/http-response.json" handle)"
mysql_query auth_app "$auth_app_password" commerce_db \
  "UPDATE auth_eval_test_principal SET created_at = TIMESTAMPADD(MINUTE, -2, CURRENT_TIMESTAMP(6)), expires_at = TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP(6)) WHERE opaque_handle = '$expiry_handle'"
assert_status 401 "expired handle cannot issue token" \
  --request POST "http://127.0.0.1:$auth_port/auth/eval/test-token" \
  --user "evaluation-client:$evaluator_password" \
  --header 'X-Eval-Sandbox-Id: sandbox-expiry' \
  --header 'Content-Type: application/json' \
  --data "{\"handle\":\"$expiry_handle\"}"

for private_value in \
  "$commerce_service_password" \
  "$evaluator_password" \
  "$agent_service_password" \
  test-user-1 \
  provision-1 \
  revoke-1; do
  if grep -Fq "$private_value" "$tmp_dir/auth.log"; then
    echo "Private evaluation input leaked into auth log." >&2
    exit 1
  fi
done
if rg -l 'BEGIN (RSA )?PRIVATE KEY' auth-service/target 2>/dev/null; then
  echo "Private signing material leaked into auth build artifact." >&2
  exit 1
fi

echo "CB-100 evaluation identity provisioning, lifecycle, JWT/JWKS, OBO, profile, grant, restart, and redaction integration passed."
