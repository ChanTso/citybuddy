#!/usr/bin/env bash
# Fixed-rate seckill ladder. Each LABEL owns one direct, non-overwriting evidence bundle.
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "Usage: run_ladder.sh LABEL [ACTIVITIES]" >&2
  exit 2
fi
LABEL="$1"
ACTIVITIES="${2:-1}"
RATES="${RATES:-50,100,200,400,800}"
STEP_SECONDS="${STEP_SECONDS:-15}"
GAP_SECONDS="${GAP_SECONDS:-5}"
REJECTION_VUS="${REJECTION_VUS:-500}"
REJECTION_OUTPUT="${REJECTION_OUTPUT:-points}"
case "$REJECTION_OUTPUT" in points|summary) ;; *) echo "REJECTION_OUTPUT must be points or summary." >&2; exit 2 ;; esac
K6_IMAGE_REFERENCE="grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec"
if [[ ! "$LABEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "${#LABEL}" -gt 96 ]; then
  echo "LABEL must be 1-96 safe characters and start with an alphanumeric." >&2
  exit 2
fi
if [[ ! "$ACTIVITIES" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "$RATES" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]] \
  || [[ ! "$STEP_SECONDS" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "$GAP_SECONDS" =~ ^[0-9]+$ ]] \
  || [[ ! "$REJECTION_VUS" =~ ^[1-9][0-9]*$ ]]; then
  echo "ACTIVITIES, RATES, STEP_SECONDS, GAP_SECONDS and REJECTION_VUS must be ASCII integers." >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
out="$repo_root/bench/results"
run_dir="$repo_root/bench/.run"
bench_env="$run_dir/bench.env"
auth_jar="$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar"
commerce_jar="$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar"
mkdir -p "$out"
if [ ! -s "$bench_env" ]; then
  echo "Rerun setup_bench_env.sh before a seckill ladder." >&2
  exit 1
fi
# shellcheck disable=SC1090
source "$bench_env"
if [ "$REJECTION_OUTPUT" = summary ] && [ "${BENCH_WORKLOAD:-seckill}" != seckill-rejection ]; then
  echo "Summary-only output is supported only for rejection calibration." >&2; exit 2
fi
if [ "${BENCH_WORKLOAD:-seckill}" = order-payment ]; then
  echo "Use run_order_payment.sh for the normal transaction fixture." >&2; exit 2
fi
if [ "${BENCH_WORKLOAD:-seckill}" = seckill-rejection ] && { [ "$LABEL" != "$TOPIC_SUFFIX" ] || [ "$ACTIVITIES" != 32 ] || [ "$GAP_SECONDS" -lt 5 ] || { [ "$STEP_SECONDS" != 30 ] && [ "$STEP_SECONDS" != 120 ]; } || { [[ "$RATES" == *,* ]] && [ "$STEP_SECONDS" != 30 ]; }; }; then
  echo "Rejection requires this setup label, 32 activities, gap >= 5s and 30/120s; multi-rate probes use 30s." >&2; exit 2
fi

source_changes="$(git status --porcelain --untracked-files=all -- . \
  ':(exclude)bench/results/**' \
  ':(exclude)bench/.run/**')"
if [ -n "$source_changes" ] || [ "$(git rev-parse --verify HEAD)" != "$CITYBUDDY_COMMIT" ] \
  || [ "$(openssl dgst -sha256 "$auth_jar" | awk '{print $NF}')" != "$IDENTITY_JAR_SHA256" ] \
  || [ "$(openssl dgst -sha256 "$commerce_jar" | awk '{print $NF}')" != "$COMMERCE_JAR_SHA256" ]; then
  echo "Seckill ladder requires the clean checkout and JARs recorded by setup." >&2
  [ -z "$source_changes" ] || printf '%s\n' "$source_changes" >&2
  exit 1
fi
if [ "$ACTIVITIES" -gt "$BENCH_ACTIVITIES" ]; then
  echo "ACTIVITIES exceeds the seeded benchmark activity count." >&2
  exit 2
fi

nominal_iterations=0
rate_count=0
IFS=, read -r -a rate_values <<< "$RATES"
for rate in "${rate_values[@]}"; do
  nominal_iterations=$((nominal_iterations + rate * STEP_SECONDS))
  rate_count=$((rate_count + 1))
done
required_tokens=$((nominal_iterations + 50 * rate_count))
token_count="$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' \
  "$run_dir/tokens.json")"
if [ "${BENCH_WORKLOAD:-seckill}" = seckill-rejection ]; then required_tokens=16704; fi
if [ "$token_count" -lt "$required_tokens" ]; then
  echo "Token pool has $token_count entries; this ladder requires at least $required_tokens." >&2
  exit 1
fi
if [ "${BENCH_WORKLOAD:-seckill}" = seckill ] && { [ "$BENCH_STOCK" -lt "$nominal_iterations" ] \
  || { [ "$ACTIVITIES" -eq 1 ] && [ "$BENCH_QUOTA" -lt "$nominal_iterations" ]; }; }; then
  echo "Seeded stock or activity quota cannot keep this ladder on the admitted path." >&2
  exit 1
fi

if [ "$(docker info --format '{{.NCPU}}')" != 8 ] || [ "$(docker inspect -f '{{.HostConfig.NanoCpus}}' citybuddy-bench-commerce)" != 4000000000 ]; then
  echo "This series requires Docker VM 8 CPUs and Commerce 4 CPUs." >&2; exit 1
fi
script_name=seckill_ladder.js
if [ "${BENCH_WORKLOAD:-seckill}" = seckill-rejection ]; then
  script_name=seckill_rejection.js
fi
summary_name="k6_${LABEL}_summary.json"
points_name="k6_${LABEL}_points.json"
cpu_name="k6_${LABEL}_cpu.txt"
console_name="k6_${LABEL}_console.txt"
steps_name="ladder_${LABEL}_steps.txt"
output_args=(--out "json=/out/$points_name")
if [ "$REJECTION_OUTPUT" = summary ]; then
  output_args=(--summary-mode full --new-machine-readable-summary)
fi
setup_name="seckill_${LABEL}_setup.txt"
for name in "$summary_name" "$points_name" "$cpu_name" "$console_name" "$steps_name" "$setup_name"; do
  if [ -e "$out/$name" ]; then
    echo "Refusing to overwrite existing seckill benchmark output: $out/$name" >&2
    exit 1
  fi
done
if docker inspect citybuddy-bench-k6 >/dev/null 2>&1; then
  echo "Refusing to replace existing container citybuddy-bench-k6." >&2
  exit 1
fi
if [ "${BENCH_WORKLOAD:-seckill}" = seckill-rejection ]; then
  python3 bench/rejection_snapshot.py "$LABEL" --phase before --require-ready
fi
python3 - "$run_dir/tokens.json" "$((STEP_SECONDS * rate_count + GAP_SECONDS * rate_count + 180))" <<'PY'
import base64,json,sys,time
pool=json.load(open(sys.argv[1]))
try:
    earliest=min(json.loads(base64.urlsafe_b64decode(t.split('.')[1]+'==='))["exp"] for t in pool)
except (ValueError,KeyError,IndexError,TypeError):
    raise SystemExit('Invalid token metadata; values withheld.')
if earliest-time.time()<max(300,int(sys.argv[2])):
    raise SystemExit('Tokens do not cover the planned input and completion window; remint outside load.')
print(f'token_count={len(pool)} minimum_seconds_remaining={int(earliest-time.time())}')
PY

cp "$bench_env" "$out/$setup_name"

run_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run_completed_at=""
metadata() {
  printf 'citybuddy_commit=%s\n' "$CITYBUDDY_COMMIT"
  printf 'setup_window_utc=%s/%s\n' "$SETUP_STARTED_AT_UTC" "$SETUP_COMPLETED_AT_UTC"
  printf 'run_started_at_utc=%s\n' "$run_started_at"
  [ -z "$run_completed_at" ] || printf 'run_completed_at_utc=%s\n' "$run_completed_at"
  printf 'label=%s activities=%s rates=%s step_seconds=%s gap_seconds=%s nominal_iterations=%s\n' \
    "$LABEL" "$ACTIVITIES" "$RATES" "$STEP_SECONDS" "$GAP_SECONDS" "$nominal_iterations"
  printf 'fixture_users=%s fixture_activities=%s fixture_quota=%s fixture_stock=%s topic_suffix=%s\n' \
    "$BENCH_USERS" "$BENCH_ACTIVITIES" "$BENCH_QUOTA" "$BENCH_STOCK" "$TOPIC_SUFFIX"
  printf 'docker_cpus=%s docker_memory_bytes=%s commerce_cpu_limit=%s\n' \
    "$DOCKER_CPUS" "$DOCKER_MEMORY_BYTES" "$COMMERCE_CPU_LIMIT"
  printf 'k6_image=%s\n' "$K6_IMAGE_REFERENCE"
  printf 'request_key_prefix=%s activity_prefix=%s workload=%s\n' "$LABEL" "${ACTIVITY_PREFIX:-bench-activity-}" "${BENCH_WORKLOAD:-seckill}"
  if [ "${BENCH_WORKLOAD:-seckill}" = seckill-rejection ]; then
    printf 'rejection_output=%s\n' "$REJECTION_OUTPUT"
    printf 'warmup=1000/s*30s warmup_gap=5s rates=%s load_users=16384 preparation_users=320 fixed_vus_per_phase=%s\n' "$RATES" "$REJECTION_VUS"
    if [[ "$RATES" == *,* ]]; then
      printf 'measurement_kind=coarse_probe stop=per_phase_drops_1pct_nominal_or_unexpected_1pct_or_p99_1000ms delay=5s\n'
    else
      printf 'measurement_kind=fixed_load\n'
    fi
  fi
}
{ metadata; echo; } > "$out/$cpu_name"

echo "== ladder '$LABEL' (commit=$CITYBUDDY_COMMIT activities=$ACTIVITIES rates=$RATES) =="
# Only this invocation's container ID is stopped on interruption; SQL and outputs remain.
k6_container_id=""
cleanup() {
  status=$?
  trap - EXIT INT TERM
  if [ -n "$k6_container_id" ]; then
    docker stop --time 15 "$k6_container_id" >/dev/null 2>&1 || true
    { metadata; echo; docker logs "$k6_container_id" 2>&1; } > "$out/$console_name"
    docker rm "$k6_container_id" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
k6_container_id="$(docker run --detach --name citybuddy-bench-k6 \
  --network citybuddy_default \
  --volume "$repo_root/bench/k6:/scripts:ro" \
  --volume "$run_dir/tokens.json:/run-data/tokens.json:ro" \
  --volume "$out:/out" \
  --env TOKENS_FILE=/run-data/tokens.json \
  --env RATES="$RATES" --env STEP_SECONDS="$STEP_SECONDS" --env GAP_SECONDS="$GAP_SECONDS" \
  --env REJECTION_VUS="$REJECTION_VUS" \
  --env REJECTION_OUTPUT="$REJECTION_OUTPUT" \
  --env ACTIVITIES="$ACTIVITIES" \
  --env REQUEST_KEY_PREFIX="$LABEL" --env ACTIVITY_PREFIX="${ACTIVITY_PREFIX:-bench-activity-}" \
  --entrypoint k6 "$K6_IMAGE_REFERENCE" run \
  --tag "citybuddy_commit=$CITYBUDDY_COMMIT" --tag "bench_label=$LABEL" \
  --tag "run_started_at_utc=$run_started_at" --tag "activities=$ACTIVITIES" \
  --tag "step_seconds=$STEP_SECONDS" \
  --summary-export="/out/$summary_name" "${output_args[@]}" \
  "/scripts/$script_name")"
while [ "$(docker inspect -f '{{.State.Running}}' "$k6_container_id" 2>/dev/null)" = true ]; do
  if [ "${EXTERNAL_RESOURCE_OBSERVER:-0}" = 0 ]; then
  python3 -c 'import time; print("host_wall_ns="+str(time.time_ns())+" host_monotonic_ns="+str(time.monotonic_ns()))' >> "$out/$cpu_name"
  docker exec citybuddy-bench-commerce sh -c 'date -u +vm_wall_epoch=%s; cat /proc/uptime; cat /sys/fs/cgroup/cpu.stat; cat /sys/fs/cgroup/cpu.max; cat /sys/fs/cgroup/memory.current' >> "$out/$cpu_name" 2>&1 || true
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' \
    "$k6_container_id" citybuddy-bench-commerce citybuddy-bench-auth citybuddy-mysql-1 citybuddy-redis-commerce-1 citybuddy-rocketmq-broker-proxy-1 \
    2>&1 | sed "s/^/$(date -u +%H:%M:%S) /" >> "$out/$cpu_name" || true
  fi
  sleep 3
done
k6_exit_code="$(docker wait "$k6_container_id")"
run_completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '\nrun_completed_at_utc=%s\n' "$run_completed_at" >> "$out/$cpu_name"
{ metadata; echo; docker logs "$k6_container_id" 2>&1; } > "$out/$console_name"
docker rm "$k6_container_id" >/dev/null
k6_container_id=""
if [ "${BENCH_WORKLOAD:-seckill}" = seckill-rejection ]; then
  python3 bench/rejection_snapshot.py "$LABEL" --phase after
fi

if [ -s "$out/$summary_name" ]; then
  python3 - "$out/$summary_name" "$CITYBUDDY_COMMIT" "$run_started_at" "$run_completed_at" \
    "$LABEL" "$ACTIVITIES" "$RATES" "$STEP_SECONDS" "$GAP_SECONDS" "$K6_IMAGE_REFERENCE" "${BENCH_WORKLOAD:-seckill}" "$REJECTION_VUS" "$REJECTION_OUTPUT" <<'PY'
import json, sys
path = sys.argv[1]
document = json.load(open(path))
document["citybuddyCommit"] = sys.argv[2]
document["benchmark"] = {
    "windowUtc": {"startedAt": sys.argv[3], "completedAt": sys.argv[4]},
    "label": sys.argv[5], "activities": int(sys.argv[6]),
    "requestKeyPrefix": sys.argv[5],
    "rates": [int(value) for value in sys.argv[7].split(",")],
    "workload": sys.argv[11], "stepSeconds": int(sys.argv[8]), "gapSeconds": int(sys.argv[9]), "k6Image": sys.argv[10],
}
if sys.argv[11] == "seckill-rejection":
    document["benchmark"]["preAllocatedVusPerPhase"] = int(sys.argv[12])
    document["benchmark"]["outputMode"] = sys.argv[13]
json.dump(document, open(path, "w"), indent=2, sort_keys=True)
open(path, "a").write("\n")
PY
fi
if [ "$(git rev-parse --verify HEAD)" != "$CITYBUDDY_COMMIT" ] || [ -n "$(git status --porcelain --untracked-files=all -- . ':(exclude)bench/results/**' ':(exclude)bench/.run/**')" ]; then
  echo "CityBuddy HEAD changed during the ladder." >&2
  exit 1
fi
if [ "$REJECTION_OUTPUT" = points ]; then
  { metadata; echo; python3 bench/analyze_ladder.py "$out/$points_name" "$LABEL" \
    --rates "$RATES" --step-seconds "$STEP_SECONDS"; } > "$out/$steps_name"
else
  { metadata; echo "Native tagged aggregates: $summary_name (no per-request points exported)."; } > "$out/$steps_name"
fi
if [[ ! "$k6_exit_code" =~ ^[0-9]+$ ]] || [ "$k6_exit_code" -ne 0 ]; then
  echo "k6 exited with status ${k6_exit_code:-unknown}." >&2
  exit 1
fi
if [ ! -s "$out/$summary_name" ] || { [ "$REJECTION_OUTPUT" = points ] && [ ! -s "$out/$points_name" ]; }; then
  echo "k6 did not produce the required raw outputs for $REJECTION_OUTPUT mode." >&2
  exit 1
fi
echo "-- peak generator CPU --"
awk '/citybuddy-bench-k6/ {value=$3; sub(/^cpu=/,"",value); sub(/%$/,"",value); print value}' \
  "$out/$cpu_name" | sort -n | tail -1
echo "-- k6 summary tail --"
tail -30 "$out/$console_name"
