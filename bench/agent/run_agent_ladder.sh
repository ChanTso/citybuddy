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
  chat) EXPECTED_TOOL_PROFILE="read" ;;
  retrieval | prepare) EXPECTED_TOOL_PROFILE=all ;;
  *)
    printf "Unknown agent path '%s'; expected one of: greeting chat retrieval prepare.\n" \
      "$PATH_NAME" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$repo_root"
out="${AGENT_RESULTS_DIR:-$repo_root/bench/results}"
run_dir="$repo_root/bench/.run"
live_setup_environment="$repo_root/bench/.run/agent_setup_environment.json"
K6_IMAGE_REFERENCE="grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec"
# shellcheck source=bench/agent/setup_environment_gate.sh
source "$repo_root/bench/agent/setup_environment_gate.sh"

# Output files are named by LABEL, not by path, so a control run against a changed setting does
# not overwrite the baseline it is meant to be compared with.
LABEL="${LABEL:-$PATH_NAME}"
RATES="${RATES:-10,20,40,80,160}"
STEP_SECONDS="${STEP_SECONDS:-20}"
GRACEFUL_STOP_SECONDS="${GRACEFUL_STOP_SECONDS:-45}"
GAP_SECONDS="${GAP_SECONDS:-$((GRACEFUL_STOP_SECONDS + 10))}"
RUN_ID="${RUN_ID:-$(date -u +%H%M%S)}"
POOL_BASE="${POOL_BASE:-0}"
if [[ ! "$LABEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "${#LABEL}" -gt 96 ]; then
  echo "LABEL must be 1-96 characters from [A-Za-z0-9._-] and start with an alphanumeric." >&2
  exit 2
fi
if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "${#RUN_ID}" -gt 96 ]; then
  echo "RUN_ID must be 1-96 characters from [A-Za-z0-9._-] and start with an alphanumeric." >&2
  exit 2
fi
if [[ ! "$RATES" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]] \
  || [[ ! "$STEP_SECONDS" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "$GRACEFUL_STOP_SECONDS" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "$GAP_SECONDS" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "$POOL_BASE" =~ ^[0-9]+$ ]]; then
  echo "Rates and numeric ladder configuration must be ASCII non-negative or positive integers as applicable." >&2
  exit 2
fi
if [ "$GAP_SECONDS" -lt "$((GRACEFUL_STOP_SECONDS + 10))" ]; then
  echo "GAP_SECONDS must be at least GRACEFUL_STOP_SECONDS plus 10." >&2
  exit 2
fi
correlation_boundary="$RUN_ID-$PATH_NAME-"
correlation_boundary_hex="$(printf '%s' "$correlation_boundary" | od -An -v -tx1 | tr -d ' \n')"

summary_name="agent_${LABEL}_summary.json"
console_name="agent_${LABEL}_console.txt"
cpu_name="agent_${LABEL}_cpu.txt"
cpu_errors_name="agent_${LABEL}_cpu_errors.txt"
mysql_name="agent_${LABEL}_mysql.txt"
steps_name="agent_${LABEL}_steps.txt"
workload_contract_name="agent_${LABEL}_workload_contract.tsv"
setup_environment_name="agent_${LABEL}_setup_environment.json"
result_names=(
  "$summary_name"
  "$console_name"
  "$cpu_name"
  "$cpu_errors_name"
  "$mysql_name"
  "$steps_name"
  "$workload_contract_name"
)
for name in "${result_names[@]}" "$setup_environment_name"; do
  target_path="$out/$name"
  if [ -e "$target_path" ]; then
    echo "Refusing to overwrite existing agent benchmark output: $target_path" >&2
    exit 1
  fi
done
mkdir -p "$run_dir"
staging_dir="$(mktemp -d "$run_dir/agent-ladder.XXXXXX")"
k6_owner="${staging_dir##*/}"
result_published=false
summary_path="$staging_dir/$summary_name"
console_path="$staging_dir/$console_name"
cpu_path="$staging_dir/$cpu_name"
cpu_errors_path="$staging_dir/$cpu_errors_name"
mysql_path="$staging_dir/$mysql_name"
steps_path="$staging_dir/$steps_name"
workload_contract_path="$staging_dir/$workload_contract_name"
setup_environment_path="$staging_dir/$setup_environment_name"
k6_container_id=""
resolve_owned_k6_container() {
  local candidate_id candidate_nonce candidate_commit candidate_owner
  [ -z "$k6_container_id" ] || return 0
  if [ -z "${setup_nonce:-}" ] || [ -z "${citybuddy_commit:-}" ]; then
    return 0
  fi
  candidate_id="$(docker inspect --format '{{.Id}}' citybuddy-bench-k6 2>/dev/null || true)"
  candidate_nonce="$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.setup-nonce" }}' \
    citybuddy-bench-k6 2>/dev/null || true)"
  candidate_commit="$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.citybuddy-commit" }}' \
    citybuddy-bench-k6 2>/dev/null || true)"
  candidate_owner="$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.ladder-owner" }}' \
    citybuddy-bench-k6 2>/dev/null || true)"
  if [ -n "$candidate_id" ] && [ "$candidate_nonce" = "$setup_nonce" ] \
    && [ "$candidate_commit" = "$citybuddy_commit" ] \
    && [ "$candidate_owner" = "$k6_owner" ]; then
    k6_container_id="$candidate_id"
  fi
}
cleanup_k6_container() {
  local container_id
  resolve_owned_k6_container
  container_id="$k6_container_id"
  if [ -z "$container_id" ]; then
    return 0
  fi
  if docker rm -f "$container_id" >/dev/null 2>&1; then
    k6_container_id=""
    return 0
  fi
  return 1
}
capture_k6_diagnostics() {
  local status="$1"
  resolve_owned_k6_container
  [ -n "$k6_container_id" ] || return 0
  {
    printf 'runner_exit_status=%s\n' "$status"
    docker inspect --format \
      'container_id={{.Id}} started_at={{.State.StartedAt}} finished_at={{.State.FinishedAt}} running={{.State.Running}} exit_code={{.State.ExitCode}}' \
      "$k6_container_id" 2>/dev/null || true
    docker logs "$k6_container_id" 2>&1 || true
  } >> "$console_path"
}
report_unpublished_result() {
  local status=$?
  trap - EXIT HUP INT TERM
  capture_k6_diagnostics "$status"
  if ! cleanup_k6_container; then
    echo "Could not stop ladder k6 container $k6_container_id." >&2
  fi
  if [ "$result_published" != true ]; then
    echo "Unpublished ladder diagnostics remain in $staging_dir" >&2
  fi
  exit "$status"
}
trap report_unpublished_result EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

