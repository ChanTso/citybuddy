#!/usr/bin/env bash
# Brings up the agent four-path fixture: users that may open a support session and chat, one
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
boundary_file="$run_dir/agent_setup_boundary.json"
boundary_tmp=""
commit_tmp=""
setup_complete=false
knowledge_bootstrap_file="$run_dir/agent_knowledge_bootstrap.json"
knowledge_alias_file="$run_dir/agent_knowledge_alias.json"
knowledge_mapping_file="$run_dir/agent_knowledge_mapping.json"
knowledge_visible_query_file="$run_dir/agent_knowledge_visible_query.json"
knowledge_visible_file="$run_dir/agent_knowledge_visible.json"
knowledge_all_query_file="$run_dir/agent_knowledge_all_query.json"
knowledge_all_file="$run_dir/agent_knowledge_all.json"
knowledge_health_file="$run_dir/agent_knowledge_cluster_health.json"
knowledge_summary_file="$run_dir/agent_knowledge_fixture.json"
auth_history_file="$run_dir/agent_mysql_auth_history.tsv"
commerce_history_file="$run_dir/agent_mysql_commerce_history.tsv"
agent_history_file="$run_dir/agent_mysql_agent_history.tsv"
auth_runtime_file="$run_dir/agent_auth_java_runtime.txt"
commerce_runtime_file="$run_dir/agent_commerce_java_runtime.txt"

cleanup_setup_boundary() {
  local status=$?
  trap - EXIT
  if [ -n "$boundary_tmp" ]; then
    rm -f -- "$boundary_tmp" || status=1
  fi
  if [ -n "$commit_tmp" ]; then
    rm -f -- "$commit_tmp" || status=1
  fi
  if [ "$setup_complete" != true ]; then
    rm -f -- "$boundary_file" "$commit_file" || status=1
    if [ "$status" -eq 0 ]; then
      status=1
    fi
  fi
  exit "$status"
}
trap cleanup_setup_boundary EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# Invalidate every completed boundary before even inspecting the checkout. A failed git command,
# dirty source or interrupted setup must never leave the previous fixture looking current.
rm -f -- "$boundary_file" "$commit_file"
setup_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [[ ! "$AGENT_BENCH_USERS" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "$AGENT_ATTEMPT_BUDGET" =~ ^[1-9][0-9]*$ ]]; then
  echo "AGENT_BENCH_USERS and AGENT_ATTEMPT_BUDGET must be positive integers." >&2
  exit 2
fi

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

read_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
commerce_pw="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
auth_pw="$(read_value MYSQL_AUTH_APP_PASSWORD)"
agent_pw="$(read_value MYSQL_AGENT_APP_PASSWORD)"
root_pw="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
redis_pw="$(read_value REDIS_COMMERCE_PASSWORD)"
auth_migration_pw="$(read_value MYSQL_AUTH_MIGRATION_PASSWORD)"
commerce_migration_pw="$(read_value MYSQL_COMMERCE_MIGRATION_PASSWORD)"
agent_migration_pw="$(read_value MYSQL_AGENT_MIGRATION_PASSWORD)"

mysql_port="$(docker port citybuddy-mysql-1 3306/tcp | cut -d: -f2)"
sql() { MYSQL_PWD="$2" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u "$1" -D "$3" --batch --skip-column-names -e "$4"; }

migration_query() {
  MYSQL_PWD="$2" mysql --protocol=TCP --host=127.0.0.1 --port="$mysql_port" \
    --user="$1" --database="$3" --batch --raw --skip-column-names --execute="$4"
}

