from __future__ import annotations

import os
import re
import stat
import subprocess
from pathlib import Path

import pytest
import yaml  # type: ignore[import-untyped]
from yaml.constructor import ConstructorError  # type: ignore[import-untyped]
from yaml.nodes import MappingNode  # type: ignore[import-untyped]
from yaml.tokens import AliasToken, AnchorToken  # type: ignore[import-untyped]

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
    "REDIS_AGENT_CACHE_PASSWORD",
    "REDIS_INDEXER_CACHE_PASSWORD",
)
REQUIRED_INTEGRATION_TARGETS = (
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
    "test-knowledge-sync-integration",
    "test-knowledge-rebuild-integration",
)
INVALID_INTEGRATION_TIMEOUT_CASES = (
    "nested-timeout",
    "notes-block-scalar",
    "direct-timeout-block-scalar",
    "missing-direct-timeout",
    "duplicate-direct-timeout",
    "boolean-timeout",
    "job-timeout-in-metadata",
    "job-timeout-in-strategy",
    "job-timeout-in-env",
    "job-timeout-in-steps",
    "job-timeout-fixed",
    "duplicate-shard-index",
    "missing-shard-entry",
    "inconsistent-shard-count",
    "shard-index-out-of-range",
    "ordinary-budget-too-large",
    "extra-target",
    "missing-target",
    "duplicate-target",
    "merge-key-timeout",
    "anchor-only",
    "alias-entire-entry",
)


class StrictWorkflowLoader(yaml.SafeLoader):  # type: ignore[misc]
    def construct_mapping(
        self,
        node: yaml.Node,
        deep: bool = False,
    ) -> dict[object, object]:
        if not isinstance(node, MappingNode):
            raise ConstructorError(
                None,
                None,
                "expected a mapping node",
                node.start_mark,
            )

        mapping: dict[object, object] = {}
        for key_node, value_node in node.value:
            if key_node.tag == "tag:yaml.org,2002:merge":
                raise ConstructorError(
                    "while constructing a workflow mapping",
                    node.start_mark,
                    "YAML merge keys are not allowed",
                    key_node.start_mark,
                )
            key = self.construct_object(key_node, deep=deep)
            try:
                duplicate = key in mapping
            except TypeError as exc:
                raise ConstructorError(
                    "while constructing a workflow mapping",
                    node.start_mark,
                    "found an unhashable mapping key",
                    key_node.start_mark,
                ) from exc
            if duplicate:
                raise ConstructorError(
                    "while constructing a workflow mapping",
                    node.start_mark,
                    f"found duplicate key {key!r}",
                    key_node.start_mark,
                )
            mapping[key] = self.construct_object(value_node, deep=deep)
        return mapping


def load_strict_workflow(text: str) -> dict[object, object]:
    for token in yaml.scan(text, Loader=StrictWorkflowLoader):
        if isinstance(token, AnchorToken | AliasToken):
            raise ConstructorError(
                "while scanning a workflow",
                token.start_mark,
                "YAML anchors and aliases are not allowed",
                token.start_mark,
            )
    loaded = yaml.load(text, Loader=StrictWorkflowLoader)
    assert type(loaded) is dict
    return loaded


