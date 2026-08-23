#!/usr/bin/env bash
# Brings the whole of CityBuddy up locally and leaves it running, so that the flagship refund
# flow can be driven from a terminal or from the browser.
#
# The repository has no single "run everything" configuration checked in: auth-service and
# commerce-service take their entire configuration from flags, and the working combinations lived
# only inside the integration scripts and the benchmark rig. This script is that combination,
# written once, for the demonstration.
#
# Two of the four services run on the host rather than in a container. The agent binds 127.0.0.1
# (agent-service/src/citybuddy_agent/__main__.py), so a published port would not reach it, and the
# browser has to reach it directly; the model fixture sits beside it for the same reason. The two
# Java services run as containers because they need to resolve each other and the data topology by
# name on the compose network.
#
# The ports are the ones web/.env.example already proxies to, so the web surface needs no
# configuration of its own: auth 8081, commerce 8082, agent 8000.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

run_dir="$repo_root/.citybuddy-demo"
auth_port=8081
commerce_port=8082
agent_port=8000
model_port=8100
demo_subject="demo-user"
demo_product="demo-product"
demo_price_minor=1990

# Everything written here is a generated credential: the demonstration login, the agent service
# secret, the mock-payment signing secret, the RSA private key and the bootstrap password inside
# demo.env. The directory is gitignored, and nothing in it should be group or world readable.
mkdir -p "$run_dir"
chmod 700 "$run_dir"
umask 077

read_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
auth_pw="$(read_value MYSQL_AUTH_APP_PASSWORD)"
commerce_pw="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
agent_pw="$(read_value MYSQL_AGENT_APP_PASSWORD)"
root_pw="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
redis_pw="$(read_value REDIS_COMMERCE_PASSWORD)"

# Both starting and stopping need the data topology: stopping hands back rows in the auth schema.
docker inspect -f '{{.State.Running}}' citybuddy-mysql-1 2>/dev/null | grep -q true || {
  echo "the local data topology is not running; start it with: make up" >&2; exit 1; }

mysql_port="$(docker port citybuddy-mysql-1 3306/tcp | cut -d: -f2)"
es_port="$(docker port citybuddy-elasticsearch-1 9200/tcp | cut -d: -f2)"
sql() { MYSQL_PWD="$2" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u "$1" -D "$3" --batch --skip-column-names -e "$4"; }

stop_services() {
  docker rm -f citybuddy-demo-auth citybuddy-demo-commerce >/dev/null 2>&1 || true
  for name in agent model; do
    if [ -f "$run_dir/$name.pid" ]; then
      pid="$(cat "$run_dir/$name.pid")"
      kill "$pid" >/dev/null 2>&1 || true
      # uv forwards the signal to the server it launched, but not instantly, and the next start
      # binds the same port. Wait for the process to actually leave rather than assume it has.
      for _ in $(seq 20); do kill -0 "$pid" >/dev/null 2>&1 || break; sleep 0.2; done
      kill -0 "$pid" >/dev/null 2>&1 && kill -9 "$pid" >/dev/null 2>&1 || true
      rm -f "$run_dir/$name.pid"
    fi
  done
}

# Two rows in the shared auth schema are singletons that the whole local topology contends for:
# the published signing metadata, because auth fails the entire JWKS document when any published
# kid has no configured runtime key, and the agent-service client credential, because both
# auth-service and commerce-service pin that exact client id. The demonstration takes both over
# while it runs and gives them back when it stops, so the benchmark rig can reseed its own.
release_shared_identity() {
  sql root "$root_pw" commerce_db "
DELETE FROM auth_signing_key_metadata WHERE kid = 'demo-current';
DELETE FROM auth_service_identity WHERE service_id = '00000000-0000-0000-0000-0000000d0002';"
}

if [ "${1:-start}" = "stop" ]; then
  stop_services
  release_shared_identity
  echo "demo services stopped; the data topology and the demonstration fixture are untouched"
  exit 0
fi

echo "== stopping any previous demo services =="
stop_services

# Credentials are generated once and reused, so a restart does not invalidate a token that is
# still open in a browser tab.
password_file="$run_dir/demo_password"
[ -f "$password_file" ] || printf 'demo-%s' "$(openssl rand -hex 8)" > "$password_file"
demo_password="$(cat "$password_file")"
secret_file="$run_dir/agent_service_secret"
[ -f "$secret_file" ] || printf 'demo-agent-%s' "$(openssl rand -hex 12)" > "$secret_file"
agent_secret="$(cat "$secret_file")"
payment_file="$run_dir/mock_payment"
[ -f "$payment_file" ] || printf 'demo-callback-key\ndemo-%s\n' "$(openssl rand -hex 16)" > "$payment_file"
payment_key_id="$(sed -n 1p "$payment_file")"
payment_secret="$(sed -n 2p "$payment_file")"

