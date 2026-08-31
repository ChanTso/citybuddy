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
environment_file="$run_dir/agent_setup_environment.json"
legacy_boundary_file="$run_dir/agent_setup_boundary.json"
environment_tmp=""
commit_tmp=""
setup_complete=false
containers_may_exist=false
setup_nonce=""
setup_nonce_label="citybuddy.bench.setup-nonce"
citybuddy_commit_label="citybuddy.bench.citybuddy-commit"
knowledge_bootstrap_file="$run_dir/agent_knowledge_bootstrap.json"
knowledge_health_file="$run_dir/agent_knowledge_cluster_health.json"
auth_runtime_file="$run_dir/agent_auth_java_runtime.txt"
commerce_runtime_file="$run_dir/agent_commerce_java_runtime.txt"

cleanup_setup() {
  local original_status=$? cleanup_status=0 status ids id
  trap - EXIT
  if [ "$setup_complete" != true ] && [ "$containers_may_exist" = true ]; then
    for name in citybuddy-bench-pool citybuddy-bench-k6 citybuddy-bench-profile-load \
      citybuddy-bench-indexer \
      citybuddy-bench-agent citybuddy-bench-model citybuddy-bench-net \
      citybuddy-bench-commerce citybuddy-bench-auth citybuddy-bench-elasticsearch; do
      if ids="$(docker ps -aq \
        --filter "name=^/${name}$" \
        --filter "label=$setup_nonce_label=$setup_nonce" \
        --filter "label=$citybuddy_commit_label=$citybuddy_commit")"; then
        while IFS= read -r id; do
          [ -n "$id" ] || continue
          if docker rm -f "$id" >/dev/null; then :; else
            status=$?
            echo "Failed to remove setup-owned container $name (status $status)." >&2
            if [ "$cleanup_status" -eq 0 ]; then cleanup_status="$status"; fi
          fi
        done <<<"$ids"
      else
        status=$?
        echo "Failed to inspect setup-owned container $name (status $status)." >&2
        if [ "$cleanup_status" -eq 0 ]; then cleanup_status="$status"; fi
      fi
    done
  fi
  if [ -n "$environment_tmp" ]; then
    if rm -f -- "$environment_tmp"; then :; else
      status=$?
      echo "Failed to remove temporary setup environment record (status $status)." >&2
      if [ "$cleanup_status" -eq 0 ]; then cleanup_status="$status"; fi
    fi
  fi
  if [ -n "$commit_tmp" ]; then
    if rm -f -- "$commit_tmp"; then :; else
      status=$?
      echo "Failed to remove temporary setup commit marker (status $status)." >&2
      if [ "$cleanup_status" -eq 0 ]; then cleanup_status="$status"; fi
    fi
  fi
  if [ "$setup_complete" != true ]; then
    if rm -f -- "$environment_file" "$commit_file"; then :; else
      status=$?
      echo "Failed to invalidate incomplete setup markers (status $status)." >&2
      if [ "$cleanup_status" -eq 0 ]; then cleanup_status="$status"; fi
    fi
  fi
  if [ "$cleanup_status" -ne 0 ]; then
    echo "Agent setup cleanup failed with status $cleanup_status (original setup status: $original_status)." >&2
  fi
  if [ "$original_status" -ne 0 ]; then exit "$original_status"; fi
  if [ "$setup_complete" = true ]; then exit 0; fi
  if [ "$cleanup_status" -ne 0 ]; then exit "$cleanup_status"; fi
  exit 1
}
trap cleanup_setup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# Invalidate every completed environment before even inspecting the checkout. A failed git command,
# dirty source or interrupted setup must never leave the previous fixture looking current.
rm -f -- "$environment_file" "$legacy_boundary_file" "$commit_file"
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
setup_nonce="$(openssl rand -hex 16)"
if [[ ! "$setup_nonce" =~ ^[0-9a-f]{32}$ ]]; then
  echo "Cannot generate the setup nonce." >&2
  exit 1
