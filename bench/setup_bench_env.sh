#!/usr/bin/env bash
# Each setup owns new label-scoped fixtures; it never deletes prior transaction rows.
set -euo pipefail
umask 077
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
BENCH_WORKLOAD="${BENCH_WORKLOAD:-seckill}"
BENCH_USERS="${BENCH_USERS:-600}"
BENCH_ACTIVITIES="${BENCH_ACTIVITIES:-32}"
BENCH_QUOTA="${BENCH_QUOTA:-1000000}"
BENCH_STOCK="${BENCH_STOCK:-2000000}"
topic_suffix="${TOPIC_SUFFIX:-bench}"
if [[ ! "$topic_suffix" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "${#topic_suffix}" -gt 40 ]; then
  echo "TOPIC_SUFFIX must be 1-40 safe characters and start with an alphanumeric." >&2; exit 2
fi
case "$BENCH_WORKLOAD" in seckill|seckill-rejection|order-payment) ;; *) echo "Unknown BENCH_WORKLOAD." >&2; exit 2 ;; esac
for value in "$BENCH_USERS" "$BENCH_ACTIVITIES" "$BENCH_QUOTA" "$BENCH_STOCK"; do
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    echo "Benchmark fixture sizes must be positive ASCII integers." >&2; exit 2
  fi
done
if [ "$BENCH_WORKLOAD" = seckill-rejection ] && { [ "$BENCH_USERS" != 16704 ] || [ "$BENCH_ACTIVITIES" != 32 ] || [ "$BENCH_QUOTA" != 10 ]; }; then
  echo "Rejection fixture requires 16384 load users + 320 preparation users, 32 activities, quota 10." >&2; exit 2
fi
out_dir="$repo_root/bench/results"
run_dir="$repo_root/bench/.run"
fixture_dir="$run_dir/fixtures/$topic_suffix"
bench_env="$run_dir/bench.env"
user_prefix="bench-user-$topic_suffix-"
activity_prefix="bench-activity-$topic_suffix-"
product_id="bench-product-$topic_suffix"
normal_prefix="bench-normal-$topic_suffix-"
auth_jar="$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar"
commerce_jar="$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar"
setup_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
citybuddy_commit="$(git rev-parse --verify HEAD)"
if [ ! -f "$auth_jar" ] || [ ! -f "$commerce_jar" ]; then
  echo "Build auth-service and commerce-service JARs before benchmark setup." >&2; exit 1
fi
auth_jar_sha256="$(openssl dgst -sha256 "$auth_jar" | awk '{print $NF}')"
commerce_jar_sha256="$(openssl dgst -sha256 "$commerce_jar" | awk '{print $NF}')"
source_changes="$(git status --porcelain --untracked-files=all -- . ':(exclude)bench/results/**' ':(exclude)bench/.run/**')"
if [ -n "$source_changes" ]; then
  echo "Benchmark setup requires a source-clean checkout." >&2
  printf '%s\n' "$source_changes" >&2; exit 1
fi
read_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
commerce_pw="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
auth_pw="$(read_value MYSQL_AUTH_APP_PASSWORD)"
root_pw="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
redis_pw="$(read_value REDIS_COMMERCE_PASSWORD)"
mysql_port="$(docker port citybuddy-mysql-1 3306/tcp | cut -d: -f2)"
redis_port="$(docker port citybuddy-redis-commerce-1 6379/tcp | cut -d: -f2)"
proxy_port="$(docker port citybuddy-rocketmq-broker-proxy-1 8081/tcp | cut -d: -f2)"
sql() { MYSQL_PWD="$2" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u "$1" -D "$3" --batch --raw --skip-column-names -e "$4"; }

