#!/usr/bin/env bash
# Throughput ladder. Runs k6 inside the compose network so the generator does not pay the
# Docker Desktop host-to-VM hop, and samples the generator's own CPU so a saturated generator
# can be told apart from a saturated server.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$repo_root"
out="$repo_root/bench/results"; mkdir -p "$out"

LABEL="$1"           # e.g. contended | spread
ACTIVITIES="${2:-1}"
RATES="${RATES:-50,100,200,400,800}"
STEP_SECONDS="${STEP_SECONDS:-15}"

echo "== ladder '$LABEL' (activities=$ACTIVITIES rates=$RATES step=${STEP_SECONDS}s) =="
docker rm -f citybuddy-bench-k6 >/dev/null 2>&1 || true

docker run --detach --name citybuddy-bench-k6 \
  --network citybuddy_default \
  --volume "$repo_root/bench/k6:/scripts:ro" \
  --volume "$repo_root/bench/.run:/run-data:ro" \
  --volume "$out:/out" \
  --env TOKENS_FILE=/run-data/tokens.json \
  --env RATES="$RATES" --env STEP_SECONDS="$STEP_SECONDS" --env ACTIVITIES="$ACTIVITIES" \
  --entrypoint k6 grafana/k6:latest \
  run --summary-export="/out/k6_${LABEL}_summary.json" \
      --out "json=/out/k6_${LABEL}_points.json" \
      /scripts/seckill_ladder.js >/dev/null

# Sample generator and server CPU for the life of the run.
: > "$out/k6_${LABEL}_cpu.txt"
while [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-k6 2>/dev/null)" = "true" ]; do
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' \
    citybuddy-bench-k6 citybuddy-bench-commerce citybuddy-mysql-1 citybuddy-redis-commerce-1 \
    2>/dev/null | sed "s/^/$(date -u +%H:%M:%S) /" >> "$out/k6_${LABEL}_cpu.txt" || true
  sleep 3
done

docker logs citybuddy-bench-k6 > "$out/k6_${LABEL}_console.txt" 2>&1
echo "-- peak generator CPU --"
grep bench-k6 "$out/k6_${LABEL}_cpu.txt" | awk '{print $3}' | sed 's/cpu=//;s/%//' | sort -n | tail -1
echo "-- k6 summary tail --"
tail -30 "$out/k6_${LABEL}_console.txt"
