#!/usr/bin/env bash
# Throughput ladder for one agent path.
#
# k6 joins the namespace the agent binds loopback in, so a measured request is a loopback write
# with no proxy hop and no Docker Desktop host-to-VM hop. Generator CPU is sampled for the life of
# the run: a percentile taken while the generator is saturated describes the generator.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$repo_root"
out="$repo_root/bench/results"; mkdir -p "$out"

PATH_NAME="$1"                                   # chat | retrieval | prepare
# Output files are named by LABEL, not by path, so a control run against a changed setting does
# not overwrite the baseline it is meant to be compared with.
LABEL="${LABEL:-$PATH_NAME}"
RATES="${RATES:-10,20,40,80,160}"
STEP_SECONDS="${STEP_SECONDS:-20}"
RUN_ID="${RUN_ID:-$(date -u +%H%M%S)}"
POOL_BASE="${POOL_BASE:-0}"

echo "== agent ladder '$LABEL' (path=$PATH_NAME rates=$RATES step=${STEP_SECONDS}s) =="
docker rm -f citybuddy-bench-k6 >/dev/null 2>&1 || true

# The agent opens a fresh MySQL connection for every persistence call rather than pooling, so
# connection churn is a first-class cost of a turn and the limit is a candidate ceiling. Counters
# are read either side of the run and reported as a delta.
root_pw="$(grep -E '^MYSQL_BOOTSTRAP_PASSWORD=' .env | head -1 | cut -d= -f2-)"
mysql_port="$(docker port citybuddy-mysql-1 3306/tcp | cut -d: -f2)"
counter() {
  MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root \
    --batch --skip-column-names -e \
    "SHOW GLOBAL STATUS WHERE Variable_name = '$1'" | awk '{print $2}'
}
# FLUSH STATUS first so Max_used_connections describes this run rather than every run since the
# server started; the other two are read as a delta, which holds whether or not the flush resets
# them in this server version.
MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root \
  -e "FLUSH STATUS" >/dev/null 2>&1 || true
connections_before="$(counter Connections)"
rejected_before="$(counter Connection_errors_max_connections)"

# Preparing against an order that already carries an outstanding prepared action answers with a
# clarification rather than preparing again, which is a different and much cheaper path. A second
# prepare ladder over the same fixture would therefore measure the wrong thing while still
# reporting a clean run, so the fixture is checked here instead of trusted.
if [ "$PATH_NAME" = "prepare" ]; then
  outstanding="$(MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" \
    -u root -D commerce_db --batch --skip-column-names -e \
    "SELECT COUNT(*) FROM pending_action
      WHERE user_subject LIKE 'bench-user-%' AND state = 'PREPARED'")"
  if [ "$outstanding" -ne 0 ]; then
    echo "The fixture already holds $outstanding prepared actions, so this ladder would measure" >&2
    echo "the clarification path. Rerun ./bench/agent/setup_agent_bench.sh first." >&2
    exit 1
  fi
fi

docker run --detach --name citybuddy-bench-k6 \
  --network "container:citybuddy-bench-net" \
  --volume "$repo_root/bench/agent/k6:/scripts:ro" \
  --volume "$repo_root/bench/.run:/run-data:ro" \
  --volume "$out:/out" \
  --env POOL_FILE=/run-data/agent_pool.json \
  --env PATH_NAME="$PATH_NAME" --env RUN_ID="$RUN_ID" --env POOL_BASE="$POOL_BASE" \
  --env RATES="$RATES" --env STEP_SECONDS="$STEP_SECONDS" \
  --entrypoint k6 grafana/k6:latest \
  run --summary-export="/out/agent_${LABEL}_summary.json" \
      --out "json=/out/agent_${LABEL}_points.json" \
      /scripts/agent_paths.js >/dev/null

: > "$out/agent_${LABEL}_cpu.txt"
sampled=(citybuddy-bench-k6 citybuddy-bench-agent citybuddy-bench-model citybuddy-bench-commerce
         citybuddy-mysql-1 citybuddy-elasticsearch-1)
while [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-k6 2>/dev/null)" = "true" ]; do
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' \
    "${sampled[@]}" 2>>"$out/agent_${LABEL}_cpu_errors.txt" \
    | sed "s/^/$(date -u +%H:%M:%S) /" >> "$out/agent_${LABEL}_cpu.txt" || true
done

# Kept only when docker stats actually complained; an empty file is noise in the evidence set.
[ -s "$out/agent_${LABEL}_cpu_errors.txt" ] || rm -f "$out/agent_${LABEL}_cpu_errors.txt"

docker logs citybuddy-bench-k6 > "$out/agent_${LABEL}_console.txt" 2>&1
echo "-- peak CPU by container --"
for container in "${sampled[@]}"; do
  peak="$(awk -v name="$container" '$2 == name {gsub(/cpu=|%/, "", $3); print $3}' \
    "$out/agent_${LABEL}_cpu.txt" | sort -n | tail -1)"
  printf '%-28s %s%%\n' "$container" "${peak:-no-sample}"
done
{
  echo "connections opened during the run: $(( $(counter Connections) - connections_before ))"
  rejected_after="$(counter Connection_errors_max_connections)"
  echo "attempts rejected at max_connections: $(( rejected_after - rejected_before ))"
  echo "peak concurrent connections since FLUSH STATUS: $(counter Max_used_connections)"
  echo "max_connections limit: $(MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 \
    -P "$mysql_port" -u root --batch --skip-column-names \
    -e "SHOW VARIABLES LIKE 'max_connections'" | awk '{print $2}')"
} > "$out/agent_${LABEL}_mysql.txt"
echo "-- MySQL connection pressure over the run --"
cat "$out/agent_${LABEL}_mysql.txt"
echo "-- k6 summary tail --"
tail -25 "$out/agent_${LABEL}_console.txt"
