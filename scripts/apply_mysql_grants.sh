#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Grant job rejects caller-supplied SQL or arguments." >&2
  exit 1
fi

v013_force_revoke="${V013_FORCE_REVOKE:-false}"
if [[ "$v013_force_revoke" != true && "$v013_force_revoke" != false ]]; then
  echo "V013_FORCE_REVOKE must be true or false." >&2
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
  "GRANT CREATE, ALTER, REFERENCES ON commerce_db.* TO 'commerce_migration'@'%';"
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
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.mock_refund TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON cs_db.support_session TO 'agent_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON cs_db.support_conversation TO 'agent_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON cs_db.support_turn TO 'agent_app'@'%';"
  "GRANT SELECT, INSERT ON cs_db.support_event TO 'agent_app'@'%';"
  "GRANT SELECT, INSERT ON cs_db.support_feedback TO 'agent_app'@'%';"
  "GRANT SELECT, INSERT ON cs_db.retrieval_decision TO 'agent_app'@'%';"
  "GRANT SELECT, INSERT ON cs_db.retrieval_evidence TO 'agent_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.auth_eval_test_principal TO 'auth_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.eval_sandbox TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT, UPDATE, DELETE ON commerce_db.eval_sandbox_product_fixture TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON commerce_db.eval_sandbox_effect_stub TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON commerce_db.eval_commerce_audit_reference TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON commerce_db.eval_commerce_product_observation TO 'commerce_app'@'%';"
  "GRANT SELECT ON commerce_db.eval_commerce_audit_legacy_watermark TO 'commerce_app'@'%';"
  "GRANT SELECT ON commerce_db.eval_commerce_audit_reference TO 'commerce_migration'@'%';"
  "GRANT INSERT ON commerce_db.eval_commerce_audit_legacy_watermark TO 'commerce_migration'@'%';"
  "REVOKE IF EXISTS SELECT ON commerce_db.eval_commerce_audit_reference FROM 'commerce_migration'@'%';"
  "REVOKE IF EXISTS INSERT ON commerce_db.eval_commerce_audit_legacy_watermark FROM 'commerce_migration'@'%';"
  "GRANT SELECT, INSERT, UPDATE ON commerce_db.faq_source TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON commerce_db.faq_draft_command TO 'commerce_app'@'%';"
  "GRANT SELECT, INSERT ON commerce_db.faq_publication_command TO 'commerce_app'@'%';"
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
support_grant="${actual[23]}"
support_lifecycle_grants="$(printf '%s\n' "${actual[@]:23:4}")"
support_feedback_grants="$(printf '%s\n' "${actual[@]:23:5}")"
legacy_runtime_sql="$(printf '%s\n' "${actual[@]:5:4}" "$support_grant")"
evaluation_grant="${actual[30]}"
sandbox_grants="$(printf '%s\n' "${actual[@]:31:3}")"
evaluation_audit_grant="${actual[34]}"
evaluation_product_observation_grant="${actual[35]}"
evaluation_audit_watermark_grant="${actual[36]}"
v013_migration_grants="$(printf '%s\n' "${actual[37]}" "${actual[38]}")"
v013_migration_revokes="$(printf '%s\n' "${actual[39]}" "${actual[40]}")"
faq_runtime_grants="$(printf '%s\n' "${actual[@]:41:3}")"

if [[ "$v013_force_revoke" == true ]]; then
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $v013_migration_revokes
    SET ROLE NONE;"
  echo "migration-v013-grants=force-revoked"
  exit 0
fi

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
  SET SESSION group_concat_max_len = 8192;
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
      'auth_eval_test_principal',
      'eval_sandbox',
      'eval_sandbox_product_fixture',
      'eval_sandbox_effect_stub',
      'eval_commerce_audit_reference',
      'eval_commerce_product_observation',
      'eval_commerce_audit_legacy_watermark',
      'faq_source',
      'faq_draft_command',
      'faq_publication_command',
      'crm_profile',
      'product',
      'catalog_metadata',
      'commerce_outbox',
      'standard_order',
      'mock_payment_attempt',
      'mock_payment_callback',
      'mock_refund',
      'order_idempotency',
      'seckill_activity',
      'seckill_reservation',
      'seckill_order',
      'inventory_ledger',
      'support_session',
      'support_conversation',
      'support_turn',
      'support_event',
      'support_feedback',
      'retrieval_decision',
      'retrieval_evidence'
    );
  SET ROLE NONE;")"

