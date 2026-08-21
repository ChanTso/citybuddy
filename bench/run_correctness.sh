#!/usr/bin/env bash
# Correctness phase: drive contention far above the quota on ONE activity, then assert the
# authoritative MySQL invariants. Hardware-independent; run before any throughput work.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$repo_root"
run_dir="$repo_root/bench/.run"; out="$repo_root/bench/results"; mkdir -p "$out"

QUOTA="${QUOTA:-100}"
ATTEMPTS="${ATTEMPTS:-600}"
ACTIVITY="bench-correctness"

read_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
commerce_pw="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"; root_pw="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
redis_pw="$(read_value REDIS_COMMERCE_PASSWORD)"
mysql_port="$(docker port citybuddy-mysql-1 3306/tcp | cut -d: -f2)"
q() { MYSQL_PWD="$2" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u "$1" -D commerce_db --batch "${@:3}"; }

echo "== resetting correctness fixture (quota=$QUOTA, attempts=$ATTEMPTS) =="
q root "$root_pw" -e "
DELETE FROM inventory_ledger WHERE activity_id='$ACTIVITY';
DELETE FROM seckill_order WHERE activity_id='$ACTIVITY';
DELETE FROM seckill_reservation WHERE activity_id='$ACTIVITY';
DELETE FROM seckill_activity WHERE activity_id='$ACTIVITY';
UPDATE product SET stock_quantity=1000000 WHERE product_id='bench-product';" >/dev/null
q commerce_app "$commerce_pw" -e "
INSERT INTO seckill_activity (activity_id, product_id, starts_at, ends_at, state, allocated_quota, projection_version)
VALUES ('$ACTIVITY','bench-product','2020-01-01 00:00:00','2035-01-01 00:00:00','ACTIVE',$QUOTA,1);" >/dev/null
docker exec citybuddy-redis-commerce-1 redis-cli -a "$redis_pw" --no-auth-warning DEL \
  "commerce:seckill:activity:$ACTIVITY" >/dev/null 2>&1
docker exec citybuddy-redis-commerce-1 redis-cli -a "$redis_pw" --no-auth-warning SET \
  "commerce:seckill:activity:$ACTIVITY" \
  "{\"activityId\":\"$ACTIVITY\",\"projectionVersion\":1,\"startsAt\":\"2020-01-01T00:00:00Z\",\"endsAt\":\"2035-01-01T00:00:00Z\",\"state\":\"ACTIVE\",\"remainingQuota\":$QUOTA}" >/dev/null 2>&1
stock_before="$(q commerce_app "$commerce_pw" --skip-column-names -e "SELECT stock_quantity FROM product WHERE product_id='bench-product'")"
echo "stock before: $stock_before"

echo "== firing $ATTEMPTS concurrent reservations at one activity with quota $QUOTA =="
python3 - "$ATTEMPTS" "$ACTIVITY" "$out/correctness_http.txt" <<'PY'
import json, sys, urllib.request, urllib.error, collections, concurrent.futures, time
attempts, activity, out = int(sys.argv[1]), sys.argv[2], sys.argv[3]
tokens = json.load(open("bench/.run/tokens.json"))
def fire(i):
    body = json.dumps({"quantity": 1, "expectedActivityVersion": 1}).encode()
    req = urllib.request.Request(
        f"http://127.0.0.1:18081/api/seckill/activities/{activity}/reservations", data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {tokens[i % len(tokens)]}",
                 "Idempotency-Key": f"corr-{i}"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.load(r).get("decisionCode")
    except urllib.error.HTTPError as e:
        try: return e.code, json.load(e).get("decisionCode")
        except Exception: return e.code, "PARSE_ERROR"
    except Exception as e:
        return 0, type(e).__name__
start = time.time()
with concurrent.futures.ThreadPoolExecutor(max_workers=128) as ex:
    results = list(ex.map(fire, range(attempts)))
elapsed = time.time() - start
counts = collections.Counter(results)
lines = [f"attempts={attempts} wall_seconds={elapsed:.2f}"]
for (status, code), n in sorted(counts.items(), key=lambda x: -x[1]):
    lines.append(f"HTTP {status} decision={code}: {n}")
text = "\n".join(lines)
print(text); open(out, "w").write(text + "\n")
PY

echo "== waiting for asynchronous order creation to settle =="
prev=-1; stable=0
for _ in $(seq 1 90); do
  cur="$(q commerce_app "$commerce_pw" --skip-column-names -e "SELECT COUNT(*) FROM seckill_order WHERE activity_id='$ACTIVITY'")"
  pend="$(q commerce_app "$commerce_pw" --skip-column-names -e "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id='$ACTIVITY' AND state='PENDING'")"
  if [ "$cur" = "$prev" ] && [ "$pend" = "0" ]; then stable=$((stable+1)); else stable=0; fi
  prev="$cur"
  [ "$stable" -ge 3 ] && break
  sleep 2
done
echo "orders settled at: $prev (pending reservations: $pend)"

echo "== authoritative correctness queries =="
{
  echo "CityBuddy seckill correctness — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "activity=$ACTIVITY allocated_quota=$QUOTA attempts=$ATTEMPTS stock_before=$stock_before"
  echo
} > "$out/correctness_sql.txt"
QUOTA="$QUOTA" ACTIVITY="$ACTIVITY" STOCK_BEFORE="$stock_before" \
  envsubst < bench/sql/correctness.sql > "$run_dir/correctness_resolved.sql"
q commerce_app "$commerce_pw" --table < "$run_dir/correctness_resolved.sql" >> "$out/correctness_sql.txt"
cat "$out/correctness_sql.txt"