setup_safety() {
  local name existing running collision
  if [ "$(docker info --format '{{.NCPU}}')" != 8 ]; then
    echo "This series requires the existing 8-CPU Docker VM." >&2; return 1
  fi
  for name in citybuddy-bench-auth citybuddy-bench-commerce citybuddy-bench-k6; do
    existing="$(docker ps -aq --filter "name=^/${name}$")"
    [ -n "$existing" ] || continue
    running="$(docker inspect -f '{{.State.Running}}' "$existing")"
    if [ "$running" = true ]; then
      echo "Preserve/drain the prior result and stop $name before setup; no fixture changed." >&2; return 1
    fi
  done
  # Admission-only integration fixtures have no MQ delivery; this lifecycle owns bench activities.
  # Timeout dispatch and Redis handoffs remain global because the workers scan them globally.
  if [ "$(sql root "$root_pw" commerce_db "SELECT
      (SELECT COUNT(*) FROM seckill_reservation WHERE state IN ('PENDING','ADMITTED')
        AND BINARY LEFT(activity_id,CHAR_LENGTH('bench-activity-'))=BINARY 'bench-activity-')+
      (SELECT COUNT(*) FROM seckill_order WHERE status='UNPAID' AND timeout_dispatch_state IN ('PENDING','FAILED'));")" != 0 ] \
    || [ "$(REDISCLI_AUTH="$redis_pw" docker exec --env REDISCLI_AUTH citybuddy-redis-commerce-1 redis-cli --no-auth-warning --raw ZCARD commerce:seckill:handoff:pending)" != 0 ]; then
    echo "Prior async work is unresolved; preserve/drain it before changing the bench runtime." >&2; return 1
  fi
  if [ -e "$fixture_dir" ]; then
    echo "Fixture label already has private state; choose a new label, do not overwrite it." >&2; return 1
  fi
  collision="$(sql root "$root_pw" commerce_db "SELECT
    (SELECT COUNT(*) FROM product WHERE BINARY product_id=BINARY '$product_id' OR BINARY LEFT(product_id,CHAR_LENGTH('$normal_prefix'))=BINARY '$normal_prefix')+
    (SELECT COUNT(*) FROM seckill_activity WHERE BINARY LEFT(activity_id,CHAR_LENGTH('$activity_prefix'))=BINARY '$activity_prefix')+
    (SELECT COUNT(*) FROM auth_user_principal WHERE BINARY LEFT(subject,CHAR_LENGTH('$user_prefix'))=BINARY '$user_prefix');")"
  if [ "$collision" != 0 ]; then
    echo "Label already owns SQL rows; no deletion or reuse is allowed." >&2; return 1
  fi
}
# No signing material, user/fixture SQL or old setup record is changed before this guard.
setup_safety
mkdir -p "$out_dir" "$fixture_dir"
if [ -f "$bench_env" ]; then cp "$bench_env" "$fixture_dir/previous-setup.env"; fi
rm -f "$bench_env"
# Public signing metadata is global. Preserve the original rows once across this series;
# the normal application key files are not changed. Restore after stopping bench apps.
backup="$run_dir/bench-original-signing-metadata.sql"
current="$fixture_dir/signing-before.sql"
sql root "$root_pw" commerce_db "SET SESSION time_zone='+00:00';
SELECT CONCAT('INSERT INTO auth_signing_key_metadata (kid,state,activated_at,retire_after) VALUES (',QUOTE(kid),',',QUOTE(state),',',QUOTE(DATE_FORMAT(activated_at,'%Y-%m-%d %H:%i:%s.%f')),',',IF(retire_after IS NULL,'NULL',QUOTE(DATE_FORMAT(retire_after,'%Y-%m-%d %H:%i:%s.%f'))),');') FROM auth_signing_key_metadata ORDER BY kid;" > "$current"
if [ ! -e "$backup" ]; then
  if [ "$(sql root "$root_pw" commerce_db "SELECT COUNT(*) FROM auth_signing_key_metadata WHERE kid='bench-current';")" != 0 ]; then
    echo "Current key is already synthetic; locate/restore its original backup before a new series." >&2; exit 1
  fi
  cp "$current" "$backup"
elif ! cmp -s "$current" "$backup"; then
  if [ "$(sql root "$root_pw" commerce_db "SELECT COUNT(*)=1 AND COALESCE(SUM(kid='bench-current' AND state='CURRENT'),0)=1 FROM auth_signing_key_metadata;")" != 1 ]; then
    echo "Signing metadata differs from the saved original and from the bench key; preserve state." >&2; exit 1
  fi