fi

read_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
commerce_pw="$(read_value MYSQL_COMMERCE_APP_PASSWORD)"
auth_pw="$(read_value MYSQL_AUTH_APP_PASSWORD)"
agent_pw="$(read_value MYSQL_AGENT_APP_PASSWORD)"
root_pw="$(read_value MYSQL_BOOTSTRAP_PASSWORD)"
redis_pw="$(read_value REDIS_COMMERCE_PASSWORD)"
auth_migration_pw="$(read_value MYSQL_AUTH_MIGRATION_PASSWORD)"
commerce_migration_pw="$(read_value MYSQL_COMMERCE_MIGRATION_PASSWORD)"
agent_migration_pw="$(read_value MYSQL_AGENT_MIGRATION_PASSWORD)"

sql() { MYSQL_PWD="$2" mysql --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u "$1" -D "$3" --batch --skip-column-names -e "$4"; }

migration_query() {
  MYSQL_PWD="$2" mysql --protocol=TCP --host=127.0.0.1 --port="$mysql_port" \
    --user="$1" --database="$3" --batch --raw --skip-column-names --execute="$4"
}

migration_latest_version() {
  local user="$1" password="$2" database="$3" history_table="$4" version
  version="$(migration_query "$user" "$password" "$database" "
SELECT version
  FROM ${history_table}
 WHERE success = TRUE
 ORDER BY CAST(version AS UNSIGNED) DESC, version DESC
 LIMIT 1;")"
  if [[ ! "$version" =~ ^[0-9]+$ ]]; then
    echo "Cannot record the latest successful version from $history_table." >&2
    return 1
  fi
  printf '%s\n' "$version"
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

validate_image_id() {
  local image_id="$1" boundary="$2"
  if [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "Invalid immutable image ID at $boundary." >&2
    return 1
  fi
}

container_image_id() {
  local container="$1" image_id
  image_id="$(docker inspect --format '{{.Image}}' "$container")"
  validate_image_id "$image_id" "$container"
  printf '%s\n' "$image_id"
}

container_started_at() {
  local container="$1" started_at
  started_at="$(docker inspect --format '{{.State.StartedAt}}' "$container")"
  if [ -z "$started_at" ]; then
    echo "Missing StartedAt for $container." >&2
    return 1
  fi
  printf '%s\n' "$started_at"
}

container_restart_count() {
  local container="$1" restart_count
  restart_count="$(docker inspect --format '{{.RestartCount}}' "$container")"
  if [[ ! "$restart_count" =~ ^[0-9]+$ ]]; then
    echo "Invalid restart count for $container." >&2
    return 1
  fi
  printf '%s\n' "$restart_count"
}

resolve_image_id() {
  local image_ref="$1" image_id
  if ! image_id="$(docker image inspect --format '{{.Id}}' "$image_ref" 2>/dev/null)"; then
    docker pull "$image_ref" >/dev/null
    image_id="$(docker image inspect --format '{{.Id}}' "$image_ref")"
  fi
  validate_image_id "$image_id" "$image_ref"
  printf '%s\n' "$image_id"
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

verify_setup_container() {
  local name="$1" expected_id="$2" expected_image_id="$3"
  local expected_started_at="$4" expected_restart_count="$5"
  local actual_id actual_image_id actual_nonce actual_commit running
  local actual_started_at actual_restart_count
  actual_id="$(docker inspect --format '{{.Id}}' "$name")"
  actual_image_id="$(container_image_id "$name")"
  actual_nonce="$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.setup-nonce" }}' "$name")"
  actual_commit="$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.citybuddy-commit" }}' "$name")"
  running="$(docker inspect --format '{{.State.Running}}' "$name")"
  actual_started_at="$(container_started_at "$name")"
  actual_restart_count="$(container_restart_count "$name")"
  if [ "$actual_id" != "$expected_id" ] || [ "$actual_image_id" != "$expected_image_id" ] \
    || [ "$actual_started_at" != "$expected_started_at" ] \
    || [ "$actual_restart_count" != "$expected_restart_count" ] \
    || [ "$actual_nonce" != "$setup_nonce" ] \
    || [ "$actual_commit" != "$citybuddy_commit" ] || [ "$running" != true ]; then
    echo "Setup-owned container identity changed for $name." >&2
    return 1
  fi
}

