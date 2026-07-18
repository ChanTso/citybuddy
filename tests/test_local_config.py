from __future__ import annotations

import os
import re
import stat
import subprocess
from pathlib import Path

ROOT = Path(__file__).parents[1]
CREDENTIAL_NAMES = (
    "MYSQL_BOOTSTRAP_PASSWORD",
    "MYSQL_AUTH_MIGRATION_PASSWORD",
    "MYSQL_COMMERCE_MIGRATION_PASSWORD",
    "MYSQL_AGENT_MIGRATION_PASSWORD",
    "MYSQL_AUTH_APP_PASSWORD",
    "MYSQL_COMMERCE_APP_PASSWORD",
    "MYSQL_AGENT_APP_PASSWORD",
    "REDIS_COMMERCE_PASSWORD",
    "REDIS_SUPPORT_PASSWORD",
)


def run_script(script: str, env_file: Path) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["ENV_FILE"] = str(env_file)
    return subprocess.run(
        [str(ROOT / "scripts" / script)],
        cwd=ROOT,
        env=env,
        check=False,
        capture_output=True,
        text=True,
    )


def parse_env(path: Path) -> dict[str, str]:
    return dict(
        line.split("=", maxsplit=1)
        for line in path.read_text().splitlines()
        if line and not line.startswith("#")
    )


def test_init_local_creates_private_distinct_credentials_and_preserves_them(
    tmp_path: Path,
) -> None:
    env_file = tmp_path / ".env"

    first = run_script("init_local.sh", env_file)

    assert first.returncode == 0, first.stderr
    original = env_file.read_bytes()
    values = parse_env(env_file)
    credentials = [values[name] for name in CREDENTIAL_NAMES]
    assert all(re.fullmatch(r"[0-9a-f]{48}", value) for value in credentials)
    assert len(set(credentials)) == len(credentials)
    assert stat.S_IMODE(env_file.stat().st_mode) == 0o600
    assert values["COMMERCE_REDIS_URL"] == (
        f"redis://:{values['REDIS_COMMERCE_PASSWORD']}@redis-commerce:6379/0"
    )
    assert values["SUPPORT_REDIS_URL"] == (
        f"redis://:{values['REDIS_SUPPORT_PASSWORD']}@redis-support:6379/0"
    )
    assert values["COMMERCE_REDIS_URL"] != values["SUPPORT_REDIS_URL"]
    assert values["ROCKETMQ_PROXY_PORT"] == "8081"

    second = run_script("init_local.sh", env_file)

    assert second.returncode == 0, second.stderr
    assert "preserving it unchanged" in second.stdout
    assert env_file.read_bytes() == original


def test_require_local_env_rejects_missing_or_malformed_credentials(tmp_path: Path) -> None:
    env_file = tmp_path / ".env"
    missing = run_script("require_local_env.sh", env_file)
    assert missing.returncode != 0
    assert "Missing local configuration" in missing.stderr

    env_file.write_text("MYSQL_BOOTSTRAP_PASSWORD=not-a-credential\n")
    malformed = run_script("require_local_env.sh", env_file)
    assert malformed.returncode != 0
    assert "Invalid or missing MYSQL_BOOTSTRAP_PASSWORD" in malformed.stderr


def test_example_and_compose_contain_no_credential_defaults() -> None:
    example = parse_env(ROOT / ".env.example")
    assert all(example[name] == "" for name in CREDENTIAL_NAMES)

    compose = (ROOT / "compose.yaml").read_text()
    assert "mysql:8.4.10@sha256:" in compose
    assert "redis:7.2.14-bookworm@sha256:" in compose
    for name in CREDENTIAL_NAMES:
        assert f"${{{name}:?" in compose


def test_compose_defines_distinct_authenticated_redis_policies_and_storage() -> None:
    compose = (ROOT / "compose.yaml").read_text()

    commerce = re.search(r"(?ms)^  redis-commerce:\n(.*?)(?=^  [a-z][^:\n]*:\n|^volumes:)", compose)
    support = re.search(r"(?ms)^  redis-support:\n(.*?)(?=^  [a-z][^:\n]*:\n|^volumes:)", compose)
    assert commerce is not None
    assert support is not None

    commerce_config = commerce.group(1)
    support_config = support.group(1)
    assert "REDIS_COMMERCE_PASSWORD" in commerce_config
    assert "--maxmemory-policy\n      - noeviction" in commerce_config
    assert '--appendonly\n      - "yes"' in commerce_config
    assert "redis-commerce-data:/data" in commerce_config
    assert "PING | grep -qx PONG" in commerce_config
    assert "CONFIG GET maxmemory-policy" in commerce_config
    assert "CONFIG GET appendonly" in commerce_config

    assert "REDIS_SUPPORT_PASSWORD" in support_config
    assert "--maxmemory-policy\n      - volatile-lfu" in support_config
    assert '--appendonly\n      - "no"' in support_config
    assert "--maxmemory\n      - 64mb" in support_config
    assert "redis-support-data:/data" in support_config
    assert "PING | grep -qx PONG" in support_config
    assert "CONFIG GET maxmemory-policy" in support_config
    assert "CONFIG GET maxmemory" in support_config