fi
commerce_env_args=(--env "SPRING_DATASOURCE_PASSWORD=$commerce_pw")
normal_enabled=false
seckill_enabled=true
if [ "$BENCH_WORKLOAD" = order-payment ]; then
  normal_enabled=true; seckill_enabled=false
  payment_env="${BENCH_PAYMENT_ENV_FILE:-$fixture_dir/payment.env}"
  python3 - "$payment_env" <<'PY'
import os,re,secrets,stat,sys
from pathlib import Path
p=Path(sys.argv[1]).resolve()
if not p.exists():
    with p.open('x') as f:
        os.chmod(p,0o600)
        f.write('CITYBUDDY_MOCKPAYMENT_CALLBACKKEYID=bench-payment\n')
        f.write('CITYBUDDY_MOCKPAYMENT_CALLBACKSECRET='+secrets.token_hex(32)+'\n')
if stat.S_IMODE(p.stat().st_mode) & 0o077:
    raise SystemExit('Callback env file must be private (mode 0600).')
rows=p.read_text().splitlines()
values=dict(line.split('=',1) for line in rows if '=' in line)
if len(rows)!=2 or set(values)!={'CITYBUDDY_MOCKPAYMENT_CALLBACKKEYID','CITYBUDDY_MOCKPAYMENT_CALLBACKSECRET'}:
    raise SystemExit('Callback env file must contain only the two canonical payment keys.')
if not re.fullmatch(r'[A-Za-z0-9._-]{1,64}',values['CITYBUDDY_MOCKPAYMENT_CALLBACKKEYID']) or not re.fullmatch(r'[A-Za-z0-9._-]{32,512}',values['CITYBUDDY_MOCKPAYMENT_CALLBACKSECRET']):
    raise SystemExit('Invalid callback env value; contents withheld.')
PY
  if [ "$payment_env" != "$fixture_dir/payment.env" ]; then
    cp "$payment_env" "$fixture_dir/payment.env"
    chmod 600 "$fixture_dir/payment.env"
  fi
  commerce_env_args+=(--env-file "$fixture_dir/payment.env")
fi

tx_topic="cb060-seckill-transaction-$topic_suffix"
tx_group="cb060-seckill-order-consumer-$topic_suffix"
to_topic="cb061-seckill-timeout-$topic_suffix"
to_group="cb061-seckill-timeout-consumer-$topic_suffix"
cat_topic="cb030-catalog-$topic_suffix"
cat_group="cb030-catalog-consumer-$topic_suffix"
admin() { docker compose --project-name citybuddy --env-file .env --file compose.yaml run --rm --no-deps rocketmq-admin "$@" >/dev/null 2>&1; }
admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic "$cat_topic" --readQueueNums 4 --writeQueueNums 4
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --groupName "$cat_group" --consumeEnable true
if [ "$seckill_enabled" = true ]; then
  admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic "$tx_topic" --readQueueNums 4 --writeQueueNums 4 -a +message.type=TRANSACTION
  admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic "$to_topic" --readQueueNums 4 --writeQueueNums 4 -a +message.type=DELAY
  for g in "$tx_group" "$to_group"; do
    admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --groupName "$g" --consumeEnable true
  done
fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$fixture_dir/bench-private.pem" 2>/dev/null
openssl pkey -in "$fixture_dir/bench-private.pem" -pubout -out "$fixture_dir/bench-public.pem" 2>/dev/null
bench_password="bench-$(openssl rand -hex 8)"
bench_hash="$(printf '%s' "$bench_password" | uv run python -c 'import bcrypt,sys; print(bcrypt.hashpw(sys.stdin.buffer.read(), bcrypt.gensalt(rounds=4)).decode())')"
printf '%s' "$bench_password" > "$run_dir/bench_password"
sql root "$root_pw" commerce_db "START TRANSACTION; DELETE FROM auth_signing_key_metadata;
INSERT INTO auth_signing_key_metadata (kid,state,activated_at,retire_after) VALUES ('bench-current','CURRENT',CURRENT_TIMESTAMP(6),NULL); COMMIT;"
python3 - "$BENCH_USERS" "$bench_hash" "$user_prefix" > "$fixture_dir/users.sql" <<'PY'
import sys,uuid
n,h,prefix=int(sys.argv[1]),sys.argv[2],sys.argv[3]
ids=[str(uuid.uuid4()) for _ in range(n)]
print('START TRANSACTION;')
print('INSERT INTO auth_user_principal (principal_id,subject,login_identifier,state,permissions) VALUES')
print(',\n'.join(f"('{pid}','{prefix}{i}','{prefix}{i}','ACTIVE','catalog:read order:create seckill:reserve payment:create refund:create')" for i,pid in enumerate(ids))+';')
print('INSERT INTO auth_login_credential (principal_id,password_hash) VALUES')
print(',\n'.join(f"('{pid}','{h}')" for pid in ids)+';')
print('COMMIT;')
PY
MYSQL_PWD="$auth_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u auth_app -D commerce_db < "$fixture_dir/users.sql"
if [ "$normal_enabled" = true ]; then
  python3 - "$normal_prefix" > "$fixture_dir/products.sql" <<'PY'