verify_mysql_container() {
  local actual_id actual_image_id actual_configured_image actual_started_at actual_restart_count
  local running current_compose_image
  actual_id="$(docker inspect --format '{{.Id}}' citybuddy-mysql-1)"
  actual_image_id="$(container_image_id citybuddy-mysql-1)"
  actual_configured_image="$(docker inspect --format '{{.Config.Image}}' citybuddy-mysql-1)"
  actual_started_at="$(container_started_at citybuddy-mysql-1)"
  actual_restart_count="$(container_restart_count citybuddy-mysql-1)"
  running="$(docker inspect --format '{{.State.Running}}' citybuddy-mysql-1)"
  current_compose_image="$("${compose_command[@]}" config --format json \
    | jq -er '.services.mysql.image | select(type == "string" and length > 0)')"
  if [ "$actual_id" != "$mysql_container_id" ] \
    || [ "$actual_image_id" != "$mysql_image_id" ] \
    || [ "$actual_configured_image" != "$mysql_configured_image" ] \
    || [ "$current_compose_image" != "$mysql_configured_image" ] \
    || [ "$actual_started_at" != "$mysql_started_at" ] \
    || [ "$actual_restart_count" != "$mysql_restart_count" ] \
    || [ "$running" != true ]; then
    echo "The measured MySQL container changed while the fixture was being built." >&2
    return 1
  fi
}

