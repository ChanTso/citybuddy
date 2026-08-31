from __future__ import annotations

import hashlib
import json
import os
import subprocess
import textwrap
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parents[1]
GATE = REPOSITORY / "bench/agent/setup_environment_gate.sh"
LADDER = REPOSITORY / "bench/agent/run_agent_ladder.sh"
PROFILE = REPOSITORY / "bench/agent/profile_agent_cpu.sh"


def _write_executable(path: Path, source: str) -> None:
    path.write_text(textwrap.dedent(source).lstrip(), encoding="utf-8")
    path.chmod(0o755)


def _prepare_gate(tmp_path: Path) -> tuple[Path, Path, dict[str, str], Path, Path]:
    subprocess.run(["git", "init", "--quiet"], cwd=tmp_path, check=True)
    (tmp_path / ".gitignore").write_text(
        "target/\nbench/.run/\nbench/results/\nfake-bin/\nrun-gate.sh\n", encoding="utf-8"
    )
    (tmp_path / "tracked.txt").write_text("tracked\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=tmp_path, check=True)
    subprocess.run(
        [
            "git",
            "-c",
            "user.name=CityBuddy",
            "-c",
            "user.email=citybuddy@example.invalid",
            "commit",
            "--quiet",
            "-m",
            "fixture",
        ],
        cwd=tmp_path,
        check=True,
    )
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=tmp_path,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    nonce = "a" * 32
    auth_jar = tmp_path / "auth-service/target/auth-service-0.0.1-SNAPSHOT.jar"
    commerce_jar = tmp_path / "commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar"
    auth_jar.parent.mkdir(parents=True)
    commerce_jar.parent.mkdir(parents=True)
    auth_jar.write_bytes(b"auth-jar")
    commerce_jar.write_bytes(b"commerce-jar")
    auth_digest = hashlib.sha256(auth_jar.read_bytes()).hexdigest()
    commerce_digest = hashlib.sha256(commerce_jar.read_bytes()).hexdigest()
    names = [
        "citybuddy-bench-elasticsearch",
        "citybuddy-bench-auth",
        "citybuddy-bench-commerce",
        "citybuddy-bench-net",
        "citybuddy-bench-model",
        "citybuddy-bench-agent",
    ]
    ids = {name: str(index) * 64 for index, name in enumerate(names, start=1)}
    labels = {
        "citybuddy.bench.citybuddy-commit": commit,
        "citybuddy.bench.setup-nonce": nonce,
    }
    record = {
        "citybuddyCommit": commit,
        "containers": {
            name: {"id": container_id, "labels": labels} for name, container_id in ids.items()
        },
        "formatVersion": "citybuddy-agent-setup-environment-v1",
        "java": {
            "authService": {
                "hostJarSha256": auth_digest,
                "mountedJarSha256": auth_digest,
            },
            "commerceService": {
                "hostJarSha256": commerce_digest,
                "mountedJarSha256": commerce_digest,
            },
        },
        "setupNonce": nonce,
    }
    run_dir = tmp_path / "bench/.run"
    result_dir = tmp_path / "bench/results"
    run_dir.mkdir(parents=True)
    result_dir.mkdir(parents=True)
    live_record = run_dir / "agent_setup_environment.json"
    saved_record = result_dir / "agent_probe_setup_environment.json"
    serialized = json.dumps(record, sort_keys=True) + "\n"
    live_record.write_text(serialized, encoding="utf-8")
    saved_record.write_text(serialized, encoding="utf-8")
    (run_dir / "citybuddy_commit").write_text(commit + "\n", encoding="utf-8")

    fake_bin = tmp_path / "fake-bin"
    fake_bin.mkdir()
    _write_executable(
        fake_bin / "docker",
        f"""
        #!/bin/sh
        command="$1"
        shift
        case "$command" in
          inspect)
            format="$2"
            name="$3"
            case "$format" in
              '{{{{.Id}}}}')
                case "$name" in
                  citybuddy-bench-elasticsearch) printf '%s\\n' '{ids[names[0]]}' ;;
                  citybuddy-bench-auth) printf '%s\\n' '{ids[names[1]]}' ;;
                  citybuddy-bench-commerce) printf '%s\\n' '{ids[names[2]]}' ;;
                  citybuddy-bench-net) printf '%s\\n' '{ids[names[3]]}' ;;
                  citybuddy-bench-model) printf '%s\\n' '{ids[names[4]]}' ;;
                  citybuddy-bench-agent) printf '%s\\n' "${{REPLACED_AGENT_ID:-{ids[names[5]]}}}" ;;
                esac
                ;;
              '{{{{.State.Running}}}}') printf 'true\\n' ;;
              *setup-nonce*) printf '%s\\n' '{nonce}' ;;
              *citybuddy-commit*) printf '%s\\n' '{commit}' ;;
              *) exit 2 ;;
            esac
            ;;
          exec)
            case "$1" in
              citybuddy-bench-auth)
                printf '%s  %s\\n' "${{MOUNTED_AUTH_DIGEST:-{auth_digest}}}" "$3"
                ;;
              citybuddy-bench-commerce) printf '%s  %s\\n' '{commerce_digest}' "$3" ;;
              *) exit 2 ;;
            esac
            ;;
          *) exit 2 ;;
        esac
        """,
    )
    environment = dict(os.environ)
    environment["PATH"] = f"{fake_bin}:{environment['PATH']}"
    wrapper = tmp_path / "run-gate.sh"
    _write_executable(
        wrapper,
        """
        #!/usr/bin/env bash
        set -euo pipefail
        repo_root="$1"
        source "$2"
        verify_agent_setup_environment "$3" test
        """,
    )
    return wrapper, saved_record, environment, live_record, auth_jar


def _run_gate(
    wrapper: Path, saved_record: Path, environment: dict[str, str], repo_root: Path
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(wrapper), str(repo_root), str(GATE), str(saved_record)],
        cwd=repo_root,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )


