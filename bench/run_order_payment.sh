#!/usr/bin/env bash
# One flow creates and pays one ordinary order; login and fixture work are excluded.
set -euo pipefail
if [ "$#" -ne 1 ] || [[ ! "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "${#1}" -gt 40 ]; then
  echo "Usage: run_order_payment.sh LABEL (1-40 safe characters)." >&2; exit 2
fi
LABEL="$1"
RATE="${RATE:-20}"
DURATION_SECONDS="${DURATION_SECONDS:-120}"
if [[ ! "$RATE" =~ ^[1-9][0-9]*$ ]] || [[ ! "$DURATION_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "RATE and DURATION_SECONDS must be positive ASCII integers." >&2; exit 2
fi
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
run_dir="$repo_root/bench/.run"
out="$repo_root/bench/results"
source "$run_dir/bench.env"
if [ "${BENCH_WORKLOAD:-}" != order-payment ] || [ "$LABEL" != "$TOPIC_SUFFIX" ]; then
  echo "Normal flow requires the same label's order-payment setup." >&2; exit 2
fi
changes="$(git status --porcelain --untracked-files=all -- . ':(exclude)bench/results/**' ':(exclude)bench/.run/**')"
if [ -n "$changes" ] || [ "$(git rev-parse HEAD)" != "$CITYBUDDY_COMMIT" ] \
 || [ "$(openssl dgst -sha256 auth-service/target/auth-service-0.0.1-SNAPSHOT.jar | awk '{print $NF}')" != "$IDENTITY_JAR_SHA256" ] \
 || [ "$(openssl dgst -sha256 commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar | awk '{print $NF}')" != "$COMMERCE_JAR_SHA256" ]; then
  echo "Normal flow requires the setup's clean committed source and JARs." >&2; exit 1
fi
if [ "$(docker info --format '{{.NCPU}}')" != 8 ] || [ "$(docker inspect -f '{{.HostConfig.NanoCpus}}' citybuddy-bench-commerce)" != 4000000000 ]; then
  echo "This series requires Docker VM 8 CPUs and Commerce 4 CPUs." >&2; exit 1
fi
if [ "$((RATE * DURATION_SECONDS))" -gt 32000 ]; then
  echo "Nominal flows exceed the finite 32-SKU inventory." >&2; exit 2
fi
payment_env="$run_dir/$FIXTURE_REL/payment.env"
python3 - "$run_dir/tokens.json" "$((RATE * DURATION_SECONDS + 50))" "$((DURATION_SECONDS + 180))" "$payment_env" <<'PY'
import base64,json,re,stat,sys,time
from pathlib import Path
pool=json.loads(Path(sys.argv[1]).read_text())
if len(pool)<int(sys.argv[2]):
    raise SystemExit('Insufficient fresh user tokens for the normal flow.')
try:
    expiry=min(json.loads(base64.urlsafe_b64decode(t.split('.')[1]+'==='))['exp'] for t in pool)
except (ValueError,TypeError,KeyError,IndexError):
    raise SystemExit('Invalid token metadata; values withheld.')
if expiry-time.time()<max(300,int(sys.argv[3])):
    raise SystemExit('Login tokens do not cover the complete flow window; remint outside load.')
p=Path(sys.argv[4])
rows=p.read_text().splitlines()
values=dict(row.split('=',1) for row in rows if '=' in row)
if stat.S_IMODE(p.stat().st_mode)&0o077 or len(rows)!=2 or set(values)!={'CITYBUDDY_MOCKPAYMENT_CALLBACKKEYID','CITYBUDDY_MOCKPAYMENT_CALLBACKSECRET'}:
    raise SystemExit('Use a private callback env containing only the two canonical payment keys.')
if not re.fullmatch(r'[A-Za-z0-9._-]{1,64}',values['CITYBUDDY_MOCKPAYMENT_CALLBACKKEYID']) or not re.fullmatch(r'[A-Za-z0-9._-]{32,512}',values['CITYBUDDY_MOCKPAYMENT_CALLBACKSECRET']):
    raise SystemExit('Invalid callback configuration; contents withheld.')
print(f'token_count={len(pool)} minimum_seconds_remaining={int(expiry-time.time())}')
PY
stem="order_payment_$LABEL"
for name in "${stem}_points.json" "${stem}_summary.json" "${stem}_console.txt" "${stem}_cpu.txt" "${stem}_setup.txt" "${stem}_before.txt" "${stem}_after.txt"; do
  if [ -e "$out/$name" ]; then echo "Refusing to overwrite $out/$name" >&2; exit 1; fi
done
if docker inspect citybuddy-bench-k6 >/dev/null 2>&1; then
  echo "Preserve existing citybuddy-bench-k6 before another run." >&2; exit 1
fi
python3 bench/order_payment_snapshot.py "$LABEL" --phase before
cp "$run_dir/bench.env" "$out/${stem}_setup.txt"
image='grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec'
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
metadata() {
  printf 'citybuddy_commit=%s\nlabel=%s\nrate_flows_per_s=%s\ninput_seconds=%s\nstarted_at_utc=%s\n' "$CITYBUDDY_COMMIT" "$LABEL" "$RATE" "$DURATION_SECONDS" "$started"
  printf 'workload=ordinary order then payment attempt then signed success callback; three sequential HTTP requests per completed flow\n'
  printf 'docker_cpus=%s docker_memory_bytes=%s commerce_cpu_limit=4 fixed_vus=100 k6_image=%s\n' "$DOCKER_CPUS" "$DOCKER_MEMORY_BYTES" "$image"
}
metadata > "$out/${stem}_cpu.txt"
cid=""
cleanup() {
  status=$?
  trap - EXIT INT TERM
  if [ -n "$cid" ]; then
    docker stop --time 15 "$cid" >/dev/null 2>&1 || true
    { metadata; docker logs "$cid" 2>&1; } > "$out/${stem}_console.txt"
    docker rm "$cid" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cid="$(docker run --detach --name citybuddy-bench-k6 --network citybuddy_default \
 --volume "$repo_root/bench/k6:/scripts:ro" --volume "$run_dir/tokens.json:/run-data/tokens.json:ro" --volume "$out:/out" \
 --env-file "$payment_env" --env TOKENS_FILE=/run-data/tokens.json \
 --volume "$run_dir/$FIXTURE_REL/products.json:/run-data/products.json:ro" --env PRODUCTS_FILE=/run-data/products.json --env RATE="$RATE" \
 --env DURATION_SECONDS="$DURATION_SECONDS" --env REQUEST_KEY_PREFIX="$LABEL" \
 --entrypoint k6 "$image" run --tag "citybuddy_commit=$CITYBUDDY_COMMIT" --tag "bench_label=$LABEL" \
 --summary-export="/out/${stem}_summary.json" --out "json=/out/${stem}_points.json" /scripts/order_payment.js)"
while [ "$(docker inspect -f '{{.State.Running}}' "$cid")" = true ]; do
  python3 -c 'import time; print("host_wall_ns="+str(time.time_ns())+" host_monotonic_ns="+str(time.monotonic_ns()))' >> "$out/${stem}_cpu.txt"
  docker exec citybuddy-bench-commerce sh -c 'date -u +vm_wall_epoch=%s; cat /proc/uptime; cat /sys/fs/cgroup/cpu.stat; cat /sys/fs/cgroup/cpu.max; cat /sys/fs/cgroup/memory.current' >> "$out/${stem}_cpu.txt" 2>&1 || true
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' "$cid" \
   citybuddy-bench-commerce citybuddy-bench-auth citybuddy-mysql-1 citybuddy-redis-commerce-1 citybuddy-rocketmq-broker-proxy-1 \
   >> "$out/${stem}_cpu.txt" 2>&1 || true
  sleep 3
done
result="$(docker wait "$cid")"
finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'completed_at_utc=%s\n' "$finished" >> "$out/${stem}_cpu.txt"
{ metadata; printf 'completed_at_utc=%s\nk6_exit_code=%s\n' "$finished" "$result"; docker logs "$cid" 2>&1; } > "$out/${stem}_console.txt"
docker rm "$cid" >/dev/null
cid=""
# Even a failed flow leaves its actual order/attempt state for diagnosis; never retry or reset it.
python3 bench/order_payment_snapshot.py "$LABEL" --phase after
python3 - "$out/${stem}_summary.json" "$CITYBUDDY_COMMIT" "$LABEL" "$RATE" "$DURATION_SECONDS" "$started" "$finished" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])
if not p.is_file():
    raise SystemExit('No k6 summary; retain the raw output and fixture.')
data=json.loads(p.read_text())
data['citybuddyCommit']=sys.argv[2]
data['benchmark']={'label':sys.argv[3],'rateFlowsPerSecond':int(sys.argv[4]),'inputSeconds':int(sys.argv[5]),'startedAt':sys.argv[6],'completedAt':sys.argv[7],'requestsPerSuccessfulFlow':3,'flowDuration':'Date.now end-to-end; includes script work and HMAC; raw negative values are retained'}
p.write_text(json.dumps(data,indent=2,sort_keys=True)+'\n')
PY
if [ "$(git rev-parse HEAD)" != "$CITYBUDDY_COMMIT" ] || [ -n "$(git status --porcelain --untracked-files=all -- . ':(exclude)bench/results/**' ':(exclude)bench/.run/**')" ]; then echo "Source changed during measurement." >&2; exit 1; fi
if [[ ! "$result" =~ ^[0-9]+$ ]] || [ "$result" -ne 0 ]; then echo "k6 exited ${result:-unknown}; keep all raw outputs." >&2; exit 1; fi
tail -35 "$out/${stem}_console.txt"