compose_command=(docker compose --project-name citybuddy --env-file .env --file compose.yaml)
mysql_container_id="$(docker inspect --format '{{.Id}}' citybuddy-mysql-1)"
if [[ ! "$mysql_container_id" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Invalid MySQL container ID." >&2
  exit 1
fi
mysql_image_id="$(container_image_id citybuddy-mysql-1)"
mysql_configured_image="$("${compose_command[@]}" config --format json \
  | jq -er '.services.mysql.image | select(type == "string" and length > 0)')"
mysql_actual_configured_image="$(docker inspect --format '{{.Config.Image}}' citybuddy-mysql-1)"
mysql_resolved_image_id="$(resolve_image_id "$mysql_configured_image")"
mysql_started_at="$(container_started_at citybuddy-mysql-1)"
mysql_restart_count="$(container_restart_count citybuddy-mysql-1)"
if [ "$mysql_actual_configured_image" != "$mysql_configured_image" ] \
  || [ "$mysql_resolved_image_id" != "$mysql_image_id" ] \
  || [ "$(docker inspect --format '{{.State.Running}}' citybuddy-mysql-1)" != true ]; then
  echo "The running MySQL container does not match the Compose image boundary." >&2
  exit 1
fi
mysql_port="$(docker port "$mysql_container_id" 3306/tcp | cut -d: -f2)"

auth_jar="$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar"
commerce_jar="$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar"

echo "== building current auth-service and commerce-service JARs =="
./mvnw --batch-mode --no-transfer-progress -pl auth-service,commerce-service -am clean package
[ -s "$auth_jar" ]
[ -s "$commerce_jar" ]
auth_jar_sha256="$(sha256_file "$auth_jar")"
commerce_jar_sha256="$(sha256_file "$commerce_jar")"

echo "== building the agent and dedicated benchmark Elasticsearch images =="
agent_image_id="$(git archive --format=tar "$citybuddy_commit" -- \
  bench/agent/Dockerfile \
  pyproject.toml \
  uv.lock \
  agent-service/pyproject.toml \
  agent-service/src \
  knowledge-indexer/pyproject.toml \
  knowledge-indexer/src \
  | docker build --quiet --file bench/agent/Dockerfile -)"
elasticsearch_image_id="$(docker build --quiet --file infra/elasticsearch/Dockerfile \
  infra/elasticsearch)"
validate_image_id "$agent_image_id" "agent image build"
validate_image_id "$elasticsearch_image_id" "Elasticsearch image build"
java_runtime_image_id="$(resolve_image_id \
  eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd)"
net_image_id="$(resolve_image_id alpine:3.20)"

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
  citybuddy-bench-profile-load citybuddy-bench-indexer \
  citybuddy-bench-elasticsearch >/dev/null 2>&1 || true

echo "== applying and validating the current MySQL migration streams =="
mysql_setup_make=(make "ENV_FILE=.env" "COMPOSE_PROJECT_NAME=citybuddy")
"${mysql_setup_make[@]}" grant-access
"${mysql_setup_make[@]}" migrate-auth
"${mysql_setup_make[@]}" migrate-commerce
"${mysql_setup_make[@]}" migrate-agent
"${mysql_setup_make[@]}" grant-access
auth_migration_version="$(migration_latest_version \
  auth_migration "$auth_migration_pw" commerce_db auth_schema_history)"
commerce_migration_version="$(migration_latest_version \
  commerce_migration "$commerce_migration_pw" commerce_db commerce_schema_history)"
agent_migration_version="$(migration_latest_version \
  agent_migration "$agent_migration_pw" cs_db agent_schema_history)"

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
containers_may_exist=true
es_container_id="$(docker run --detach --name citybuddy-bench-elasticsearch \
  --label "$setup_nonce_label=$setup_nonce" \
  --label "$citybuddy_commit_label=$citybuddy_commit" \
  --network citybuddy_default \
  --network-alias citybuddy-bench-elasticsearch \
  --publish 127.0.0.1::9200 \
  --mount \
  type=tmpfs,destination=/usr/share/elasticsearch/data,tmpfs-size=1073741824,tmpfs-mode=1777 \
  --env discovery.type=single-node \
  --env xpack.security.enabled=false \
  --env xpack.ml.enabled=false \
  --env 'ES_JAVA_OPTS=-Xms512m -Xmx512m' \
  "$elasticsearch_image_id")"
es_started_at="$(container_started_at citybuddy-bench-elasticsearch)"
es_restart_count="$(container_restart_count citybuddy-bench-elasticsearch)"
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

echo "== bootstrapping the benchmark knowledge corpus =="
# The indexer owns corpus and mapping validation. Its raw response is retained without a second
# implementation of those rules in the benchmark harness. It runs from the commit-only image, so
# ignored host bytecode and editable-install state cannot change the bootstrap.
docker run --rm --name citybuddy-bench-indexer \
  --label "$setup_nonce_label=$setup_nonce" \
  --label "$citybuddy_commit_label=$citybuddy_commit" \
  --network citybuddy_default \
  --entrypoint /opt/citybuddy/.venv/bin/python \
  "$agent_image_id" -m citybuddy_indexer bootstrap \
  --elasticsearch-url http://citybuddy-bench-elasticsearch:9200 \
  --index knowledge_docs_v1 --alias knowledge_docs_read > "$knowledge_bootstrap_file"

echo "== starting auth-service =="
auth_container_id="$(docker run --detach --name citybuddy-bench-auth \
  --label "$setup_nonce_label=$setup_nonce" \
  --label "$citybuddy_commit_label=$citybuddy_commit" \
  --network citybuddy_default \
  --publish 127.0.0.1:18080:8080 \
  --volume "$auth_jar:/opt/citybuddy/auth.jar:ro" \
  --volume "$run_dir/bench-private.pem:/opt/citybuddy/bench-private.pem:ro" \
  --volume "$run_dir/bench-public.pem:/opt/citybuddy/bench-public.pem:ro" \
  --env SPRING_DATASOURCE_PASSWORD="$auth_pw" \
  "$java_runtime_image_id" \
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
  --citybuddy.identity.exchange-scopes[1]=refund:create)"
auth_started_at="$(container_started_at citybuddy-bench-auth)"
auth_restart_count="$(container_restart_count citybuddy-bench-auth)"

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
commerce_container_id="$(docker run --detach --name citybuddy-bench-commerce \
  --label "$setup_nonce_label=$setup_nonce" \
  --label "$citybuddy_commit_label=$citybuddy_commit" \
  --network citybuddy_default \
  --publish 127.0.0.1:18081:8080 \
  --cpus 4 \
  --volume "$commerce_jar:/opt/citybuddy/commerce.jar:ro" \
  --env SPRING_DATASOURCE_PASSWORD="$commerce_pw" \
  "$java_runtime_image_id" \
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
  --citybuddy.actions.observation-backoff=25ms)"
