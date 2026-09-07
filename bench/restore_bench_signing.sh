#!/usr/bin/env bash
# Restore the saved public JWKS metadata only after the benchmark applications have stopped.
set -euo pipefail
umask 077
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
backup="$repo_root/bench/.run/bench-original-signing-metadata.sql"
if [ ! -f "$backup" ]; then echo "No original signing metadata backup; preserve current state." >&2; exit 1; fi
docker info >/dev/null
for name in citybuddy-bench-auth citybuddy-bench-commerce citybuddy-bench-k6; do
  cid="$(docker ps -aq --filter "name=^/${name}$")"
  if [ -n "$cid" ] && [ "$(docker inspect -f '{{.State.Running}}' "$cid")" = true ]; then
    echo "Stop $name before restoring signing metadata." >&2; exit 1
  fi
done
port="$(docker port citybuddy-mysql-1 3306/tcp | cut -d: -f2)"
password="$(grep -E '^MYSQL_BOOTSTRAP_PASSWORD=' .env | head -1 | cut -d= -f2-)"
sql() { MYSQL_PWD="$password" mysql --protocol=TCP -h 127.0.0.1 -P "$port" -u root -D commerce_db --batch --raw --skip-column-names; }
current="$(mktemp "$repo_root/bench/.run/signing-current.XXXXXX")"
trap 'rm -f "$current"' EXIT
snapshot="SET SESSION time_zone='+00:00'; SELECT CONCAT('INSERT INTO auth_signing_key_metadata (kid,state,activated_at,retire_after) VALUES (',QUOTE(kid),',',QUOTE(state),',',QUOTE(DATE_FORMAT(activated_at,'%Y-%m-%d %H:%i:%s.%f')),',',IF(retire_after IS NULL,'NULL',QUOTE(DATE_FORMAT(retire_after,'%Y-%m-%d %H:%i:%s.%f'))),');') FROM auth_signing_key_metadata ORDER BY kid;"
printf '%s\n' "$snapshot" | sql > "$current"
if cmp -s "$current" "$backup"; then echo "Original signing metadata is already present."; exit 0; fi
if [ "$(printf '%s\n' "SELECT COUNT(*)=1 AND COALESCE(SUM(kid='bench-current' AND state='CURRENT'),0)=1 FROM auth_signing_key_metadata;" | sql)" != 1 ]; then
  echo "Signing metadata is neither the bench key nor the saved original; no change made." >&2; exit 1
fi
{ printf "SET SESSION time_zone='+00:00'; START TRANSACTION; DELETE FROM auth_signing_key_metadata;\n"; cat "$backup"; printf 'COMMIT;\n'; } | sql
printf '%s\n' "$snapshot" | sql > "$current"
cmp "$current" "$backup" >/dev/null
echo "Original public signing metadata restored; fixture rows, outputs and key files retained."