record_migration_boundary() {
  local stream="$1"
  local database user password history_table output_file
  case "$stream" in
    auth)
      database=commerce_db
      user=auth_migration
      password="$auth_migration_pw"
      history_table=auth_schema_history
      output_file="$auth_history_file"
      ;;
    commerce)
      database=commerce_db
      user=commerce_migration
      password="$commerce_migration_pw"
      history_table=commerce_schema_history
      output_file="$commerce_history_file"
      ;;
    agent)
      database=cs_db
      user=agent_migration
      password="$agent_migration_pw"
      history_table=agent_schema_history
      output_file="$agent_history_file"
      ;;
    *)
      echo "Unknown migration boundary stream: $stream" >&2
      return 1
      ;;
  esac

  local migration_dir="$repo_root/infra/mysql/migrations/$stream"
  local -a migration_files=("$migration_dir"/V*__*.sql)
  local expected_count="${#migration_files[@]}"
  if [ "$expected_count" -eq 0 ] || [ ! -f "${migration_files[0]}" ]; then
    echo "Migration stream '$stream' has no committed migrations." >&2
    return 1
  fi
  local expected_latest_file="${migration_files[$((expected_count - 1))]}"
  local expected_latest="${expected_latest_file##*/}"
  expected_latest="${expected_latest%%__*}"
  expected_latest="${expected_latest#V}"

  local counts total_count completed_count failed_count extra
  counts="$(migration_query "$user" "$password" "$database" "
SELECT COUNT(*),
       COALESCE(SUM(success = TRUE), 0),
       COALESCE(SUM(success = FALSE), 0)
  FROM ${history_table};")"
  extra=""
  IFS=$'\t' read -r total_count completed_count failed_count extra <<<"$counts"
  if [ -n "$extra" ] || [[ ! "$total_count" =~ ^[0-9]+$ ]] \
    || [[ ! "$completed_count" =~ ^[0-9]+$ ]] || [[ ! "$failed_count" =~ ^[0-9]+$ ]]; then
    echo "Malformed migration history counts for '$stream'." >&2
    return 1
  fi
  if [ "$total_count" != "$expected_count" ] || [ "$completed_count" != "$expected_count" ] \
    || [ "$failed_count" != 0 ]; then
    echo "Migration boundary mismatch for '$stream': expected=$expected_count total=$total_count completed=$completed_count failed=$failed_count" >&2
    return 1
  fi

  local latest latest_version latest_checksum latest_extra
  latest="$(migration_query "$user" "$password" "$database" "