commerce_started_at="$(container_started_at citybuddy-bench-commerce)"
commerce_restart_count="$(container_restart_count citybuddy-bench-commerce)"

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
net_container_id="$(docker run --detach --name citybuddy-bench-net \
  --label "$setup_nonce_label=$setup_nonce" \
  --label "$citybuddy_commit_label=$citybuddy_commit" \
  --network citybuddy_default \
  "$net_image_id" sleep infinity)"
net_started_at="$(container_started_at citybuddy-bench-net)"
net_restart_count="$(container_restart_count citybuddy-bench-net)"

model_container_id="$(docker run --detach --name citybuddy-bench-model \
  --label "$setup_nonce_label=$setup_nonce" \
  --label "$citybuddy_commit_label=$citybuddy_commit" \
  --network "container:citybuddy-bench-net" \
  --volume "$repo_root/scripts/fake_litellm_server.py:/opt/fake.py:ro" \
  --entrypoint /opt/citybuddy/.venv/bin/python \
  "$agent_image_id" /opt/fake.py --port 8000)"
model_started_at="$(container_started_at citybuddy-bench-model)"
model_restart_count="$(container_restart_count citybuddy-bench-model)"

agent_container_id="$(docker run --detach --name citybuddy-bench-agent \
  --label "$setup_nonce_label=$setup_nonce" \
  --label "$citybuddy_commit_label=$citybuddy_commit" \
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
  "$agent_image_id")"
agent_started_at="$(container_started_at citybuddy-bench-agent)"
agent_restart_count="$(container_restart_count citybuddy-bench-agent)"

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
  --label "$setup_nonce_label=$setup_nonce" \
  --label "$citybuddy_commit_label=$citybuddy_commit" \
  --network "container:citybuddy-bench-net" \
  --volume "$repo_root/bench/agent/build_agent_pool.py:/opt/build_agent_pool.py:ro" \
  --volume "$run_dir:/run-data" \
  --entrypoint /opt/citybuddy/.venv/bin/python \
  "$agent_image_id" /opt/build_agent_pool.py \
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

es_container_image_id="$(container_image_id citybuddy-bench-elasticsearch)"
auth_container_image_id="$(container_image_id citybuddy-bench-auth)"
commerce_container_image_id="$(container_image_id citybuddy-bench-commerce)"
net_container_image_id="$(container_image_id citybuddy-bench-net)"
model_container_image_id="$(container_image_id citybuddy-bench-model)"
agent_container_image_id="$(container_image_id citybuddy-bench-agent)"
if [ "$es_container_image_id" != "$elasticsearch_image_id" ] \
  || [ "$auth_container_image_id" != "$java_runtime_image_id" ] \
  || [ "$commerce_container_image_id" != "$java_runtime_image_id" ] \
  || [ "$net_container_image_id" != "$net_image_id" ] \
  || [ "$model_container_image_id" != "$agent_image_id" ] \
  || [ "$agent_container_image_id" != "$agent_image_id" ]; then
  echo "A setup container does not use the immutable image built for this checkout." >&2
  exit 1