@pytest.mark.parametrize(
    ("counterexample", "diagnostic"),
    [
        ("live_nonce", "the live setup record changed"),
        ("container_id", "container identity changed for citybuddy-bench-agent"),
        ("host_jar", "a mounted JAR boundary changed"),
        ("mounted_jar", "a mounted JAR boundary changed"),
    ],
)
def test_runtime_gate_rejects_replaced_setup_or_jar(
    tmp_path: Path, counterexample: str, diagnostic: str
) -> None:
    wrapper, saved_record, environment, live_record, auth_jar = _prepare_gate(tmp_path)
    baseline = _run_gate(wrapper, saved_record, environment, tmp_path)
    assert baseline.returncode == 0, baseline.stderr

    if counterexample == "live_nonce":
        document = json.loads(live_record.read_text(encoding="utf-8"))
        document["setupNonce"] = "b" * 32
        live_record.write_text(json.dumps(document) + "\n", encoding="utf-8")
    elif counterexample == "container_id":
        environment["REPLACED_AGENT_ID"] = "f" * 64
    elif counterexample == "host_jar":
        auth_jar.write_bytes(b"changed-auth-jar")
    else:
        environment["MOUNTED_AUTH_DIGEST"] = "f" * 64

    result = _run_gate(wrapper, saved_record, environment, tmp_path)

    assert result.returncode != 0
    assert diagnostic in result.stderr


def test_ladder_and_profile_gate_the_saved_setup_before_and_after_load() -> None:
    ladder = LADDER.read_text(encoding="utf-8")
    ladder_snapshot = ladder.index('mv "$setup_environment_path.tmp" "$setup_environment_path"')
    ladder_pre = ladder.index(
        'verify_agent_setup_environment "$setup_environment_path" "before ladder"'
    )
    ladder_load = ladder.index('k6_container_id="$(docker run --detach --name citybuddy-bench-k6')
    ladder_post = ladder.index(
        'verify_agent_setup_environment "$setup_environment_path" "after ladder"'
    )
    assert ladder_snapshot < ladder_pre < ladder_load < ladder_post
    assert '"$setup_environment_path"' in ladder[ladder.index("target_paths=(") : ladder_pre]

    profile = PROFILE.read_text(encoding="utf-8")
    profile_snapshot = profile.index('mv "$setup_environment_path.tmp" "$setup_environment_path"')
    profile_pre = profile.index(
        'verify_agent_setup_environment "$setup_environment_path" "before profile"'
    )
    profile_load = profile.index(
        'profile_load_id="$(docker run --detach --rm --name citybuddy-bench-profile-load'
    )
    profile_post = profile.index(
        'verify_agent_setup_environment "$setup_environment_path" "after profile"'
    )
    assert profile_snapshot < profile_pre < profile_load < profile_post
    assert 'rm -f "$profile_path"' not in profile
