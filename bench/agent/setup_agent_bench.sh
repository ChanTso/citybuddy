#!/usr/bin/env bash
# Brings up the agent three-path fixture: users that may open a support session and chat, one
# paid order per user for the action path, the knowledge index the retrieval path reads, the
# deterministic model fixture, and a pre-created session pool.
#
# Everything here is setup and is excluded from every measured window: tokens are minted, orders
# are paid and their callbacks settled, and sessions are opened before the generator starts.
#
# The agent binds 127.0.0.1 (agent-service/src/citybuddy_agent/__main__.py), so it cannot be
# reached across a Docker network, and publishing a port does not help either: the forwarder
# connects to the container's bridge address, where nothing is listening. Rather than change
# production code or insert a proxy hop into the path being measured, the agent, the model
# fixture, the fixture builder and the k6 generator all join one network namespace held by a
# placeholder container and reach each other over loopback.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# One user, one paid order and one session per pool entry, so the ladder never reuses any of
# them. Size this past the total iteration count of the longest ladder to be run.
AGENT_BENCH_USERS="${AGENT_BENCH_USERS:-6000}"
AGENT_ATTEMPT_BUDGET="${AGENT_ATTEMPT_BUDGET:-16}"
run_dir="$repo_root/bench/.run"
mkdir -p "$run_dir"
commit_file="$run_dir/citybuddy_commit"

source_changes() {
  git status --porcelain --untracked-files=all -- . \
    ':(exclude)bench/results/**' \
    ':(exclude)bench/.run/**'
}
changes="$(source_changes)"
if [ -n "$changes" ]; then
  echo "The agent benchmark requires a committed, source-clean tree:" >&2
  printf '%s\n' "$changes" >&2
  exit 1
fi
citybuddy_commit="$(git rev-parse --verify HEAD)"
# An interrupted setup must not leave a previous image's commit looking current.
: > "$commit_file"

read_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
commerce_pw="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
auth_pw="$(read_value MYSQL_AUTH_APP_PASSWORD)"
agent_pw="$(read_value MYSQL_AGENT_APP_PASSWORD)"
root_pw="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
redis_pw="$(read_value REDIS_COMMERCE_PASSWORD)"

mysql_port="$(docker port citybuddy-mysql-1 3306/tcp | cut -d: -f2)"
es_port="$(docker port citybuddy-elasticsearch-1 9200/tcp | cut -d: -f2)"
sql() { MYSQL_PWD="$2" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u "$1" -D "$3" --batch --skip-column-names -e "$4"; }

# Reused across runs so a rerun does not invalidate tokens already minted for a live agent.
agent_secret_file="$run_dir/agent_service_secret"
[ -f "$agent_secret_file" ] || printf 'bench-agent-%s' "$(openssl rand -hex 12)" > "$agent_secret_file"
agent_secret="$(cat "$agent_secret_file")"
payment_file="$run_dir/mock_payment"
[ -f "$payment_file" ] || printf 'bench-callback-key\nbench-%s\n' "$(openssl rand -hex 16)" > "$payment_file"
payment_key_id="$(sed -n 1p "$payment_file")"
payment_secret="$(sed -n 2p "$payment_file")"

topic_suffix="${TOPIC_SUFFIX:-bench}"
cat_topic="cb030-catalog-$topic_suffix"
cat_group="cb030-catalog-consumer-$topic_suffix"

echo "== granting support permissions to $AGENT_BENCH_USERS bench users =="
# setup_bench_env.sh seeds the seckill permission set; the agent paths additionally need to open
# a session and to chat, and the action path needs refund:create to survive the token exchange.
sql auth_app "$auth_pw" commerce_db "
UPDATE auth_user_principal
   SET permissions = 'catalog:read order:create seckill:reserve payment:create refund:create support:session:create support:chat'
 WHERE subject LIKE 'bench-user-%'
   AND CAST(SUBSTRING(subject, 12) AS UNSIGNED) < $AGENT_BENCH_USERS;"

# The on-behalf-of exchange authenticates the agent as this client, and both auth-service and
# commerce-service pin the id, so it is one row the whole local topology shares. Seeded here
# rather than assumed to exist: nothing else in the repository creates it, and any other fixture
# that needs the same id replaces it with a secret this rig does not hold.
echo "== registering the agent service identity =="
agent_service_hash="$(uv run python scripts/hash_test_credential.py "$agent_secret")"
sql root "$root_pw" commerce_db "DELETE FROM auth_service_identity WHERE client_id = 'agent-service';"
sql auth_app "$auth_pw" commerce_db "
INSERT INTO auth_service_identity (service_id, client_id, credential_hash, state, allowed_scopes)
VALUES ('00000000-0000-0000-0000-0000000009a3', 'agent-service', '$agent_service_hash', 'ACTIVE',
        'catalog:read refund:create');"

