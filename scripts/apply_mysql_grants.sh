#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Grant job rejects caller-supplied SQL or arguments." >&2
  exit 1
fi

: "${MYSQL_HOST:?MYSQL_HOST is required}"
: "${MYSQL_PORT:?MYSQL_PORT is required}"
: "${MYSQL_USER:?MYSQL_USER is required}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"

mysql_connect_attempts="${MYSQL_CONNECT_ATTEMPTS:-30}"
mysql_connect_retry_seconds="${MYSQL_CONNECT_RETRY_SECONDS:-1}"
if [[ ! "$mysql_connect_attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "MYSQL_CONNECT_ATTEMPTS must be a positive integer." >&2
  exit 1
fi
if [[ ! "$mysql_connect_retry_seconds" =~ ^[0-9]+$ ]]; then
  echo "MYSQL_CONNECT_RETRY_SECONDS must be a non-negative integer." >&2
  exit 1
fi

if [[ "$MYSQL_USER" != "bootstrap_admin" ]]; then
  echo "Grant job requires the bootstrap_admin identity." >&2
  exit 1
fi

manifest="/opt/citybuddy/grants/V001__migration_access.sql"
if [[ ! -f "$manifest" ]]; then
  echo "Grant job is missing its fixed manifest: $manifest" >&2
  exit 1
fi

expected=(
  "GRANT CREATE, REFERENCES ON commerce_db.* TO 'auth_migration'@'%';"
  "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, TRIGGER ON commerce_db.auth_schema_history TO 'auth_migration'@'%';"
  "GRANT CREATE, ALTER ON commerce_db.* TO 'commerce_migration'@'%';"
  "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, TRIGGER ON commerce_db.commerce_schema_history TO 'commerce_migration'@'%';"
  "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER ON cs_db.* TO 'agent_migration'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.auth_user_principal TO 'auth_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.auth_login_credential TO 'auth_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.auth_service_identity TO 'auth_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.auth_signing_key_metadata TO 'auth_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.crm_profile TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.product TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.catalog_metadata TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.commerce_outbox TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON commerce_db.standard_order TO 'commerce_app'@'%';"
  "GRANT UPDATE ON commerce_db.standard_order TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.mock_payment_attempt TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON commerce_db.mock_payment_callback TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.order_idempotency TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.seckill_activity TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.seckill_reservation TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.seckill_order TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON commerce_db.inventory_ledger TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON cs_db.support_session TO 'agent_app'@'%';"
)
mapfile -t actual < <(sed -e '/^[[:space:]]*$/d' -e '/^[[:space:]]*--/d' "$manifest")

if (( ${#actual[@]} != ${#expected[@]} )); then
  echo "Grant manifest rejects non-allowlisted statement count." >&2
  exit 1
fi
for index in "${!expected[@]}"; do
  if [[ "${actual[$index]}" != "${expected[$index]}" ]]; then
    echo "Grant manifest rejects non-allowlisted statement at line $((index + 1))." >&2
    exit 1
  fi
done

export MYSQL_PWD="$MYSQL_PASSWORD"
mysql_args=(
  --protocol=tcp
  --host="$MYSQL_HOST"
  --port="$MYSQL_PORT"
  --user="$MYSQL_USER"
  --connect-timeout=2
  --batch
  --skip-column-names
)

wait_for_mysql_tcp() {
  local attempt
  local output
  for ((attempt = 1; attempt <= mysql_connect_attempts; attempt++)); do
    if output="$(mysql "${mysql_args[@]}" --execute='SELECT 1' 2>&1)"; then
      if [[ "$output" != "1" ]]; then
        echo "MySQL TCP readiness returned an unexpected response: $output" >&2
        return 1
      fi
      if (( attempt > 1 )); then
        echo "mysql-tcp-ready-after-attempt=$attempt"
      fi
      return 0
    fi

    if ! grep -Eq 'ERROR 2003 .*Can.t connect to MySQL server' <<<"$output"; then
      printf '%s\n' "$output" >&2
      return 1
    fi
    if (( attempt == mysql_connect_attempts )); then
      echo "MySQL TCP readiness failed after $mysql_connect_attempts attempts." >&2
      printf '%s\n' "$output" >&2
      return 1
    fi
    echo "MySQL TCP is not ready (attempt $attempt/$mysql_connect_attempts); retrying." >&2
    sleep "$mysql_connect_retry_seconds"
  done
}

wait_for_mysql_tcp

auto_roles="$(mysql "${mysql_args[@]}" --execute='SELECT @@GLOBAL.activate_all_roles_on_login')"
if [[ "$auto_roles" != "0" ]]; then
  echo "Grant job refuses activate_all_roles_on_login=ON." >&2
  exit 1
fi

fresh_role="$(mysql "${mysql_args[@]}" --execute='SELECT CURRENT_ROLE()')"
if [[ "$fresh_role" != "NONE" ]]; then
  echo "Grant job refuses a fresh session with active role: $fresh_role" >&2
  exit 1
fi
echo "role-before=$fresh_role"

migration_statement_count=5
migration_sql="$(printf '%s\n' "${actual[@]:0:$migration_statement_count}")"
runtime_sql="$(printf '%s\n' "${actual[@]:$migration_statement_count}")"
support_grant="${actual[22]}"
legacy_runtime_sql="$(printf '%s\n' "${actual[@]:5:4}" "$support_grant")"

sql="SET ROLE 'bootstrap_grant_role';
SELECT CONCAT('role-active=', CURRENT_ROLE());
$migration_sql
SET ROLE NONE;
SELECT CONCAT('role-after=', CURRENT_ROLE());"
output="$(mysql "${mysql_args[@]}" --execute="$sql")"
echo "$output"

grep -qx 'role-active=`bootstrap_grant_role`@`%`' <<<"$output"
grep -qx 'role-after=NONE' <<<"$output"

runtime_table_state="$(mysql "${mysql_args[@]}" --execute="
  SET ROLE 'bootstrap_grant_role';
  SELECT CONCAT(
    COUNT(*),
    ':',
    COALESCE(
      GROUP_CONCAT(
        CONCAT(table_schema, '.', table_name)
        ORDER BY table_schema, table_name
        SEPARATOR ','
      ),
      'none'
    )
  ) FROM information_schema.tables
  WHERE table_schema IN ('commerce_db', 'cs_db')
    AND table_name IN (
      'auth_user_principal',
      'auth_login_credential',
      'auth_service_identity',
      'auth_signing_key_metadata',
      'crm_profile',
      'product',
      'catalog_metadata',
      'commerce_outbox',
      'standard_order',
      'mock_payment_attempt',
      'mock_payment_callback',
      'order_idempotency',
      'seckill_activity',
      'seckill_reservation',
      'seckill_order',
      'inventory_ledger',
      'support_session'
    );
  SET ROLE NONE;")"
legacy_runtime_table_state="5:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,cs_db.support_session"
catalog_runtime_table_state="9:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.product,cs_db.support_session"
order_runtime_table_state="11:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.order_idempotency,commerce_db.product,commerce_db.standard_order,cs_db.support_session"
seckill_runtime_table_state="12:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.standard_order,cs_db.support_session"
reservation_runtime_table_state="13:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_session"
transaction_runtime_table_state="15:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.inventory_ledger,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_order,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_session"
complete_runtime_table_state="17:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.inventory_ledger,commerce_db.mock_payment_attempt,commerce_db.mock_payment_callback,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_order,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_session"

if [[ "$runtime_table_state" == "$complete_runtime_table_state" ]]; then
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $runtime_sql
    SET ROLE NONE;"
  echo "runtime-grants=applied"
elif [[ "$runtime_table_state" == "$transaction_runtime_table_state" ]]; then
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:9}" "${actual[@]:17:5}" "$support_grant")
    SET ROLE NONE;"
  echo "runtime-grants=transaction-applied-awaiting-payment-migration"
elif [[ "$runtime_table_state" == "$reservation_runtime_table_state" ]]; then
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:9}" "${actual[@]:17:3}" "$support_grant")
    SET ROLE NONE;"
  echo "runtime-grants=reservation-applied-awaiting-transaction-order-migration"
elif [[ "$runtime_table_state" == "$seckill_runtime_table_state" ]]; then
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:9}" "${actual[@]:17:2}" "$support_grant")
    SET ROLE NONE;"
  echo "runtime-grants=seckill-applied-awaiting-reservation-migration"
elif [[ "$runtime_table_state" == "$order_runtime_table_state" ]]; then
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:9}" "${actual[17]}" "$support_grant")
    SET ROLE NONE;"
  echo "runtime-grants=order-applied-awaiting-seckill-migration"
elif [[ "$runtime_table_state" == "$catalog_runtime_table_state" ]]; then
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:8}" "$support_grant")
    SET ROLE NONE;"
  echo "runtime-grants=catalog-applied-awaiting-order-migration"
elif [[ "$runtime_table_state" == "$legacy_runtime_table_state" ]]; then
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $legacy_runtime_sql
    SET ROLE NONE;"
  echo "runtime-grants=legacy-applied-awaiting-migrations"
elif [[ "$runtime_table_state" == "0:none" ]]; then
  echo "runtime-grants=deferred-until-migrations"
else
  echo "Grant job found an unexpected runtime table set: $runtime_table_state" >&2
  exit 1
fi
echo "Grant manifest V001 applied with explicit role activation and cleanup."