remove_runtime_table() {
  local table_list="$1"
  local table_name="$2"
  table_list=",$table_list,"
  table_list="${table_list/,${table_name},/,}"
  table_list="${table_list#,}"
  table_list="${table_list%,}"
  printf '%s\n' "$table_list"
}

normalized_runtime_table_state="$runtime_table_state"
auth_runtime_table_state="4:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal"
evaluation_table_present=false
sandbox_tables_present=false
evaluation_audit_table_present=false
evaluation_product_observation_table_present=false
evaluation_audit_watermark_table_present=false
faq_tables_present=false
feedback_table_present=false
retrieval_tables_present=false
retrieval_decision_present=false
retrieval_evidence_present=false
if [[ "$normalized_runtime_table_state" == *"commerce_db.auth_eval_test_principal"* ]]; then
  runtime_table_count="${normalized_runtime_table_state%%:*}"
  runtime_table_list="${normalized_runtime_table_state#*:}"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.auth_eval_test_principal)"
  normalized_runtime_table_state="$((runtime_table_count - 1)):$runtime_table_list"
  evaluation_table_present=true
fi
sandbox_table_count=0
for sandbox_table in \
  commerce_db.eval_sandbox \
  commerce_db.eval_sandbox_effect_stub \
  commerce_db.eval_sandbox_product_fixture; do
  if [[ ",$normalized_runtime_table_state," == *",$sandbox_table,"* ]]; then
    sandbox_table_count=$((sandbox_table_count + 1))
  fi
done
if (( sandbox_table_count != 0 && sandbox_table_count != 3 )); then
  echo "Grant job found a partial evaluation-sandbox schema." >&2
  exit 1
elif (( sandbox_table_count == 3 )); then
  runtime_table_count="${normalized_runtime_table_state%%:*}"
  runtime_table_list="${normalized_runtime_table_state#*:}"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.eval_sandbox)"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.eval_sandbox_effect_stub)"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.eval_sandbox_product_fixture)"
  normalized_runtime_table_state="$((runtime_table_count - 3)):$runtime_table_list"
  sandbox_tables_present=true
fi
if [[ ",$normalized_runtime_table_state," == *",commerce_db.eval_commerce_audit_reference,"* ]]; then
  if [[ "$sandbox_tables_present" != true ]]; then
    echo "Grant job found evaluation audit without the prerequisite sandbox schema." >&2
    exit 1
  fi
  runtime_table_count="${normalized_runtime_table_state%%:*}"
  runtime_table_list="${normalized_runtime_table_state#*:}"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.eval_commerce_audit_reference)"
  normalized_runtime_table_state="$((runtime_table_count - 1)):$runtime_table_list"
  evaluation_audit_table_present=true
fi
if [[ ",$normalized_runtime_table_state," == *",commerce_db.eval_commerce_product_observation,"* ]]; then
  if [[ "$evaluation_audit_table_present" != true ]]; then
    echo "Grant job found product observation truth without the prerequisite audit schema." >&2
    exit 1
  fi
  runtime_table_count="${normalized_runtime_table_state%%:*}"
  runtime_table_list="${normalized_runtime_table_state#*:}"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.eval_commerce_product_observation)"
  normalized_runtime_table_state="$((runtime_table_count - 1)):$runtime_table_list"
  evaluation_product_observation_table_present=true
fi
if [[ ",$normalized_runtime_table_state," == *",commerce_db.eval_commerce_audit_legacy_watermark,"* ]]; then
  if [[ "$evaluation_product_observation_table_present" != true ]]; then
    echo "Grant job found audit legacy watermark without totality truth." >&2
    exit 1
  fi
  runtime_table_count="${normalized_runtime_table_state%%:*}"
  runtime_table_list="${normalized_runtime_table_state#*:}"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.eval_commerce_audit_legacy_watermark)"
  normalized_runtime_table_state="$((runtime_table_count - 1)):$runtime_table_list"
  evaluation_audit_watermark_table_present=true
