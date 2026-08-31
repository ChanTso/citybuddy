#!/usr/bin/env bash
# Throughput ladder for one agent path.
#
# k6 joins the namespace the agent binds loopback in, so a measured request is a loopback write
# with no proxy hop and no Docker Desktop host-to-VM hop. Generator CPU is sampled for the life of
# the run: a percentile taken while the generator is saturated describes the generator.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  printf 'Usage: %s {greeting|chat|retrieval|prepare}\n' "${0##*/}" >&2
  exit 2
fi
PATH_NAME="$1"
case "$PATH_NAME" in
  greeting) EXPECTED_TOOL_PROFILE=none ;;
  chat) EXPECTED_TOOL_PROFILE=read ;;
  retrieval | prepare) EXPECTED_TOOL_PROFILE=all ;;
  *)
    printf "Unknown agent path '%s'; expected one of: greeting chat retrieval prepare.\n" \
      "$PATH_NAME" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$repo_root"
out="$repo_root/bench/results"
live_setup_environment="$repo_root/bench/.run/agent_setup_environment.json"
# shellcheck source=bench/agent/setup_environment_gate.sh
source "$repo_root/bench/agent/setup_environment_gate.sh"

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
correlation_boundary="$RUN_ID-$PATH_NAME-"
correlation_boundary_hex="$(printf '%s' "$correlation_boundary" | od -An -v -tx1 | tr -d ' \n')"

summary_path="$out/agent_${LABEL}_summary.json"
points_path="$out/agent_${LABEL}_points.json"
console_path="$out/agent_${LABEL}_console.txt"
cpu_path="$out/agent_${LABEL}_cpu.txt"
cpu_errors_path="$out/agent_${LABEL}_cpu_errors.txt"
cpu_by_step_path="$out/agent_${LABEL}_cpu_by_step.txt"
mysql_path="$out/agent_${LABEL}_mysql.txt"
steps_path="$out/agent_${LABEL}_steps.txt"
workload_contract_path="$out/agent_${LABEL}_workload_contract.tsv"
setup_environment_path="$out/agent_${LABEL}_setup_environment.json"
target_paths=(
  "$summary_path"
  "$summary_path.tmp"
  "$points_path"
  "$console_path"
  "$cpu_path"
  "$cpu_errors_path"
  "$cpu_errors_path.tmp"
  "$cpu_by_step_path"
  "$mysql_path"
  "$steps_path"
  "$workload_contract_path"
  "$setup_environment_path"
  "$setup_environment_path.tmp"
)
for target_path in "${target_paths[@]}"; do
  if [ -e "$target_path" ]; then
    echo "Refusing to overwrite existing agent benchmark output: $target_path" >&2
    exit 1
  fi
done
mkdir -p "$out"

cp "$live_setup_environment" "$setup_environment_path.tmp"
mv "$setup_environment_path.tmp" "$setup_environment_path"
verify_agent_setup_environment "$setup_environment_path" "before ladder"
citybuddy_commit="$(agent_setup_json_string "$setup_environment_path" '.citybuddyCommit')"
setup_nonce="$(agent_setup_json_string "$setup_environment_path" '.setupNonce')"

echo "== agent ladder '$LABEL' (commit=$citybuddy_commit setup=$setup_nonce path=$PATH_NAME rates=$RATES step=${STEP_SECONDS}s) =="
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

k6_container_id="$(docker run --detach --name citybuddy-bench-k6 \
  --label "citybuddy.bench.setup-nonce=$setup_nonce" \
  --label "citybuddy.bench.citybuddy-commit=$citybuddy_commit" \
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
      --tag "citybuddy_commit=$citybuddy_commit" \
      --tag "setup_nonce=$setup_nonce" \
      /scripts/agent_paths.js)"
if [ "$(docker inspect --format '{{.Id}}' "$k6_container_id")" != "$k6_container_id" ] \
  || [ "$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.setup-nonce" }}' "$k6_container_id")" \
    != "$setup_nonce" ] \
  || [ "$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.citybuddy-commit" }}' "$k6_container_id")" \
    != "$citybuddy_commit" ]; then
  echo "The k6 container does not belong to the saved setup environment." >&2
  exit 1
fi

run_started="$(date -u +%s)"
printf 'citybuddy_commit=%s\n' "$citybuddy_commit" > "$cpu_path"
: > "$cpu_errors_path"
sampled_names=(citybuddy-bench-k6 citybuddy-bench-agent citybuddy-bench-model
               citybuddy-bench-commerce citybuddy-mysql-1 citybuddy-bench-elasticsearch)