cp "$live_setup_environment" "$setup_environment_path"
verify_agent_setup_environment "$setup_environment_path" "before ladder"
citybuddy_commit="$(agent_setup_json_string "$setup_environment_path" '.citybuddyCommit')"
setup_nonce="$(agent_setup_json_string "$setup_environment_path" '.setupNonce')"
requested_workers="$(jq -er '.configuration.requestedWorkers | tostring' \
  "$setup_environment_path")"
http_client_layout="$(agent_setup_json_string \
  "$setup_environment_path" '.configuration.httpClientLayout')"
mysql_max_connections="$(jq -er '.mysql.maxConnections | tostring' \
  "$setup_environment_path")"

echo "== agent ladder '$LABEL' (commit=$citybuddy_commit setup=$setup_nonce path=$PATH_NAME rates=$RATES step=${STEP_SECONDS}s workers=$requested_workers layout=$http_client_layout) =="
if docker inspect citybuddy-bench-k6 >/dev/null 2>&1; then
  echo "Refusing to replace existing container citybuddy-bench-k6." >&2
  exit 1
fi

# The agent opens a fresh MySQL connection for every persistence call rather than pooling, so
# connection churn is a first-class cost of a turn and the limit is a candidate ceiling. Counters
# are retained as raw before/after SHOW output.
root_pw="$(grep -E '^MYSQL_BOOTSTRAP_PASSWORD=' .env | head -1 | cut -d= -f2-)"
mysql_container_id="$(agent_setup_json_string "$setup_environment_path" '.mysql.container.id')"
mysql_port="$(docker port "$mysql_container_id" 3306/tcp | cut -d: -f2)"
mysql_snapshot() {
  MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root \
    --batch -e \
    "SHOW GLOBAL STATUS WHERE Variable_name IN
       ('Connections', 'Connection_errors_max_connections', 'Max_used_connections');
     SHOW GLOBAL VARIABLES LIKE 'max_connections';"
}
mysql_snapshot_value() {
  local snapshot="$1" name="$2"
  awk -v snapshot="$snapshot" -v name="$name" '
    $0 == "snapshot=" snapshot { active=1; next }
    /^snapshot=/ { active=0 }
    active && $1 == name { print $2; exit }
  ' "$mysql_path"
}
# FLUSH STATUS first so Max_used_connections describes this run rather than every run since the
# server started; the other two are read as a delta, which holds whether or not the flush resets
# them in this server version.
MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root \
  -e "FLUSH STATUS" >/dev/null
{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  printf 'sut_commit=%s\n' "$citybuddy_commit"
  printf 'benchmark_harness_commit=%s\n' "$citybuddy_commit"
  printf 'snapshot=before\n'
  mysql_snapshot
} > "$mysql_path"
rejected_before="$(mysql_snapshot_value before Connection_errors_max_connections)"
max_connections_before="$(mysql_snapshot_value before max_connections)"
if [[ ! "$rejected_before" =~ ^[0-9]+$ ]] \
  || [ "$max_connections_before" != "$mysql_max_connections" ]; then
  echo "MySQL before-run boundary is malformed or changed." >&2
  exit 1
