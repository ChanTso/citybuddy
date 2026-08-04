from __future__ import annotations

import json
import os
import stat
import sys
import uuid
from pathlib import Path

import pytest
import redis

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import citybuddy_demo as demo  # noqa: E402

RUN_ID = "20260804-120000-deadbeef"


def isolated_state(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> demo.ActiveRun:
    state_root = tmp_path / ".citybuddy-demo"
    runs_root = state_root / "runs"
    active = state_root / "active.json"
    monkeypatch.setattr(demo, "STATE_ROOT", state_root)
    monkeypatch.setattr(demo, "RUNS_ROOT", runs_root)
    monkeypatch.setattr(demo, "ACTIVE_STATE", active)
    run = demo.ActiveRun(RUN_ID, runs_root / RUN_ID, f"citybuddy-demo-{RUN_ID}")
    run.run_directory.mkdir(parents=True)
    demo.write_json(
        active,
        {
            "project": run.project,
            "runDirectory": str(run.run_directory),
            "runId": run.run_id,
        },
        mode=0o600,
    )
    return run


def test_active_run_requires_exact_confirmation_and_bounded_path(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run = isolated_state(monkeypatch, tmp_path)

    assert demo.ActiveRun.load() == run
    run.require_confirmation(RUN_ID)
    with pytest.raises(demo.DemoError, match="exactly match"):
        run.require_confirmation(f"{RUN_ID}-other")

    escaped = demo.ActiveRun(RUN_ID, tmp_path / RUN_ID, f"citybuddy-demo-{RUN_ID}")
    with pytest.raises(demo.DemoError, match="directory"):
        escaped.validate_scope()


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("project", "citybuddy"),
        ("runId", "../outside"),
        ("runDirectory", "/tmp/outside"),
    ],
)
def test_active_state_rejects_every_scope_mismatch(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    field: str,
    value: str,
) -> None:
    run = isolated_state(monkeypatch, tmp_path)
    payload = json.loads(demo.ACTIVE_STATE.read_text(encoding="utf-8"))
    payload[field] = value
    demo.write_json(demo.ACTIVE_STATE, payload, mode=0o600)

    with pytest.raises(demo.DemoError, match="guard|directory"):
        demo.ActiveRun.load()
    assert run.run_directory.exists()


def test_private_json_is_atomic_and_mode_0600(tmp_path: Path) -> None:
    private = tmp_path / "private.json"
    demo.write_json(private, {"secret": "synthetic"}, mode=0o600)

    assert json.loads(private.read_text(encoding="utf-8")) == {"secret": "synthetic"}
    assert stat.S_IMODE(private.stat().st_mode) == 0o600
    assert not list(tmp_path.glob(".*.tmp"))


def test_private_properties_are_created_once_with_mode_0600(tmp_path: Path) -> None:
    private = tmp_path / "commerce-secrets.properties"
    demo.write_private_text(private, "secret=value\n")

    assert private.read_text(encoding="utf-8") == "secret=value\n"
    assert stat.S_IMODE(private.stat().st_mode) == 0o600
    with pytest.raises(FileExistsError):
        demo.write_private_text(private, "replacement=value\n")


def test_listener_ports_accepts_real_lsof_field_output() -> None:
    assert demo.listener_ports("p26198\nf3\nn127.0.0.1:49779\n") == {49779}
    assert demo.listener_ports("n*:8080\nn[::]:8080\n") == {8080}
    assert demo.listener_ports("n10.0.0.2:9000\n") == set()


def test_stable_uuid4_is_deterministic_with_required_version_and_variant() -> None:
    first = demo.stable_uuid4(RUN_ID, "published-event")
    parsed = uuid.UUID(first)

    assert first == demo.stable_uuid4(RUN_ID, "published-event")
    assert first != demo.stable_uuid4(RUN_ID, "other-event")
    assert parsed.version == 4
    assert parsed.variant == uuid.RFC_4122


def test_knowledge_fixture_identities_match_verified_retrieval_probes() -> None:
    assert demo.KNOWLEDGE_PRODUCT_ID == "product-jasmine-tea"
    assert demo.KNOWLEDGE_REFUND_FAQ_ID == "faq-refund-policy"
    assert demo.KNOWLEDGE_DELIVERY_FAQ_ID == "faq-delivery"
    assert demo.DEMO_AGENT_ATTEMPT_BUDGET == 9


