#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tmp_dir="$(mktemp -d)"
env_file="$tmp_dir/.env"
missing_env="$tmp_dir/missing.env"
bad_env="$tmp_dir/bad.env"
project="citybuddy-cb010-test-$$"
compose=(docker compose --project-name "$project" --env-file "$env_file" --file compose.yaml)

cleanup() {
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
  local args=(
    mysql
    --protocol=tcp
    --host=127.0.0.1
    --port=3306
    --user="$user"
    --batch
    --skip-column-names
  )
  if [[ -n "$database" ]]; then
    args+=(--database="$database")
  fi
  "${compose[@]}" exec -T -e MYSQL_PWD="$password" mysql "${args[@]}" --execute="$statement"
}

assert_fails() {
  local label="$1"
  local expected="$2"
  shift 2
  local log="$tmp_dir/rejection.log"
  local status
  if "$@" >"$log" 2>&1; then
    echo "Expected rejection succeeded unexpectedly: $label" >&2
    cat "$log" >&2
    exit 1
  else
    status=$?
  fi
  if ! grep -Eq "$expected" "$log"; then
    echo "Rejection '$label' failed for an unexpected reason (exit $status):" >&2
    sed -E 's/[0-9a-f]{48}/<redacted>/g' "$log" >&2
    exit 1
  fi
  echo "Verified rejection (exit $status): $label"
  sed -E 's/[0-9a-f]{48}/<redacted>/g' "$log"
}

assert_no_admin_grants() {
  local user="$1"
  local password="$2"
  local grants
  grants="$(mysql_query "$user" "$password" "" 'SHOW GRANTS FOR CURRENT_USER')"
  echo "$grants"
  if grep -Eq 'ALL PRIVILEGES|CREATE USER|SYSTEM_USER|SUPER|WITH GRANT OPTION' <<<"$grants"; then
    echo "Unexpected admin/global grant for $user." >&2
    exit 1
  fi
}

assert_bootstrap_grant_path() {
  local grants
  grants="$(mysql_query bootstrap_admin "$bootstrap_password" "" 'SHOW GRANTS FOR CURRENT_USER')"
  echo "$grants"
  grep -Eq 'CREATE USER' <<<"$grants"
  grep -Eq 'GRANT `bootstrap_grant_role`@`%` TO `bootstrap_admin`@`%`' <<<"$grants"
  if grep -Eq 'ON `(commerce_db|cs_db)`\.' <<<"$grants"; then
    echo "Bootstrap unexpectedly has direct application database privileges." >&2
    exit 1
  fi
}

ENV_FILE="$env_file" ./scripts/init_local.sh
first_hash="$(sha256sum "$env_file" | awk '{print $1}')"
ENV_FILE="$env_file" ./scripts/init_local.sh
second_hash="$(sha256sum "$env_file" | awk '{print $1}')"
test "$first_hash" = "$second_hash"
echo "Verified init-local preserves existing credentials."

assert_fails "missing configuration blocks make up" 'Missing local configuration' \
  make ENV_FILE="$missing_env" COMPOSE_PROJECT_NAME="$project-missing" up

# Isolate the integration project from any developer runtime using a per-process
# high port. The tests connect through the Docker network, not this host port.
export MYSQL_PORT
MYSQL_PORT="$((33060 + ($$ % 1000)))"

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" up

bootstrap_password="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
auth_migration_password="$(read_value MYSQL_AUTH_MIGRATION_PASSWORD)"
commerce_migration_password="$(read_value MYSQL_COMMERCE_MIGRATION_PASSWORD)"
agent_migration_password="$(read_value MYSQL_AGENT_MIGRATION_PASSWORD)"
auth_app_password="$(read_value MYSQL_AUTH_APP_PASSWORD)"
commerce_app_password="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
agent_app_password="$(read_value MYSQL_AGENT_APP_PASSWORD)"

declare -a identities=(
  "bootstrap_admin:$bootstrap_password"
  "auth_migration:$auth_migration_password"
  "commerce_migration:$commerce_migration_password"
  "agent_migration:$agent_migration_password"
  "auth_app:$auth_app_password"
  "commerce_app:$commerce_app_password"
  "agent_app:$agent_app_password"
)
for identity in "${identities[@]}"; do
  user="${identity%%:*}"
  password="${identity#*:}"
  current_user="$(mysql_query "$user" "$password" "" 'SELECT CURRENT_USER()')"
  test "$current_user" = "$user@%"
  echo "Authenticated distinct identity: $current_user"
done

auth_history="$(mysql_query auth_migration "$auth_migration_password" commerce_db 'SELECT COUNT(*) FROM auth_schema_history WHERE success = TRUE')"
commerce_history="$(mysql_query commerce_migration "$commerce_migration_password" commerce_db 'SELECT COUNT(*) FROM commerce_schema_history WHERE success = TRUE')"
agent_history="$(mysql_query agent_migration "$agent_migration_password" cs_db 'SELECT COUNT(*) FROM agent_schema_history WHERE success = TRUE')"
test "$auth_history" = "1"
test "$commerce_history" = "1"
test "$agent_history" = "1"
echo "Verified three independent migration histories."

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-auth
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-commerce
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" migrate-agent