import sys
prefix=sys.argv[1]
print('INSERT INTO product (product_id,name,description,price_minor,currency,stock_quantity,available,publication_state,publication_version) VALUES')
print(',\n'.join(f"('{prefix}{i:02d}','Normal benchmark {i}','ordinary order payment fixture',{1990+i},'CNY',1000,TRUE,'PUBLISHED',1)" for i in range(32))+';')
PY
  MYSQL_PWD="$commerce_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u commerce_app -D commerce_db < "$fixture_dir/products.sql"
  sql commerce_app "$commerce_pw" commerce_db "SELECT JSON_OBJECT('productId',product_id,'unitPriceMinor',price_minor,'currency',currency,'productVersion',publication_version,'initialStock',stock_quantity) FROM product WHERE BINARY LEFT(product_id,CHAR_LENGTH('$normal_prefix'))=BINARY '$normal_prefix' ORDER BY product_id;" | python3 -c 'import json,sys; print(json.dumps([json.loads(line) for line in sys.stdin if line.strip()]))' > "$fixture_dir/products.json"
else
  sql commerce_app "$commerce_pw" commerce_db "INSERT INTO product (product_id,name,description,price_minor,currency,stock_quantity,available,publication_state,publication_version) VALUES ('$product_id','Bench Product','seckill benchmark fixture',1990,'CNY',$BENCH_STOCK,TRUE,'PUBLISHED',1);"
  python3 - "$BENCH_ACTIVITIES" "$BENCH_QUOTA" "$activity_prefix" "$product_id" > "$fixture_dir/activities.sql" <<'PY'
import sys
n,q,prefix,product=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3],sys.argv[4]
print('INSERT INTO seckill_activity (activity_id,product_id,starts_at,ends_at,state,allocated_quota,projection_version) VALUES')
print(',\n'.join(f"('{prefix}{i}','{product}','2020-01-01 00:00:00','2035-01-01 00:00:00','ACTIVE',{q},1)" for i in range(n))+';')
PY
  MYSQL_PWD="$commerce_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u commerce_app -D commerce_db < "$fixture_dir/activities.sql"
  for i in $(seq 0 $((BENCH_ACTIVITIES - 1))); do
    REDISCLI_AUTH="$redis_pw" docker exec --env REDISCLI_AUTH citybuddy-redis-commerce-1 redis-cli --no-auth-warning SET "commerce:seckill:activity:$activity_prefix$i" \
      "{\"activityId\":\"$activity_prefix$i\",\"projectionVersion\":1,\"startsAt\":\"2020-01-01T00:00:00Z\",\"endsAt\":\"2035-01-01T00:00:00Z\",\"startsAtEpochMicros\":1577836800000000,\"endsAtEpochMicros\":2051222400000000,\"state\":\"ACTIVE\",\"remainingQuota\":$BENCH_QUOTA}" >/dev/null
  done
fi

