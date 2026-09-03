from __future__ import annotations

import os
import subprocess
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parents[1]
CPU_LIMIT = REPOSITORY / "bench/commerce_cpu_limit.sh"
SECKILL_SETUP = REPOSITORY / "bench/setup_bench_env.sh"
SECKILL_LADDER = REPOSITORY / "bench/run_ladder.sh"
SECKILL_CORRECTNESS = REPOSITORY / "bench/run_correctness.sh"
AGENT_SETUP = REPOSITORY / "bench/agent/setup_agent_bench.sh"
AGENT_GATE = REPOSITORY / "bench/agent/setup_environment_gate.sh"
CONTAINER_ID = "a" * 64
IMAGE_ID = "sha256:" + "b" * 64
STARTED_AT = "2026-09-03T01:02:03.123456789Z"
JAR_SHA256 = "c" * 64


def _verify(
    tmp_path: Path,
    *,
    live_nano_cpus: str,
    live_cpuset_cpus: str,
    requested_cpus: str,
    recorded_nano_cpus: str,
    recorded_cpuset_cpus: str,
) -> subprocess.CompletedProcess[str]:
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir(exist_ok=True)
    docker = fake_bin / "docker"
    docker.write_text(
        '#!/bin/sh\n[ "$1" = inspect ] || exit 91\n'
        'printf \'%s|%s\\n\' "$LIVE_NANO_CPUS" "$LIVE_CPUSET_CPUS"\n',
        encoding="utf-8",
    )
    docker.chmod(0o755)
    environment = dict(os.environ)
    environment["LIVE_NANO_CPUS"] = live_nano_cpus
    environment["LIVE_CPUSET_CPUS"] = live_cpuset_cpus
    environment["PATH"] = f"{fake_bin}:{environment['PATH']}"
    return subprocess.run(
        [
            "/bin/bash",
            "-c",
            'source "$1"; bench_verify_commerce_cpu_limit commerce "$2" "$3" "$4" test',
            "cpu-limit-test",
            str(CPU_LIMIT),
            requested_cpus,
            recorded_nano_cpus,
            recorded_cpuset_cpus,
        ],
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )


def test_commerce_cpu_limit_accepts_the_requested_live_nanocpus(tmp_path: Path) -> None:
    result = _verify(
        tmp_path,
        live_nano_cpus="4000000000",
        live_cpuset_cpus="",
        requested_cpus="4",
        recorded_nano_cpus="4000000000",
        recorded_cpuset_cpus="",
    )

    assert result.returncode == 0
    assert result.stdout == "4000000000\n"
    assert result.stderr == ""


@pytest.mark.parametrize(
    (
        "live_nano_cpus",
        "live_cpuset_cpus",
        "requested_cpus",
        "recorded_nano_cpus",
        "recorded_cpuset_cpus",
    ),
    [
        ("3000000000", "", "4", "4000000000", ""),
        ("4000000000", "", "3", "4000000000", ""),
        ("4000000000", "", "4", "3000000000", ""),
        ("4000000000", "0-3", "4", "4000000000", ""),
        ("4000000000", "0-3", "4", "4000000000", "0-3"),
    ],
)
def test_commerce_cpu_limit_rejects_live_or_recorded_drift(
    tmp_path: Path,
    live_nano_cpus: str,
    live_cpuset_cpus: str,
    requested_cpus: str,
    recorded_nano_cpus: str,
    recorded_cpuset_cpus: str,
) -> None:
    result = _verify(
        tmp_path,
        live_nano_cpus=live_nano_cpus,
        live_cpuset_cpus=live_cpuset_cpus,
        requested_cpus=requested_cpus,
        recorded_nano_cpus=recorded_nano_cpus,
        recorded_cpuset_cpus=recorded_cpuset_cpus,
    )

    assert result.returncode != 0
    assert "Commerce CPU limit drifted (test)" in result.stderr


