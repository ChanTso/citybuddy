#!/usr/bin/env bash
# Drive 600 reservations at one activity with quota 100, then require the exact durable result.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: run_correctness.sh LABEL" >&2
  exit 2
fi
LABEL="$1"
if [[ ! "$LABEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "${#LABEL}" -gt 96 ]; then
  echo "LABEL must be 1-96 safe characters and start with an alphanumeric." >&2
  exit 2
fi
QUOTA="${QUOTA:-100}"
ATTEMPTS="${ATTEMPTS:-600}"
if [ "$QUOTA" != 100 ] || [ "$ATTEMPTS" != 600 ]; then
  echo "The formal correctness workload is fixed at QUOTA=100 and ATTEMPTS=600." >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
run_dir="$repo_root/bench/.run"
out="$repo_root/bench/results"
bench_env="$run_dir/bench.env"
auth_jar="$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar"
commerce_jar="$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar"
mkdir -p "$out" "$run_dir"
http_name="correctness_${LABEL}_http.txt"
sql_name="correctness_${LABEL}_sql.txt"
setup_name="seckill_${LABEL}_setup.txt"
bundle_dir="$out/correctness_${LABEL}"
claim_dir="$out/.claim.correctness_${LABEL}"
if [ -e "$bundle_dir" ]; then
  echo "Refusing to overwrite existing seckill correctness bundle: $bundle_dir" >&2
  exit 1
fi
if [ ! -s "$bench_env" ]; then
  echo "Rerun setup_bench_env.sh before seckill correctness." >&2
  exit 1
fi
# shellcheck disable=SC1090
source "$bench_env"
# shellcheck source=bench/commerce_cpu_limit.sh
source "$repo_root/bench/commerce_cpu_limit.sh"

verify_checkout_and_jars() {
  local phase="$1" source_changes
  source_changes="$(git status --porcelain --untracked-files=all -- . \
    ':(exclude)bench/results/**' \
    ':(exclude)bench/.run/**')"
  if [ -n "$source_changes" ] || [ "$(git rev-parse --verify HEAD)" != "$CITYBUDDY_COMMIT" ] \
    || [ "$(openssl dgst -sha256 "$auth_jar" | awk '{print $NF}')" \
      != "$IDENTITY_JAR_SHA256" ] \
    || [ "$(openssl dgst -sha256 "$commerce_jar" | awk '{print $NF}')" \
      != "$COMMERCE_JAR_SHA256" ]; then
    echo "Seckill correctness boundary changed ($phase): checkout or JAR mismatch." >&2
    [ -z "$source_changes" ] || printf '%s\n' "$source_changes" >&2
    return 1
  fi
}

verify_fixture_containers() {
  local phase="$1"
  bench_verify_fixture_container \
    citybuddy-bench-auth /opt/citybuddy/auth.jar \
    "$AUTH_CONTAINER_ID" "$AUTH_CONTAINER_IMAGE_ID" "$AUTH_CONTAINER_STARTED_AT" \
    "$AUTH_CONTAINER_RUNNING" "$AUTH_CONTAINER_RESTART_COUNT" \
    "$AUTH_MOUNTED_JAR_SHA256" "$IDENTITY_JAR_SHA256" "$phase"
  bench_verify_fixture_container \
    citybuddy-bench-commerce /opt/citybuddy/commerce.jar \
    "$COMMERCE_CONTAINER_ID" "$COMMERCE_CONTAINER_IMAGE_ID" \
    "$COMMERCE_CONTAINER_STARTED_AT" "$COMMERCE_CONTAINER_RUNNING" \
    "$COMMERCE_CONTAINER_RESTART_COUNT" "$COMMERCE_MOUNTED_JAR_SHA256" \
    "$COMMERCE_JAR_SHA256" "$phase"
  bench_verify_dependency_container \
    citybuddy-mysql-1 "$MYSQL_CONTAINER_ID" "$MYSQL_CONTAINER_IMAGE_ID" \
    "$MYSQL_CONTAINER_STARTED_AT" "$MYSQL_CONTAINER_RUNNING" "$MYSQL_CONTAINER_RESTART_COUNT" \
    "$phase"
  bench_verify_dependency_container \
    citybuddy-redis-commerce-1 "$REDIS_COMMERCE_CONTAINER_ID" \
    "$REDIS_COMMERCE_CONTAINER_IMAGE_ID" "$REDIS_COMMERCE_CONTAINER_STARTED_AT" \
    "$REDIS_COMMERCE_CONTAINER_RUNNING" "$REDIS_COMMERCE_CONTAINER_RESTART_COUNT" "$phase"
  bench_verify_dependency_container \
    citybuddy-rocketmq-broker-proxy-1 "$ROCKETMQ_BROKER_PROXY_CONTAINER_ID" \
    "$ROCKETMQ_BROKER_PROXY_CONTAINER_IMAGE_ID" "$ROCKETMQ_BROKER_PROXY_CONTAINER_STARTED_AT" \
    "$ROCKETMQ_BROKER_PROXY_CONTAINER_RUNNING" \
    "$ROCKETMQ_BROKER_PROXY_CONTAINER_RESTART_COUNT" "$phase"
  bench_verify_dependency_container \
    citybuddy-rocketmq-namesrv-1 "$ROCKETMQ_NAMESRV_CONTAINER_ID" \
    "$ROCKETMQ_NAMESRV_CONTAINER_IMAGE_ID" "$ROCKETMQ_NAMESRV_CONTAINER_STARTED_AT" \
    "$ROCKETMQ_NAMESRV_CONTAINER_RUNNING" "$ROCKETMQ_NAMESRV_CONTAINER_RESTART_COUNT" "$phase"
  bench_verify_commerce_cpu_limit \
    citybuddy-bench-commerce \
    "$COMMERCE_CPU_LIMIT_REQUESTED_CPUS" \
    "$COMMERCE_CPU_LIMIT_OBSERVED_NANO_CPUS" \
    "$COMMERCE_CPU_LIMIT_OBSERVED_CPUSET_CPUS" \
    "$phase" >/dev/null
}

verify_run_boundary() {
  local phase="$1"
  verify_checkout_and_jars "$phase"
  verify_fixture_containers "$phase"
}

verify_run_boundary "before seckill correctness"
if [ "$BENCH_USERS" -lt "$ATTEMPTS" ]; then
  echo "Correctness needs at least $ATTEMPTS distinct benchmark users." >&2
  exit 1
fi

stage_dir=""
claim_owned=false
cleanup() {
  if [ -n "$stage_dir" ] && [[ "$stage_dir" == "$out/.correctness.${LABEL}."* ]]; then
    rm -rf -- "$stage_dir"
  fi
  if [ "$claim_owned" = true ]; then
    rmdir "$claim_dir" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
if ! mkdir "$claim_dir" 2>/dev/null; then
  echo "Another seckill correctness run owns label '$LABEL': $claim_dir" >&2
  exit 1
fi
claim_owned=true
stage_dir="$(mktemp -d "$out/.correctness.${LABEL}.XXXXXX")"
cp "$bench_env" "$stage_dir/$setup_name"

ACTIVITY="bench-correctness"
PRODUCT="bench-correctness-product"
run_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run_completed_at=""
metadata() {
  printf 'citybuddy_commit=%s\n' "$CITYBUDDY_COMMIT"
  printf 'setup_window_utc=%s/%s\n' "$SETUP_STARTED_AT_UTC" "$SETUP_COMPLETED_AT_UTC"
  printf 'run_started_at_utc=%s\n' "$run_started_at"
  [ -z "$run_completed_at" ] || printf 'run_completed_at_utc=%s\n' "$run_completed_at"
  printf 'label=%s activity=%s allocated_quota=%s attempts=%s\n' \
    "$LABEL" "$ACTIVITY" "$QUOTA" "$ATTEMPTS"
  printf 'fixture_users=%s fixture_activities=%s fixture_quota=%s fixture_stock=%s topic_suffix=%s\n' \
    "$BENCH_USERS" "$BENCH_ACTIVITIES" "$BENCH_QUOTA" "$BENCH_STOCK" "$TOPIC_SUFFIX"
  printf 'docker_cpus=%s docker_memory_bytes=%s commerce_cpu_limit_requested_cpus=%s commerce_cpu_limit_observed_nano_cpus=%s commerce_cpu_limit_observed_cpuset_cpus=%s\n' \
    "$DOCKER_CPUS" "$DOCKER_MEMORY_BYTES" "$COMMERCE_CPU_LIMIT_REQUESTED_CPUS" \
    "$COMMERCE_CPU_LIMIT_OBSERVED_NANO_CPUS" "$COMMERCE_CPU_LIMIT_OBSERVED_CPUSET_CPUS"
}
{ metadata; echo; } > "$stage_dir/$http_name"

read_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
commerce_pw="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
root_pw="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
redis_pw="$(read_value REDIS_COMMERCE_PASSWORD)"
mysql_port="$(docker port citybuddy-mysql-1 3306/tcp | cut -d: -f2)"
q() {
  MYSQL_PWD="$2" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u "$1" \
    -D commerce_db --batch "${@:3}"
}

echo "== resetting correctness fixture (quota=$QUOTA, attempts=$ATTEMPTS) =="
q root "$root_pw" -e "
DELETE FROM inventory_ledger WHERE activity_id='$ACTIVITY';
DELETE FROM seckill_order WHERE activity_id='$ACTIVITY';
DELETE FROM seckill_reservation WHERE activity_id='$ACTIVITY';
DELETE FROM seckill_activity WHERE activity_id='$ACTIVITY';
DELETE FROM product WHERE product_id='$PRODUCT';" >/dev/null
q commerce_app "$commerce_pw" -e "
INSERT INTO product (product_id, name, description, price_minor, currency, stock_quantity, available, publication_state, publication_version)
VALUES ('$PRODUCT','Correctness Product','seckill correctness fixture',1990,'CNY',1000000,TRUE,'PUBLISHED',1);" >/dev/null
q commerce_app "$commerce_pw" -e "
INSERT INTO seckill_activity (activity_id, product_id, starts_at, ends_at, state, allocated_quota, projection_version)
VALUES ('$ACTIVITY','$PRODUCT','2020-01-01 00:00:00','2035-01-01 00:00:00','ACTIVE',$QUOTA,1);" >/dev/null
for prefix in activity user rebuild; do
  docker exec citybuddy-redis-commerce-1 redis-cli -a "$redis_pw" --no-auth-warning EVAL \
    "local c='0' repeat local p=redis.call('SCAN',c,'MATCH',ARGV[1],'COUNT',200) c=p[1] if #p[2]>0 then redis.call('UNLINK',unpack(p[2])) end until c=='0' return 1" \
    0 "commerce:seckill:${prefix}:${ACTIVITY}*" >/dev/null
done
docker exec citybuddy-redis-commerce-1 redis-cli -a "$redis_pw" --no-auth-warning SET \
  "commerce:seckill:activity:$ACTIVITY" \
  "{\"activityId\":\"$ACTIVITY\",\"projectionVersion\":1,\"startsAt\":\"2020-01-01T00:00:00Z\",\"endsAt\":\"2035-01-01T00:00:00Z\",\"state\":\"ACTIVE\",\"remainingQuota\":$QUOTA}" \
  >/dev/null 2>&1
stock_before="$(q commerce_app "$commerce_pw" --skip-column-names \
  -e "SELECT stock_quantity FROM product WHERE product_id='$PRODUCT'")"

echo "== firing $ATTEMPTS concurrent reservations =="
python3 - "$ATTEMPTS" "$ACTIVITY" <<'PY' | tee -a "$stage_dir/$http_name"
import collections, concurrent.futures, json, sys, time, urllib.error, urllib.request
attempts, activity = int(sys.argv[1]), sys.argv[2]
tokens = json.load(open("bench/.run/tokens.json"))
if len(tokens) < attempts:
    raise SystemExit(f"Correctness needs {attempts} distinct tokens")
def fire(index):
    request = urllib.request.Request(
        f"http://127.0.0.1:18081/api/seckill/activities/{activity}/reservations",
        data=json.dumps({"quantity": 1, "expectedActivityVersion": 1}).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {tokens[index]}",
                 "Idempotency-Key": f"corr-{index}"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, json.load(response).get("decisionCode")
    except urllib.error.HTTPError as error:
        try: return error.code, json.load(error).get("decisionCode")
        except Exception: return error.code, "PARSE_ERROR"
    except Exception as error: return 0, type(error).__name__
started = time.time()
with concurrent.futures.ThreadPoolExecutor(max_workers=128) as executor:
    counts = collections.Counter(executor.map(fire, range(attempts)))
print(f"wall_seconds={time.time() - started:.2f}")
for (status, code), count in sorted(counts.items(), key=lambda item: -item[1]):
    print(f"HTTP {status} decision={code}: {count}")
expected = collections.Counter({(201, "ADMITTED"): 100, (409, "EXHAUSTED"): 500})
if counts != expected:
    raise SystemExit(f"Unexpected correctness decision mix: {counts}")
PY

echo "== waiting for asynchronous order creation to settle =="
admitted=0; orders=0; pending=0
for _ in $(seq 1 180); do
  admitted="$(q commerce_app "$commerce_pw" --skip-column-names -e \
    "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id='$ACTIVITY' AND decision_code='ADMITTED'")"
  orders="$(q commerce_app "$commerce_pw" --skip-column-names -e \
    "SELECT COUNT(*) FROM seckill_order WHERE activity_id='$ACTIVITY'")"
  pending="$(q commerce_app "$commerce_pw" --skip-column-names -e \
    "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id='$ACTIVITY' AND state='PENDING'")"
  if [ "$pending" = 0 ] && [ "$orders" = "$admitted" ] && [ "$orders" != 0 ]; then break; fi
  sleep 2
done
rejected="$(q commerce_app "$commerce_pw" --skip-column-names -e \
  "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id='$ACTIVITY' AND state='REJECTED' AND decision_code='EXHAUSTED'")"
if [ "$admitted" != 100 ] || [ "$orders" != 100 ] || [ "$rejected" != 500 ] || [ "$pending" != 0 ]; then
  echo "Correctness did not settle at 100 admitted/orders, 500 exhausted and zero pending." >&2
  exit 1
fi

{ metadata; printf 'stock_before=%s\n\n' "$stock_before"; } > "$stage_dir/$sql_name"
resolved_sql="$stage_dir/correctness_resolved.sql"
{ echo "-- citybuddy_commit=$CITYBUDDY_COMMIT"; \
  QUOTA="$QUOTA" ACTIVITY="$ACTIVITY" PRODUCT="$PRODUCT" STOCK_BEFORE="$stock_before" \
  envsubst < bench/sql/correctness.sql; } > "$resolved_sql"
q commerce_app "$commerce_pw" --table < "$resolved_sql" >> "$stage_dir/$sql_name"
for check in 01 02 03 04 05 06 07 08 09 10; do
  grep -Eq "^[|][[:space:]]*Q${check}[[:space:]]" "$stage_dir/$sql_name" \
    || { echo "Correctness SQL did not execute Q$check." >&2; exit 1; }
done
if grep -Eq '[|][[:space:]]*FAIL[[:space:]]*[|]' "$stage_dir/$sql_name"; then
  echo "Correctness SQL reported FAIL." >&2
  exit 1
fi
verify_run_boundary "after seckill correctness"
run_completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'run_completed_at_utc=%s\n' "$run_completed_at" >> "$stage_dir/$http_name"
printf '\nrun_completed_at_utc=%s\n' "$run_completed_at" >> "$stage_dir/$sql_name"
for name in "$http_name" "$sql_name" "$setup_name"; do
  if [ ! -s "$stage_dir/$name" ]; then
    echo "Seckill correctness evidence is missing or empty: $name" >&2
    exit 1
  fi
done
if [ -e "$bundle_dir" ]; then
  echo "Refusing to overwrite a bundle created during seckill correctness: $bundle_dir" >&2
  exit 1
fi
mv -- "$stage_dir" "$bundle_dir"
stage_dir=""
rmdir "$claim_dir"
claim_owned=false
cat "$bundle_dir/$sql_name"
