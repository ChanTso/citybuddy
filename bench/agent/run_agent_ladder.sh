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
# Must match the k6 script's defaults; both sides need the same schedule for the per-step CPU
# windows below to line up with the steps they claim to describe.
GRACEFUL_STOP_SECONDS="${GRACEFUL_STOP_SECONDS:-45}"
GAP_SECONDS="${GAP_SECONDS:-$((GRACEFUL_STOP_SECONDS + 10))}"
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
  --env GRACEFUL_STOP_SECONDS="$GRACEFUL_STOP_SECONDS" --env GAP_SECONDS="$GAP_SECONDS" \
  --entrypoint k6 grafana/k6:latest \
  run --summary-export="/out/agent_${LABEL}_summary.json" \
      --out "json=/out/agent_${LABEL}_points.json" \
      /scripts/agent_paths.js >/dev/null

run_started="$(date -u +%s)"
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

# A k6 iteration that throws does not by itself fail the run, and a k6 that dies during init
# leaves the sampling loop with nothing to sample, so both would otherwise produce a results file
# that reads clean. The exit code is the one signal that covers each, and it is checked here.
k6_status="$(docker inspect -f '{{.State.ExitCode}}' citybuddy-bench-k6)"
if [ "$k6_status" -ne 0 ]; then
  echo "k6 exited $k6_status; the results for '$LABEL' are not a measurement." >&2
  tail -30 "$out/agent_${LABEL}_console.txt" >&2
  exit 1
fi

echo "-- peak CPU by container, over the whole ladder --"
for container in "${sampled[@]}"; do
  peak="$(awk -v name="$container" '$2 == name {gsub(/cpu=|%/, "", $3); print $3}' \
    "$out/agent_${LABEL}_cpu.txt" | sort -n | tail -1)"
  printf '%-28s %s%%\n' "$container" "${peak:-no-sample}"
done

# A peak over the whole ladder is dominated by whichever step collapsed, so on its own it says
# nothing about what serving the load costs at a rate the system actually holds. Each step's own
# window is reported instead, derived from the schedule k6 was given.
{
  printf '%-8s %-19s %10s %10s %10s\n' step window agent_min agent_med agent_max
  index=0
  for rate in ${RATES//,/ }; do
    from=$((run_started + index * (STEP_SECONDS + GAP_SECONDS)))
    to=$((from + STEP_SECONDS))
    from_hms="$(date -u -r "$from" +%H:%M:%S)"
    to_hms="$(date -u -r "$to" +%H:%M:%S)"
    samples="$(awk -v from="$from_hms" -v to="$to_hms" \
      '$2 == "citybuddy-bench-agent" && $1 >= from && $1 <= to { gsub(/cpu=|%/, "", $3); print $3 }' \
      "$out/agent_${LABEL}_cpu.txt" | sort -n)"
    if [ -z "$samples" ]; then
      printf '%-8s %-19s %10s\n' "$rate" "$from_hms-$to_hms" "no-sample"
    else
      printf '%-8s %-19s %9.0f%% %9.0f%% %9.0f%%\n' "$rate" "$from_hms-$to_hms" \
        "$(printf '%s\n' "$samples" | head -1)" \
        "$(printf '%s\n' "$samples" | awk '{v[n++]=$1} END {print v[int(n/2)]}')" \
        "$(printf '%s\n' "$samples" | tail -1)"
    fi
    index=$((index + 1))
  done
} | tee "$out/agent_${LABEL}_cpu_by_step.txt"
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
echo "-- iterations k6 could not issue or could not finish --"
grep -E 'dropped_iterations|interrupted iterations' "$out/agent_${LABEL}_console.txt" | tail -2
echo "-- per-step statistics --"
uv run python bench/agent/analyze_agent_ladder.py \
  "$out/agent_${LABEL}_points.json" "$LABEL" "$STEP_SECONDS" "$RATES" \
  | tee "$out/agent_${LABEL}_steps.txt"