def _verify_container(
    tmp_path: Path,
    *,
    live_id: str = CONTAINER_ID,
    live_image_id: str = IMAGE_ID,
    live_started_at: str = STARTED_AT,
    live_running: str = "true",
    live_restart_count: str = "0",
    live_mounted_sha256: str = JAR_SHA256,
    verification: str = "fixture",
) -> subprocess.CompletedProcess[str]:
    fake_bin = tmp_path / "identity-bin"
    fake_bin.mkdir(exist_ok=True)
    docker = fake_bin / "docker"
    docker.write_text(
        """#!/bin/sh
case "$1" in
  inspect)
    printf '%s|%s|%s|%s|%s\n' \
      "$LIVE_ID" "$LIVE_IMAGE_ID" "$LIVE_STARTED_AT" "$LIVE_RUNNING" "$LIVE_RESTART_COUNT"
    ;;
  exec)
    printf '%s  %s\n' "$LIVE_MOUNTED_SHA256" "$3"
    ;;
  *) exit 91 ;;
esac
""",
        encoding="utf-8",
    )
    docker.chmod(0o755)
    environment = dict(os.environ)
    environment.update(
        {
            "LIVE_ID": live_id,
            "LIVE_IMAGE_ID": live_image_id,
            "LIVE_STARTED_AT": live_started_at,
            "LIVE_RUNNING": live_running,
            "LIVE_RESTART_COUNT": live_restart_count,
            "LIVE_MOUNTED_SHA256": live_mounted_sha256,
            "PATH": f"{fake_bin}:{environment['PATH']}",
        }
    )
    command = (
        'source "$1"; bench_verify_fixture_container fixture /fixture.jar '
        '"$2" "$3" "$4" true 0 "$5" "$5" test'
        if verification == "fixture"
        else 'source "$1"; bench_verify_dependency_container dependency "$2" "$3" "$4" true 0 test'
    )
    return subprocess.run(
        [
            "/bin/bash",
            "-c",
            command,
            "container-identity-test",
            str(CPU_LIMIT),
            CONTAINER_ID,
            IMAGE_ID,
            STARTED_AT,
            JAR_SHA256,
        ],
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )


def test_fixture_container_identity_accepts_the_recorded_running_instance(
    tmp_path: Path,
) -> None:
    result = _verify_container(tmp_path)

    assert result.returncode == 0
    assert result.stdout == ""
    assert result.stderr == ""


@pytest.mark.parametrize(
    "overrides",
    [
        {"live_id": "d" * 64},
        {"live_image_id": "sha256:" + "e" * 64},
        {"live_started_at": "2026-09-03T02:03:04.123456789Z"},
        {"live_running": "false"},
        {"live_restart_count": "1"},
        {"live_mounted_sha256": "f" * 64},
    ],
)
def test_fixture_container_identity_rejects_replacement_restart_stop_or_jar_drift(
    tmp_path: Path, overrides: dict[str, str]
) -> None:
    result = _verify_container(tmp_path, **overrides)

    assert result.returncode != 0
    assert "(test)" in result.stderr


@pytest.mark.parametrize("overrides", [{"live_id": "d" * 64}, {"live_restart_count": "1"}])
def test_dependency_container_rejects_recreation_or_restart(
    tmp_path: Path, overrides: dict[str, str]
) -> None:
    result = _verify_container(tmp_path, verification="dependency", **overrides)

    assert result.returncode != 0
    assert "Benchmark container identity drifted (test): dependency" in result.stderr