fi
if [[ "$normalized_runtime_table_state" == "$auth_runtime_table_state" ]] && \
  [[ "$sandbox_tables_present" == true || "$evaluation_audit_table_present" == true || \
    "$evaluation_product_observation_table_present" == true || \
    "$evaluation_audit_watermark_table_present" == true ]]; then
  echo "Grant job rejects commerce evaluation tables without the commerce runtime schema." >&2
  exit 1
fi
faq_table_count=0
for faq_table in \
  commerce_db.faq_draft_command \
  commerce_db.faq_publication_command \
  commerce_db.faq_source; do
  if [[ ",$normalized_runtime_table_state," == *",$faq_table,"* ]]; then
    faq_table_count=$((faq_table_count + 1))
  fi
done
if (( faq_table_count != 0 && faq_table_count != 3 )); then
  echo "Grant job found a partial FAQ publication schema." >&2
  exit 1
elif (( faq_table_count == 3 )); then
  runtime_table_count="${normalized_runtime_table_state%%:*}"
  runtime_table_list="${normalized_runtime_table_state#*:}"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.faq_draft_command)"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.faq_publication_command)"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    commerce_db.faq_source)"
  normalized_runtime_table_state="$((runtime_table_count - 3)):$runtime_table_list"
  faq_tables_present=true
fi
if [[ "$evaluation_audit_watermark_table_present" == true ]]; then
  v013_phase="$(mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    SELECT table_comment FROM information_schema.tables
    WHERE table_schema = 'commerce_db'
      AND table_name = 'eval_commerce_audit_legacy_watermark';
    SET ROLE NONE;")"
  case "$v013_phase" in
    V013_AWAITING_COMMITMENT)
      mysql "${mysql_args[@]}" --execute="
        SET ROLE 'bootstrap_grant_role';
        $v013_migration_grants
        SET ROLE NONE;"
      echo "migration-v013-grants=exact-prepared"
      ;;
    V013_COMMITMENT_SEALED)
      mysql "${mysql_args[@]}" --execute="
        SET ROLE 'bootstrap_grant_role';
        $v013_migration_revokes
        SET ROLE NONE;"
      echo "migration-v013-grants=revoked"
      ;;
    V013_COMMITMENT_POPULATING)
      mysql "${mysql_args[@]}" --execute="
        SET ROLE 'bootstrap_grant_role';
        $v013_migration_revokes
        SET ROLE NONE;"
      echo "Grant job found an interrupted V013 commitment; exact grants were removed." >&2
      exit 1
      ;;
    *)
      mysql "${mysql_args[@]}" --execute="
        SET ROLE 'bootstrap_grant_role';
        $v013_migration_revokes
        SET ROLE NONE;"
      echo "Grant job found an unknown V013 commitment phase: $v013_phase" >&2
      exit 1
      ;;
  esac
fi
if [[ "$runtime_table_state" == *"cs_db.retrieval_decision"* ]]; then
  retrieval_decision_present=true
fi
if [[ "$runtime_table_state" == *"cs_db.retrieval_evidence"* ]]; then
  retrieval_evidence_present=true
fi
if [[ "$retrieval_decision_present" != "$retrieval_evidence_present" ]]; then
  echo "Grant job found a partial retrieval-evidence schema." >&2
  exit 1
elif [[ "$retrieval_decision_present" == true ]]; then
  runtime_table_count="${normalized_runtime_table_state%%:*}"
  runtime_table_list="${normalized_runtime_table_state#*:}"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    cs_db.retrieval_decision)"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" \
    cs_db.retrieval_evidence)"
  normalized_runtime_table_state="$((runtime_table_count - 2)):$runtime_table_list"
  retrieval_tables_present=true
fi
if [[ ",$normalized_runtime_table_state," == *",cs_db.support_feedback,"* ]]; then
  runtime_table_count="${normalized_runtime_table_state%%:*}"
  runtime_table_list="${normalized_runtime_table_state#*:}"
  runtime_table_list="$(remove_runtime_table "$runtime_table_list" cs_db.support_feedback)"
  normalized_runtime_table_state="$((runtime_table_count - 1)):$runtime_table_list"
  feedback_table_present=true
