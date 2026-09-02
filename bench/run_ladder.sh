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
K6_IMAGE_REFERENCE="grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec"
if [[ ! "$LABEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "${#LABEL}" -gt 96 ]; then
  echo "LABEL must be 1-96 safe characters and start with an alphanumeric." >&2
  exit 2
fi
if [[ ! "$ACTIVITIES" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "$RATES" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]] \
  || [[ ! "$STEP_SECONDS" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "$GAP_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "ACTIVITIES, RATES, STEP_SECONDS and GAP_SECONDS must be ASCII integers." >&2
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

source_changes="$(git status --porcelain --untracked-files=all -- . \
  ':(exclude)bench/results/**' \
  ':(exclude)bench/.run/**')"
if [ -n "$source_changes" ] || [ "$(git rev-parse --verify HEAD)" != "$CITYBUDDY_COMMIT" ] \
  || [ "$(openssl dgst -sha256 "$auth_jar" | awk '{print $NF}')" != "$AUTH_JAR_SHA256" ] \
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
if [ "$token_count" -lt "$required_tokens" ]; then
  echo "Token pool has $token_count entries; this ladder requires at least $required_tokens." >&2
  exit 1
fi
if [ "$BENCH_STOCK" -lt "$nominal_iterations" ] \
  || { [ "$ACTIVITIES" -eq 1 ] && [ "$BENCH_QUOTA" -lt "$nominal_iterations" ]; }; then
  echo "Seeded stock or activity quota cannot keep this ladder on the admitted path." >&2
  exit 1
fi

summary_name="k6_${LABEL}_summary.json"
points_name="k6_${LABEL}_points.json"
cpu_name="k6_${LABEL}_cpu.txt"
console_name="k6_${LABEL}_console.txt"
steps_name="ladder_${LABEL}_steps.txt"
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
}
{ metadata; echo; } > "$out/$cpu_name"

echo "== ladder '$LABEL' (commit=$CITYBUDDY_COMMIT activities=$ACTIVITIES rates=$RATES) =="
k6_container_id="$(docker run --detach --name citybuddy-bench-k6 \
  --network citybuddy_default \
  --volume "$repo_root/bench/k6:/scripts:ro" \
  --volume "$run_dir:/run-data:ro" \
  --volume "$out:/out" \
  --env TOKENS_FILE=/run-data/tokens.json \
  --env RATES="$RATES" --env STEP_SECONDS="$STEP_SECONDS" --env GAP_SECONDS="$GAP_SECONDS" \
  --env ACTIVITIES="$ACTIVITIES" \
  --entrypoint k6 "$K6_IMAGE_REFERENCE" run \
  --tag "citybuddy_commit=$CITYBUDDY_COMMIT" --tag "bench_label=$LABEL" \
  --tag "run_started_at_utc=$run_started_at" --tag "activities=$ACTIVITIES" \
  --tag "step_seconds=$STEP_SECONDS" \
  --summary-export="/out/$summary_name" --out "json=/out/$points_name" \
  /scripts/seckill_ladder.js)"
while [ "$(docker inspect -f '{{.State.Running}}' "$k6_container_id" 2>/dev/null)" = true ]; do
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' \
    "$k6_container_id" citybuddy-bench-commerce citybuddy-mysql-1 citybuddy-redis-commerce-1 \
    2>/dev/null | sed "s/^/$(date -u +%H:%M:%S) /" >> "$out/$cpu_name" || true
  sleep 3
done
k6_exit_code="$(docker wait "$k6_container_id")"
run_completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '\nrun_completed_at_utc=%s\n' "$run_completed_at" >> "$out/$cpu_name"
{ metadata; echo; docker logs "$k6_container_id" 2>&1; } > "$out/$console_name"
docker rm "$k6_container_id" >/dev/null

if [ -s "$out/$summary_name" ]; then
  python3 - "$out/$summary_name" "$CITYBUDDY_COMMIT" "$run_started_at" "$run_completed_at" \
    "$LABEL" "$ACTIVITIES" "$RATES" "$STEP_SECONDS" "$GAP_SECONDS" "$K6_IMAGE_REFERENCE" <<'PY'
import json, sys
path = sys.argv[1]
document = json.load(open(path))
document["citybuddyCommit"] = sys.argv[2]
document["benchmark"] = {
    "windowUtc": {"startedAt": sys.argv[3], "completedAt": sys.argv[4]},
    "label": sys.argv[5], "activities": int(sys.argv[6]),
    "rates": [int(value) for value in sys.argv[7].split(",")],
    "stepSeconds": int(sys.argv[8]), "gapSeconds": int(sys.argv[9]), "k6Image": sys.argv[10],
}
json.dump(document, open(path, "w"), indent=2, sort_keys=True)
open(path, "a").write("\n")
PY
fi
if [ "$(git rev-parse --verify HEAD)" != "$CITYBUDDY_COMMIT" ]; then
  echo "CityBuddy HEAD changed during the ladder." >&2
  exit 1
fi
if [[ ! "$k6_exit_code" =~ ^[0-9]+$ ]] || [ "$k6_exit_code" -ne 0 ]; then
  echo "k6 exited with status ${k6_exit_code:-unknown}." >&2
  exit 1
fi
if [ ! -s "$out/$summary_name" ] || [ ! -s "$out/$points_name" ]; then
  echo "k6 did not produce both required raw outputs." >&2
  exit 1
fi
{ metadata; echo; python3 bench/analyze_ladder.py "$out/$points_name" "$LABEL" \
  --rates "$RATES" --step-seconds "$STEP_SECONDS"; } > "$out/$steps_name"

echo "-- peak generator CPU --"
awk '/citybuddy-bench-k6/ {value=$3; sub(/^cpu=/,"",value); sub(/%$/,"",value); print value}' \
  "$out/$cpu_name" | sort -n | tail -1
echo "-- k6 summary tail --"
tail -30 "$out/$console_name"