fi
verify_mysql_container
verify_setup_container citybuddy-bench-elasticsearch "$es_container_id" \
  "$es_container_image_id" "$es_started_at" "$es_restart_count"
verify_setup_container citybuddy-bench-auth "$auth_container_id" \
  "$auth_container_image_id" "$auth_started_at" "$auth_restart_count"
verify_setup_container citybuddy-bench-commerce "$commerce_container_id" \
  "$commerce_container_image_id" "$commerce_started_at" "$commerce_restart_count"
verify_setup_container citybuddy-bench-net "$net_container_id" \
  "$net_container_image_id" "$net_started_at" "$net_restart_count"
verify_setup_container citybuddy-bench-model "$model_container_id" \
  "$model_container_image_id" "$model_started_at" "$model_restart_count"
verify_setup_container citybuddy-bench-agent "$agent_container_id" \
  "$agent_container_image_id" "$agent_started_at" "$agent_restart_count"

setup_completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
environment_tmp="$(mktemp "$run_dir/.agent_setup_environment.XXXXXX")"
commit_tmp="$(mktemp "$run_dir/.citybuddy_commit.XXXXXX")"
echo "== publishing the completed agent benchmark environment =="
uv run python - \
  "$environment_tmp" \
  "$setup_nonce" \
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
  "$es_container_id" \
  "$es_container_image_id" \
  "$es_started_at" \
  "$es_restart_count" \
  "$auth_container_id" \
  "$auth_container_image_id" \
  "$auth_started_at" \
  "$auth_restart_count" \
  "$commerce_container_id" \
  "$commerce_container_image_id" \
  "$commerce_started_at" \
  "$commerce_restart_count" \
  "$net_container_id" \
  "$net_container_image_id" \
  "$net_started_at" \
  "$net_restart_count" \
  "$model_container_id" \
  "$model_container_image_id" \
  "$model_started_at" \
  "$model_restart_count" \
  "$agent_container_id" \
  "$agent_container_image_id" \
  "$agent_started_at" \
  "$agent_restart_count" \
  "$mysql_container_id" \
  "$mysql_image_id" \
  "$mysql_configured_image" \
  "$mysql_started_at" \
  "$mysql_restart_count" \
  "$auth_migration_version" \
  "$commerce_migration_version" \
  "$agent_migration_version" \
  "$knowledge_bootstrap_file" <<'PY'
from __future__ import annotations

import json
import sys
from pathlib import Path

(
    output_name,
    setup_nonce,
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
    elasticsearch_container_id,
    elasticsearch_image_id,
    elasticsearch_started_at,
    elasticsearch_restart_count,
    auth_container_id,
    auth_image_id,
    auth_started_at,
    auth_restart_count,
    commerce_container_id,
    commerce_image_id,
    commerce_started_at,
    commerce_restart_count,
    net_container_id,
    net_image_id,
    net_started_at,
    net_restart_count,
    model_container_id,
    model_image_id,
    model_started_at,
    model_restart_count,
    agent_container_id,
    agent_image_id,
    agent_started_at,
    agent_restart_count,
    mysql_container_id,
    mysql_image_id,
    mysql_configured_image,
    mysql_started_at,
    mysql_restart_count,
    auth_migration_version,
    commerce_migration_version,
    agent_migration_version,
    knowledge_bootstrap_name,
) = sys.argv[1:]


label_values = {
    "citybuddy.bench.citybuddy-commit": commit,
    "citybuddy.bench.setup-nonce": setup_nonce,
}


