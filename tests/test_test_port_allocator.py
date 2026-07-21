from __future__ import annotations

import fcntl
import os
import socket
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
ALLOCATOR = ROOT / "scripts/test_port_allocator.py"
PORT_MIN = 20_000
PORT_MAX = 31_999
INTEGRATION_SCRIPTS = {
    "test_runtime_integration.sh",
    "test_mysql_integration.sh",
    "test_identity_integration.sh",
    "test_evaluation_identity_integration.sh",
    "test_evaluation_sandbox_integration.sh",
    "test_catalog_integration.sh",
    "test_redis_integration.sh",
    "test_elasticsearch_integration.sh",
    "test_knowledge_search_integration.sh",
    "test_retrieval_evidence_integration.sh",
    "test_rocketmq_integration.sh",
    "test_knowledge_indexer_rocketmq_spike.sh",
    "test_knowledge_sync_integration.sh",
}


def allocator_environment(lease_root: Path) -> dict[str, str]:
    return {**os.environ, "CITYBUDDY_TEST_PORT_LEASE_ROOT": str(lease_root)}


def allocate(owner: str, count: int, lease_root: Path, start: int) -> list[int]:
    result = subprocess.run(
        [
            sys.executable,
            str(ALLOCATOR),
            "allocate",
            "--owner",
            owner,
            "--count",
            str(count),
            "--start",
            str(start),
        ],
        check=True,
        capture_output=True,
        text=True,
        env=allocator_environment(lease_root),
    )
    return [int(line) for line in result.stdout.splitlines()]


def release(owner: str, lease_root: Path) -> None:
    subprocess.run(
        [sys.executable, str(ALLOCATOR), "release", "--owner", owner],
        check=True,
        env=allocator_environment(lease_root),
    )


def test_allocator_skips_a_preoccupied_candidate(tmp_path: Path) -> None:
    owner = f"{os.getpid()}:preoccupied"
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as occupied:
        occupied.bind(("127.0.0.1", PORT_MIN))
        occupied.listen()
        allocated = allocate(owner, 1, tmp_path, PORT_MIN)
    try:
        assert allocated == [PORT_MIN + 1]
    finally:
        release(owner, tmp_path)


def test_concurrent_allocators_receive_disjoint_ports(tmp_path: Path) -> None:
    owners = [f"{os.getpid()}:parallel-a", f"{os.getpid()}:parallel-b"]
    candidate = PORT_MIN + 100
    (tmp_path / str(candidate)).write_text(
        "2000000000:stale\n2000000000\nold-process-start\n", encoding="utf-8"
    )

    def allocate_concurrently(owner: str) -> list[int]:
        return allocate(owner, 8, tmp_path, candidate)

    with ThreadPoolExecutor(max_workers=2) as executor:
        allocations = list(executor.map(allocate_concurrently, owners))
    try:
        assert set(allocations[0]).isdisjoint(allocations[1])
        assert all(PORT_MIN <= port <= PORT_MAX < 32_768 for ports in allocations for port in ports)
    finally:
        for owner in owners:
            release(owner, tmp_path)


@pytest.mark.parametrize(
    "corrupt_state",
    [
        "empty-file",
        "invalid-utf8",
        "empty-directory",
        "legacy-directory",
        "reused-pid",
        "mismatched-owner-pid",
    ],
)
def test_allocator_reclaims_incomplete_or_stale_claims(tmp_path: Path, corrupt_state: str) -> None:
    candidate = PORT_MIN + 200
    stale_lease = tmp_path / str(candidate)
    if corrupt_state == "empty-file":
        stale_lease.touch()
    elif corrupt_state == "invalid-utf8":
        stale_lease.write_bytes(b"\xff")
    elif corrupt_state == "empty-directory":
        stale_lease.mkdir()
    elif corrupt_state == "legacy-directory":
        stale_lease.mkdir()
        (stale_lease / "owner").write_text("2000000000:legacy\n", encoding="utf-8")
    elif corrupt_state == "reused-pid":
        stale_lease.write_text(
            f"{os.getpid()}:old-owner\n{os.getpid()}\nwrong-process-start\n",
            encoding="utf-8",
        )
    else:
        stale_lease.write_text(
            f"2000000000:wrong-owner\n{os.getpid()}\nactive-but-mismatched\n",
            encoding="utf-8",
        )
    owner = f"{os.getpid()}:replacement"
    allocated = allocate(owner, 1, tmp_path, candidate)
    try:
        assert allocated == [candidate]
    finally:
        release(owner, tmp_path)