fi

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

k6_version="$(docker run --rm --entrypoint k6 "$K6_IMAGE_REFERENCE" version)"
k6_image_id="$(docker image inspect --format '{{.Id}}' "$K6_IMAGE_REFERENCE")"
if [[ ! "$k6_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || [ -z "$k6_version" ]; then
  echo "Cannot resolve the pinned k6 image boundary." >&2
  exit 1
fi
k6_container_id="$(docker create --name citybuddy-bench-k6 \
  --label "citybuddy.bench.setup-nonce=$setup_nonce" \
  --label "citybuddy.bench.citybuddy-commit=$citybuddy_commit" \
  --label "citybuddy.bench.ladder-owner=$k6_owner" \
  --network "container:citybuddy-bench-net" \
  --volume "$repo_root/bench/agent/k6:/scripts:ro" \
  --volume "$repo_root/bench/.run:/run-data:ro" \
  --volume "$staging_dir:/out" \
  --env POOL_FILE=/run-data/agent_pool.json \
  --env PATH_NAME="$PATH_NAME" --env RUN_ID="$RUN_ID" --env POOL_BASE="$POOL_BASE" \
  --env RATES="$RATES" --env STEP_SECONDS="$STEP_SECONDS" \
  --env GRACEFUL_STOP_SECONDS="$GRACEFUL_STOP_SECONDS" --env GAP_SECONDS="$GAP_SECONDS" \
  --entrypoint k6 "$k6_image_id" \
  run --summary-export="/out/$summary_name" \
      --tag "citybuddy_commit=$citybuddy_commit" \
      --tag "sut_commit=$citybuddy_commit" \
      --tag "benchmark_harness_commit=$citybuddy_commit" \
      --tag "setup_nonce=$setup_nonce" \
      /scripts/agent_paths.js)"
if [ "$(docker inspect --format '{{.Id}}' "$k6_container_id")" != "$k6_container_id" ] \
  || [ "$(docker inspect --format '{{.Image}}' "$k6_container_id")" != "$k6_image_id" ] \
  || [ "$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.setup-nonce" }}' "$k6_container_id")" \
    != "$setup_nonce" ] \
  || [ "$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.citybuddy-commit" }}' "$k6_container_id")" \
    != "$citybuddy_commit" ] \
  || [ "$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.ladder-owner" }}' "$k6_container_id")" \
    != "$k6_owner" ]; then
  echo "The k6 container does not belong to the saved setup environment." >&2
  exit 1
fi
docker start "$k6_container_id" >/dev/null
run_started_at="$(docker inspect --format '{{.State.StartedAt}}' "$k6_container_id")"
if [ -z "$run_started_at" ] || [ "$run_started_at" = "0001-01-01T00:00:00Z" ]; then
  echo "The k6 container has no runtime start timestamp." >&2
  exit 1
fi