sampled_targets=(
  "$k6_container_id"
  "$(jq -er '.containers["citybuddy-bench-agent"].id' "$setup_environment_path")"
  "$(jq -er '.containers["citybuddy-bench-model"].id' "$setup_environment_path")"
  "$(jq -er '.containers["citybuddy-bench-commerce"].id' "$setup_environment_path")"
  citybuddy-mysql-1
  "$(jq -er '.containers["citybuddy-bench-elasticsearch"].id' "$setup_environment_path")"
)
while [ "$(docker inspect -f '{{.State.Running}}' "$k6_container_id" 2>/dev/null)" = "true" ]; do
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' \
    "${sampled_targets[@]}" 2>>"$cpu_errors_path" \
    | sed "s/^/$(date -u +%H:%M:%S) /" >> "$cpu_path" || true
done

# Preserve the file even when it records no errors, so every declared target has run evidence.
{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  cat "$cpu_errors_path"
} > "$cpu_errors_path.tmp"
mv "$cpu_errors_path.tmp" "$cpu_errors_path"

{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  docker logs "$k6_container_id"
} > "$console_path" 2>&1

# A k6 iteration that throws does not by itself fail the run, and a k6 that dies during init
# leaves the sampling loop with nothing to sample, so both would otherwise produce a results file
# that reads clean. The exit code is the one signal that covers each, and it is checked here.
k6_status="$(docker inspect -f '{{.State.ExitCode}}' "$k6_container_id")"
if [ -f "$summary_path" ]; then
  uv run python - "$summary_path" "$citybuddy_commit" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
document = json.loads(path.read_text(encoding="utf-8"))
document["citybuddyCommit"] = sys.argv[2]
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(document, indent=4) + "\n", encoding="utf-8")
temporary.replace(path)
PY
fi
if [ "$k6_status" -ne 0 ]; then
  echo "k6 exited $k6_status; the results for '$LABEL' are not a measurement." >&2
  tail -30 "$console_path" >&2
  exit 1
fi
if [ ! -f "$summary_path" ]; then
  echo "k6 exited successfully without a summary; the results are incomplete." >&2
  exit 1
fi

# This SQL evidence checks only that the completed turns selected the declared workload route and
# started with empty history. It is neither a latency result nor a business-state grader.
{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  printf 'correlation_boundary=%s\n' "$correlation_boundary"
  printf 'path=%s\n' "$PATH_NAME"
  printf 'expected_tool_profile=%s\n' "$EXPECTED_TOOL_PROFILE"
  printf 'evidence_role=workload_contract_only_not_performance_or_business_grader\n'
  MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root \
    -D cs_db --batch -e "
WITH measured_turns AS (
  SELECT turn_id, state
  FROM support_turn
  WHERE LEFT(CAST(correlation_key AS BINARY), OCTET_LENGTH(UNHEX('$correlation_boundary_hex')))
    = UNHEX('$correlation_boundary_hex')
),
routing_events AS (
  SELECT event.turn_id,
         COUNT(*) AS event_count,
         MAX(JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.toolProfile'))) AS tool_profile,
         GROUP_CONCAT(
           DISTINCT JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.toolProfile'))
           ORDER BY JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.toolProfile'))
           SEPARATOR ','
         ) AS tool_profiles
  FROM support_event event
  JOIN measured_turns measured ON measured.turn_id = event.turn_id
  WHERE event.event_type = 'ROUTING_DECISION'
  GROUP BY event.turn_id
),
context_events AS (
  SELECT event.turn_id,
         COUNT(*) AS event_count,
         MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(
           event.payload_json, '$.loadedTurnCount'
         )) AS UNSIGNED)) AS loaded_turn_count,
         MAX(JSON_LENGTH(JSON_EXTRACT(
           event.payload_json, '$.includedTurnIds'
         ))) AS included_turn_count,
         GROUP_CONCAT(
           DISTINCT JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.loadedTurnCount'))
           ORDER BY JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.loadedTurnCount'))
           SEPARATOR ','
         ) AS loaded_turn_counts,
         GROUP_CONCAT(
           DISTINCT JSON_LENGTH(JSON_EXTRACT(event.payload_json, '$.includedTurnIds'))
           ORDER BY JSON_LENGTH(JSON_EXTRACT(event.payload_json, '$.includedTurnIds'))
           SEPARATOR ','
         ) AS included_turn_counts
  FROM support_event event
  JOIN measured_turns measured ON measured.turn_id = event.turn_id
  WHERE event.event_type = 'CONTEXT_WINDOW'
  GROUP BY event.turn_id
)
SELECT COUNT(*) AS boundary_turns,
       COALESCE(SUM(measured.state = 'COMPLETED'), 0) AS completed_turns,
       COALESCE(SUM(measured.state = 'FAILED'), 0) AS failed_turns,
       COALESCE(SUM(measured.state = 'PROCESSING'), 0) AS processing_turns,
       COALESCE(SUM(
         measured.state = 'COMPLETED'
         AND routing.event_count = 1
         AND routing.tool_profile = '$EXPECTED_TOOL_PROFILE'
       ), 0) AS matching_profile_turns,
       COALESCE(SUM(
         measured.state = 'COMPLETED'
         AND context_evidence.event_count = 1
         AND context_evidence.loaded_turn_count = 0
         AND context_evidence.included_turn_count = 0
       ), 0) AS empty_history_turns,
       COALESCE(SUM(routing.event_count), 0) AS routing_events,
       COALESCE(SUM(context_evidence.event_count), 0) AS context_events,
       COALESCE(GROUP_CONCAT(
         DISTINCT routing.tool_profiles ORDER BY routing.tool_profiles SEPARATOR ','
       ), 'missing') AS actual_tool_profiles,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.loaded_turn_counts
         ORDER BY context_evidence.loaded_turn_counts SEPARATOR ','
       ), 'missing') AS loaded_turn_counts,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.included_turn_counts
         ORDER BY context_evidence.included_turn_counts SEPARATOR ','
       ), 'missing') AS included_turn_counts