def validate_integration_timeout_contract(text: str) -> dict[str, int]:
    workflow = load_strict_workflow(text)
    jobs = workflow["jobs"]
    assert type(jobs) is dict
    integration = jobs["integration"]
    assert type(integration) is dict
    assert integration["timeout-minutes"] == "${{ matrix.timeout_minutes }}"

    strategy = integration["strategy"]
    assert type(strategy) is dict
    matrix = strategy["matrix"]
    assert type(matrix) is dict
    include = matrix["include"]
    assert type(include) is list

    timeout_by_target: dict[str, int] = {}
    entries_by_target: dict[str, int] = {}
    # A target may appear more than once only to divide one suite across shards, and those entries
    # must together cover every shard index exactly once. A repeated or missing index would leave
    # part of a sharded matrix unprobed while CI still reported green, so the workflow declaration
    # is checked here instead of trusted.
    shards_by_target: dict[str, set[int]] = {}
    shard_count_by_target: dict[str, int] = {}
    for entry in include:
        assert type(entry) is dict
        target = entry["target"]
        timeout_minutes = entry["timeout_minutes"]
        assert type(target) is str
        assert type(timeout_minutes) is int
        if target in timeout_by_target:
            assert timeout_by_target[target] == timeout_minutes
        timeout_by_target[target] = timeout_minutes
        entries_by_target[target] = entries_by_target.get(target, 0) + 1
        if "payment_matrix_shard" in entry or "payment_matrix_shards" in entry:
            shard = entry["payment_matrix_shard"]
            shard_count = entry["payment_matrix_shards"]
            assert type(shard) is int
            assert type(shard_count) is int
            assert shard_count >= 1
            assert 0 <= shard < shard_count
            assert shard_count_by_target.setdefault(target, shard_count) == shard_count
            assert shard not in shards_by_target.setdefault(target, set())
            shards_by_target[target].add(shard)

    for target, entry_count in entries_by_target.items():
        shards = shards_by_target.get(target)
        if shards is None:
            assert entry_count == 1
        else:
            assert entry_count == len(shards)
            assert shards == set(range(shard_count_by_target[target]))

    assert set(timeout_by_target) == set(REQUIRED_INTEGRATION_TARGETS)
    assert all(timeout == 30 for timeout in timeout_by_target.values())
    return timeout_by_target


def replace_once(text: str, old: str, new: str) -> str:
    assert text.count(old) == 1
    return text.replace(old, new, 1)