{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  printf 'sut_commit=%s\n' "$citybuddy_commit"
  printf 'benchmark_harness_commit=%s\n' "$citybuddy_commit"
  printf 'columns=epoch_utc\ttimestamp_utc\tcontainer\tcpu_percent\tmemory_usage\n'
} > "$cpu_path"
{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  printf 'sut_commit=%s\n' "$citybuddy_commit"
  printf 'benchmark_harness_commit=%s\n' "$citybuddy_commit"
  printf 'columns=docker_stats_stderr\n'
} > "$cpu_errors_path"
cpu_errors_header_bytes="$(wc -c < "$cpu_errors_path" | tr -d ' ')"
sampled_targets=(
  "$k6_container_id"
  "$(jq -er '.containers["citybuddy-bench-agent"].id' "$setup_environment_path")"
  "$(jq -er '.containers["citybuddy-bench-model"].id' "$setup_environment_path")"
  "$(jq -er '.containers["citybuddy-bench-auth"].id' "$setup_environment_path")"
  "$(jq -er '.containers["citybuddy-bench-commerce"].id' "$setup_environment_path")"
  "$(jq -er '.containers["citybuddy-bench-elasticsearch"].id' "$setup_environment_path")"
  "$mysql_container_id"
)
while [ "$(docker inspect -f '{{.State.Running}}' "$k6_container_id" 2>/dev/null)" = "true" ]; do
  sample_epoch="$(date -u +%s)"
  sample_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  docker stats --no-stream --format '{{.Name}}{{"\t"}}{{.CPUPerc}}{{"\t"}}{{.MemUsage}}' \
    "${sampled_targets[@]}" 2>>"$cpu_errors_path" \
    | sed "s/^/${sample_epoch}\t${sample_utc}\t/" >> "$cpu_path" || true
done
run_completed_at="$(docker inspect --format '{{.State.FinishedAt}}' "$k6_container_id")"
if [ -z "$run_completed_at" ] || [ "$run_completed_at" = "0001-01-01T00:00:00Z" ]; then
  echo "The completed k6 container has no runtime finish timestamp." >&2
  exit 1
fi
if [ "$(wc -c < "$cpu_errors_path" | tr -d ' ')" -ne "$cpu_errors_header_bytes" ]; then
  echo "Docker CPU sampling reported an error; the ladder is operationally invalid." >&2
  cat "$cpu_errors_path" >&2
  exit 1
fi

{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  printf 'sut_commit=%s\n' "$citybuddy_commit"
  printf 'benchmark_harness_commit=%s\n' "$citybuddy_commit"
  printf 'k6_image_reference=%s\n' "$K6_IMAGE_REFERENCE"
  printf 'k6_image_id=%s\n' "$k6_image_id"
  printf 'k6_version=%s\n' "$k6_version"
  printf 'container_started_at_utc=%s\n' "$run_started_at"
  printf 'container_finished_at_utc=%s\n' "$run_completed_at"
  docker logs "$k6_container_id"
} > "$console_path" 2>&1

# A k6 iteration that throws does not by itself fail the run, and a k6 that dies during init
# leaves the sampling loop with nothing to sample, so both would otherwise produce a results file
# that reads clean. The exit code is the one signal that covers each, and it is checked here.
k6_status="$(docker inspect -f '{{.State.ExitCode}}' "$k6_container_id")"
if ! cleanup_k6_container; then
  echo "Could not remove completed ladder k6 container $k6_container_id." >&2
  exit 1
fi
if [ -f "$summary_path" ]; then
  uv run python - "$summary_path" "$citybuddy_commit" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
document = json.loads(path.read_text(encoding="utf-8"))
document["citybuddyCommit"] = sys.argv[2]
document["sutCommit"] = sys.argv[2]
document["benchmarkHarnessCommit"] = sys.argv[2]
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(document, indent=4) + "\n", encoding="utf-8")
temporary.replace(path)
PY
fi
if [[ ! "$k6_status" =~ ^[0-9]+$ ]] || [ "$k6_status" -ne 0 ]; then
  echo "k6 exited ${k6_status:-unknown}; the results for '$LABEL' are not a measurement." >&2
  tail -30 "$console_path" >&2
  exit 1
fi
if [ ! -f "$summary_path" ]; then
  echo "k6 exited successfully without a summary; the results are incomplete." >&2
  exit 1
fi