fi
legacy_runtime_table_state="5:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,cs_db.support_session"
cb080_legacy_runtime_table_state="8:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,cs_db.support_conversation,cs_db.support_event,cs_db.support_session,cs_db.support_turn"
catalog_runtime_table_state="9:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.product,cs_db.support_session"
cb080_catalog_runtime_table_state="12:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.product,cs_db.support_conversation,cs_db.support_event,cs_db.support_session,cs_db.support_turn"
order_runtime_table_state="11:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.order_idempotency,commerce_db.product,commerce_db.standard_order,cs_db.support_session"
cb080_order_runtime_table_state="14:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.order_idempotency,commerce_db.product,commerce_db.standard_order,cs_db.support_conversation,cs_db.support_event,cs_db.support_session,cs_db.support_turn"
seckill_runtime_table_state="12:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.standard_order,cs_db.support_session"
cb080_seckill_runtime_table_state="15:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.standard_order,cs_db.support_conversation,cs_db.support_event,cs_db.support_session,cs_db.support_turn"
reservation_runtime_table_state="13:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_session"
cb080_reservation_runtime_table_state="16:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_conversation,cs_db.support_event,cs_db.support_session,cs_db.support_turn"
transaction_runtime_table_state="15:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.inventory_ledger,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_order,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_session"
cb080_transaction_runtime_table_state="18:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.inventory_ledger,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_order,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_conversation,cs_db.support_event,cs_db.support_session,cs_db.support_turn"
payment_runtime_table_state="17:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.inventory_ledger,commerce_db.mock_payment_attempt,commerce_db.mock_payment_callback,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_order,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_session"
cb080_payment_runtime_table_state="20:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.inventory_ledger,commerce_db.mock_payment_attempt,commerce_db.mock_payment_callback,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_order,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_conversation,cs_db.support_event,cs_db.support_session,cs_db.support_turn"
complete_runtime_table_state="18:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.inventory_ledger,commerce_db.mock_payment_attempt,commerce_db.mock_payment_callback,commerce_db.mock_refund,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_order,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_session"
commerce_complete_runtime_table_state="17:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.inventory_ledger,commerce_db.mock_payment_attempt,commerce_db.mock_payment_callback,commerce_db.mock_refund,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_order,commerce_db.seckill_reservation,commerce_db.standard_order"
cb080_runtime_table_state="21:commerce_db.auth_login_credential,commerce_db.auth_service_identity,commerce_db.auth_signing_key_metadata,commerce_db.auth_user_principal,commerce_db.catalog_metadata,commerce_db.commerce_outbox,commerce_db.crm_profile,commerce_db.inventory_ledger,commerce_db.mock_payment_attempt,commerce_db.mock_payment_callback,commerce_db.mock_refund,commerce_db.order_idempotency,commerce_db.product,commerce_db.seckill_activity,commerce_db.seckill_order,commerce_db.seckill_reservation,commerce_db.standard_order,cs_db.support_conversation,cs_db.support_event,cs_db.support_session,cs_db.support_turn"
optional_evaluation_grants=""
if [[ "$evaluation_table_present" == true ]]; then
  optional_evaluation_grants="$evaluation_grant"
fi
if [[ "$sandbox_tables_present" == true ]]; then
  optional_evaluation_grants="$(printf '%s\n' "$optional_evaluation_grants" "$sandbox_grants")"
fi
if [[ "$evaluation_audit_table_present" == true ]]; then
  optional_evaluation_grants="$(printf '%s\n' "$optional_evaluation_grants" "$evaluation_audit_grant")"
fi
if [[ "$evaluation_product_observation_table_present" == true ]]; then
  optional_evaluation_grants="$(printf '%s\n' "$optional_evaluation_grants" "$evaluation_product_observation_grant")"
fi
if [[ "$evaluation_audit_watermark_table_present" == true ]]; then
  optional_evaluation_grants="$(printf '%s\n' "$optional_evaluation_grants" "$evaluation_audit_watermark_grant")"