def invalid_integration_timeout_workflows(workflow: str) -> dict[str, str]:
    job_timeout = "    timeout-minutes: ${{ matrix.timeout_minutes }}\n"
    runtime_entry = """          - target: test-runtime-integration
            java: false
            python: false
            timeout_minutes: 30
"""
    mysql_entry = """          - target: test-mysql-integration
            java: false
            python: false
            timeout_minutes: 30
"""

    def sandbox_shard_entry(shard: int, shard_count: int = 8) -> str:
        return f"""          - target: test-evaluation-sandbox-integration
            lane: test-evaluation-sandbox-integration shard {shard} of {shard_count}
            payment_matrix_shards: {shard_count}
            payment_matrix_shard: {shard}
            java: true
            python: true
            timeout_minutes: 30
"""

    rebuild_tail = """          - target: test-knowledge-rebuild-integration
            java: true
            python: true
            timeout_minutes: 30
    steps:
"""

    without_job_timeout = replace_once(workflow, job_timeout, "")
    job_timeout_in_strategy = replace_once(
        workflow,
        job_timeout + "    strategy:\n",
        ("    strategy:\n      timeout-minutes: ${{ matrix.timeout_minutes }}\n"),
    )
    integration_step = """      - name: Run integration suite
        env:
          SANDBOX_PAYMENT_MATRIX_SHARDS: ${{ matrix.payment_matrix_shards }}
          SANDBOX_PAYMENT_MATRIX_SHARD: ${{ matrix.payment_matrix_shard }}
        run: make ${{ matrix.target }}
"""
    job_timeout_in_steps = replace_once(
        without_job_timeout,
        integration_step,
        integration_step.replace(
            "      - name: Run integration suite\n",
            "      - name: Run integration suite\n"
            "        timeout-minutes: ${{ matrix.timeout_minutes }}\n",
        ),
    )
    anchored_runtime_entry = runtime_entry.replace(
        "          - target:",
        "          - &runtime\n            target:",
        1,
    )

    return {
        "nested-timeout": replace_once(
            workflow,
            runtime_entry,
            runtime_entry.replace(
                "            timeout_minutes: 30\n",
                "            metadata:\n              timeout_minutes: 30\n",
            ),
        ),
        "notes-block-scalar": replace_once(
            workflow,
            runtime_entry,
            runtime_entry.replace(
                "            timeout_minutes: 30\n",
                "            notes: |\n              timeout_minutes: 30\n",
            ),
        ),
        "direct-timeout-block-scalar": replace_once(
            workflow,
            runtime_entry,
            runtime_entry.replace(
                "            timeout_minutes: 30\n",
                "            timeout_minutes: |\n              30\n",
            ),
        ),
        "missing-direct-timeout": replace_once(
            workflow,
            runtime_entry,
            runtime_entry.replace("            timeout_minutes: 30\n", ""),
        ),
        "duplicate-direct-timeout": replace_once(
            workflow,
            runtime_entry,
            runtime_entry.replace(
                "            timeout_minutes: 30\n",
                ("            timeout_minutes: 30\n            timeout_minutes: 30\n"),
            ),
        ),
        "boolean-timeout": replace_once(
            workflow,
            runtime_entry,
            runtime_entry.replace("timeout_minutes: 30", "timeout_minutes: true"),
        ),
        "job-timeout-in-metadata": replace_once(
            workflow,
            job_timeout,
            ("    metadata:\n      timeout-minutes: ${{ matrix.timeout_minutes }}\n"),
        ),
        "job-timeout-in-strategy": job_timeout_in_strategy,
        "job-timeout-in-env": replace_once(
            workflow,
            job_timeout,
            ("    env:\n      timeout-minutes: ${{ matrix.timeout_minutes }}\n"),
        ),
        "job-timeout-in-steps": job_timeout_in_steps,
        "job-timeout-fixed": replace_once(
            workflow,
            job_timeout,
            "    timeout-minutes: 30\n",
        ),
        "duplicate-shard-index": replace_once(
            workflow,
            sandbox_shard_entry(1),
            sandbox_shard_entry(1).replace(
                "payment_matrix_shard: 1",
                "payment_matrix_shard: 0",
            ),
        ),
        "missing-shard-entry": replace_once(workflow, sandbox_shard_entry(7), ""),
        "inconsistent-shard-count": replace_once(
            workflow,
            sandbox_shard_entry(0),
            sandbox_shard_entry(0).replace(
                "payment_matrix_shards: 8",
                "payment_matrix_shards: 4",
            ),
        ),
        "shard-index-out-of-range": replace_once(
            workflow,
            sandbox_shard_entry(0),
            sandbox_shard_entry(0).replace(
                "payment_matrix_shard: 0",
                "payment_matrix_shard: 8",
            ),
        ),
        "ordinary-budget-too-large": replace_once(
            workflow,
            runtime_entry,
            runtime_entry.replace("timeout_minutes: 30", "timeout_minutes: 60"),
        ),
        "extra-target": replace_once(
            workflow,
            rebuild_tail,
            rebuild_tail.replace(
                "    steps:\n",
                (
                    "          - target: test-extra-integration\n"
                    "            java: false\n"
                    "            python: false\n"
                    "            timeout_minutes: 30\n"
                    "    steps:\n"
                ),
            ),
        ),
        "missing-target": replace_once(workflow, runtime_entry, ""),
        "duplicate-target": replace_once(
            workflow,
            runtime_entry,
            runtime_entry + runtime_entry,
        ),
        "merge-key-timeout": replace_once(
            workflow,
            runtime_entry,
            runtime_entry.replace(
                "            timeout_minutes: 30\n",
                "            <<: {timeout_minutes: 30}\n",
            ),
        ),
        "anchor-only": replace_once(
            workflow,
            runtime_entry,
            anchored_runtime_entry,
        ),
        "alias-entire-entry": replace_once(
            replace_once(
                workflow,
                runtime_entry,
                anchored_runtime_entry,
            ),
            mysql_entry,
            "          - *runtime\n",
        ),
    }


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
    assert values["AGENT_SUPPORT_REDIS_URL"] == (
        f"redis://agent_cache:{values['REDIS_AGENT_CACHE_PASSWORD']}@redis-support:6379/0"
    )
    assert values["INDEXER_SUPPORT_REDIS_URL"] == (
        f"redis://knowledge_indexer:{values['REDIS_INDEXER_CACHE_PASSWORD']}@redis-support:6379/0"
    )
    assert values["COMMERCE_REDIS_URL"] != values["SUPPORT_REDIS_URL"]
    assert not any(name.endswith("_PORT") for name in values)

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