def test_elasticsearch_image_and_health_pin_the_matching_ik_analyzer() -> None:
    compose = (ROOT / "compose.yaml").read_text()
    dockerfile = (ROOT / "infra" / "elasticsearch" / "Dockerfile").read_text()
    integration = (ROOT / "scripts" / "test_elasticsearch_integration.sh").read_text()

    assert (
        "docker.elastic.co/elasticsearch/elasticsearch:8.19.8@sha256:"
        "1b6a877f18352510860ee065f01472bd37d33ac5eb1d943e0b9ed366b149638c"
    ) in dockerfile
    assert "https://get.infini.cloud/elasticsearch/analysis-ik/8.19.8" in dockerfile
    assert "0afb783891e7a5443ef45b8964a2cb8d6ac2421827f94c587d1827936f00b81d" in dockerfile
    assert "sha256sum --check" in dockerfile
    assert "elasticsearch-plugin install --batch" in dockerfile
    assert "${ELASTICSEARCH_IMAGE:-citybuddy-elasticsearch-ik:8.19.8}" in compose

    elasticsearch = re.search(
        r"(?ms)^  elasticsearch:\n(.*?)(?=^  [a-z][^:\n]*:\n|^volumes:)", compose
    )
    assert elasticsearch is not None
    elasticsearch_config = elasticsearch.group(1)
    assert "_cluster/health?wait_for_status=yellow" in elasticsearch_config
    assert "_cat/plugins?h=component,version" in elasticsearch_config
    assert "analysis-ik[[:space:]]+8.19.8" in elasticsearch_config
    assert '"analyzer":"ik_smart"' in elasticsearch_config

    assert '"type":"dense_vector"' in integration
    assert '"knn"' in integration
    assert 'POST "/_aliases"' in integration
    assert 'ELASTICSEARCH_IMAGE="citybuddy-elasticsearch-ik:${project}"' in integration
    assert 'fault_project="${project}-missing-ik"' in integration
    assert 'env ELASTICSEARCH_IMAGE="$missing_ik_image"' in integration
    assert "fault_container_id" in integration
    assert "fault_health" in integration
    assert "container .*elasticsearch.* is unhealthy" in integration
    assert "knowledge_docs_v" not in integration


def test_rocketmq_runtime_uses_pinned_proxy_and_grpc_probe() -> None:
    compose = (ROOT / "compose.yaml").read_text()
    probe_pom = (ROOT / "infra" / "rocketmq" / "probe" / "pom.xml").read_text()
    probe_java = (
        ROOT
        / "infra"
        / "rocketmq"
        / "probe"
        / "src"
        / "main"
        / "java"
        / "io"
        / "citybuddy"
        / "rocketmq"
        / "RocketMqProbe.java"
    ).read_text()
    integration = (ROOT / "scripts" / "test_rocketmq_integration.sh").read_text()

    assert (
        "apache/rocketmq:5.5.0@sha256:"
        "7e8f6c9dbd9df742ed26ba69c00d4ad69e2f86a56f3ca7782ff8144dd0798132"
    ) in compose
    assert "--enable-proxy" in compose
    assert 'user: "0:0"' in compose
    assert "chown -R 3000:3000 /home/rocketmq/store" in compose
    assert '"127.0.0.1:${ROCKETMQ_PROXY_PORT:-8081}:8081"' in compose
    assert "clusterList --namesrvAddr rocketmq-namesrv:9876" in compose
    assert "rocketmq-probe.jar\n        - route" in compose
    assert "<artifactId>rocketmq-client-java</artifactId>" in probe_pom
    assert "<rocketmq-client-java.version>5.2.1</rocketmq-client-java.version>" in probe_pom
    assert ".setEndpoints(endpoints)" in probe_java
    assert ".setRequestTimeout(Duration.ofSeconds(10))" in probe_java
    assert ".enableSsl(false)" in probe_java
    assert "ROUND_TRIP_OK" in probe_java
    assert "consumer.ack(consumed)" in probe_java
    assert "consumer.receive(8, INVISIBLE_DURATION)" in probe_java
    assert "Expected one matching probe message in the receive batch" in probe_java
    assert "ACKNOWLEDGED" not in probe_java
    assert "secondDelivery" not in probe_java
    assert "deleteTopic" in integration
    assert "deleteSubGroup" in integration
    assert "ROCKETMQ_PROXY_ARGS=" in integration
    assert "produced=1 consumed=1" in integration