fi
if [[ "$faq_tables_present" == true ]]; then
  optional_evaluation_grants="$(printf '%s\n' "$optional_evaluation_grants" "$faq_runtime_grants")"
fi
if [[ "$normalized_runtime_table_state" == "$commerce_complete_runtime_table_state" ]]; then
  selected_runtime_sql="$(printf '%s\n' "${actual[@]:5:18}")"
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_runtime_sql="$(printf '%s\n' "$selected_runtime_sql" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $selected_runtime_sql
    SET ROLE NONE;"
  echo "runtime-grants=commerce-applied-awaiting-support-migration"
elif [[ "$normalized_runtime_table_state" == "$cb080_runtime_table_state" ]]; then
  selected_runtime_sql="$(printf '%s\n' "${actual[@]:5:22}")"
  if [[ "$retrieval_tables_present" == true ]]; then
    if [[ "$feedback_table_present" != true ]]; then
      echo "Grant job found retrieval tables without the prerequisite feedback schema." >&2
      exit 1
    fi
    selected_runtime_sql="$(printf '%s\n' "${actual[@]:5:25}")"
  elif [[ "$feedback_table_present" == true ]]; then
    selected_runtime_sql="$(printf '%s\n' "${actual[@]:5:23}")"
  fi
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_runtime_sql="$(printf '%s\n' "$selected_runtime_sql" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $selected_runtime_sql
    SET ROLE NONE;"
  echo "runtime-grants=applied"
elif [[ "$normalized_runtime_table_state" == "$complete_runtime_table_state" ]]; then
  selected_runtime_sql="$(printf '%s\n' "${actual[@]:5:19}")"
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_runtime_sql="$(printf '%s\n' "$selected_runtime_sql" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $selected_runtime_sql
    SET ROLE NONE;"
  echo "runtime-grants=refund-applied-awaiting-support-lifecycle-migration"
elif [[ "$normalized_runtime_table_state" == "$payment_runtime_table_state" || "$normalized_runtime_table_state" == "$cb080_payment_runtime_table_state" ]]; then
  selected_support_grants="$support_grant"
  if [[ "$feedback_table_present" == true ]]; then
    selected_support_grants="$support_feedback_grants"
  elif [[ "$normalized_runtime_table_state" == "$cb080_payment_runtime_table_state" ]]; then
    selected_support_grants="$support_lifecycle_grants"
  fi
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_support_grants="$(printf '%s\n' "$selected_support_grants" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:17}" "$selected_support_grants")
    SET ROLE NONE;"
  echo "runtime-grants=payment-applied-awaiting-refund-migration"
elif [[ "$normalized_runtime_table_state" == "$transaction_runtime_table_state" || "$normalized_runtime_table_state" == "$cb080_transaction_runtime_table_state" ]]; then
  selected_support_grants="$support_grant"
  if [[ "$feedback_table_present" == true ]]; then
    selected_support_grants="$support_feedback_grants"
  elif [[ "$normalized_runtime_table_state" == "$cb080_transaction_runtime_table_state" ]]; then
    selected_support_grants="$support_lifecycle_grants"
  fi
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_support_grants="$(printf '%s\n' "$selected_support_grants" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:9}" "${actual[@]:17:5}" "$selected_support_grants")
    SET ROLE NONE;"
  echo "runtime-grants=transaction-applied-awaiting-payment-migration"
elif [[ "$normalized_runtime_table_state" == "$reservation_runtime_table_state" || "$normalized_runtime_table_state" == "$cb080_reservation_runtime_table_state" ]]; then
  selected_support_grants="$support_grant"
  if [[ "$feedback_table_present" == true ]]; then
    selected_support_grants="$support_feedback_grants"
  elif [[ "$normalized_runtime_table_state" == "$cb080_reservation_runtime_table_state" ]]; then
    selected_support_grants="$support_lifecycle_grants"
  fi
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_support_grants="$(printf '%s\n' "$selected_support_grants" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:9}" "${actual[@]:17:3}" "$selected_support_grants")
    SET ROLE NONE;"
  echo "runtime-grants=reservation-applied-awaiting-transaction-order-migration"