echo "== starting auth-service in the compose network =="
docker rm citybuddy-bench-auth citybuddy-bench-commerce >/dev/null 2>&1 || true
docker run --detach --name citybuddy-bench-auth \
  --network citybuddy_default \
  --publish 127.0.0.1:18080:8080 \
  --volume "$auth_jar:/opt/citybuddy/auth.jar:ro" \
  --volume "$fixture_dir/bench-private.pem:/opt/citybuddy/bench-private.pem:ro" \
  --volume "$fixture_dir/bench-public.pem:/opt/citybuddy/bench-public.pem:ro" \
  --env SPRING_DATASOURCE_PASSWORD="$auth_pw" \
  eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd \
  java -jar /opt/citybuddy/auth.jar \
  --server.port=8080 \
  --spring.datasource.url='jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true' \
  --spring.datasource.username=auth_app \
  --spring.datasource.hikari.maximum-pool-size=32 \
  --citybuddy.identity.enabled=true \
  --citybuddy.identity.issuer=https://identity.citybuddy.test \
  --citybuddy.identity.user-audience=citybuddy-web \
  --citybuddy.identity.current-kid=bench-current \
  --citybuddy.identity.current-private-key-path=/opt/citybuddy/bench-private.pem \
  --citybuddy.identity.current-public-key-path=/opt/citybuddy/bench-public.pem >/dev/null

ready_deadline=$((SECONDS + 90))
until curl --max-time 2 -sf http://127.0.0.1:18080/auth/jwks >/dev/null 2>&1; do
  if [ "$SECONDS" -ge "$ready_deadline" ]; then echo "Auth readiness timed out; keep fixture for diagnosis." >&2; exit 1; fi
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-auth)" != "true" ]; then
    docker logs --tail 30 citybuddy-bench-auth; exit 1
  fi
  sleep 1
done
echo "auth-service ready on 18080"

echo "== starting commerce-service in the compose network =="
docker run --detach --name citybuddy-bench-commerce \
  --network citybuddy_default \
  --publish 127.0.0.1:18081:8080 \
  --cpus 4 \
  --volume "$commerce_jar:/opt/citybuddy/commerce.jar:ro" \
  "${commerce_env_args[@]}" \
  eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd \
  java -XX:MaxRAMPercentage=70 -jar /opt/citybuddy/commerce.jar \
  --server.port=8080 \
  --spring.datasource.url='jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true' \
  --spring.datasource.username=commerce_app \
  --spring.datasource.hikari.maximum-pool-size=64 \
  --spring.data.redis.url="redis://:$redis_pw@redis-commerce:6379/0" \
  --citybuddy.catalog.enabled=true \
  --citybuddy.catalog.issuer=https://identity.citybuddy.test \
  --citybuddy.catalog.user-audience=citybuddy-web \
  --citybuddy.catalog.jwks-url=http://citybuddy-bench-auth:8080/auth/jwks \
  --citybuddy.catalog.jwks-cache-ttl=300s \
  --citybuddy.catalog.clock-skew=30s \
  --citybuddy.catalog.required-permission=catalog:read \
  --citybuddy.catalog.cache-ttl=30s \
  --citybuddy.catalog.cache-jitter=10s \
  --citybuddy.catalog.null-ttl=3s \
  --citybuddy.catalog.mutex-ttl=2s \
  --citybuddy.catalog.worker-initial-delay-ms=3600000 \
  --citybuddy.catalog.worker-delay-ms=3600000 \
  --citybuddy.catalog.rocketmq-endpoints=rocketmq-broker-proxy:8081 \
  --citybuddy.catalog.rocketmq-topic="$cat_topic" \
  --citybuddy.catalog.rocketmq-consumer-group="$cat_group" \
  --citybuddy.orders.enabled="$normal_enabled" \
  --citybuddy.mock-payment.enabled="$normal_enabled" \
  --citybuddy.seckill.enabled="$seckill_enabled" \
  --citybuddy.seckill.order.enabled="$seckill_enabled" \
  --citybuddy.seckill.order.rocketmq-endpoints=rocketmq-broker-proxy:8081 \
  --citybuddy.seckill.order.rocketmq-topic="$tx_topic" \
  --citybuddy.seckill.order.rocketmq-consumer-group="$tx_group" \
  --citybuddy.seckill.order.worker-initial-delay-ms=1000 \
  --citybuddy.seckill.order.worker-delay-ms=10 \
  --citybuddy.seckill.order.receive-batch-size=32 \
  --citybuddy.seckill.order.resolution-worker-initial-delay=2000 \
  --citybuddy.seckill.order.resolution-worker-delay=1000 \
  --citybuddy.seckill.order.receive-await=1s \
  --citybuddy.seckill.order.receive-invisible-duration=10s \
  --citybuddy.seckill.order.unpaid-timeout=15m \
  --citybuddy.seckill.timeout.enabled="$seckill_enabled" \
  --citybuddy.seckill.timeout.dispatch-worker-delay-ms=10 \
  --citybuddy.seckill.timeout.consumer-worker-delay-ms=50 \
  --citybuddy.seckill.timeout.rocketmq-endpoints=rocketmq-broker-proxy:8081 \
  --citybuddy.seckill.timeout.rocketmq-topic="$to_topic" \
  --citybuddy.seckill.timeout.rocketmq-consumer-group="$to_group" >/dev/null

