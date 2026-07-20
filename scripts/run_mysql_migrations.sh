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

prepare_v013="${MIGRATION_PREPARE_V013:-false}"
if [[ "$prepare_v013" != true && "$prepare_v013" != false ]]; then
  echo "MIGRATION_PREPARE_V013 must be true or false." >&2
  exit 1
fi
if [[ "$prepare_v013" == true && "$MIGRATION_STREAM" != commerce ]]; then
  echo "The V013 grant barrier is available only to the commerce migration stream." >&2
  exit 1
fi

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

v013_barrier='CITYBUDDY_V013_EXACT_GRANT_BARRIER'
v013_awaiting_comment='V013_AWAITING_COMMITMENT'

v013_comment() {
  mysql "${mysql_args[@]}" --execute="
    SELECT table_comment FROM information_schema.tables
    WHERE table_schema = 'commerce_db'
      AND table_name = 'eval_commerce_audit_legacy_watermark'"
}

shopt -s nullglob
migrations=("$migration_dir"/V*__*.sql)
if (( ${#migrations[@]} == 0 )); then
  echo "Migration stream '$MIGRATION_STREAM' contains no versioned migrations." >&2
  exit 1
fi

prepared_v013=false

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
      if [[ "$MIGRATION_STREAM" == commerce && "$version" == 013 ]]; then
        phase="$(v013_comment)"
        if [[ "$phase" != "$v013_awaiting_comment" ]]; then
          echo "Migration commerce/$filename is incomplete outside its exact grant barrier; refusing automatic retry." >&2
          exit 1
        fi
        if [[ "$prepare_v013" == true ]]; then
          echo "[commerce] prepared exact grant barrier: $filename"
          prepared_v013=true
          break
        fi
        echo "[commerce] resuming after exact grant barrier: $filename"
        awk -v marker="$v013_barrier" 'seen { print } index($0, marker) { seen = 1 }' \
          "$migration" | mysql "${mysql_args[@]}"
        mysql "${mysql_args[@]}" --execute="
          UPDATE ${history_table} SET success = TRUE WHERE version = '${version}';"
        continue
      fi
      echo "Migration $MIGRATION_STREAM/$filename previously failed or is incomplete; refusing automatic retry." >&2
      exit 1
    fi
    echo "[$MIGRATION_STREAM] already applied: $filename"
    if [[ "$prepare_v013" == true && "$MIGRATION_STREAM" == commerce && "$version" == 013 ]]; then
      break
    fi
    continue
  fi

  if [[ "$prepare_v013" == true && "$MIGRATION_STREAM" == commerce && "$version" == 013 ]]; then
    if ! grep -Fq -- "-- $v013_barrier" "$migration"; then
      echo "Commerce V013 is missing its exact grant barrier." >&2
      exit 1
    fi
    echo "[commerce] preparing exact grant barrier: $filename"
    mysql "${mysql_args[@]}" --execute="
      INSERT INTO ${history_table} (version, description, checksum, success)
      VALUES ('${version}', '${description}', '${checksum}', FALSE);"
    awk -v marker="$v013_barrier" 'index($0, marker) { exit } { print }' \
      "$migration" | mysql "${mysql_args[@]}"
    if [[ "$(v013_comment)" != "$v013_awaiting_comment" ]]; then
      echo "Commerce V013 did not reach its exact grant barrier cleanly." >&2
      exit 1
    fi
    prepared_v013=true
    break
  fi

  echo "[$MIGRATION_STREAM] applying: $filename"
  mysql "${mysql_args[@]}" --execute="
    INSERT INTO ${history_table} (version, description, checksum, success)
    VALUES ('${version}', '${description}', '${checksum}', FALSE);"
  mysql "${mysql_args[@]}" <"$migration"
  mysql "${mysql_args[@]}" --execute="
    UPDATE ${history_table} SET success = TRUE WHERE version = '${version}';"
done

if [[ "$prepared_v013" == true ]]; then
  echo "[commerce] V013 schema prepared; exact table grants are required before commitment."
  exit 0
fi

failed="$(mysql "${mysql_args[@]}" --execute="SELECT COUNT(*) FROM ${history_table} WHERE success = FALSE")"
if [[ "$failed" != "0" ]]; then
  echo "Migration stream '$MIGRATION_STREAM' has failed history entries." >&2
  exit 1
fi

echo "[$MIGRATION_STREAM] migration history validated in $MYSQL_DATABASE."
