#!/usr/bin/env bash
# Runs one fixed-arrival-rate delivery/read workload over deterministic completed history.
set -euo pipefail

if [ "$#" -ne 4 ]; then
  printf 'Usage: %s {empty|one-short|max-count|high-pressure} RATE DURATION_SECONDS LABEL\n' \
    "${0##*/}" >&2
  exit 2
fi
HISTORY_CASE="$1"
RATE="$2"
DURATION_SECONDS="$3"
LABEL="$4"
case "$HISTORY_CASE" in
  empty | one-short | max-count | high-pressure) ;;
  *)
    printf "Unknown warm-history case '%s'; expected one of: empty one-short max-count high-pressure.\n" \
      "$HISTORY_CASE" >&2
    exit 2
    ;;
esac
if [[ ! "$RATE" =~ ^[1-9][0-9]*$ ]]; then
  echo "RATE must be a positive integer." >&2
  exit 2
fi
if [[ ! "$DURATION_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "DURATION_SECONDS must be a positive integer." >&2
  exit 2
fi
if [[ ! "$LABEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "${#LABEL}" -gt 64 ]; then
  echo "LABEL must be 1-64 characters from [A-Za-z0-9._-] and start with an alphanumeric." >&2
  exit 2
fi

nominal_requests=$((10#$RATE * 10#$DURATION_SECONDS))
target_session_count=$((nominal_requests + 20))
if [ "$nominal_requests" -lt 1 ] || [ "$target_session_count" -le "$nominal_requests" ]; then
  echo "RATE multiplied by DURATION_SECONDS is outside the supported integer range." >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
out="$repo_root/bench/results"
run_dir="$repo_root/bench/.run"
live_setup_environment="$run_dir/agent_setup_environment.json"
K6_IMAGE_REFERENCE="grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec"

prefix="agent_warm_history_${LABEL}"
summary_name="${prefix}_summary.json"
points_name="${prefix}_points.json"
console_name="${prefix}_console.txt"
contract_name="${prefix}_contract.tsv"
fixture_name="${prefix}_fixture.json"
result_name="${prefix}_result.json"
setup_environment_name="${prefix}_setup_environment.json"
result_names=(
  "$summary_name"
  "$points_name"
  "$console_name"
  "$contract_name"
  "$fixture_name"
  "$result_name"
)
for name in "${result_names[@]}" "$setup_environment_name"; do
  target_path="$out/$name"
  if [ -e "$target_path" ]; then
    echo "Refusing to overwrite existing warm-history benchmark output: $target_path" >&2
    exit 1
  fi
done

# shellcheck source=bench/agent/setup_environment_gate.sh
source "$repo_root/bench/agent/setup_environment_gate.sh"
mkdir -p "$run_dir"
staging_dir="$(mktemp -d "$run_dir/agent-warm-history.XXXXXX")"
result_published=false
k6_container_id=""
cleanup_k6_container() {
  local container_id="$k6_container_id"
  if [ -z "$container_id" ]; then
    return 0
  fi
  if docker rm -f "$container_id" >/dev/null 2>&1; then
    k6_container_id=""
    return 0
  fi
  return 1
}
report_unpublished_result() {
  local status=$?
  trap - EXIT HUP INT TERM
  if ! cleanup_k6_container; then
    echo "Could not stop warm-history k6 container $k6_container_id." >&2
  fi
  if [ "$result_published" != true ]; then
    echo "Unpublished warm-history diagnostics remain in $staging_dir" >&2
  fi
  exit "$status"
}
trap report_unpublished_result EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

summary_path="$staging_dir/$summary_name"
points_path="$staging_dir/$points_name"
console_path="$staging_dir/$console_name"
contract_path="$staging_dir/$contract_name"
fixture_path="$staging_dir/$fixture_name"
result_path="$staging_dir/$result_name"
setup_environment_path="$staging_dir/$setup_environment_name"

cp "$live_setup_environment" "$setup_environment_path"
verify_agent_setup_environment "$setup_environment_path" "before warm-history fixture"
citybuddy_commit="$(agent_setup_json_string "$setup_environment_path" '.citybuddyCommit')"
setup_nonce="$(agent_setup_json_string "$setup_environment_path" '.setupNonce')"
agent_image_id="$(jq -er \
  '.containers["citybuddy-bench-agent"].imageId
   | select(type == "string" and test("^sha256:[0-9a-f]{64}$"))' \
  "$setup_environment_path")"
agent_password="$(grep -E '^MYSQL_AGENT_APP_PASSWORD=' .env | head -1 | cut -d= -f2-)"
if [ -z "$agent_password" ]; then
  echo "MYSQL_AGENT_APP_PASSWORD is missing from .env." >&2
  exit 1
fi

echo "== building warm-history fixture '$HISTORY_CASE' for $target_session_count sessions =="
docker rm -f citybuddy-bench-warm-fixture >/dev/null 2>&1 || true
docker run --rm --name citybuddy-bench-warm-fixture \
  --label "citybuddy.bench.setup-nonce=$setup_nonce" \
  --label "citybuddy.bench.citybuddy-commit=$citybuddy_commit" \
  --network citybuddy_default \
  --volume "$repo_root/bench/agent/build_warm_history_fixture.py:/opt/build_warm_history_fixture.py:ro" \
  --volume "$run_dir:/run-data:ro" \
  --volume "$staging_dir:/out" \
  --entrypoint /opt/citybuddy/.venv/bin/python \
  "$agent_image_id" /opt/build_warm_history_fixture.py \
  --case "$HISTORY_CASE" \
  --sessions "$target_session_count" \
  --pool /run-data/agent_pool.json \
  --mysql-host mysql \
  --mysql-port 3306 \
  --mysql-password "$agent_password" \
  --citybuddy-commit "$citybuddy_commit" \
  --setup-nonce "$setup_nonce" \
  --out "/out/$fixture_name"

jq -e \
  --arg commit "$citybuddy_commit" \
  --arg nonce "$setup_nonce" \
  --arg case_name "$HISTORY_CASE" \
  --argjson sessions "$target_session_count" \
  '.formatVersion == "citybuddy-agent-warm-history-fixture-v1"
   and .citybuddyCommit == $commit
   and .setupNonce == $nonce
   and .case == $case_name
   and .targetSessionCount == $sessions' \
  "$fixture_path" >/dev/null

fixture_integer() {
  jq -er --arg field "$1" \
    '.history[$field] | select(type == "number" and . >= 0 and floor == .) | tostring' \
    "$fixture_path"
}
expected_persisted="$(fixture_integer persistedTurnCount)"
expected_candidate="$(fixture_integer candidateTurnCount)"
expected_loaded="$(fixture_integer loadedTurnCount)"
expected_included="$(fixture_integer includedTurnCount)"
expected_candidate_tokens="$(fixture_integer candidateTokens)"
expected_included_tokens="$(fixture_integer includedTokens)"
expected_token_budget="$(fixture_integer tokenBudget)"
expected_omitted="$(fixture_integer omittedLoadedTurnCount)"
expected_older="$(jq -er \
  '.history.olderTurnsAvailable
   | if . == true then "true" elif . == false then "false" else error("invalid boolean") end' \
  "$fixture_path")"
expected_estimator="$(agent_setup_json_string "$fixture_path" '.history.tokenEstimator')"
expected_watermark="$(agent_setup_json_string "$fixture_path" '.history.tokenWatermark')"

k6_version="$(docker run --rm --entrypoint k6 "$K6_IMAGE_REFERENCE" version)"
k6_image_id="$(docker image inspect --format '{{.Id}}' "$K6_IMAGE_REFERENCE")"
if [[ ! "$k6_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || [ -z "$k6_version" ]; then
  echo "Cannot resolve the pinned k6 image boundary." >&2
  exit 1
fi

run_id="$LABEL"
correlation_boundary="$run_id-warm-$HISTORY_CASE-"
correlation_boundary_hex="$(printf '%s' "$correlation_boundary" | od -An -v -tx1 | tr -d ' \n')"
docker rm -f citybuddy-bench-k6 >/dev/null 2>&1 || true
k6_container_id="$(docker run --detach --name citybuddy-bench-k6 \
  --label "citybuddy.bench.setup-nonce=$setup_nonce" \
  --label "citybuddy.bench.citybuddy-commit=$citybuddy_commit" \
  --network "container:citybuddy-bench-net" \
  --volume "$repo_root/bench/agent/k6:/scripts:ro" \
  --volume "$run_dir:/run-data:ro" \
  --volume "$staging_dir:/out" \
  --env POOL_FILE=/run-data/agent_pool.json \
  --env HISTORY_CASE="$HISTORY_CASE" \
  --env RATE="$RATE" \
  --env DURATION_SECONDS="$DURATION_SECONDS" \
  --env TARGET_SESSION_COUNT="$target_session_count" \
  --env RUN_ID="$run_id" \
  --entrypoint k6 "$k6_image_id" \
  run --summary-export="/out/$summary_name" \
      --out "json=/out/$points_name" \
      --tag "citybuddy_commit=$citybuddy_commit" \
      --tag "setup_nonce=$setup_nonce" \
      --tag "history_case=$HISTORY_CASE" \
      /scripts/warm_history.js)"
if [ "$(docker inspect --format '{{.Id}}' "$k6_container_id")" != "$k6_container_id" ] \
  || [ "$(docker inspect --format '{{.Image}}' "$k6_container_id")" != "$k6_image_id" ] \
  || [ "$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.setup-nonce" }}' "$k6_container_id")" \
    != "$setup_nonce" ] \
  || [ "$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.citybuddy-commit" }}' "$k6_container_id")" \
    != "$citybuddy_commit" ]; then
  echo "The warm-history k6 container does not belong to the saved setup environment." >&2
  exit 1
fi

run_started_at="$(docker inspect --format '{{.State.StartedAt}}' "$k6_container_id")"
k6_status="$(docker wait "$k6_container_id")"
run_completed_at="$(docker inspect --format '{{.State.FinishedAt}}' "$k6_container_id")"
{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  printf 'setup_nonce=%s\n' "$setup_nonce"
  printf 'case=%s\n' "$HISTORY_CASE"
  printf 'rate_per_second=%s\n' "$RATE"
  printf 'duration_seconds=%s\n' "$DURATION_SECONDS"
  printf 'target_session_count=%s\n' "$target_session_count"
  printf 'k6_image_reference=%s\n' "$K6_IMAGE_REFERENCE"
  printf 'k6_image_id=%s\n' "$k6_image_id"
  printf 'k6_version=%s\n' "$k6_version"
  docker logs "$k6_container_id"
} > "$console_path" 2>&1

if [ -f "$summary_path" ]; then
  uv run python - "$summary_path" "$citybuddy_commit" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
document = json.loads(path.read_text(encoding="utf-8"))
document["citybuddyCommit"] = sys.argv[2]
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
temporary.replace(path)
PY
fi
if [[ ! "$k6_status" =~ ^[0-9]+$ ]] || [ "$k6_status" -ne 0 ]; then
  echo "k6 exited ${k6_status:-unknown}; the warm-history output is not a measurement." >&2
  tail -30 "$console_path" >&2
  exit 1
fi
if [ ! -f "$summary_path" ]; then
  echo "k6 exited successfully without a summary; the warm-history output is incomplete." >&2
  exit 1
fi
cleanup_k6_container

mysql_container_id="$(agent_setup_json_string "$setup_environment_path" '.mysql.container.id')"
mysql_port="$(docker port "$mysql_container_id" 3306/tcp | cut -d: -f2)"
{
  printf 'citybuddy_commit=%s\n' "$citybuddy_commit"
  printf 'setup_nonce=%s\n' "$setup_nonce"
  printf 'case=%s\n' "$HISTORY_CASE"
  printf 'correlation_boundary=%s\n' "$correlation_boundary"
  printf 'expected_tool_profile=read\n'
  printf 'evidence_role=workload_and_context_contract_only_not_performance_attribution\n'
  MYSQL_PWD="$agent_password" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" \
    -u agent_app -D cs_db --batch -e "
WITH measured_turns AS (
  SELECT turn_id, conversation_id, session_id, user_subject, turn_sequence, state
  FROM support_turn
  WHERE LEFT(CAST(correlation_key AS BINARY), OCTET_LENGTH(UNHEX('$correlation_boundary_hex')))
    = UNHEX('$correlation_boundary_hex')
),
prior_counts AS (
  SELECT measured.turn_id, COUNT(prior_turn.turn_id) AS persisted_turn_count
  FROM measured_turns measured
  LEFT JOIN support_turn prior_turn
    ON prior_turn.conversation_id = measured.conversation_id
   AND prior_turn.session_id = measured.session_id
   AND prior_turn.user_subject = measured.user_subject
   AND prior_turn.turn_sequence < measured.turn_sequence
   AND prior_turn.state = 'COMPLETED'
  GROUP BY measured.turn_id
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
         MAX(JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.tokenEstimator'))) AS token_estimator,
         MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(
           event.payload_json, '$.tokenBudget'
         )) AS UNSIGNED)) AS token_budget,
         MAX(JSON_UNQUOTE(JSON_EXTRACT(event.payload_json, '$.tokenWatermark'))) AS token_watermark,
         MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(
           event.payload_json, '$.candidateTokens'
         )) AS UNSIGNED)) AS candidate_tokens,
         MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(
           event.payload_json, '$.includedTokens'
         )) AS UNSIGNED)) AS included_tokens,
         MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(
           event.payload_json, '$.loadedTurnCount'
         )) AS UNSIGNED)) AS loaded_turn_count,
         MAX(JSON_LENGTH(JSON_EXTRACT(
           event.payload_json, '$.includedTurnIds'
         ))) AS included_turn_count,
         MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(
           event.payload_json, '$.omittedLoadedTurnCount'
         )) AS UNSIGNED)) AS omitted_loaded_turn_count,
         MAX(JSON_UNQUOTE(JSON_EXTRACT(
           event.payload_json, '$.olderTurnsAvailable'
         ))) AS older_turns_available
  FROM support_event event
  JOIN measured_turns measured ON measured.turn_id = event.turn_id
  WHERE event.event_type = 'CONTEXT_WINDOW'
  GROUP BY event.turn_id
),
per_session AS (
  SELECT session_id, COUNT(*) AS request_count
  FROM measured_turns
  GROUP BY session_id
)
SELECT COUNT(*) AS boundary_turns,
       COALESCE(SUM(measured.state = 'COMPLETED'), 0) AS completed_turns,
       COALESCE(SUM(measured.state = 'FAILED'), 0) AS failed_turns,
       COALESCE(SUM(measured.state = 'PROCESSING'), 0) AS processing_turns,
       COUNT(DISTINCT measured.session_id) AS distinct_sessions,
       COALESCE(MAX(per_session.request_count), 0) AS max_requests_per_session,
       COALESCE(SUM(
         measured.state = 'COMPLETED'
         AND routing.event_count = 1
         AND routing.tool_profile = 'read'
       ), 0) AS matching_profile_turns,
       COALESCE(SUM(
         measured.state = 'COMPLETED'
         AND context_evidence.event_count = 1
         AND prior_counts.persisted_turn_count = $expected_persisted
         AND LEAST(prior_counts.persisted_turn_count, 17) = $expected_candidate
         AND context_evidence.loaded_turn_count = $expected_loaded
         AND context_evidence.included_turn_count = $expected_included
         AND context_evidence.older_turns_available = '$expected_older'
         AND context_evidence.token_estimator = '$expected_estimator'
         AND context_evidence.token_budget = $expected_token_budget
         AND context_evidence.token_watermark = '$expected_watermark'
         AND context_evidence.candidate_tokens = $expected_candidate_tokens
         AND context_evidence.included_tokens = $expected_included_tokens
         AND context_evidence.omitted_loaded_turn_count = $expected_omitted
       ), 0) AS matching_context_turns,
       COALESCE(SUM(routing.event_count), 0) AS routing_events,
       COALESCE(SUM(context_evidence.event_count), 0) AS context_events,
       COALESCE(GROUP_CONCAT(
         DISTINCT routing.tool_profiles ORDER BY routing.tool_profiles SEPARATOR ','
       ), 'missing') AS actual_tool_profiles,
       COALESCE(GROUP_CONCAT(
         DISTINCT prior_counts.persisted_turn_count
         ORDER BY prior_counts.persisted_turn_count SEPARATOR ','
       ), 'missing') AS persisted_turn_counts,
       COALESCE(GROUP_CONCAT(
         DISTINCT LEAST(prior_counts.persisted_turn_count, 17)
         ORDER BY LEAST(prior_counts.persisted_turn_count, 17) SEPARATOR ','
       ), 'missing') AS candidate_turn_counts,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.loaded_turn_count
         ORDER BY context_evidence.loaded_turn_count SEPARATOR ','
       ), 'missing') AS loaded_turn_counts,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.included_turn_count
         ORDER BY context_evidence.included_turn_count SEPARATOR ','
       ), 'missing') AS included_turn_counts,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.older_turns_available
         ORDER BY context_evidence.older_turns_available SEPARATOR ','
       ), 'missing') AS older_turn_values,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.token_watermark
         ORDER BY context_evidence.token_watermark SEPARATOR ','
       ), 'missing') AS token_watermarks,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.candidate_tokens
         ORDER BY context_evidence.candidate_tokens SEPARATOR ','
       ), 'missing') AS candidate_token_counts,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.included_tokens
         ORDER BY context_evidence.included_tokens SEPARATOR ','
       ), 'missing') AS included_token_counts,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.token_budget
         ORDER BY context_evidence.token_budget SEPARATOR ','
       ), 'missing') AS token_budgets,
       COALESCE(GROUP_CONCAT(
         DISTINCT context_evidence.omitted_loaded_turn_count
         ORDER BY context_evidence.omitted_loaded_turn_count SEPARATOR ','
       ), 'missing') AS omitted_loaded_turn_counts