ready_deadline=$((SECONDS + 90))
until [ "$(curl --max-time 2 -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18081/api/products || true)" = "401" ]; do
  if [ "$SECONDS" -ge "$ready_deadline" ]; then echo "Commerce readiness timed out; keep fixture for diagnosis." >&2; exit 1; fi
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-commerce)" != "true" ]; then
    docker logs --tail 40 citybuddy-bench-commerce; exit 1
  fi
  sleep 1
done
echo "commerce-service ready on 18081"

echo "== minting $BENCH_USERS tokens (setup, excluded from measurement) =="
BENCH_LOGIN_PASSWORD="$bench_password" python3 - "$BENCH_USERS" "$user_prefix" "$run_dir/tokens.json" <<'PY'
import json, os, sys, urllib.request, concurrent.futures
n, prefix, out = int(sys.argv[1]), sys.argv[2], sys.argv[3]
pw = os.environ["BENCH_LOGIN_PASSWORD"]
def login(i):
    body = json.dumps({"loginIdentifier": f"{prefix}{i}", "password": pw}).encode()
    req = urllib.request.Request("http://127.0.0.1:18080/auth/login", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)["accessToken"]
with concurrent.futures.ThreadPoolExecutor(max_workers=16) as ex:
    tokens = list(ex.map(login, range(n)))
json.dump(tokens, open(out, "w"))
print(f"minted {len(tokens)} tokens")
PY

if [ "$(git rev-parse --verify HEAD)" != "$citybuddy_commit" ]; then
  echo "CityBuddy HEAD changed during benchmark setup." >&2
  exit 1
fi
setup_completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
docker_cpus="$(docker info --format '{{.NCPU}}')"
docker_memory_bytes="$(docker info --format '{{.MemTotal}}')"
cat > "$bench_env" <<EOF
MYSQL_PORT=$mysql_port
REDIS_PORT=$redis_port
PROXY_PORT=$proxy_port
COMMERCE_URL=http://127.0.0.1:18081
AUTH_URL=http://127.0.0.1:18080
BENCH_WORKLOAD=$BENCH_WORKLOAD
BENCH_USERS=$BENCH_USERS
USER_PREFIX=$user_prefix
ACTIVITY_PREFIX=$activity_prefix
BENCH_PRODUCT_ID=$product_id
FIXTURE_REL=fixtures/$topic_suffix
NORMAL_LABEL=$topic_suffix
NORMAL_SKU_COUNT=32
NORMAL_STOCK_PER_SKU=1000
BENCH_ACTIVITIES=$BENCH_ACTIVITIES
BENCH_QUOTA=$BENCH_QUOTA
BENCH_STOCK=$BENCH_STOCK
TOPIC_SUFFIX=$topic_suffix
CITYBUDDY_COMMIT=$citybuddy_commit
IDENTITY_JAR_SHA256=$auth_jar_sha256
COMMERCE_JAR_SHA256=$commerce_jar_sha256
SETUP_STARTED_AT_UTC=$setup_started_at
SETUP_COMPLETED_AT_UTC=$setup_completed_at
DOCKER_CPUS=$docker_cpus
DOCKER_MEMORY_BYTES=$docker_memory_bytes
COMMERCE_CPU_LIMIT=4
ORDER_WORKER_DELAY_MS=10
ORDER_RECEIVE_BATCH_SIZE=32
TIMEOUT_DISPATCH_WORKER_DELAY_MS=50
EOF
cp "$bench_env" "$fixture_dir/setup.env"
echo "== bench environment ready =="
echo "setup record: $bench_env"