def test_both_harnesses_use_one_requested_limit_and_gate_the_live_container() -> None:
    helper = CPU_LIMIT.read_text(encoding="utf-8")
    seckill_setup = SECKILL_SETUP.read_text(encoding="utf-8")
    agent_setup = AGENT_SETUP.read_text(encoding="utf-8")
    agent_gate = AGENT_GATE.read_text(encoding="utf-8")

    assert helper.count("BENCH_COMMERCE_CPU_LIMIT_REQUESTED_CPUS=4") == 1
    for setup in (seckill_setup, agent_setup):
        assert 'source "$repo_root/bench/commerce_cpu_limit.sh"' in setup
        assert '--cpus "$BENCH_COMMERCE_CPU_LIMIT_REQUESTED_CPUS"' in setup
        assert "--cpus 4" not in setup
        assert "bench_verify_commerce_cpu_limit" in setup
        assert "citybuddy-bench-commerce" in setup

    for runner in (SECKILL_LADDER, SECKILL_CORRECTNESS):
        source = runner.read_text(encoding="utf-8")
        assert source.count('verify_run_boundary "') == 2
        assert source.count("bench_verify_fixture_container") == 2
        assert source.count("bench_verify_dependency_container") == 4
        assert source.count("bench_verify_commerce_cpu_limit") == 1
        assert "COMMERCE_CPU_LIMIT_REQUESTED_CPUS" in source
        assert "COMMERCE_CPU_LIMIT_OBSERVED_NANO_CPUS" in source
        assert "COMMERCE_CPU_LIMIT_OBSERVED_CPUSET_CPUS" in source
        for dependency in (
            "citybuddy-mysql-1",
            "citybuddy-redis-commerce-1",
            "citybuddy-rocketmq-broker-proxy-1",
            "citybuddy-rocketmq-namesrv-1",
        ):
            assert dependency in source
        assert "commerce_cpu_limit=4" not in source

    for field in (
        "AUTH_CONTAINER_ID",
        "AUTH_CONTAINER_IMAGE_ID",
        "AUTH_CONTAINER_STARTED_AT",
        "AUTH_CONTAINER_RUNNING",
        "AUTH_CONTAINER_RESTART_COUNT",
        "AUTH_MOUNTED_JAR_SHA256",
        "COMMERCE_CONTAINER_ID",
        "COMMERCE_CONTAINER_IMAGE_ID",
        "COMMERCE_CONTAINER_STARTED_AT",
        "COMMERCE_CONTAINER_RUNNING",
        "COMMERCE_CONTAINER_RESTART_COUNT",
        "COMMERCE_MOUNTED_JAR_SHA256",
        "MYSQL_CONTAINER_ID",
        "MYSQL_CONTAINER_IMAGE_ID",
        "MYSQL_CONTAINER_STARTED_AT",
        "MYSQL_CONTAINER_RUNNING",
        "MYSQL_CONTAINER_RESTART_COUNT",
        "REDIS_COMMERCE_CONTAINER_ID",
        "REDIS_COMMERCE_CONTAINER_IMAGE_ID",
        "REDIS_COMMERCE_CONTAINER_STARTED_AT",
        "REDIS_COMMERCE_CONTAINER_RUNNING",
        "REDIS_COMMERCE_CONTAINER_RESTART_COUNT",
        "ROCKETMQ_BROKER_PROXY_CONTAINER_ID",
        "ROCKETMQ_BROKER_PROXY_CONTAINER_IMAGE_ID",
        "ROCKETMQ_BROKER_PROXY_CONTAINER_STARTED_AT",
        "ROCKETMQ_BROKER_PROXY_CONTAINER_RUNNING",
        "ROCKETMQ_BROKER_PROXY_CONTAINER_RESTART_COUNT",
        "ROCKETMQ_NAMESRV_CONTAINER_ID",
        "ROCKETMQ_NAMESRV_CONTAINER_IMAGE_ID",
        "ROCKETMQ_NAMESRV_CONTAINER_STARTED_AT",
        "ROCKETMQ_NAMESRV_CONTAINER_RUNNING",
        "ROCKETMQ_NAMESRV_CONTAINER_RESTART_COUNT",
        "COMMERCE_CPU_LIMIT_OBSERVED_CPUSET_CPUS",
    ):
        assert f"{field}=" in seckill_setup

    ladder = SECKILL_LADDER.read_text(encoding="utf-8")
    correctness = SECKILL_CORRECTNESS.read_text(encoding="utf-8")
    assert 'bundle_dir="$out/ladder_${LABEL}"' in ladder
    assert 'claim_dir="$out/.claim.ladder_${LABEL}"' in ladder
    assert 'stage_dir="$(mktemp -d "$out/.ladder.${LABEL}.XXXXXX")"' in ladder
    assert '--volume "$stage_dir:/out"' in ladder
    assert 'bundle_dir="$out/correctness_${LABEL}"' in correctness
    assert 'claim_dir="$out/.claim.correctness_${LABEL}"' in correctness
    assert 'stage_dir="$(mktemp -d "$out/.correctness.${LABEL}.XXXXXX")"' in correctness
    assert 'tee -a "$stage_dir/$http_name"' in correctness
    for source in (ladder, correctness):
        assert 'cp "$bench_env" "$stage_dir/$setup_name"' in source
        assert "trap cleanup EXIT" in source
        assert 'rm -rf -- "$stage_dir"' in source
        assert source.count('mv -- "$stage_dir" "$bundle_dir"') == 1
        assert source.index('verify_run_boundary "after') < source.index(
            'mv -- "$stage_dir" "$bundle_dir"'
        )
        before_publication = source[: source.index('mv -- "$stage_dir" "$bundle_dir"')]
        assert '> "$out/' not in before_publication
        assert '>> "$out/' not in before_publication
        assert 'tee -a "$out/' not in before_publication

    assert ".configuration.commerceCpuLimit.requestedCpus" in agent_gate
    assert ".configuration.commerceCpuLimit.observedNanoCpus" in agent_gate
    assert ".configuration.commerceCpuLimit.observedCpusetCpus" in agent_gate
    assert "bench_verify_commerce_cpu_limit" in agent_gate