if [ ! -f "$run_dir/demo-private.pem" ]; then
  echo "== generating the demo signing key =="
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$run_dir/demo-private.pem" 2>/dev/null
  openssl pkey -in "$run_dir/demo-private.pem" -pubout -out "$run_dir/demo-public.pem" 2>/dev/null
fi

echo "== creating the catalog topic =="
admin() { docker compose --project-name citybuddy --env-file .env --file compose.yaml run --rm --no-deps rocketmq-admin "$@" >/dev/null 2>&1; }
admin updateTopic --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --topic cb030-catalog-demo --readQueueNums 4 --writeQueueNums 4 || true
admin updateSubGroup --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster --groupName cb030-catalog-consumer-demo --consumeEnable true || true

echo "== seeding the demo identity and catalog fixture =="
demo_hash="$(uv run python -c "import bcrypt,sys; print(bcrypt.hashpw(sys.argv[1].encode(), bcrypt.gensalt(rounds=10)).decode())" "$demo_password")"
service_hash="$(uv run python scripts/hash_test_credential.py "$agent_secret")"

# The runtime accounts hold no DELETE grant by design, so fixture teardown uses bootstrap.
sql root "$root_pw" commerce_db "
DELETE c FROM auth_login_credential c JOIN auth_user_principal p USING (principal_id)
  WHERE p.subject = '$demo_subject';
DELETE FROM auth_user_principal WHERE subject = '$demo_subject';
-- auth-service and commerce-service both pin the client id 'agent-service', so this credential
-- cannot be namespaced per fixture; whoever starts last owns it, and stopping hands it back.
DELETE FROM auth_service_identity WHERE client_id = 'agent-service';
-- Every published kid has to resolve to a configured runtime key, so a leftover row from another
-- local fixture makes the whole JWKS document fail rather than only its own entry. The
-- demonstration cannot configure another fixture's key, so it clears the table and reseeds.
DELETE FROM auth_signing_key_metadata;"

sql auth_app "$auth_pw" commerce_db "
INSERT INTO auth_signing_key_metadata (kid, state, activated_at, retire_after)
VALUES ('demo-current', 'CURRENT', CURRENT_TIMESTAMP(6), NULL);
INSERT INTO auth_user_principal (principal_id, subject, login_identifier, state, permissions)
VALUES ('00000000-0000-0000-0000-0000000d0001', '$demo_subject', '$demo_subject', 'ACTIVE',
        'catalog:read order:create payment:create refund:create support:session:create support:chat');
INSERT INTO auth_login_credential (principal_id, password_hash)
VALUES ('00000000-0000-0000-0000-0000000d0001', '$demo_hash');
INSERT INTO auth_service_identity (service_id, client_id, credential_hash, state, allowed_scopes)
VALUES ('00000000-0000-0000-0000-0000000d0002', 'agent-service', '$service_hash', 'ACTIVE',
        'catalog:read refund:create');"

sql root "$root_pw" commerce_db "DELETE FROM product WHERE product_id = '$demo_product';"
sql commerce_app "$commerce_pw" commerce_db "
INSERT INTO product (product_id, name, description, price_minor, currency, stock_quantity,
                     available, publication_state, publication_version)
VALUES ('$demo_product', '街角咖啡券', '本地演示商品', $demo_price_minor, 'CNY', 500, TRUE, 'PUBLISHED', 1);"

echo "== bootstrapping the knowledge index =="
# The retrieval path resolves an alias, validates the mapping, then runs BM25 and dense retrieval
# and a rerank. Without a real index it fails closed and answers nothing. Bootstrap creates the
# index, publishes the alias and indexes the corpus the indexer ships, which is what the
# demonstration's question is answered from; rerunning it on an existing index is a no-op.
uv run citybuddy-indexer bootstrap \
  --elasticsearch-url "http://127.0.0.1:$es_port" \
  --index knowledge_docs_v1 --alias knowledge_docs_read

echo "== starting auth-service on $auth_port =="
test -f auth-service/target/auth-service-0.0.1-SNAPSHOT.jar || {
  echo "auth-service jar is missing; run ./mvnw -pl auth-service package -DskipTests" >&2; exit 1; }