assert_bootstrap_grant_path
assert_no_admin_grants auth_migration "$auth_migration_password"
assert_no_admin_grants commerce_migration "$commerce_migration_password"
assert_no_admin_grants agent_migration "$agent_migration_password"
assert_no_admin_grants auth_app "$auth_app_password"
assert_no_admin_grants commerce_app "$commerce_app_password"
assert_no_admin_grants agent_app "$agent_app_password"

assert_fails "bootstrap cannot read migration or business data" '(Access denied|command denied).*bootstrap_admin' \
  mysql_query bootstrap_admin "$bootstrap_password" commerce_db 'SELECT * FROM auth_schema_history'
assert_fails "auth migration cannot read commerce migration history" 'SELECT command denied' \
  mysql_query auth_migration "$auth_migration_password" commerce_db 'SELECT * FROM commerce_schema_history'
assert_fails "commerce migration cannot read auth migration history" 'SELECT command denied' \
  mysql_query commerce_migration "$commerce_migration_password" commerce_db 'SELECT * FROM auth_schema_history'
assert_fails "auth runtime cannot execute DDL" '(Access denied|command denied).*auth_app' \
  mysql_query auth_app "$auth_app_password" "" 'CREATE TABLE commerce_db.cb010_forbidden (id INT)'
assert_fails "commerce runtime cannot execute DDL" '(Access denied|command denied).*commerce_app' \
  mysql_query commerce_app "$commerce_app_password" "" 'CREATE TABLE commerce_db.cb010_forbidden (id INT)'
assert_fails "agent runtime cannot execute DDL" '(Access denied|command denied).*agent_app' \
  mysql_query agent_app "$agent_app_password" "" 'CREATE TABLE cs_db.cb010_forbidden (id INT)'
assert_fails "agent runtime cannot access commerce_db" 'Access denied.*agent_app' \
  mysql_query agent_app "$agent_app_password" commerce_db 'SELECT 1'
assert_fails "agent migration cannot access commerce_db" 'Access denied.*agent_migration' \
  mysql_query agent_migration "$agent_migration_password" commerce_db 'SELECT 1'
assert_fails "auth runtime cannot access cs_db" 'Access denied.*auth_app' \
  mysql_query auth_app "$auth_app_password" cs_db 'SELECT 1'
assert_fails "commerce runtime cannot access cs_db" 'Access denied.*commerce_app' \
  mysql_query commerce_app "$commerce_app_password" cs_db 'SELECT 1'
assert_fails "auth stream rejects the wrong target database" 'Migration configuration refuses identity' \
  "${compose[@]}" run --rm -e MYSQL_DATABASE=cs_db auth-migrate
assert_fails "auth identity cannot execute the commerce stream" 'Migration configuration refuses identity' \
  "${compose[@]}" run --rm -e MIGRATION_STREAM=commerce auth-migrate

before_down="$auth_history:$commerce_history:$agent_history"
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" down
docker volume inspect "${project}_mysql-data" >/dev/null
make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" up
after_down="$(mysql_query auth_migration "$auth_migration_password" commerce_db 'SELECT COUNT(*) FROM auth_schema_history WHERE success = TRUE'):$(mysql_query commerce_migration "$commerce_migration_password" commerce_db 'SELECT COUNT(*) FROM commerce_schema_history WHERE success = TRUE'):$(mysql_query agent_migration "$agent_migration_password" cs_db 'SELECT COUNT(*) FROM agent_schema_history WHERE success = TRUE')"
test "$before_down" = "$after_down"
echo "Verified make down preserves durable migration history."

bad_password="$(printf '%048d' 0)"
sed "s/^MYSQL_AUTH_MIGRATION_PASSWORD=.*/MYSQL_AUTH_MIGRATION_PASSWORD=$bad_password/" "$env_file" >"$bad_env"
assert_fails "migration authentication failure makes startup non-zero" "Access denied for user 'auth_migration'" \
  make ENV_FILE="$bad_env" COMPOSE_PROJECT_NAME="$project" up

make ENV_FILE="$env_file" COMPOSE_PROJECT_NAME="$project" up

failure_dir="$tmp_dir/failing-auth-migrations"
mkdir -p "$failure_dir"
cp infra/mysql/migrations/auth/V001__validate_auth_target.sql "$failure_dir/"
printf '%s\n' 'SELECT * FROM cb010_missing_failure_probe;' >"$failure_dir/V002__controlled_failure.sql"
assert_fails "failed migration is visible and non-zero" 'ERROR 1142.*SELECT command denied.*auth_migration' \
  "${compose[@]}" run --rm --volume "$failure_dir:/opt/citybuddy/migrations:ro" auth-migrate
failed_history="$(mysql_query auth_migration "$auth_migration_password" commerce_db "SELECT success FROM auth_schema_history WHERE version = '002'")"
test "$failed_history" = "0"
assert_fails "failed migration history blocks automatic retry" 'previously failed or is incomplete' \
  "${compose[@]}" run --rm --volume "$failure_dir:/opt/citybuddy/migrations:ro" auth-migrate

echo "CB-010 MySQL integration checks passed."