def test_allocator_reclaims_an_orphaned_atomic_publish_temporary(tmp_path: Path) -> None:
    orphan = tmp_path / ".lease-abandoned"
    orphan.write_text("2000000000:abandoned\n2000000000\nold-process-start\n", encoding="utf-8")
    owner = f"{os.getpid()}:temporary-cleanup"
    allocated = allocate(owner, 1, tmp_path, PORT_MIN + 300)
    try:
        assert allocated == [PORT_MIN + 300]
        assert not orphan.exists()
    finally:
        release(owner, tmp_path)


def test_registry_lock_serializes_cleanup_and_publication(tmp_path: Path) -> None:
    owner = f"{os.getpid()}:locked-publication"
    directory_fd = os.open(tmp_path, os.O_RDONLY)
    fcntl.flock(directory_fd, fcntl.LOCK_EX)
    process = subprocess.Popen(
        [
            sys.executable,
            str(ALLOCATOR),
            "allocate",
            "--owner",
            owner,
            "--count",
            "1",
            "--start",
            str(PORT_MIN + 400),
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=allocator_environment(tmp_path),
    )
    try:
        time.sleep(0.1)
        assert process.poll() is None
    finally:
        fcntl.flock(directory_fd, fcntl.LOCK_UN)
        os.close(directory_fd)
    stdout, stderr = process.communicate(timeout=5)
    try:
        assert process.returncode == 0, stderr
        assert stdout.strip() == str(PORT_MIN + 400)
    finally:
        release(owner, tmp_path)


def test_failed_resource_stop_retains_the_lease_and_fails_cleanup(tmp_path: Path) -> None:
    result = subprocess.run(
        [
            "/bin/bash",
            "-c",
            "set -euo pipefail; "
            "source scripts/test_port_allocator.sh; allocate_test_ports held_port; "
            "finalize_test_port_cleanup 0 9",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env=allocator_environment(tmp_path),
    )
    assert result.returncode == 9
    assert "Retaining test-port leases" in result.stderr
    leases = [path for path in tmp_path.iterdir() if path.name.isdecimal()]
    assert len(leases) == 1


def test_successful_resource_stop_releases_the_lease(tmp_path: Path) -> None:
    result = subprocess.run(
        [
            "/bin/bash",
            "-c",
            "set -euo pipefail; "
            "source scripts/test_port_allocator.sh; allocate_test_ports held_port; "
            "finalize_test_port_cleanup 0 0",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env=allocator_environment(tmp_path),
    )
    assert result.returncode == 0, result.stderr
    assert not [path for path in tmp_path.iterdir() if path.name.isdecimal()]


def test_every_integration_suite_uses_the_shared_allocator() -> None:
    scripts = ROOT / "scripts"
    discovered = {path.name for path in scripts.glob("test_*integration.sh")}
    discovered.add("test_knowledge_indexer_rocketmq_spike.sh")
    assert discovered == INTEGRATION_SCRIPTS
    for name in INTEGRATION_SCRIPTS:
        content = (scripts / name).read_text(encoding="utf-8")
        assert 'source "$repo_root/scripts/test_port_allocator.sh"' in content
        assert "allocate_test_ports " in content
        assert "finalize_test_port_cleanup" in content
        assert "$$ %" not in content
        cleanup = content.split("cleanup() {", 1)[1].split("\n}", 1)[0]
        assert "down --volumes --remove-orphans >/dev/null 2>&1 || true" not in cleanup
        assert "resource_stop_status=$?" in cleanup
        assert cleanup.index("down --volumes") < cleanup.index("finalize_test_port_cleanup")


@pytest.mark.parametrize(
    ("name", "minimum_waits"),
    [
        ("test_identity_integration.sh", 5),
        ("test_evaluation_identity_integration.sh", 1),
        ("test_evaluation_sandbox_integration.sh", 1),
    ],
)
def test_local_service_processes_exit_before_port_release(name: str, minimum_waits: int) -> None:
    content = (ROOT / "scripts" / name).read_text(encoding="utf-8")
    cleanup = content.split("cleanup() {", 1)[1].split("\n}", 1)[0]
    assert cleanup.count('wait "') >= minimum_waits
    assert cleanup.rindex('wait "') < cleanup.index("finalize_test_port_cleanup")
