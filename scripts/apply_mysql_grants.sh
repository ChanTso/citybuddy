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
  "GRANT CREATE ON commerce_db.* TO 'auth_migration'@'%';"
  "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, TRIGGER ON commerce_db.auth_schema_history TO 'auth_migration'@'%';"
  "GRANT CREATE ON commerce_db.* TO 'commerce_migration'@'%';"
  "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, TRIGGER ON commerce_db.commerce_schema_history TO 'commerce_migration'@'%';"
  "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER ON cs_db.* TO 'agent_migration'@'%';"
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
  --batch
  --skip-column-names
)

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

sql="SET ROLE 'bootstrap_grant_role';
SELECT CONCAT('role-active=', CURRENT_ROLE());
$(<"$manifest")
SET ROLE NONE;
SELECT CONCAT('role-after=', CURRENT_ROLE());"
output="$(mysql "${mysql_args[@]}" --execute="$sql")"
echo "$output"

grep -qx 'role-active=`bootstrap_grant_role`@`%`' <<<"$output"
grep -qx 'role-after=NONE' <<<"$output"
echo "Grant manifest V001 applied with explicit role activation and cleanup."