# Capture the raw connection boundary immediately when load generation finishes. Route/history
# SQL below must not inflate the measured rejection counter.
{
  printf 'snapshot=after\n'
  mysql_snapshot
} >> "$mysql_path"
rejected_after="$(mysql_snapshot_value after Connection_errors_max_connections)"
max_connections_after="$(mysql_snapshot_value after max_connections)"
if [[ ! "$rejected_after" =~ ^[0-9]+$ ]] \
  || [ "$max_connections_after" != "$max_connections_before" ]; then
  echo "MySQL after-run boundary is malformed or changed." >&2
  exit 1
fi
if [ "$((rejected_after - rejected_before))" -ne 0 ]; then
  echo "MySQL rejected connections during the measured window." >&2
  exit 1
fi
echo "-- MySQL connection pressure over the run --"
cat "$mysql_path"

# Retain the authoritative route/history SQL output without reconstructing it in the harness.
requested_rates_sql=""
for rate in ${RATES//,/ }; do
  [ -z "$requested_rates_sql" ] \
    || requested_rates_sql="$requested_rates_sql UNION ALL "
  requested_rates_sql="${requested_rates_sql}SELECT ${rate} AS rate"
done
{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  printf 'sut_commit=%s\n' "$citybuddy_commit"
  printf 'benchmark_harness_commit=%s\n' "$citybuddy_commit"
  printf 'correlation_boundary=%s\n' "$correlation_boundary"
  printf 'path=%s\n' "$PATH_NAME"
  printf 'expected_tool_profile=%s\n' "$EXPECTED_TOOL_PROFILE"
  printf 'evidence_role=workload_contract_only_not_performance_or_business_grader\n'
  MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root \
    -D cs_db --batch -e "
WITH requested_rates AS (
  $requested_rates_sql
),
measured_turns AS (
  SELECT turn_id,
         state,
         CAST(SUBSTRING(
           SUBSTRING_INDEX(
             SUBSTRING(CAST(correlation_key AS CHAR), CHAR_LENGTH('$correlation_boundary') + 1),
             '-', 1
           ),
           6
         ) AS UNSIGNED) AS rate
  FROM support_turn
  WHERE LEFT(CAST(correlation_key AS BINARY), OCTET_LENGTH(UNHEX('$correlation_boundary_hex')))
    = UNHEX('$correlation_boundary_hex')
),
routing_events AS (
  SELECT event.turn_id,
         COUNT(*) AS event_count,
         MAX(JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.toolProfile'))) AS tool_profile,
         SUM(NOT (
           JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.toolProfile'))
           <=> '$EXPECTED_TOOL_PROFILE'
         )) AS unexpected_event_count,
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
         SUM(
           NOT (CAST(JSON_UNQUOTE(JSON_EXTRACT(
             event.payload_json, '$.loadedTurnCount'
           )) AS UNSIGNED) <=> 0)
           OR NOT (JSON_LENGTH(JSON_EXTRACT(
             event.payload_json, '$.includedTurnIds'
           )) <=> 0)
         ) AS nonempty_event_count,
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
SELECT requested.rate AS rate_per_second,
       COUNT(measured.turn_id) AS boundary_turns,
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
       COALESCE(SUM(routing.unexpected_event_count), 0) AS unexpected_routing_events,
       COALESCE(SUM(context_evidence.nonempty_event_count), 0) AS nonempty_context_events,
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
FROM requested_rates requested
LEFT JOIN measured_turns measured ON measured.rate = requested.rate
LEFT JOIN routing_events routing ON routing.turn_id = measured.turn_id
LEFT JOIN context_events context_evidence ON context_evidence.turn_id = measured.turn_id
GROUP BY requested.rate
ORDER BY requested.rate;
"
} > "$workload_contract_path"

echo "-- iterations k6 could not issue or could not finish --"
grep -E 'dropped_iterations|interrupted iterations' "$console_path" | tail -2 || true
echo "-- per-step statistics --"
{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  printf 'sut_commit=%s\n' "$citybuddy_commit"
  printf 'benchmark_harness_commit=%s\n' "$citybuddy_commit"
  uv run python bench/agent/analyze_agent_ladder.py \
    --summary "$summary_path" \
    --label "$LABEL" \
    --rates "$RATES" \
    --step-seconds "$STEP_SECONDS"
} | tee "$steps_path"
publish_agent_results "$setup_environment_path" "after ladder" "$staging_dir" "$out" \
  "$setup_environment_name" "${result_names[@]}"
result_published=true
trap - EXIT HUP INT TERM