def test_init_local_upgrades_legacy_env_without_rotating_existing_values(tmp_path: Path) -> None:
    env_file = tmp_path / ".env"
    first = run_script("init_local.sh", env_file)
    assert first.returncode == 0, first.stderr
    values = parse_env(env_file)
    legacy_names = {
        *CREDENTIAL_NAMES[:-2],
        "COMMERCE_REDIS_URL",
        "SUPPORT_REDIS_URL",
    }
    legacy = "\n".join(f"{name}={value}" for name, value in values.items() if name in legacy_names)
    env_file.write_text(f"{legacy}\n")

    upgraded = run_script("init_local.sh", env_file)

    assert upgraded.returncode == 0, upgraded.stderr
    assert "Added CB-112 Support Redis cache identities" in upgraded.stdout
    updated = parse_env(env_file)
    for name in legacy_names:
        assert updated[name] == values[name]
    assert re.fullmatch(r"[0-9a-f]{48}", updated["REDIS_AGENT_CACHE_PASSWORD"])
    assert re.fullmatch(r"[0-9a-f]{48}", updated["REDIS_INDEXER_CACHE_PASSWORD"])


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
    assert "REDIS_AGENT_CACHE_PASSWORD" in support_config
    assert "REDIS_INDEXER_CACHE_PASSWORD" in support_config
    assert "start_support_redis.sh" in support_config
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


def test_knowledge_indexer_runtime_has_only_broker_elasticsearch_and_cache_authority() -> None:
    compose = (ROOT / "compose.yaml").read_text()
    runtime = re.search(
        r"(?ms)^  knowledge-indexer:\n(.*?)(?=^  [a-z][^:\n]*:\n|^volumes:)", compose
    )
    assert runtime is not None
    configuration = runtime.group(1)
    assert 'profiles: ["application"]' in configuration
    assert "infra/knowledge-indexer/Dockerfile" in configuration
    assert "rocketmq-broker-proxy:8081" in configuration
    assert "http://elasticsearch:9200" in configuration
    assert "knowledge_docs_read" in configuration
    assert "mysql" not in configuration.casefold()
    assert "redis-support" in configuration
    assert "INDEXER_SUPPORT_REDIS_URL" in configuration
    assert "redis-commerce" not in configuration
    assert "PASSWORD" not in configuration


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
    assert "- target: 8081" in compose
    assert "published:" not in compose
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
    positions = [aggregate_commands.index(target) for target in REQUIRED_INTEGRATION_TARGETS]
    assert positions == sorted(positions)
    assert "ci: java-ci python-ci web-ci repo-ci test-integration" in makefile
    assert "setup: setup-java setup-python setup-web setup-repo" in makefile

    assert validate_integration_timeout_contract(workflow) == {
        target: 30 for target in REQUIRED_INTEGRATION_TARGETS
    }
    for target in ("java-ci", "python-ci", "web-ci", "repo-ci"):
        assert f"run: make {target}" in workflow
    assert "cancel-in-progress: true" in workflow
    assert "fail-fast: false" in workflow
    assert "if: always()" in workflow
    assert "needs: [java, python, web, repository, integration]" in workflow
    assert workflow.count('test "$result" = success') == 1

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
    assert "allocate_test_ports" not in mysql_integration
    assert 'source "$repo_root/scripts/test_dynamic_ports.sh"' in mysql_integration


@pytest.mark.parametrize(
    "case_name",
    INVALID_INTEGRATION_TIMEOUT_CASES,
)
def test_integration_timeout_contract_rejects_non_direct_or_incomplete_metadata(
    case_name: str,
) -> None:
    workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text()
    invalid_workflows = invalid_integration_timeout_workflows(workflow)

    assert set(invalid_workflows) == set(INVALID_INTEGRATION_TIMEOUT_CASES)
    with pytest.raises((AssertionError, KeyError, ConstructorError)):
        validate_integration_timeout_contract(invalid_workflows[case_name])


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