SELECT version, checksum
  FROM ${history_table}
 WHERE success = TRUE
 ORDER BY CAST(version AS UNSIGNED) DESC, version DESC
 LIMIT 1;")"
  latest_extra=""
  IFS=$'\t' read -r latest_version latest_checksum latest_extra <<<"$latest"
  if [ -n "$latest_extra" ] || [ "$latest_version" != "$expected_latest" ] \
    || [[ ! "$latest_checksum" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Migration latest-version boundary mismatch for '$stream'." >&2
    return 1
  fi

  migration_query "$user" "$password" "$database" "
SELECT version, checksum, IF(success, 1, 0)
  FROM ${history_table}
 ORDER BY CAST(version AS UNSIGNED), version;" > "$output_file"
  local row_count=0 version checksum success row_extra
  while IFS=$'\t' read -r version checksum success row_extra; do
    if [ -n "$row_extra" ] || [[ ! "$version" =~ ^[0-9]+$ ]] \
      || [[ ! "$checksum" =~ ^[0-9a-f]{64}$ ]] || [ "$success" != 1 ]; then
      echo "Malformed migration history row for '$stream'." >&2
      return 1
    fi
    row_count=$((row_count + 1))
  done < "$output_file"
  if [ "$row_count" -ne "$expected_count" ]; then
    echo "Migration history row count changed while recording '$stream'." >&2
    return 1
  fi
}

sha256_file() {
  local path="$1" output digest
  if command -v sha256sum >/dev/null 2>&1; then
    output="$(sha256sum "$path")"
  elif command -v shasum >/dev/null 2>&1; then
    output="$(shasum -a 256 "$path")"
  else
    echo "Neither sha256sum nor shasum is available." >&2
    return 1
  fi
  digest="${output%% *}"
  if [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid SHA-256 for $path." >&2
    return 1
  fi
  printf '%s\n' "$digest"
}

container_sha256() {
  local container="$1" path="$2" output digest
  output="$(docker exec "$container" sha256sum "$path")"
  digest="${output%% *}"
  if [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid mounted SHA-256 in $container." >&2
    return 1
  fi
  printf '%s\n' "$digest"
}

java_runtime_version() {
  local container="$1" output_file="$2" settings line version="" count=0
  settings="$(docker exec "$container" java -XshowSettings:properties -version 2>&1)"
  printf '%s\n' "$settings" > "$output_file"
  while IFS= read -r line; do
    line="${line%$'\r'}"
    case "$line" in
      *'java.runtime.version = '*)
        version="${line#*java.runtime.version = }"
        count=$((count + 1))
        ;;
    esac
  done <<<"$settings"
  if [ "$count" -ne 1 ] || [ -z "$version" ] || [[ "$version" == *$'\t'* ]]; then
    echo "Ambiguous Java runtime in $container." >&2
    return 1
  fi
  printf '%s\n' "$version"
}

auth_jar="$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar"
commerce_jar="$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar"

echo "== building current auth-service and commerce-service JARs =="
./mvnw --batch-mode --no-transfer-progress -pl auth-service,commerce-service -am clean package
[ -s "$auth_jar" ]
[ -s "$commerce_jar" ]
auth_jar_sha256="$(sha256_file "$auth_jar")"
commerce_jar_sha256="$(sha256_file "$commerce_jar")"

echo "== building the agent and dedicated benchmark Elasticsearch images =="
docker build --quiet --file bench/agent/Dockerfile --tag citybuddy-bench-agent:local . >/dev/null
docker build --quiet --file infra/elasticsearch/Dockerfile \
  --tag citybuddy-bench-elasticsearch:local infra/elasticsearch >/dev/null

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

echo "== stopping the previous bench services =="
# Before the fixture is cleared, not after: a previous ladder's collapsed step can still have
# turns in flight, and a turn that lands during teardown writes rows that the delete has already
# passed, leaving retrieval decisions behind that block the support_turn delete.
docker rm -f citybuddy-bench-k6 citybuddy-bench-agent citybuddy-bench-model citybuddy-bench-net \
  citybuddy-bench-auth citybuddy-bench-commerce citybuddy-bench-pool \
  citybuddy-bench-elasticsearch >/dev/null 2>&1 || true

echo "== applying and validating the current MySQL migration streams =="
mysql_setup_make=(make "ENV_FILE=.env" "COMPOSE_PROJECT_NAME=citybuddy")
"${mysql_setup_make[@]}" grant-access
"${mysql_setup_make[@]}" migrate-auth
"${mysql_setup_make[@]}" migrate-commerce
"${mysql_setup_make[@]}" migrate-agent
"${mysql_setup_make[@]}" grant-access
record_migration_boundary auth
record_migration_boundary commerce
record_migration_boundary agent

echo "== clearing the previous agent fixture =="
mysql_bootstrap() { MYSQL_PWD="$root_pw" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u root -D "$1"; }
mysql_bootstrap commerce_db < bench/agent/sql/reset_commerce_fixture.sql
mysql_bootstrap cs_db < bench/agent/sql/reset_support_fixture.sql

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

echo "== starting the isolated benchmark knowledge node =="
# This node has no persistent volume and never reads or changes the ordinary local Elasticsearch
# service.
docker run --detach --name citybuddy-bench-elasticsearch \
  --network citybuddy_default \
  --network-alias citybuddy-bench-elasticsearch \
  --publish 127.0.0.1::9200 \
  --mount \
  type=tmpfs,destination=/usr/share/elasticsearch/data,tmpfs-size=1073741824,tmpfs-mode=1777 \
  --env discovery.type=single-node \
  --env xpack.security.enabled=false \
  --env xpack.ml.enabled=false \
  --env 'ES_JAVA_OPTS=-Xms512m -Xmx512m' \
  citybuddy-bench-elasticsearch:local >/dev/null
es_binding="$(docker port citybuddy-bench-elasticsearch 9200/tcp)"
es_port="${es_binding##*:}"
if [[ ! "$es_port" =~ ^[0-9]+$ ]]; then
  echo "Cannot resolve the dedicated benchmark Elasticsearch port." >&2
  exit 1
fi
until curl --fail --silent --show-error \
  "http://127.0.0.1:$es_port/_cluster/health?wait_for_status=yellow&timeout=2s" \
  > "$knowledge_health_file" 2>/dev/null \
  && grep -Eq '"status":"(yellow|green)"' "$knowledge_health_file"; do
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-elasticsearch)" != true ]; then
    docker logs --tail 40 citybuddy-bench-elasticsearch
    exit 1
  fi
  sleep 1
done

echo "== bootstrapping and verifying the exact benchmark knowledge corpus =="
# The retrieval path resolves this alias, validates the mapping, then runs BM25 and dense
# retrieval. All raw trust-boundary responses are retained under bench/.run.
uv run citybuddy-indexer bootstrap \
  --elasticsearch-url "http://127.0.0.1:$es_port" \
  --index knowledge_docs_v1 --alias knowledge_docs_read > "$knowledge_bootstrap_file"
curl --fail --silent --show-error \
  "http://127.0.0.1:$es_port/_alias/knowledge_docs_read" > "$knowledge_alias_file"
curl --fail --silent --show-error \
  "http://127.0.0.1:$es_port/knowledge_docs_v1/_mapping" > "$knowledge_mapping_file"
knowledge_expected_count="$(uv run python -c \
  'from citybuddy_indexer.knowledge import INITIAL_PUBLIC_CORPUS; print(len(INITIAL_PUBLIC_CORPUS))')"
if [[ ! "$knowledge_expected_count" =~ ^[1-9][0-9]*$ ]]; then
  echo "The committed knowledge corpus is empty or malformed." >&2
  exit 1
fi
knowledge_search_size=$((knowledge_expected_count + 1))
printf '{"_source":true,"query":{"bool":{"filter":[{"term":{"published":true}},{"term":{"deleted":false}}]}},"size":%s,"track_total_hits":true}\n' \
  "$knowledge_search_size" > "$knowledge_visible_query_file"
printf '{"_source":true,"query":{"match_all":{}},"size":%s,"track_total_hits":true}\n' \
  "$knowledge_search_size" > "$knowledge_all_query_file"
curl --fail --silent --show-error --request POST \
  --header 'Content-Type: application/json' --data-binary "@$knowledge_visible_query_file" \
  "http://127.0.0.1:$es_port/knowledge_docs_read/_search" > "$knowledge_visible_file"
curl --fail --silent --show-error --request POST \
  --header 'Content-Type: application/json' --data-binary "@$knowledge_all_query_file" \
  "http://127.0.0.1:$es_port/knowledge_docs_v1/_search" > "$knowledge_all_file"
uv run python scripts/verify_agent_knowledge_fixture.py \
  --bootstrap "$knowledge_bootstrap_file" \
  --alias "$knowledge_alias_file" \
  --mapping "$knowledge_mapping_file" \
  --visible "$knowledge_visible_file" \
  --all "$knowledge_all_file" > "$knowledge_summary_file"

echo "== starting auth-service =="
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
  --citybuddy.identity.current-public-key-path=/opt/citybuddy/bench-public.pem \
  --citybuddy.identity.exchange-scopes[0]=catalog:read \
  --citybuddy.identity.exchange-scopes[1]=refund:create >/dev/null

until curl -sf http://127.0.0.1:18080/auth/jwks >/dev/null 2>&1; do
  if [ "$(docker inspect -f '{{.State.Running}}' citybuddy-bench-auth)" != "true" ]; then
    docker logs --tail 30 citybuddy-bench-auth; exit 1
  fi
  sleep 1
done
auth_mounted_jar_sha256="$(container_sha256 citybuddy-bench-auth /opt/citybuddy/auth.jar)"
auth_java_runtime="$(java_runtime_version citybuddy-bench-auth "$auth_runtime_file")"
if [ "$auth_mounted_jar_sha256" != "$auth_jar_sha256" ]; then
  echo "The auth-service mounted JAR does not match the freshly built host artifact." >&2
  exit 1
fi
echo "auth-service ready on 18080"

echo "== starting commerce-service =="
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
commerce_mounted_jar_sha256="$(container_sha256 citybuddy-bench-commerce /opt/citybuddy/commerce.jar)"
commerce_java_runtime="$(java_runtime_version citybuddy-bench-commerce "$commerce_runtime_file")"
if [ "$commerce_mounted_jar_sha256" != "$commerce_jar_sha256" ]; then
  echo "The commerce-service mounted JAR does not match the freshly built host artifact." >&2
  exit 1
fi
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
  --env AGENT_ELASTICSEARCH_URL=http://citybuddy-bench-elasticsearch:9200 \
  --env AGENT_KNOWLEDGE_ALIAS=knowledge_docs_read \
  --env MYSQL_HOST=mysql \
  --env MYSQL_PORT=3306 \
  --env MYSQL_AGENT_APP_PASSWORD="$agent_pw" \
  --env CITYBUDDY_METRICS_ENABLED=true \
  --env CITYBUDDY_TRACE_EXPORT_URL= \
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

auth_final_host_sha256="$(sha256_file "$auth_jar")"
auth_final_mounted_sha256="$(container_sha256 citybuddy-bench-auth /opt/citybuddy/auth.jar)"
commerce_final_host_sha256="$(sha256_file "$commerce_jar")"
commerce_final_mounted_sha256="$(container_sha256 citybuddy-bench-commerce /opt/citybuddy/commerce.jar)"
if [ "$auth_final_host_sha256" != "$auth_jar_sha256" ] \
  || [ "$auth_final_mounted_sha256" != "$auth_jar_sha256" ]; then
  echo "The auth-service JAR changed while the fixture was being built." >&2
  exit 1
fi
if [ "$commerce_final_host_sha256" != "$commerce_jar_sha256" ] \
  || [ "$commerce_final_mounted_sha256" != "$commerce_jar_sha256" ]; then
  echo "The commerce-service JAR changed while the fixture was being built." >&2
  exit 1
fi

agent_image_id="$(docker image inspect --format '{{.Id}}' citybuddy-bench-agent:local)"
agent_container_image_id="$(docker inspect --format '{{.Image}}' citybuddy-bench-agent)"
knowledge_image_id="$(docker image inspect --format '{{.Id}}' citybuddy-bench-elasticsearch:local)"
knowledge_container_image_id="$(docker inspect --format '{{.Image}}' citybuddy-bench-elasticsearch)"
if [ "$agent_image_id" != "$agent_container_image_id" ]; then
  echo "The running agent container does not use the image built by this setup." >&2
  exit 1
fi
if [ "$knowledge_image_id" != "$knowledge_container_image_id" ]; then
  echo "The running benchmark Elasticsearch container does not use the image built by this setup." >&2
  exit 1
fi

setup_completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
boundary_tmp="$(mktemp "$run_dir/.agent_setup_boundary.XXXXXX")"
commit_tmp="$(mktemp "$run_dir/.citybuddy_commit.XXXXXX")"
echo "== publishing the completed agent benchmark boundary =="
uv run python - \
  "$boundary_tmp" \
  "$citybuddy_commit" \
  "$setup_started_at" \
  "$setup_completed_at" \
  "$AGENT_BENCH_USERS" \
  "$AGENT_ATTEMPT_BUDGET" \
  "$auth_jar_sha256" \
  "$auth_mounted_jar_sha256" \
  "$auth_java_runtime" \
  "$commerce_jar_sha256" \
  "$commerce_mounted_jar_sha256" \
  "$commerce_java_runtime" \
  "$agent_image_id" \
  "$knowledge_image_id" \
  "$es_port" \
  "$auth_history_file" \
  "$commerce_history_file" \
  "$agent_history_file" \
  "$knowledge_summary_file" <<'PY'
from __future__ import annotations

import json
import sys
from pathlib import Path

(
    output_name,
    commit,
    started_at,
    completed_at,
    users,
    attempt_budget,
    auth_host_sha,
    auth_mounted_sha,
    auth_runtime,
    commerce_host_sha,
    commerce_mounted_sha,
    commerce_runtime,
    agent_image_id,
    knowledge_image_id,
    knowledge_host_port,
    auth_history_name,
    commerce_history_name,
    agent_history_name,
    knowledge_summary_name,
) = sys.argv[1:]


def migration(name: str, database: str, table: str, path_name: str) -> dict[str, object]:
    rows = []
    for line in Path(path_name).read_text(encoding="utf-8").splitlines():
        version, checksum, success = line.split("\t")
        rows.append({"checksum": checksum, "success": success == "1", "version": version})
    return {
        "completedCount": len(rows),
        "database": database,
        "evidence": f"bench/.run/agent_mysql_{name}_history.tsv",
        "historyTable": table,
        "latestVersion": rows[-1]["version"],
        "migrations": rows,
    }


knowledge = json.loads(Path(knowledge_summary_name).read_text(encoding="utf-8"))
boundary = {
    "citybuddyCommit": commit,
    "configuration": {
        "agentAttemptBudget": int(attempt_budget),
        "agentBenchUsers": int(users),
        "composeProject": "citybuddy",
        "metricsEnabled": True,
        "traceExportUrl": "",
    },
    "formatVersion": "citybuddy-agent-bench-setup-v1",
    "images": {
        "agent": {"imageId": agent_image_id, "tag": "citybuddy-bench-agent:local"},
        "knowledge": {
            "imageId": knowledge_image_id,
            "tag": "citybuddy-bench-elasticsearch:local",
        },
    },
    "java": {
        "authService": {
            "artifact": "auth-service/target/auth-service-0.0.1-SNAPSHOT.jar",
            "hostJarSha256": auth_host_sha,
            "javaRuntimeEvidence": "bench/.run/agent_auth_java_runtime.txt",
            "javaRuntimeVersion": auth_runtime,
            "mountedContainer": "citybuddy-bench-auth",
            "mountedJar": "/opt/citybuddy/auth.jar",
            "mountedJarSha256": auth_mounted_sha,
        },
        "commerceService": {
            "artifact": "commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar",
            "hostJarSha256": commerce_host_sha,
            "javaRuntimeEvidence": "bench/.run/agent_commerce_java_runtime.txt",
            "javaRuntimeVersion": commerce_runtime,
            "mountedContainer": "citybuddy-bench-commerce",
            "mountedJar": "/opt/citybuddy/commerce.jar",
            "mountedJarSha256": commerce_mounted_sha,
        },
    },
    "knowledge": {
        **knowledge,
        "agentEndpoint": "http://citybuddy-bench-elasticsearch:9200",
        "container": "citybuddy-bench-elasticsearch",
        "evidence": {
            "alias": "bench/.run/agent_knowledge_alias.json",
            "allDocuments": "bench/.run/agent_knowledge_all.json",
            "allDocumentsQuery": "bench/.run/agent_knowledge_all_query.json",
            "bootstrap": "bench/.run/agent_knowledge_bootstrap.json",
            "clusterHealth": "bench/.run/agent_knowledge_cluster_health.json",
            "mapping": "bench/.run/agent_knowledge_mapping.json",
            "visibleDocuments": "bench/.run/agent_knowledge_visible.json",
            "visibleDocumentsQuery": "bench/.run/agent_knowledge_visible_query.json",
        },
        "hostEndpoint": f"http://127.0.0.1:{knowledge_host_port}",
        "storage": {"destination": "/usr/share/elasticsearch/data", "type": "tmpfs"},
    },
    "mysql": {
        "agent": migration("agent", "cs_db", "agent_schema_history", agent_history_name),
        "auth": migration("auth", "commerce_db", "auth_schema_history", auth_history_name),
        "commerce": migration(
            "commerce", "commerce_db", "commerce_schema_history", commerce_history_name
        ),
    },
    "setupWindowUtc": {"completedAt": completed_at, "startedAt": started_at},
}
Path(output_name).write_text(
    json.dumps(boundary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
printf '%s\n' "$citybuddy_commit" > "$commit_tmp"
mv "$boundary_tmp" "$boundary_file"
boundary_tmp=""
mv "$commit_tmp" "$commit_file"
commit_tmp=""
setup_complete=true
trap - EXIT HUP INT TERM
