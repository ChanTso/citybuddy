#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_HOST:?MYSQL_HOST is required}"
: "${MYSQL_PORT:?MYSQL_PORT is required}"
: "${MYSQL_USER:?MYSQL_USER is required}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
: "${MIGRATION_STREAM:?MIGRATION_STREAM is required}"

case "$MIGRATION_STREAM:$MYSQL_DATABASE:$MYSQL_USER" in
  auth:commerce_db:auth_migration) history_table="auth_schema_history" ;;
  commerce:commerce_db:commerce_migration) history_table="commerce_schema_history" ;;
  agent:cs_db:agent_migration) history_table="agent_schema_history" ;;
  *)
    echo "Migration configuration refuses identity '$MYSQL_USER' for stream '$MIGRATION_STREAM' and database '$MYSQL_DATABASE'." >&2
    exit 1
    ;;
esac

migration_dir="/opt/citybuddy/migrations"
if [[ ! -d "$migration_dir" ]]; then
  echo "Missing migration directory: $migration_dir" >&2
  exit 1
fi

export MYSQL_PWD="$MYSQL_PASSWORD"
mysql_args=(
  --protocol=tcp
  --host="$MYSQL_HOST"
  --port="$MYSQL_PORT"
  --user="$MYSQL_USER"
  --database="$MYSQL_DATABASE"
  --batch
  --skip-column-names
)

actual_database="$(mysql "${mysql_args[@]}" --execute='SELECT DATABASE()')"
if [[ "$actual_database" != "$MYSQL_DATABASE" ]]; then
  echo "Expected database '$MYSQL_DATABASE', connected to '$actual_database'." >&2
  exit 1
fi

mysql "${mysql_args[@]}" --execute="
CREATE TABLE IF NOT EXISTS ${history_table} (
  installed_rank BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  version VARCHAR(50) NOT NULL UNIQUE,
  description VARCHAR(200) NOT NULL,
  checksum CHAR(64) NOT NULL,
  installed_on TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  success BOOLEAN NOT NULL
) ENGINE=InnoDB;"

shopt -s nullglob
migrations=("$migration_dir"/V*__*.sql)
if (( ${#migrations[@]} == 0 )); then
  echo "Migration stream '$MIGRATION_STREAM' contains no versioned migrations." >&2
  exit 1
fi

for migration in "${migrations[@]}"; do
  filename="$(basename "$migration")"
  version="${filename%%__*}"
  version="${version#V}"
  description="${filename#*__}"
  description="${description%.sql}"
  description="${description//_/ }"
  if [[ ! "$version" =~ ^[0-9]+$ || ! "$description" =~ ^[A-Za-z0-9\ _-]+$ ]]; then
    echo "Invalid migration filename: $filename" >&2
    exit 1
  fi
  checksum="$(sha256sum "$migration" | awk '{print $1}')"
  recorded="$(mysql "${mysql_args[@]}" --execute="SELECT CONCAT(checksum, ':', IF(success, '1', '0')) FROM ${history_table} WHERE version = '${version}'")"

  if [[ -n "$recorded" ]]; then
    recorded_checksum="${recorded%%:*}"
    recorded_success="${recorded#*:}"
    if [[ "$recorded_checksum" != "$checksum" ]]; then
      echo "Checksum mismatch for $MIGRATION_STREAM migration $filename." >&2
      exit 1
    fi
    if [[ "$recorded_success" != "1" ]]; then
      echo "Migration $MIGRATION_STREAM/$filename previously failed or is incomplete; refusing automatic retry." >&2
      exit 1
    fi
    echo "[$MIGRATION_STREAM] already applied: $filename"
    continue
  fi

  echo "[$MIGRATION_STREAM] applying: $filename"
  mysql "${mysql_args[@]}" --execute="
    INSERT INTO ${history_table} (version, description, checksum, success)
    VALUES ('${version}', '${description}', '${checksum}', FALSE);"
  mysql "${mysql_args[@]}" <"$migration"
  mysql "${mysql_args[@]}" --execute="
    UPDATE ${history_table} SET success = TRUE WHERE version = '${version}';"
done

failed="$(mysql "${mysql_args[@]}" --execute="SELECT COUNT(*) FROM ${history_table} WHERE success = FALSE")"
if [[ "$failed" != "0" ]]; then
  echo "Migration stream '$MIGRATION_STREAM' has failed history entries." >&2
  exit 1
fi

echo "[$MIGRATION_STREAM] migration history validated in $MYSQL_DATABASE."