elif [[ "$normalized_runtime_table_state" == "$seckill_runtime_table_state" || "$normalized_runtime_table_state" == "$cb080_seckill_runtime_table_state" ]]; then
  selected_support_grants="$support_grant"
  if [[ "$feedback_table_present" == true ]]; then
    selected_support_grants="$support_feedback_grants"
  elif [[ "$normalized_runtime_table_state" == "$cb080_seckill_runtime_table_state" ]]; then
    selected_support_grants="$support_lifecycle_grants"
  fi
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_support_grants="$(printf '%s\n' "$selected_support_grants" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:9}" "${actual[@]:17:2}" "$selected_support_grants")
    SET ROLE NONE;"
  echo "runtime-grants=seckill-applied-awaiting-reservation-migration"
elif [[ "$normalized_runtime_table_state" == "$order_runtime_table_state" || "$normalized_runtime_table_state" == "$cb080_order_runtime_table_state" ]]; then
  selected_support_grants="$support_grant"
  if [[ "$feedback_table_present" == true ]]; then
    selected_support_grants="$support_feedback_grants"
  elif [[ "$normalized_runtime_table_state" == "$cb080_order_runtime_table_state" ]]; then
    selected_support_grants="$support_lifecycle_grants"
  fi
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_support_grants="$(printf '%s\n' "$selected_support_grants" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:9}" "${actual[17]}" "$selected_support_grants")
    SET ROLE NONE;"
  echo "runtime-grants=order-applied-awaiting-seckill-migration"
elif [[ "$normalized_runtime_table_state" == "$catalog_runtime_table_state" || "$normalized_runtime_table_state" == "$cb080_catalog_runtime_table_state" ]]; then
  selected_support_grants="$support_grant"
  if [[ "$feedback_table_present" == true ]]; then
    selected_support_grants="$support_feedback_grants"
  elif [[ "$normalized_runtime_table_state" == "$cb080_catalog_runtime_table_state" ]]; then
    selected_support_grants="$support_lifecycle_grants"
  fi
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_support_grants="$(printf '%s\n' "$selected_support_grants" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $(printf '%s\n' "${actual[@]:5:8}" "$selected_support_grants")
    SET ROLE NONE;"
  echo "runtime-grants=catalog-applied-awaiting-order-migration"
elif [[ "$normalized_runtime_table_state" == "$legacy_runtime_table_state" || "$normalized_runtime_table_state" == "$cb080_legacy_runtime_table_state" ]]; then
  selected_legacy_runtime_sql="$legacy_runtime_sql"
  if [[ "$feedback_table_present" == true ]]; then
    selected_legacy_runtime_sql="$(printf '%s\n' "${actual[@]:5:4}" "$support_feedback_grants")"
  elif [[ "$normalized_runtime_table_state" == "$cb080_legacy_runtime_table_state" ]]; then
    selected_legacy_runtime_sql="$(printf '%s\n' "${actual[@]:5:4}" "$support_lifecycle_grants")"
  fi
  if [[ -n "$optional_evaluation_grants" ]]; then
    selected_legacy_runtime_sql="$(printf '%s\n' "$selected_legacy_runtime_sql" "$optional_evaluation_grants")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $selected_legacy_runtime_sql
    SET ROLE NONE;"
  echo "runtime-grants=legacy-applied-awaiting-migrations"
elif [[ "$normalized_runtime_table_state" == "$auth_runtime_table_state" ]]; then
  selected_auth_runtime_sql="$(printf '%s\n' "${actual[@]:5:4}")"
  if [[ "$evaluation_table_present" == true ]]; then
    selected_auth_runtime_sql="$(printf '%s\n' "$selected_auth_runtime_sql" "$evaluation_grant")"
  fi
  mysql "${mysql_args[@]}" --execute="
    SET ROLE 'bootstrap_grant_role';
    $selected_auth_runtime_sql
    SET ROLE NONE;"
  echo "runtime-grants=auth-applied-awaiting-commerce-and-support-migrations"
elif [[ "$normalized_runtime_table_state" == "0:none" ]]; then
  echo "runtime-grants=deferred-until-migrations"
else
  echo "Grant job found an unexpected runtime table set: $runtime_table_state" >&2
  exit 1
fi
echo "Grant manifest V001 applied with explicit role activation and cleanup."