docker run --detach --name citybuddy-demo-auth \
  --network citybuddy_default \
  --publish "127.0.0.1:$auth_port:8080" \
  --volume "$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar:/opt/citybuddy/auth.jar:ro" \
  --volume "$run_dir/demo-private.pem:/opt/citybuddy/demo-private.pem:ro" \
  --volume "$run_dir/demo-public.pem:/opt/citybuddy/demo-public.pem:ro" \
  --env SPRING_DATASOURCE_PASSWORD="$auth_pw" \
  eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd \
  java -jar /opt/citybuddy/auth.jar \
  --server.port=8080 \
  --spring.datasource.url='jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true' \
  --spring.datasource.username=auth_app \
  --citybuddy.identity.enabled=true \
  --citybuddy.identity.issuer=https://identity.citybuddy.test \
  --citybuddy.identity.user-audience=citybuddy-web \
  --citybuddy.identity.current-kid=demo-current \
  --citybuddy.identity.current-private-key-path=/opt/citybuddy/demo-private.pem \
  --citybuddy.identity.current-public-key-path=/opt/citybuddy/demo-public.pem \
  --citybuddy.identity.exchange-scopes[0]=catalog:read \
  --citybuddy.identity.exchange-scopes[1]=refund:create >/dev/null

until curl -sf "http://127.0.0.1:$auth_port/auth/jwks" >/dev/null 2>&1; do
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-demo-auth)" != "true" ]; then
    docker logs --tail 30 citybuddy-demo-auth; exit 1
  fi
  sleep 1
done
echo "auth-service ready"

echo "== starting commerce-service on $commerce_port =="
test -f commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar || {
  echo "commerce-service jar is missing; run ./mvnw -pl commerce-service package -DskipTests" >&2; exit 1; }
docker run --detach --name citybuddy-demo-commerce \
  --network citybuddy_default \
  --publish "127.0.0.1:$commerce_port:8080" \
  --volume "$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar:/opt/citybuddy/commerce.jar:ro" \
  --env SPRING_DATASOURCE_PASSWORD="$commerce_pw" \
  eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd \
  java -XX:MaxRAMPercentage=70 -jar /opt/citybuddy/commerce.jar \
  --server.port=8080 \
  --spring.datasource.url='jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true' \
  --spring.datasource.username=commerce_app \
  --spring.data.redis.url="redis://:$redis_pw@redis-commerce:6379/0" \
  --citybuddy.catalog.enabled=true \
  --citybuddy.catalog.issuer=https://identity.citybuddy.test \
  --citybuddy.catalog.user-audience=citybuddy-web \
  --citybuddy.catalog.jwks-url=http://citybuddy-demo-auth:8080/auth/jwks \
  --citybuddy.catalog.jwks-cache-ttl=300s \
  --citybuddy.catalog.clock-skew=30s \
  --citybuddy.catalog.required-permission=catalog:read \
  --citybuddy.catalog.cache-ttl=30s \
  --citybuddy.catalog.cache-jitter=10s \
  --citybuddy.catalog.null-ttl=3s \
  --citybuddy.catalog.mutex-ttl=2s \
  --citybuddy.catalog.worker-initial-delay-ms=5000 \
  --citybuddy.catalog.worker-delay-ms=30000 \
  --citybuddy.catalog.rocketmq-endpoints=rocketmq-broker-proxy:8081 \
  --citybuddy.catalog.rocketmq-topic=cb030-catalog-demo \
  --citybuddy.catalog.rocketmq-consumer-group=cb030-catalog-consumer-demo \
  --citybuddy.orders.enabled=true \
  --citybuddy.mock-payment.enabled=true \
  --citybuddy.mock-payment.required-permission=payment:create \
  --citybuddy.mock-payment.callback-key-id="$payment_key_id" \
  --citybuddy.mock-payment.callback-secret="$payment_secret" \
  --citybuddy.mock-payment.callback-maximum-age=5m \
  --citybuddy.mock-payment.callback-clock-skew=30s \
  --citybuddy.obo.enabled=true \
  --citybuddy.obo.issuer=https://identity.citybuddy.test \
  --citybuddy.obo.jwks-url=http://citybuddy-demo-auth:8080/auth/jwks \
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

until [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$commerce_port/api/products")" != "000" ]; do
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-demo-commerce)" != "true" ]; then
    docker logs --tail 40 citybuddy-demo-commerce; exit 1
  fi
  sleep 1