def test_local_ci_order_and_parallel_workflow_cover_every_required_target() -> None:
    makefile = (ROOT / "Makefile").read_text()
    workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text()
    integration = (ROOT / "scripts" / "test_runtime_integration.sh").read_text()
    mysql_integration = (ROOT / "scripts" / "test_mysql_integration.sh").read_text()

    aggregate_target = re.search(
        r"(?ms)^test-integration:\n(.*?)(?=^[a-zA-Z][^:\n]*:|\Z)", makefile
    )
    assert aggregate_target is not None
    aggregate_commands = aggregate_target.group(1)
    expected_targets = (
        "test-runtime-integration",
        "test-mysql-integration",
        "test-identity-integration",
        "test-evaluation-identity-integration",
        "test-evaluation-sandbox-integration",
        "test-catalog-integration",
        "test-redis-integration",
        "test-elasticsearch-integration",
        "test-knowledge-search-integration",
        "test-retrieval-evidence-integration",
        "test-rocketmq-integration",
        "test-knowledge-indexer-rocketmq-spike",
    )
    positions = [aggregate_commands.index(target) for target in expected_targets]
    assert positions == sorted(positions)
    assert "ci: java-ci python-ci web-ci repo-ci test-integration" in makefile
    assert "setup: setup-java setup-python setup-web setup-repo" in makefile

    matrix_targets = re.findall(r"^\s+- target: (test-[a-z0-9-]+)$", workflow, flags=re.MULTILINE)
    assert matrix_targets == list(expected_targets)
    for target in ("java-ci", "python-ci", "web-ci", "repo-ci"):
        assert f"run: make {target}" in workflow
    assert "cancel-in-progress: true" in workflow
    assert "fail-fast: false" in workflow
    assert "if: always()" in workflow
    assert "needs: [java, python, web, repository, integration]" in workflow
    assert workflow.count('test "$result" = success') == 1
    assert "timeout-minutes: 30" in workflow

    for service in (
        "mysql",
        "redis-commerce",
        "redis-support",
        "elasticsearch",
        "rocketmq-namesrv",
        "rocketmq-broker-proxy",
        "rocketmq-probe",
    ):
        assert service in integration
    assert "auth_schema_history" in integration
    assert "commerce_schema_history" in integration
    assert "agent_schema_history" in integration
    assert "preserves existing credentials" in integration
    assert "down preserves all durable volumes" in integration
    assert "sleep " not in integration
    assert 'REDIS_COMMERCE_PORT="$((35000 + ($$ % 700)))"' in mysql_integration
    assert 'REDIS_SUPPORT_PORT="$((36000 + ($$ % 700)))"' in mysql_integration


def test_grant_job_uses_only_fixed_manifest_and_isolated_bootstrap_config() -> None:
    script = (ROOT / "scripts" / "apply_mysql_grants.sh").read_text()
    manifest = (ROOT / "infra" / "mysql" / "grants" / "V001__migration_access.sql").read_text()
    compose = (ROOT / "compose.yaml").read_text()

    statements = [line for line in manifest.splitlines() if line]
    assert statements
    assert all(line.startswith(("GRANT ", "REVOKE ")) for line in statements)
    assert "V001__migration_access.sql" in script
    assert "Grant job rejects caller-supplied SQL" in script
    assert "--activate-all-roles-on-login=OFF" in compose

    migration_anchor = re.search(
        r"(?ms)^x-migration-service:\s*&migration-service\n(.*?)(?=^\S|\Z)",
        compose,
    )
    assert migration_anchor is not None
    migration_config_blocks = [migration_anchor.group(1)]

    for service in ("auth-migrate", "commerce-migrate", "agent-migrate"):
        match = re.search(
            rf"(?ms)^  {re.escape(service)}:\n(.*?)(?=^  [a-z][^:\n]*:\n|\Z)",
            compose,
        )
        assert match is not None
        migration_config_blocks.append(match.group(1))

    for config_block in migration_config_blocks:
        assert "MYSQL_BOOTSTRAP_PASSWORD" not in config_block
        assert "bootstrap_grant_role" not in config_block
