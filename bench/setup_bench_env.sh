#!/usr/bin/env bash
# Brings up the seckill benchmark fixture: users, activities, auth-service, commerce-service,
# and a pre-minted direct-user token pool. Login and seeding are setup, not measured work.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

BENCH_USERS="${BENCH_USERS:-600}"
BENCH_ACTIVITIES="${BENCH_ACTIVITIES:-32}"
BENCH_QUOTA="${BENCH_QUOTA:-1000000}"
BENCH_STOCK="${BENCH_STOCK:-2000000}"
out_dir="$repo_root/bench/results"
run_dir="$repo_root/bench/.run"
mkdir -p "$out_dir" "$run_dir"
bench_env="$run_dir/bench.env"
auth_jar="$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar"
commerce_jar="$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar"
setup_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

clear_synthetic_redis_keys() {
  local prefix
  for prefix in activity user rebuild; do
    docker exec citybuddy-redis-commerce-1 redis-cli -a "$redis_pw" --no-auth-warning EVAL \
      "local c='0' repeat local p=redis.call('SCAN',c,'MATCH',ARGV[1],'COUNT',200) c=p[1] if #p[2]>0 then redis.call('UNLINK',unpack(p[2])) end until c=='0' return 1" \
      0 "commerce:seckill:${prefix}:bench-activity-*" >/dev/null
  done
}

rm -f "$bench_env"
for value in "$BENCH_USERS" "$BENCH_ACTIVITIES" "$BENCH_QUOTA" "$BENCH_STOCK"; do
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    echo "Benchmark fixture sizes must be positive ASCII integers." >&2
    exit 2
  fi
done

citybuddy_commit="$(git rev-parse --verify HEAD)"
if [ ! -f "$auth_jar" ] || [ ! -f "$commerce_jar" ]; then
  echo "Build auth-service and commerce-service JARs before benchmark setup." >&2
  exit 1
fi
auth_jar_sha256="$(openssl dgst -sha256 "$auth_jar" | awk '{print $NF}')"
commerce_jar_sha256="$(openssl dgst -sha256 "$commerce_jar" | awk '{print $NF}')"
source_changes="$(git status --porcelain --untracked-files=all -- . \
  ':(exclude)bench/results/**' \
  ':(exclude)bench/.run/**')"
if [ -n "$source_changes" ]; then
  echo "Seckill benchmark setup requires a source-clean checkout." >&2
  printf '%s\n' "$source_changes" >&2
  exit 1
fi

read_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
commerce_pw="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
auth_pw="$(read_value MYSQL_AUTH_APP_PASSWORD)"
root_pw="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
redis_pw="$(read_value REDIS_COMMERCE_PASSWORD)"

mysql_port="$(docker port citybuddy-mysql-1 3306/tcp | cut -d: -f2)"
redis_port="$(docker port citybuddy-redis-commerce-1 6379/tcp | cut -d: -f2)"
proxy_port="$(docker port citybuddy-rocketmq-broker-proxy-1 8081/tcp | cut -d: -f2)"

sql() { MYSQL_PWD="$2" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u "$1" -D "$3" --batch --skip-column-names -e "$4"; }