FROM measured_turns measured
LEFT JOIN routing_events routing ON routing.turn_id = measured.turn_id
LEFT JOIN context_events context_evidence ON context_evidence.turn_id = measured.turn_id;
"
} > "$workload_contract_path"

contract_row="$(tail -n 1 "$workload_contract_path")"
IFS=$'\t' read -r boundary_turns completed_turns failed_turns processing_turns \
  matching_profile_turns empty_history_turns routing_events context_events \
  actual_tool_profiles loaded_turn_counts included_turn_counts <<< "$contract_row"
if [[ ! "$completed_turns" =~ ^[0-9]+$ \
  || ! "$matching_profile_turns" =~ ^[0-9]+$ \
  || ! "$empty_history_turns" =~ ^[0-9]+$ \
  || "$completed_turns" -eq 0 \
  || "$matching_profile_turns" -ne "$completed_turns" \
  || "$empty_history_turns" -ne "$completed_turns" ]]; then
  printf 'contract_status=fail\n' >> "$workload_contract_path"
  echo "Agent workload contract failed for '$PATH_NAME'." >&2
  cat "$workload_contract_path" >&2
  exit 1
fi
printf 'contract_status=pass\n' >> "$workload_contract_path"

echo "-- peak CPU by container, over the whole ladder --"
for container in "${sampled_names[@]}"; do
  peak="$(awk -v name="$container" '$2 == name {gsub(/cpu=|%/, "", $3); print $3}' \
    "$cpu_path" | sort -n | tail -1)"
  printf '%-28s %s%%\n' "$container" "${peak:-no-sample}"
done

# A peak over the whole ladder is dominated by whichever step collapsed, so on its own it says
# nothing about what serving the load costs at a rate the system actually holds. Each step's own
# window is reported instead, derived from the schedule k6 was given.
{
  echo "citybuddy_commit=$citybuddy_commit"
  printf '%-8s %-19s %10s %10s %10s\n' step window agent_min agent_med agent_max
  index=0
  for rate in ${RATES//,/ }; do
    from=$((run_started + index * (STEP_SECONDS + GAP_SECONDS)))
    to=$((from + STEP_SECONDS))
    from_hms="$(date -u -r "$from" +%H:%M:%S)"
    to_hms="$(date -u -r "$to" +%H:%M:%S)"
    samples="$(awk -v from="$from_hms" -v to="$to_hms" \
      '$2 == "citybuddy-bench-agent" && $1 >= from && $1 <= to { gsub(/cpu=|%/, "", $3); print $3 }' \
      "$cpu_path" | sort -n)"
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
} | tee "$cpu_by_step_path"
{
  echo "citybuddy_commit=$citybuddy_commit"
  echo "connections opened during the run: $(( $(counter Connections) - connections_before ))"
  rejected_after="$(counter Connection_errors_max_connections)"
  echo "attempts rejected at max_connections: $(( rejected_after - rejected_before ))"
  echo "peak concurrent connections since FLUSH STATUS: $(counter Max_used_connections)"
  echo "max_connections limit: $(MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 \
    -P "$mysql_port" -u root --batch --skip-column-names \
    -e "SHOW VARIABLES LIKE 'max_connections'" | awk '{print $2}')"
} > "$mysql_path"
echo "-- MySQL connection pressure over the run --"
cat "$mysql_path"
echo "-- iterations k6 could not issue or could not finish --"
grep -E 'dropped_iterations|interrupted iterations' "$console_path" | tail -2
echo "-- per-step statistics --"
{
  echo "citybuddy_commit=$citybuddy_commit"
  uv run python bench/agent/analyze_agent_ladder.py \
    "$points_path" "$LABEL" "$STEP_SECONDS" "$RATES"
} | tee "$steps_path"
verify_agent_setup_environment "$setup_environment_path" "after ladder"