echo "== stopping the previous bench services =="
# Before the fixture is cleared, not after: a previous ladder's collapsed step can still have
# turns in flight, and a turn that lands during teardown writes rows that the delete has already
# passed, leaving retrieval decisions behind that block the support_turn delete.
docker rm -f citybuddy-bench-k6 citybuddy-bench-agent citybuddy-bench-model citybuddy-bench-net \
  citybuddy-bench-auth citybuddy-bench-commerce >/dev/null 2>&1 || true

echo "== clearing the previous agent fixture =="
mysql_bootstrap() { MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root -D "$1"; }
mysql_bootstrap commerce_db < bench/agent/sql/reset_commerce_fixture.sql
mysql_bootstrap cs_db < bench/agent/sql/reset_support_fixture.sql

echo "== building the agent image =="
docker build --quiet --file bench/agent/Dockerfile --tag citybuddy-bench-agent:local . >/dev/null

echo "== bootstrapping the knowledge index =="
# The retrieval path resolves an alias, validates the mapping, then runs BM25 and dense retrieval
# and a rerank. Without a real index it fails closed with retrieval_denied and measures nothing.
uv run citybuddy-indexer bootstrap \
  --elasticsearch-url "http://127.0.0.1:$es_port" \
  --index knowledge_docs_v1 --alias knowledge_docs_read >/dev/null 2>&1 || true

echo "== starting auth-service =="
docker run --detach --name citybuddy-bench-auth \
  --network citybuddy_default \
  --publish 127.0.0.1:18080:8080 \
  --volume "$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar:/opt/citybuddy/auth.jar:ro" \
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
  --citybuddy.identity.current-public-key-path=/opt/citybuddy/bench-public.pem \
  --citybuddy.identity.exchange-scopes[0]=catalog:read \
  --citybuddy.identity.exchange-scopes[1]=refund:create >/dev/null

until curl -sf http://127.0.0.1:18080/auth/jwks >/dev/null 2>&1; do
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-auth)" != "true" ]; then
    docker logs --tail 30 citybuddy-bench-auth; exit 1
  fi
  sleep 1
done
echo "auth-service ready on 18080"

echo "== starting commerce-service =="
docker run --detach --name citybuddy-bench-commerce \
  --network citybuddy_default \
  --publish 127.0.0.1:18081:8080 \
  --cpus 4 \
  --volume "$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar:/opt/citybuddy/commerce.jar:ro" \
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
  --citybuddy.orders.enabled=true \
  --citybuddy.mock-payment.enabled=true \
  --citybuddy.mock-payment.required-permission=support:chat \
  --citybuddy.mock-payment.callback-key-id="$payment_key_id" \
  --citybuddy.mock-payment.callback-secret="$payment_secret" \
  --citybuddy.mock-payment.callback-maximum-age=5m \
  --citybuddy.mock-payment.callback-clock-skew=30s \
  --citybuddy.obo.enabled=true \
  --citybuddy.obo.issuer=https://identity.citybuddy.test \
  --citybuddy.obo.jwks-url=http://citybuddy-bench-auth:8080/auth/jwks \
  --citybuddy.obo.jwks-cache-ttl=300s \
  --citybuddy.agent-tools.enabled=true \
  --citybuddy.refund.enabled=true \
  --citybuddy.refund.required-permission=refund:create \
  --citybuddy.refund.lock-wait-timeout-seconds=1 \
  --citybuddy.refund.maximum-observation-attempts=2 \
  --citybuddy.refund.observation-backoff=25ms \
  --citybuddy.actions.enabled=true \
  --citybuddy.actions.required-scope=refund:create \
  --citybuddy.actions.pending-ttl=15m \
  --citybuddy.actions.lock-wait-timeout-seconds=1 \
  --citybuddy.actions.maximum-observation-attempts=2 \
  --citybuddy.actions.observation-backoff=25ms >/dev/null

until [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18081/api/products)" != "000" ]; do
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-commerce)" != "true" ]; then
    docker logs --tail 40 citybuddy-bench-commerce; exit 1
  fi
  sleep 1
done
echo "commerce-service ready on 18081"

echo "== starting the shared network namespace, model fixture and agent =="
# The placeholder owns the namespace; the agent and the model fixture join it, so a request from
# the generator reaches the agent over loopback with no extra hop. No port is published: a
# loopback-bound server is not reachable through a published port, which is the reason for this
# arrangement in the first place.
docker run --detach --name citybuddy-bench-net \
  --network citybuddy_default \
  alpine:3.20 sleep infinity >/dev/null