# A fresh suffix gives a clean queue; reusing one leaves a prior run's backlog in front
# of the next run's messages.
topic_suffix="${TOPIC_SUFFIX:-bench}"
if [[ ! "$topic_suffix" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "${#topic_suffix}" -gt 64 ]; then
  echo "TOPIC_SUFFIX must be 1-64 safe characters and start with an alphanumeric." >&2
  exit 2
fi
tx_topic="cb060-seckill-transaction-$topic_suffix"
tx_group="cb060-seckill-order-consumer-$topic_suffix"
to_topic="cb061-seckill-timeout-$topic_suffix"
to_group="cb061-seckill-timeout-consumer-$topic_suffix"
cat_topic="cb030-catalog-$topic_suffix"
cat_group="cb030-catalog-consumer-$topic_suffix"

echo "== creating RocketMQ topics =="
admin() { docker compose --project-name citybuddy --env-file .env --file compose.yaml run --rm --no-deps rocketmq-admin "$@" >/dev/null 2>&1; }
# RocketMQ 5 topics must declare the message type they accept.
admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic "$tx_topic" --readQueueNums 4 --writeQueueNums 4 -a +message.type=TRANSACTION || true
admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic "$to_topic" --readQueueNums 4 --writeQueueNums 4 -a +message.type=DELAY || true
admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic "$cat_topic" --readQueueNums 4 --writeQueueNums 4 || true
for g in "$tx_group" "$to_group" "$cat_group"; do
  admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --groupName "$g" --consumeEnable true || true
done

echo "== generating signing key =="
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$run_dir/bench-private.pem" 2>/dev/null
openssl pkey -in "$run_dir/bench-private.pem" -pubout -out "$run_dir/bench-public.pem" 2>/dev/null

echo "== seeding $BENCH_USERS users, $BENCH_ACTIVITIES activities =="
# One shared low-cost bcrypt verifier: login is excluded from every measured window, so the
# work factor only affects setup time.
bench_password="bench-$(openssl rand -hex 8)"
bench_hash="$(uv run python -c "import bcrypt,sys; print(bcrypt.hashpw(sys.argv[1].encode(), bcrypt.gensalt(rounds=4)).decode())" "$bench_password")"
printf '%s' "$bench_password" > "$run_dir/bench_password"

# The runtime accounts hold no DELETE grant by design, so fixture teardown uses bootstrap.
sql root "$root_pw" commerce_db "
DELETE c FROM auth_login_credential c JOIN auth_user_principal p USING (principal_id)
  WHERE p.subject LIKE 'bench-user-%';
DELETE FROM auth_user_principal WHERE subject LIKE 'bench-user-%';
-- Every row here is published, and auth fails the whole JWKS document when any published kid has
-- no configured runtime key, so a row left behind by another local fixture is not survivable.
DELETE FROM auth_signing_key_metadata;"
sql auth_app "$auth_pw" commerce_db "
INSERT INTO auth_signing_key_metadata (kid, state, activated_at, retire_after)
VALUES ('bench-current', 'CURRENT', CURRENT_TIMESTAMP(6), NULL);"

python3 - "$BENCH_USERS" "$bench_hash" > "$run_dir/users.sql" <<'PY'
import sys, uuid
n, h = int(sys.argv[1]), sys.argv[2]
print("INSERT INTO auth_user_principal (principal_id, subject, login_identifier, state, permissions) VALUES")
rows=[]
ids=[]
for i in range(n):
    pid = f"9{uuid.uuid4().hex[1:8]}-0000-4000-8000-{i:012d}"
    ids.append(pid)
    rows.append(f"('{pid}','bench-user-{i}','bench-user-{i}','ACTIVE','catalog:read order:create seckill:reserve payment:create refund:create')")
print(",\n".join(rows) + ";")
print("INSERT INTO auth_login_credential (principal_id, password_hash) VALUES")
print(",\n".join(f"('{p}','{h}')" for p in ids) + ";")
PY
MYSQL_PWD="$auth_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u auth_app -D commerce_db < "$run_dir/users.sql"

sql root "$root_pw" commerce_db "
DELETE FROM inventory_ledger WHERE activity_id LIKE 'bench-%';
DELETE FROM seckill_order WHERE activity_id LIKE 'bench-%';
DELETE FROM seckill_reservation WHERE activity_id LIKE 'bench-%';
DELETE FROM seckill_activity WHERE activity_id LIKE 'bench-%';
DELETE FROM product WHERE product_id = 'bench-product';"
echo "== clearing prior synthetic Redis activity and user markers =="
clear_synthetic_redis_keys
sql commerce_app "$commerce_pw" commerce_db "
INSERT INTO product (product_id, name, description, price_minor, currency, stock_quantity, available, publication_state, publication_version)
VALUES ('bench-product','Bench Product','seckill benchmark fixture',1990,'CNY',$BENCH_STOCK,TRUE,'PUBLISHED',1);"

python3 - "$BENCH_ACTIVITIES" "$BENCH_QUOTA" > "$run_dir/activities.sql" <<'PY'
import sys
n, q = int(sys.argv[1]), int(sys.argv[2])
rows = [f"('bench-activity-{i}','bench-product','2020-01-01 00:00:00','2035-01-01 00:00:00','ACTIVE',{q},1)" for i in range(n)]
print("INSERT INTO seckill_activity (activity_id, product_id, starts_at, ends_at, state, allocated_quota, projection_version) VALUES")
print(",\n".join(rows) + ";")
PY
MYSQL_PWD="$commerce_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u commerce_app -D commerce_db < "$run_dir/activities.sql"

echo "== publishing Redis activity projections =="
# Activities are seeded straight into MySQL, so the Redis admission projection that the Lua
# script reads has to be published alongside them. startsAt/endsAt must match Instant.toString().
for i in $(seq 0 $((BENCH_ACTIVITIES - 1))); do
  docker exec citybuddy-redis-commerce-1 redis-cli -a "$redis_pw" --no-auth-warning \
    SET "commerce:seckill:activity:bench-activity-$i" \
    "{\"activityId\":\"bench-activity-$i\",\"projectionVersion\":1,\"startsAt\":\"2020-01-01T00:00:00Z\",\"endsAt\":\"2035-01-01T00:00:00Z\",\"state\":\"ACTIVE\",\"remainingQuota\":$BENCH_QUOTA}" >/dev/null 2>&1
done

echo "== starting auth-service in the compose network =="
docker rm -f citybuddy-bench-auth citybuddy-bench-commerce >/dev/null 2>&1 || true
docker run --detach --name citybuddy-bench-auth \
  --network citybuddy_default \
  --publish 127.0.0.1:18080:8080 \
  --volume "$auth_jar:/opt/citybuddy/auth.jar:ro" \
  --volume "$run_dir/bench-private.pem:/opt/citybuddy/bench-private.pem:ro" \
  --volume "$run_dir/bench-public.pem:/opt/citybuddy/bench-public.pem:ro" \
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

until curl -sf http://127.0.0.1:18080/auth/jwks >/dev/null 2>&1; do
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
  --env SPRING_DATASOURCE_PASSWORD="$commerce_pw" \
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
  --citybuddy.seckill.enabled=true \
  --citybuddy.seckill.order.enabled=true \
  --citybuddy.seckill.order.rocketmq-endpoints=rocketmq-broker-proxy:8081 \
  --citybuddy.seckill.order.rocketmq-topic="$tx_topic" \
  --citybuddy.seckill.order.rocketmq-consumer-group="$tx_group" \
  --citybuddy.seckill.order.worker-initial-delay-ms=1000 \
  --citybuddy.seckill.order.worker-delay-ms=500 \
  --citybuddy.seckill.order.resolution-worker-initial-delay=2000 \
  --citybuddy.seckill.order.resolution-worker-delay=1000 \
  --citybuddy.seckill.order.receive-await=1s \
  --citybuddy.seckill.order.receive-invisible-duration=10s \
  --citybuddy.seckill.order.unpaid-timeout=15m \
  --citybuddy.seckill.timeout.rocketmq-endpoints=rocketmq-broker-proxy:8081 \
  --citybuddy.seckill.timeout.rocketmq-topic="$to_topic" \
  --citybuddy.seckill.timeout.rocketmq-consumer-group="$to_group" >/dev/null

until [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18081/api/products)" != "000" ]; do
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-commerce)" != "true" ]; then
    docker logs --tail 40 citybuddy-bench-commerce; exit 1
  fi
  sleep 1
done
echo "commerce-service ready on 18081"

echo "== minting $BENCH_USERS tokens (setup, excluded from measurement) =="
python3 - "$BENCH_USERS" "$bench_password" "$run_dir/tokens.json" <<'PY'
import json, sys, urllib.request, concurrent.futures
n, pw, out = int(sys.argv[1]), sys.argv[2], sys.argv[3]
def login(i):
    body = json.dumps({"loginIdentifier": f"bench-user-{i}", "password": pw}).encode()
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
BENCH_USERS=$BENCH_USERS
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
EOF
echo "== bench environment ready =="
echo "setup record: $bench_env"