def container(
    container_id: str, image_id: str, started_at: str, restart_count: str
) -> dict[str, object]:
    return {
        "id": container_id,
        "imageId": image_id,
        "labels": label_values,
        "restartCount": int(restart_count),
        "startedAt": started_at,
    }


environment = {
    "citybuddyCommit": commit,
    "setupNonce": setup_nonce,
    "configuration": {
        "agentAttemptBudget": int(attempt_budget),
        "agentBenchUsers": int(users),
        "metricsEnabled": True,
        "traceExportUrl": "",
    },
    "containers": {
        "citybuddy-bench-agent": container(
            agent_container_id, agent_image_id, agent_started_at, agent_restart_count
        ),
        "citybuddy-bench-auth": container(
            auth_container_id, auth_image_id, auth_started_at, auth_restart_count
        ),
        "citybuddy-bench-commerce": container(
            commerce_container_id,
            commerce_image_id,
            commerce_started_at,
            commerce_restart_count,
        ),
        "citybuddy-bench-elasticsearch": container(
            elasticsearch_container_id,
            elasticsearch_image_id,
            elasticsearch_started_at,
            elasticsearch_restart_count,
        ),
        "citybuddy-bench-model": container(
            model_container_id, model_image_id, model_started_at, model_restart_count
        ),
        "citybuddy-bench-net": container(
            net_container_id, net_image_id, net_started_at, net_restart_count
        ),
    },
    "formatVersion": "citybuddy-agent-setup-environment-v1",
    "java": {
        "authService": {
            "artifact": "auth-service/target/auth-service-0.0.1-SNAPSHOT.jar",
            "hostJarSha256": auth_host_sha,
            "javaRuntimeVersion": auth_runtime,
            "mountedContainer": "citybuddy-bench-auth",
            "mountedJar": "/opt/citybuddy/auth.jar",
            "mountedJarSha256": auth_mounted_sha,
        },
        "commerceService": {
            "artifact": "commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar",
            "hostJarSha256": commerce_host_sha,
            "javaRuntimeVersion": commerce_runtime,
            "mountedContainer": "citybuddy-bench-commerce",
            "mountedJar": "/opt/citybuddy/commerce.jar",
            "mountedJarSha256": commerce_mounted_sha,
        },
    },
    "knowledgeBootstrapRawJson": Path(knowledge_bootstrap_name).read_text(encoding="utf-8"),
    "mysql": {
        "agent": {"latestVersion": agent_migration_version},
        "auth": {"latestVersion": auth_migration_version},
        "commerce": {"latestVersion": commerce_migration_version},
        "container": {
            "configuredImage": mysql_configured_image,
            "id": mysql_container_id,
            "imageId": mysql_image_id,
            "restartCount": int(mysql_restart_count),
            "startedAt": mysql_started_at,
        },
    },
    "migrationCommands": [
        {"command": "make ENV_FILE=.env COMPOSE_PROJECT_NAME=citybuddy grant-access", "status": "succeeded"},
        {"command": "make ENV_FILE=.env COMPOSE_PROJECT_NAME=citybuddy migrate-auth", "status": "succeeded"},
        {"command": "make ENV_FILE=.env COMPOSE_PROJECT_NAME=citybuddy migrate-commerce", "status": "succeeded"},
        {"command": "make ENV_FILE=.env COMPOSE_PROJECT_NAME=citybuddy migrate-agent", "status": "succeeded"},
        {"command": "make ENV_FILE=.env COMPOSE_PROJECT_NAME=citybuddy grant-access", "status": "succeeded"},
    ],
    "setupWindowUtc": {"completedAt": completed_at, "startedAt": started_at},
}
Path(output_name).write_text(
    json.dumps(environment, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
printf '%s\n' "$citybuddy_commit" > "$commit_tmp"
mv "$environment_tmp" "$environment_file"
environment_tmp=""
mv "$commit_tmp" "$commit_file"
commit_tmp=""
setup_complete=true
trap - EXIT HUP INT TERM