docker run --detach --name citybuddy-bench-model \
  --network "container:citybuddy-bench-net" \
  --volume "$repo_root/scripts/fake_litellm_server.py:/opt/fake.py:ro" \
  --entrypoint /opt/citybuddy/.venv/bin/python \
  citybuddy-bench-agent:local /opt/fake.py --port 8000 >/dev/null

docker run --detach --name citybuddy-bench-agent \
  --network "container:citybuddy-bench-net" \
  --cap-add SYS_PTRACE \
  --env CITYBUDDY_ENVIRONMENT=bench \
  --env AGENT_PORT=8001 \
  --env AGENT_IDENTITY_ENABLED=true \
  --env IDENTITY_ISSUER=https://identity.citybuddy.test \
  --env IDENTITY_USER_AUDIENCE=citybuddy-web \
  --env IDENTITY_JWKS_URL=http://citybuddy-bench-auth:8080/auth/jwks \
  --env IDENTITY_EXCHANGE_URL=http://citybuddy-bench-auth:8080/auth/token/exchange \
  --env AGENT_SERVICE_CLIENT_ID=agent-service \
  --env AGENT_SERVICE_CLIENT_SECRET="$agent_secret" \
  --env AGENT_EXCHANGE_SCOPES="catalog:read refund:create" \
  --env AGENT_MODEL_PROXY_URL=http://127.0.0.1:8000 \
  --env AGENT_COMMERCE_TOOLS_URL=http://citybuddy-bench-commerce:8080 \
  --env AGENT_COMMERCE_LIVENESS_URL=http://citybuddy-bench-commerce:8080 \
  --env AGENT_ELASTICSEARCH_URL=http://elasticsearch:9200 \
  --env AGENT_KNOWLEDGE_ALIAS=knowledge_docs_read \
  --env MYSQL_HOST=mysql \
  --env MYSQL_PORT=3306 \
  --env MYSQL_AGENT_APP_PASSWORD="$agent_pw" \
  --env CITYBUDDY_METRICS_ENABLED=true \
  --env AGENT_ATTEMPT_BUDGET="$AGENT_ATTEMPT_BUDGET" \
  citybuddy-bench-agent:local >/dev/null

# Probed from inside the namespace for the same reason the generator runs there. wget exits
# non-zero on the 405 that proves the route is live, so its status is discarded and the answer
# itself is matched.
agent_answers() {
  local answer
  answer="$(docker exec citybuddy-bench-net sh -c \
    'wget -q -O /dev/null --timeout=2 http://127.0.0.1:8001/api/sessions 2>&1' || true)"
  [[ "$answer" == *405* ]]
}
until agent_answers; do
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-agent)" != "true" ]; then
    docker logs --tail 40 citybuddy-bench-agent; exit 1
  fi
  sleep 1
done
echo "agent-service ready inside the bench namespace"

echo "== minting tokens, paying one order and opening one session for $AGENT_BENCH_USERS users =="
bench_password="$(cat "$run_dir/bench_password")"
# Built from inside the namespace: the agent is only reachable there, and running the fixture
# build in the same place keeps setup off the host-to-VM hop as well.
docker rm -f citybuddy-bench-pool >/dev/null 2>&1 || true
docker run --rm --name citybuddy-bench-pool \
  --network "container:citybuddy-bench-net" \
  --volume "$repo_root/bench/agent/build_agent_pool.py:/opt/build_agent_pool.py:ro" \
  --volume "$run_dir:/run-data" \
  --entrypoint /opt/citybuddy/.venv/bin/python \
  citybuddy-bench-agent:local /opt/build_agent_pool.py \
  --users "$AGENT_BENCH_USERS" \
  --password "$bench_password" \
  --auth-url http://citybuddy-bench-auth:8080 \
  --commerce-url http://citybuddy-bench-commerce:8080 \
  --agent-url http://127.0.0.1:8001 \
  --payment-key-id "$payment_key_id" \
  --payment-secret "$payment_secret" \
  --out /run-data/agent_pool.json

changes="$(source_changes)"
if [ "$(git rev-parse --verify HEAD)" != "$citybuddy_commit" ] || [ -n "$changes" ]; then
  echo "The checkout changed while the agent benchmark fixture was being built; rerun setup." >&2
  [ -z "$changes" ] || printf '%s\n' "$changes" >&2
  exit 1
fi
printf '%s\n' "$citybuddy_commit" > "$commit_file"
echo "== agent bench environment ready =="
