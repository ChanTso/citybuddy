#!/usr/bin/env bash
# Executes the fixed default-path baselines and worker/client-layout retrieval factorial.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  printf 'Usage: %s {baseline|factorial|all}\n' "${0##*/}" >&2
  exit 2
fi
PHASE="$1"
case "$PHASE" in
  baseline | factorial | all) ;;
  *)
    printf "Unknown worker-layout phase '%s'; expected baseline, factorial, or all.\n" "$PHASE" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
run_dir="$repo_root/bench/.run"
results_dir="$repo_root/bench/results"
mkdir -p "$run_dir"

source_changes="$(git status --porcelain --untracked-files=all -- . \
  ':(exclude)bench/results/**' \
  ':(exclude)bench/.run/**')"
measured_sha="$(git rev-parse --verify HEAD)"
if [[ ! "$measured_sha" =~ ^[0-9a-f]{40}$ ]] || [ -n "$source_changes" ]; then
  echo "The worker-layout experiment requires a committed, source-clean tree." >&2
  [ -z "$source_changes" ] || printf '%s\n' "$source_changes" >&2
  exit 1
fi

verify_measured_checkout() {
  local current_sha current_changes
  current_sha="$(git rev-parse --verify HEAD)"
  current_changes="$(git status --porcelain --untracked-files=all -- . \
    ':(exclude)bench/results/**' \
    ':(exclude)bench/.run/**')"
  if [ "$current_sha" != "$measured_sha" ] || [ -n "$current_changes" ]; then
    echo "The measured checkout changed from $measured_sha; discard this experiment attempt." >&2
    [ -z "$current_changes" ] || printf '%s\n' "$current_changes" >&2
    return 1
  fi
}
sha_short="${measured_sha:0:7}"
EXPERIMENT_ID="${EXPERIMENT_ID:-worker_http_layout_$sha_short}"
if [[ ! "$EXPERIMENT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
  || [ "${#EXPERIMENT_ID}" -gt 64 ]; then
  echo "EXPERIMENT_ID must be 1-64 safe filename characters." >&2
  exit 2
fi

staging_dir="$(mktemp -d "$run_dir/worker-http-layout.XXXXXX")"
publication_dir="$staging_dir/publication"
mkdir "$publication_dir"
experiment_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
root_pw="$(grep -E '^MYSQL_BOOTSTRAP_PASSWORD=' .env | head -1 | cut -d= -f2-)"

resolved_mysql_container_id=""
resolved_mysql_port=""
resolve_mysql_boundary() {
  resolved_mysql_container_id="$(docker inspect --format '{{.Id}}' citybuddy-mysql-1)"
  if [[ ! "$resolved_mysql_container_id" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Cannot resolve the current MySQL container." >&2
    return 1
  fi
  resolved_mysql_port="$(docker port "$resolved_mysql_container_id" 3306/tcp \
    | awk -F: 'NR == 1 {print $NF}')"
  if [[ ! "$resolved_mysql_port" =~ ^[1-9][0-9]*$ ]]; then
    echo "Cannot resolve the current MySQL host port." >&2
    return 1
  fi
}

mysql_raw_at_port() {
  local mysql_port="$1"
  MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root \
    --batch --raw -e "SHOW GLOBAL VARIABLES LIKE 'max_connections'"
}

mysql_raw() {
  resolve_mysql_boundary || return 1
  mysql_raw_at_port "$resolved_mysql_port"
}

mysql_value() {
  mysql_raw | awk -F '\t' '$1 == "max_connections" {print $2}'
}

write_mysql_raw() {
  local path="$1" boundary="$2" mysql_container_id mysql_port
  resolve_mysql_boundary || return 1
  mysql_container_id="$resolved_mysql_container_id"
  mysql_port="$resolved_mysql_port"
  {
    printf 'citybuddy_commit=%s\n' "$measured_sha"
    printf 'sut_commit=%s\n' "$measured_sha"
    printf 'benchmark_harness_commit=%s\n' "$measured_sha"
    printf 'boundary=%s\n' "$boundary"
    printf 'observed_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'mysql_container_id=%s\n' "$mysql_container_id"
    printf 'mysql_host_port=%s\n' "$mysql_port"
    mysql_raw_at_port "$mysql_port"
  } > "$path"
}

set_mysql_max_connections() {
  local value="$1" mysql_port
  resolve_mysql_boundary || return 1
  mysql_port="$resolved_mysql_port"
  MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root \
    -e "SET GLOBAL max_connections = $value" || return 1
  [ "$(mysql_value)" = "$value" ]
}

restore_required=false
restore_complete=false
original_mysql_max=""
publication_active=false
publication_names=()
rollback_worker_publication() {
  local index name status=0
  [ "$publication_active" = true ] || return 0
  for ((index = ${#publication_names[@]} - 1; index >= 0; index--)); do
    name="${publication_names[$index]}"
    if [ -e "$results_dir/$name" ]; then
      if [ -e "$publication_dir/$name" ] \
        || ! mv "$results_dir/$name" "$publication_dir/$name"; then
        echo "Failed to roll back worker-layout result $results_dir/$name." >&2
        status=1
      fi
    fi
  done
  if [ "$status" -eq 0 ]; then
    publication_active=false
  fi
  return "$status"
}

publish_worker_results() {
  local completion_name="$1" path name moved_status=0
  local -a ordinary_names=()
  publication_names=()
  for path in "$publication_dir"/*; do
    [ -f "$path" ] || continue
    name="${path##*/}"
    if [ -n "$completion_name" ] && [ "$name" = "$completion_name" ]; then
      continue
    fi
    ordinary_names+=("$name")
  done
  publication_names=("${ordinary_names[@]}")
  if [ -n "$completion_name" ]; then
    if [ ! -f "$publication_dir/$completion_name" ]; then
      echo "Worker-layout publication is missing completion result $completion_name." >&2
      return 1
    fi
    publication_names+=("$completion_name")
  fi
  if [ "${#publication_names[@]}" -eq 0 ]; then
    echo "Worker-layout publication has no staged artifacts." >&2
    return 1
  fi
  for name in "${publication_names[@]}"; do
    if [ -e "$results_dir/$name" ]; then
      echo "Refusing to overwrite worker-layout artifact $results_dir/$name." >&2
      return 1
    fi
  done
  verify_measured_checkout || return 1
  publication_active=true
  for name in "${publication_names[@]}"; do
    if ! mv "$publication_dir/$name" "$results_dir/$name"; then
      moved_status=1
      break
    fi
  done
  if [ "$moved_status" -ne 0 ] || ! verify_measured_checkout; then
    rollback_worker_publication || true
    return 1
  fi
  publication_active=false
}

restore_mysql() {
  local status=0
  if [ "$restore_required" = true ] && [ "$restore_complete" != true ]; then
    if set_mysql_max_connections "$original_mysql_max" \
      && write_mysql_raw "$staging_dir/mysql_restored_raw.txt" restored; then
      restore_complete=true
    else
      status=$?
      echo "Failed to restore MySQL max_connections=$original_mysql_max." >&2
    fi
  fi
  return "$status"
}

finish_or_restore() {
  local original_status=$? rollback_status=0 restore_status=0
  trap - EXIT HUP INT TERM
  rollback_worker_publication || rollback_status=$?
  restore_mysql || restore_status=$?
  if [ "$original_status" -ne 0 ]; then
    echo "Unpublished worker-layout diagnostics remain in $staging_dir" >&2
    exit "$original_status"
  fi
  if [ "$restore_status" -ne 0 ]; then
    echo "Unpublished worker-layout diagnostics remain in $staging_dir" >&2
    exit "$restore_status"
  fi
  if [ "$rollback_status" -ne 0 ]; then
    echo "Unpublished worker-layout diagnostics remain in $staging_dir" >&2
    exit "$rollback_status"
  fi
}
trap finish_or_restore EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$PHASE" = baseline ] || [ "$PHASE" = all ]; then
  echo "== base benchmark setup: BENCH_USERS=10000 =="
  BENCH_USERS=10000 ./bench/setup_bench_env.sh
  verify_measured_checkout
else
  if [ ! -s "$run_dir/bench.env" ] \
    || [ "$(grep -E '^BENCH_USERS=' "$run_dir/bench.env" | cut -d= -f2-)" != 10000 ]; then
    echo "The factorial phase requires the completed BENCH_USERS=10000 base setup." >&2
    exit 1
  fi
fi

write_mysql_raw "$staging_dir/mysql_original_raw.txt" original
original_mysql_max="$(awk -F '\t' '$1 == "max_connections" {print $2}' \
  "$staging_dir/mysql_original_raw.txt")"
if [ "$original_mysql_max" != 151 ]; then
  echo "Expected the untouched MySQL max_connections default of 151; observed $original_mysql_max." >&2
  exit 1
fi
restore_required=true

run_baseline() {
  local path="$1" rates="$2" seconds="$3" users="$4" position="$5" label
  label="${EXPERIMENT_ID}_baseline_p${position}_${path}"
  if [ "$(mysql_value)" != 151 ]; then
    echo "Default baseline $path did not start at MySQL max_connections=151." >&2
    return 1
  fi
  verify_measured_checkout || return 1
  if ! env -u AGENT_WORKERS -u AGENT_HTTP_CLIENT_LAYOUT \
    AGENT_BENCH_USERS="$users" AGENT_ATTEMPT_BUDGET=16 \
    ./bench/agent/setup_agent_bench.sh; then
    return 1
  fi
  if [ "$(mysql_value)" != 151 ]; then
    echo "Default baseline $path setup changed MySQL max_connections from 151." >&2
    return 1
  fi
  verify_measured_checkout || return 1
  if ! env \
    AGENT_RESULTS_DIR="$publication_dir" \
    LABEL="$label" RUN_ID="$label" RATES="$rates" STEP_SECONDS="$seconds" \
    GRACEFUL_STOP_SECONDS=45 GAP_SECONDS=55 POOL_BASE=0 \
    ./bench/agent/run_agent_ladder.sh "$path"; then
    return 1
  fi
  verify_measured_checkout || return 1
}

if [ "$PHASE" = baseline ] || [ "$PHASE" = all ]; then
  run_baseline greeting '25,50,75,100,125' 20 8000 1
  run_baseline chat '10,25,50,75,100' 20 6000 2
  run_baseline retrieval '40,50,60,75,90' 30 10000 3
  run_baseline prepare '5,10,15,20,30' 30 3000 4
fi

retry_blocks=()
run_factorial_cell() {
  local block="$1" attempt="$2" position="$3" treatment="$4"
  local workers layout treatment_slug label
  case "$treatment" in
    1S) workers=1; layout=shared ;;
    1PA) workers=1; layout=per-authority ;;
    2S) workers=2; layout=shared ;;
    2PA) workers=2; layout=per-authority ;;
    *) echo "Unknown factorial treatment $treatment." >&2; return 2 ;;
  esac
  treatment_slug="$(printf '%s' "$treatment" | tr '[:upper:]' '[:lower:]')"
  label="${EXPERIMENT_ID}_b${block}a${attempt}p${position}_${treatment_slug}"
  if [ "$(mysql_value)" != 1000 ]; then
    echo "Factorial cell $block/$position did not start at MySQL max_connections=1000." >&2
    return 1
  fi
  verify_measured_checkout || return 1
  if ! AGENT_BENCH_USERS=7000 AGENT_ATTEMPT_BUDGET=16 \
    AGENT_WORKERS="$workers" AGENT_HTTP_CLIENT_LAYOUT="$layout" \
    ./bench/agent/setup_agent_bench.sh; then
    return 1
  fi
  if [ "$(mysql_value)" != 1000 ]; then
    echo "Factorial cell $block/$position setup changed MySQL max_connections from 1000." >&2
    return 1
  fi
  verify_measured_checkout || return 1
  if ! LABEL="$label" RUN_ID="$label" RATES='60,75,90' STEP_SECONDS=30 \
    AGENT_RESULTS_DIR="$publication_dir" \
    GRACEFUL_STOP_SECONDS=45 GAP_SECONDS=55 POOL_BASE=0 \
    ./bench/agent/run_agent_ladder.sh retrieval; then
    return 1
  fi
  verify_measured_checkout || return 1
}

run_factorial_block() {
  local block="$1" attempt="$2" order="$3" position=0 treatment
  for treatment in $order; do
    position=$((position + 1))
    if ! run_factorial_cell "$block" "$attempt" "$position" "$treatment"; then
      return 1
    fi
  done
}

if [ "$PHASE" = factorial ] || [ "$PHASE" = all ]; then
  set_mysql_max_connections 1000
  write_mysql_raw "$staging_dir/mysql_factorial_raw.txt" factorial
  block_orders=(
    '1S 1PA 2S 2PA'
    '1PA 2PA 1S 2S'
    '2PA 2S 1PA 1S'
    '2S 1S 2PA 1PA'
  )
  for block in 1 2 3 4; do
    if run_factorial_block "$block" 1 "${block_orders[$((block - 1))]}"; then
      continue
    fi
    echo "Factorial block $block was operationally invalid; rerunning the complete block once." >&2
    retry_blocks+=("$block")
    if ! set_mysql_max_connections 1000; then
      echo "Cannot re-establish MySQL max_connections=1000 for the block retry." >&2
      exit 1
    fi
    if ! run_factorial_block "$block" 2 "${block_orders[$((block - 1))]}"; then
      echo "Factorial block $block was operationally invalid on its one full retry." >&2
      exit 1
    fi
  done
fi

restore_mysql

raw_prefix="agent_${EXPERIMENT_ID}_${PHASE}_mysql"
raw_names=("${raw_prefix}_original.txt" "${raw_prefix}_restored.txt")
cp "$staging_dir/mysql_original_raw.txt" "$publication_dir/${raw_names[0]}"
cp "$staging_dir/mysql_restored_raw.txt" "$publication_dir/${raw_names[1]}"
if [ -f "$staging_dir/mysql_factorial_raw.txt" ]; then
  raw_names+=("${raw_prefix}_factorial.txt")
  cp "$staging_dir/mysql_factorial_raw.txt" "$publication_dir/${raw_names[2]}"
fi
for name in "${raw_names[@]}"; do
  if [ -e "$results_dir/$name" ]; then
    echo "Refusing to overwrite worker-layout MySQL evidence $results_dir/$name." >&2
    exit 1
  fi
done
if [ "${#retry_blocks[@]}" -eq 0 ]; then
  retry_blocks_text=none
else
  retry_blocks_text="$(IFS=,; printf '%s' "${retry_blocks[*]}")"
fi
experiment_completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
completion_name="agent_${EXPERIMENT_ID}_${PHASE}_experiment.txt"
{
  printf 'format=citybuddy-worker-http-layout-experiment-v1\n'
  printf 'citybuddy_commit=%s\n' "$measured_sha"
  printf 'sut_commit=%s\n' "$measured_sha"
  printf 'benchmark_harness_commit=%s\n' "$measured_sha"
  printf 'experiment_id=%s\n' "$EXPERIMENT_ID"
  printf 'phase=%s\n' "$PHASE"
  printf 'started_at_utc=%s\n' "$experiment_started_at"
  printf 'completed_at_utc=%s\n' "$experiment_completed_at"
  printf 'host_os=%s\n' "$(uname -s)"
  printf 'host_architecture=%s\n' "$(uname -m)"
  printf 'docker_server_version=%s\n' \
    "$(docker version --format '{{.Server.Version}}')"
  printf 'docker_cpus=%s\n' "$(docker info --format '{{.NCPU}}')"
  printf 'docker_memory_bytes=%s\n' "$(docker info --format '{{.MemTotal}}')"
  printf 'baseline_order=greeting,chat,retrieval,prepare\n'
  printf 'baseline_schedules=greeting:25,50,75,100,125@20s;chat:10,25,50,75,100@20s;retrieval:40,50,60,75,90@30s;prepare:5,10,15,20,30@30s\n'
  printf 'factorial_rates=60,75,90@30s\n'
  printf 'factorial_treatments=1S:1/shared;1PA:1/per-authority;2S:2/shared;2PA:2/per-authority\n'
  printf 'factorial_block_orders=1S,1PA,2S,2PA|1PA,2PA,1S,2S|2PA,2S,1PA,1S|2S,1S,2PA,1PA\n'
  printf 'retry_policy=one complete-block retry after an operationally invalid cell\n'
  printf 'retry_blocks=%s\n' "$retry_blocks_text"
  printf 'measurement_boundary=single-host local end-to-end layout effect; not production capacity\n'
} > "$publication_dir/$completion_name"
if ! publish_worker_results "$completion_name"; then
  exit 1
fi
rm -f "$staging_dir/mysql_original_raw.txt" "$staging_dir/mysql_factorial_raw.txt" \
  "$staging_dir/mysql_restored_raw.txt"
rmdir "$publication_dir"
rmdir "$staging_dir"
trap - EXIT HUP INT TERM
