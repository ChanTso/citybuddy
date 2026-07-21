from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
HELPER = ROOT / "scripts/test_dynamic_ports.sh"
INTEGRATION_SCRIPTS = {
    "test_runtime_integration.sh",
    "test_mysql_integration.sh",
    "test_redis_integration.sh",
    "test_elasticsearch_integration.sh",
    "test_rocketmq_integration.sh",
    "test_catalog_integration.sh",
    "test_knowledge_search_integration.sh",
    "test_retrieval_evidence_integration.sh",
    "test_knowledge_sync_integration.sh",
    "test_identity_integration.sh",
    "test_evaluation_identity_integration.sh",
    "test_evaluation_sandbox_integration.sh",
}
EXTRA_SCRIPT = "test_knowledge_indexer_rocketmq_spike.sh"


def run_bash(command: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["/bin/bash", "-c", command],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )


def test_compose_delegates_every_host_port_to_docker() -> None:
    compose = (ROOT / "compose.yaml").read_text(encoding="utf-8")
    for old_binding in (
        "${MYSQL_PORT:-3306}:3306",
        "${REDIS_COMMERCE_PORT:-6379}:6379",
        "${REDIS_SUPPORT_PORT:-6380}:6379",
        "${ELASTICSEARCH_PORT:-9200}:9200",
        "${ROCKETMQ_PROXY_PORT:-8081}:8081",
    ):
        assert old_binding not in compose
    assert compose.count("host_ip: 127.0.0.1") == 5
    assert compose.count("- target: 6379") == 2
    for port in (3306, 9200, 8081):
        assert compose.count(f"- target: {port}") == 1
    assert "published:" not in compose


@pytest.mark.parametrize(
    ("kind", "line", "expected"),
    [
        ("spring", "Tomcat started on port 43101 (http)", "43101"),
        ("uvicorn", "Uvicorn running on http://0.0.0.0:43102", "43102"),
        ("proxy", "drop_proxy_listening_port=43103", "43103"),
    ],
)
def test_process_port_is_read_from_the_live_owner_log(
    tmp_path: Path, kind: str, line: str, expected: str
) -> None:
    log = tmp_path / "service.log"
    log.write_text(f"{line}\n", encoding="utf-8")
    result = run_bash(
        f'source "{HELPER}"; sleep 5 & pid=$!; '
        f'process_bound_port actual {kind} "$pid" "{log}" 0; status=$?; '
        'kill "$pid"; wait "$pid" 2>/dev/null || true; '
        'printf "%s" "$actual"; exit "$status"'
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout == expected


def test_process_port_ignores_a_prior_restart_log_entry(tmp_path: Path) -> None:
    log = tmp_path / "service.log"
    log.write_text("Tomcat started on port 43110 (http)\n", encoding="utf-8")
    result = run_bash(
        f'source "{HELPER}"; port_log_offset offset "{log}"; '
        f'printf "%s\n" "Tomcat started on port 43111 (http)" >>"{log}"; '
        "sleep 5 & pid=$!; "
        f'process_bound_port actual spring "$pid" "{log}" "$offset"; status=$?; '
        'kill "$pid"; wait "$pid" 2>/dev/null || true; '
        'printf "%s" "$actual"; exit "$status"'
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout == "43111"


def test_cleanup_failure_is_visible_without_a_lease_state_machine() -> None:
    failed = run_bash(f'source "{HELPER}"; finish_test_cleanup 0 9')
    assert failed.returncode == 9
    original = run_bash(f'source "{HELPER}"; finish_test_cleanup 7 9')
    assert original.returncode == 7
    success = run_bash(f'source "{HELPER}"; finish_test_cleanup 0 0')
    assert success.returncode == 0


def test_every_integration_suite_uses_runtime_owned_ports() -> None:
    scripts = ROOT / "scripts"
    discovered = {path.name for path in scripts.glob("test_*integration.sh")}
    assert discovered == INTEGRATION_SCRIPTS
    for name in sorted(INTEGRATION_SCRIPTS | {EXTRA_SCRIPT}):
        content = (scripts / name).read_text(encoding="utf-8")
        assert 'source "$repo_root/scripts/test_dynamic_ports.sh"' in content
        assert "test_port_allocator" not in content
        assert "allocate_test_ports" not in content
        assert "finish_test_cleanup" in content


@pytest.mark.parametrize(
    ("name", "minimum_waits"),
    [
        ("test_identity_integration.sh", 5),
        ("test_evaluation_identity_integration.sh", 1),
        ("test_evaluation_sandbox_integration.sh", 1),
    ],
)
def test_local_processes_exit_before_cleanup_finishes(name: str, minimum_waits: int) -> None:
    content = (ROOT / "scripts" / name).read_text(encoding="utf-8")
    cleanup = content.split("cleanup() {", 1)[1].split("\n}", 1)[0]
    assert cleanup.count('wait "') >= minimum_waits
    assert cleanup.rindex('wait "') < cleanup.index("finish_test_cleanup")


def test_host_applications_request_kernel_assigned_ports() -> None:
    identity = (ROOT / "scripts/test_identity_integration.sh").read_text(encoding="utf-8")
    evaluation_identity = (ROOT / "scripts/test_evaluation_identity_integration.sh").read_text(
        encoding="utf-8"
    )
    evaluation = (ROOT / "scripts/test_evaluation_sandbox_integration.sh").read_text(
        encoding="utf-8"
    )
    retrieval = (ROOT / "scripts/test_retrieval_evidence_integration.sh").read_text(
        encoding="utf-8"
    )
    combined = identity + evaluation_identity + evaluation + retrieval
    assert '--server.port="$' not in combined
    assert 'AGENT_PORT="$' not in combined
    assert 'fake_litellm_server.py --port "$' not in combined
    assert combined.count("--server.port=0") >= 4
    assert combined.count("AGENT_PORT=0") >= 3
    assert combined.count("fake_litellm_server.py --port 0") >= 3
    assert "process_bound_port" in combined


def test_catalog_container_uses_docker_assigned_host_port() -> None:
    content = (ROOT / "scripts/test_catalog_integration.sh").read_text(encoding="utf-8")
    assert '--publish "127.0.0.1::8080"' in content
    assert 'container_host_port auth_port "$auth_container" 8080' in content


def test_host_clients_read_docker_assigned_container_ports() -> None:
    expectations = {
        "test_identity_integration.sh": ("compose_host_port MYSQL_PORT mysql 3306",),
        "test_evaluation_identity_integration.sh": ("compose_host_port MYSQL_PORT mysql 3306",),
        "test_evaluation_sandbox_integration.sh": ("compose_host_port MYSQL_PORT mysql 3306",),
        "test_knowledge_search_integration.sh": (
            "compose_host_port ELASTICSEARCH_PORT elasticsearch 9200",
        ),
        "test_retrieval_evidence_integration.sh": (
            "compose_host_port MYSQL_PORT mysql 3306",
            "compose_host_port ELASTICSEARCH_PORT elasticsearch 9200",
        ),
    }
    for name, required in expectations.items():
        content = (ROOT / "scripts" / name).read_text(encoding="utf-8")
        for statement in required:
            assert statement in content