@pytest.mark.parametrize(
    ("support", "indexer", "username", "password_name"),
    [
        (False, False, None, "REDIS_COMMERCE_PASSWORD"),
        (True, False, "agent_cache", "REDIS_AGENT_CACHE_PASSWORD"),
        (True, True, "knowledge_indexer", "REDIS_INDEXER_CACHE_PASSWORD"),
    ],
)
def test_redis_client_uses_committed_acl_identity(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    support: bool,
    indexer: bool,
    username: str | None,
    password_name: str,
) -> None:
    run = isolated_state(monkeypatch, tmp_path)
    demo.write_json(
        run.manifest_file,
        {
            "ports": {"redisCommerce": 16379, "redisSupport": 26379},
            "project": run.project,
            "runId": run.run_id,
            "schemaVersion": demo.SCHEMA_VERSION,
        },
    )
    private = {
        "REDIS_COMMERCE_PASSWORD": "commerce-secret",
        "REDIS_AGENT_CACHE_PASSWORD": "agent-secret",
        "REDIS_INDEXER_CACHE_PASSWORD": "indexer-secret",
    }
    demo.write_json(run.private_file, private, mode=0o600)
    captured: dict[str, object] = {}

    def fake_redis(**arguments: object) -> object:
        captured.update(arguments)
        return object()

    monkeypatch.setattr(redis, "Redis", fake_redis)

    demo.redis_client(run, support=support, indexer=indexer)

    assert captured["username"] == username
    assert captured["password"] == private[password_name]


def test_catalog_cache_control_requires_a_structured_authoritative_refill() -> None:
    assert demo.decode_catalog_cache('{"productId":"product-jasmine-tea"}') == {
        "productId": "product-jasmine-tea"
    }
    with pytest.raises(demo.DemoError, match="omitted"):
        demo.decode_catalog_cache(None)
    with pytest.raises(demo.DemoError, match="malformed"):
        demo.decode_catalog_cache("not-json")