done
echo "commerce-service ready"

echo "== starting the model fixture on $model_port =="
# There is no model-provider access in this repository. The fixture answers the completion API
# deterministically, and the scenario is selected by a keyword in the message; docs/DEMO.md says
# which keyword drives which beat. Everything the demonstration is about happens after this
# response is received, so a deterministic model does not weaken it.
uv run python scripts/fake_litellm_server.py --port "$model_port" \
  >"$run_dir/model.log" 2>&1 &
echo $! > "$run_dir/model.pid"
until curl -s -o /dev/null "http://127.0.0.1:$model_port/fixture/counts"; do sleep 1; done
echo "model fixture ready"

echo "== starting agent-service on $agent_port =="
# A retrieval turn charges the attempt budget once for the model call that requests the tool, twice
# to resolve the alias and validate the mapping, twice per query text for BM25 and dense recall,
# and once for the rerank — eight in all when the tool call carries a query rewrite, which the
# demonstration's does. The default budget is eight, so the answer itself never gets an attempt.
CITYBUDDY_ENVIRONMENT=development \
AGENT_PORT="$agent_port" \
AGENT_IDENTITY_ENABLED=true \
IDENTITY_ISSUER=https://identity.citybuddy.test \
IDENTITY_USER_AUDIENCE=citybuddy-web \
IDENTITY_JWKS_URL="http://127.0.0.1:$auth_port/auth/jwks" \
IDENTITY_EXCHANGE_URL="http://127.0.0.1:$auth_port/auth/token/exchange" \
AGENT_SERVICE_CLIENT_ID=agent-service \
AGENT_SERVICE_CLIENT_SECRET="$agent_secret" \
AGENT_EXCHANGE_SCOPES="catalog:read refund:create" \
AGENT_MODEL_PROXY_URL="http://127.0.0.1:$model_port" \
AGENT_COMMERCE_TOOLS_URL="http://127.0.0.1:$commerce_port" \
AGENT_COMMERCE_LIVENESS_URL="http://127.0.0.1:$commerce_port" \
AGENT_ELASTICSEARCH_URL="http://127.0.0.1:$es_port" \
AGENT_KNOWLEDGE_ALIAS=knowledge_docs_read \
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT="$mysql_port" \
MYSQL_AGENT_APP_PASSWORD="$agent_pw" \
AGENT_ATTEMPT_BUDGET=16 \
  uv run citybuddy-agent >"$run_dir/agent.log" 2>&1 &
echo $! > "$run_dir/agent.pid"

# The sessions route answers 405 to a GET, which is the cheapest proof that routing is live.
until curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$agent_port/api/sessions" | grep -q 405; do
  if ! kill -0 "$(cat "$run_dir/agent.pid")" 2>/dev/null; then
    tail -30 "$run_dir/agent.log"; exit 1
  fi
  sleep 1
done
echo "agent-service ready"

cat > "$run_dir/demo.env" <<ENV
CITYBUDDY_DEMO_AUTH_URL=http://127.0.0.1:$auth_port
CITYBUDDY_DEMO_COMMERCE_URL=http://127.0.0.1:$commerce_port
CITYBUDDY_DEMO_AGENT_URL=http://127.0.0.1:$agent_port
CITYBUDDY_DEMO_MODEL_URL=http://127.0.0.1:$model_port
CITYBUDDY_DEMO_SUBJECT=$demo_subject
CITYBUDDY_DEMO_PASSWORD=$demo_password
CITYBUDDY_DEMO_PRODUCT=$demo_product
CITYBUDDY_DEMO_PRICE_MINOR=$demo_price_minor
CITYBUDDY_DEMO_PAYMENT_KEY_ID=$payment_key_id
CITYBUDDY_DEMO_PAYMENT_SECRET=$payment_secret
CITYBUDDY_DEMO_MYSQL_PORT=$mysql_port
CITYBUDDY_DEMO_MYSQL_ROOT_PASSWORD=$root_pw
ENV

echo
echo "CityBuddy is up."
echo "  auth      http://127.0.0.1:$auth_port"
echo "  commerce  http://127.0.0.1:$commerce_port"
echo "  agent     http://127.0.0.1:$agent_port"
echo "  login     $demo_subject / $demo_password"
echo
echo "Drive the flagship flow in a terminal:  make demo-story"
echo "Or open the web surface:                npm --prefix web run dev"
echo "Stop the demo services:                 ./scripts/demo.sh stop"