FROM measured_turns measured
JOIN prior_counts ON prior_counts.turn_id = measured.turn_id
LEFT JOIN routing_events routing ON routing.turn_id = measured.turn_id
LEFT JOIN context_events context_evidence ON context_evidence.turn_id = measured.turn_id
LEFT JOIN per_session ON per_session.session_id = measured.session_id;
"
} > "$contract_path"

contract_row="$(tail -n 1 "$contract_path")"
IFS=$'\t' read -r boundary_turns completed_turns failed_turns processing_turns \
  distinct_sessions max_requests_per_session matching_profile_turns matching_context_turns \
  routing_events context_events actual_tool_profiles persisted_turn_counts \
  candidate_turn_counts loaded_turn_counts included_turn_counts older_turn_values \
  token_watermarks candidate_token_counts included_token_counts token_budgets \
  omitted_loaded_turn_counts <<< "$contract_row"
if [[ ! "$boundary_turns" =~ ^[0-9]+$ \
  || ! "$completed_turns" =~ ^[0-9]+$ \
  || ! "$distinct_sessions" =~ ^[0-9]+$ \
  || ! "$max_requests_per_session" =~ ^[0-9]+$ \
  || ! "$matching_profile_turns" =~ ^[0-9]+$ \
  || ! "$matching_context_turns" =~ ^[0-9]+$ \
  || "$completed_turns" -eq 0 \
  || "$distinct_sessions" -ne "$boundary_turns" \
  || "$max_requests_per_session" -ne 1 \
  || "$matching_profile_turns" -ne "$completed_turns" \
  || "$matching_context_turns" -ne "$completed_turns" ]]; then
  printf 'contract_status=fail\n' >> "$contract_path"
  echo "Warm-history workload contract failed for '$HISTORY_CASE'." >&2
  cat "$contract_path" >&2
  exit 1
fi
printf 'contract_status=pass\n' >> "$contract_path"

uv run python bench/agent/summarize_warm_history.py \
  --fixture "$fixture_path" \
  --summary "$summary_path" \
  --contract "$contract_path" \
  --setup-environment "$setup_environment_path" \
  --label "$LABEL" \
  --rate "$RATE" \
  --duration "$DURATION_SECONDS" \
  --run-started-at "$run_started_at" \
  --run-completed-at "$run_completed_at" \
  --k6-image-reference "$K6_IMAGE_REFERENCE" \
  --k6-image-id "$k6_image_id" \
  --k6-version "$k6_version" \
  --artifact-prefix "bench/results/$prefix" \
  --out "$result_path"

publish_agent_results "$setup_environment_path" "after warm history" "$staging_dir" "$out" \
  "$setup_environment_name" "${result_names[@]}"
result_published=true
trap - EXIT HUP INT TERM