def test_compose_topics_reuses_verified_types_and_named_groups(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run = isolated_state(monkeypatch, tmp_path)
    calls: list[tuple[str, ...]] = []

    def record_compose(run: demo.ActiveRun, *arguments: str, timeout: float = 600) -> str:
        calls.append(arguments)
        return ""

    monkeypatch.setattr(demo, "compose", record_compose)

    demo.compose_topics(run)

    assert any(
        "citybuddy-seckill-transactions" in call and "+message.type=TRANSACTION" in call
        for call in calls
    )
    assert any(
        "citybuddy-seckill-timeouts" in call and "+message.type=DELAY" in call for call in calls
    )
    groups = {call[call.index("--groupName") + 1] for call in calls if "--groupName" in call}
    assert groups == {
        f"cb151-catalog-{RUN_ID}",
        f"cb151-seckill-{RUN_ID}",
        f"cb151-timeout-{RUN_ID}",
        f"cb151-rebuild-{RUN_ID}",
        "citybuddy-knowledge-indexer",
    }


def test_rebuild_bootstraps_verified_predecessor_before_handoff(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run = isolated_state(monkeypatch, tmp_path)
    calls: list[tuple[str, ...]] = []

    def record_compose(run: demo.ActiveRun, *arguments: str, timeout: float = 600) -> str:
        calls.append(arguments)
        if "bootstrap" in arguments:
            return ""
        return '{"candidate":"knowledge_docs_v2","documentCount":3}'

    monkeypatch.setattr(demo, "compose", record_compose)

    demo.rebuild_knowledge(run, {}, {})

    assert "bootstrap" in calls[0]
    assert calls[0][-2:] == ("--index", "knowledge_docs_v1")
    assert any("demo_knowledge_rebuild.py" in argument for argument in calls[1])


def test_stop_process_rejects_a_reused_unrelated_pid(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(demo, "process_exists", lambda pid: True)
    monkeypatch.setattr(demo, "process_command", lambda pid: "unrelated-process")

    with pytest.raises(demo.DemoError, match="unrelated PID"):
        demo.stop_process(12345, "citybuddy-agent")


def test_runtime_container_guard_rejects_unrelated_name(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run = isolated_state(monkeypatch, tmp_path)

    with pytest.raises(demo.DemoError, match="destructive-scope"):
        demo.stop_runtime_container(run, "unrelated-commerce")


def test_runtime_container_names_and_internal_identity_route_are_exact(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run = isolated_state(monkeypatch, tmp_path)
    command = demo.commerce_command(
        run,
        {"REDIS_COMMERCE_PASSWORD": "redis-secret", "MOCK_PAYMENT_KEY_ID": "key-id"},
        {"auth": 12345, "mysql": 3306, "redisCommerce": 6379, "rocketmq": 8081},
        inside_project=True,
    )

    assert demo.runtime_container_name(run, "auth") == f"{run.project}-auth"
    assert demo.runtime_container_name(run, "commerce") == f"{run.project}-commerce"
    assert demo.runtime_container_name(run, "evaluation-commerce") == (
        f"{run.project}-evaluation-commerce"
    )
    assert any("http://cb151-auth:8080/auth/jwks" in argument for argument in command)
    assert all("host.docker.internal" not in argument for argument in command)
    with pytest.raises(demo.DemoError, match="destructive-scope"):
        demo.runtime_container_name(run, "other")


def test_evaluation_commerce_keeps_catalog_authorizer_out_of_its_boundary(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run = isolated_state(monkeypatch, tmp_path)
    command = demo.evaluation_commerce_command(
        run,
        {"MOCK_PAYMENT_KEY_ID": "key-id"},
    )

    assert "--spring.profiles.active=evaluation" in command
    assert "--citybuddy.mock-payment.required-permission=support:chat" in command
    assert "--citybuddy.catalog.enabled=true" not in command
    assert any("http://cb151-auth:8080/auth/jwks" in argument for argument in command)


def test_agent_routes_sandbox_liveness_only_to_evaluation_commerce() -> None:
    urls = demo.agent_runtime_urls({"commerce": 8100, "evaluationCommerce": 8200, "model": 8300})

    assert urls["AGENT_COMMERCE_LIVENESS_URL"] == "http://127.0.0.1:8200"
    assert urls["AGENT_MODEL_PROXY_URL"] == "http://127.0.0.1:8300"
    assert urls["AGENT_COMMERCE_TOOLS_URL"] == "http://127.0.0.1:8300"


def test_elasticsearch_restoration_preserves_the_exact_dynamic_port() -> None:
    assert demo.ELASTICSEARCH_RESTORE_ARGS == (
        "start",
        "--wait",
        "--wait-timeout",
        "90",
        "elasticsearch",
    )
    assert "up" not in demo.ELASTICSEARCH_RESTORE_ARGS


def test_agent_rebind_updates_only_exact_runtime_identity(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run = isolated_state(monkeypatch, tmp_path)
    manifest = {
        "baseUrls": {
            "agent": "http://127.0.0.1:8001",
            "webEnv": {"CITYBUDDY_AGENT_TARGET": "http://127.0.0.1:8001"},
        },
        "ports": {"agent": 8001, "elasticsearch": 9200},
        "processes": {"agent": {"marker": "citybuddy-agent", "pid": 123}},
        "project": run.project,
        "runId": run.run_id,
        "schemaVersion": demo.SCHEMA_VERSION,
    }
    demo.write_json(run.manifest_file, manifest)
    demo.write_json(run.private_file, {}, mode=0o600)
    stopped: list[tuple[int, str]] = []
    monkeypatch.setattr(demo, "stop_process", lambda pid, marker: stopped.append((pid, marker)))
    monkeypatch.setattr(demo, "start_agent", lambda run, private, ports: (456, 8101))

    demo.rebind_agent_to_dependency_ports(run, manifest, {"agent": 8001, "elasticsearch": 9300})

    updated = run.manifest()
    assert stopped == [(123, "citybuddy-agent")]
    assert updated["processes"]["agent"] == {"marker": "citybuddy-agent", "pid": 456}
    assert updated["ports"]["elasticsearch"] == 9300
    assert updated["baseUrls"]["agent"] == "http://127.0.0.1:8101"
    assert updated["baseUrls"]["webEnv"]["CITYBUDDY_AGENT_TARGET"] == ("http://127.0.0.1:8101")


def test_fault_baseline_queries_only_exact_named_triggers(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run = isolated_state(monkeypatch, tmp_path)
    statements: list[str] = []

    def record_query(
        run: demo.ActiveRun,
        database: str,
        statement: str,
        *,
        user: str = "root",
        password: str | None = None,
    ) -> str:
        statements.append(statement)
        return ""

    monkeypatch.setattr(demo, "mysql_query", record_query)

    demo.capture_fault_baseline(run)

    trigger_query = next(statement for statement in statements if "FROM triggers" in statement)
    assert " LIKE " not in trigger_query
    assert "*" not in trigger_query
    assert all(f"'{name}'" in trigger_query for name in demo.TRIGGERS)


def test_process_exists_reaps_an_exited_child(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(os, "waitpid", lambda pid, options: (pid, 0))

    assert not demo.process_exists(12345)


def test_cleanup_parser_requires_explicit_confirmation() -> None:
    parser = demo.parser()

    with pytest.raises(SystemExit):
        parser.parse_args(["cleanup"])
    parsed = parser.parse_args(["cleanup", "--confirm-run-id", RUN_ID])
    assert parsed.confirm_run_id == RUN_ID
