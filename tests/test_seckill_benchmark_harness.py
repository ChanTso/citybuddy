import json
import re
import subprocess
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[1]
LADDER = REPOSITORY / "bench/run_ladder.sh"
CORRECTNESS = REPOSITORY / "bench/run_correctness.sh"
ANALYZER = REPOSITORY / "bench/analyze_ladder.py"


def test_seckill_benchmark_scripts_reject_unsafe_labels_before_external_work() -> None:
    for script, arguments in ((LADDER, ("../unsafe", "1")), (CORRECTNESS, ("../unsafe",))):
        result = subprocess.run(
            ["/bin/bash", str(script), *arguments], capture_output=True, text=True, check=False
        )
        assert result.returncode == 2
        assert result.stderr.startswith("LABEL must be 1-96 safe characters")


def test_ladder_analyzer_counts_failures_and_pre_iteration_drops(tmp_path: Path) -> None:
    def point(metric: str, value: float, tags: dict[str, str]) -> str:
        return json.dumps(
            {"type": "Point", "metric": metric, "data": {"value": value, "tags": tags}}
        )

    points = tmp_path / "points.json"
    points.write_text(
        "\n".join(
            (
                point("http_req_duration", 10, {"rate": "3"}),
                point("http_req_duration", 30, {"rate": "3"}),
                point("http_req_failed", 1, {"rate": "3"}),
                point("dropped_iterations", 1, {"scenario": "rate_3"}),
                point("dropped_iterations", 4, {"scenario": "rate_4"}),
            )
        ),
        encoding="utf-8",
    )
    result = subprocess.run(
        ["python3", str(ANALYZER), str(points), "fixture", "--rates", "3,4", "--step-seconds", "1"],
        capture_output=True,
        text=True,
        check=True,
    )
    assert re.search(
        r"^\s*3\s+3\s+2\s+1\s+1\s+50\.00\s+2\.0\s+20\.0\s+29\.0\s+29\.8\s+30\.0",
        result.stdout,
        re.MULTILINE,
    )
    assert re.search(r"^\s*4\s+4\s+0\s+4\s+0\s+0\.00\s+0\.0", result.stdout, re.MULTILINE)


def test_ladder_analyzer_separates_warmup_and_retains_negative_duration(tmp_path: Path) -> None:
    records = []
    for scenario, duration in (("warmup", 9000), ("rate_1000", -0.5), ("rate_1000", 2.5)):
        for metric, value, extra in (
            ("http_req_duration", duration, {}),
            ("seckill_decisions", 1, {"decision": "EXHAUSTED", "replay": "false"}),
        ):
            records.append(
                {
                    "type": "Point",
                    "metric": metric,
                    "data": {
                        "value": value,
                        "tags": {"rate": "1000", "scenario": scenario, **extra},
                    },
                }
            )
    path = tmp_path / "points.json"
    path.write_text("\n".join(json.dumps(record) for record in records))
    result = subprocess.run(
        [
            "python3",
            str(ANALYZER),
            str(path),
            "rejection",
            "--rates",
            "1000",
            "--step-seconds",
            "30",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    assert re.search(r"^\s*1000\s+30000\s+2\s+0\s+0\s", result.stdout, re.MULTILINE)
    assert "EXHAUSTED=2" in result.stdout and "'false': 2.0" in result.stdout
    assert "negative_durations=1 min_ms=-0.5" in result.stdout
    assert (
        "negative_timing scenario=rate_1000 metric=http_req_duration count=1 min=-0.5"
        in result.stdout
    )


def test_setup_refuses_running_application_before_fixture_or_signing_mutation(
    tmp_path: Path,
) -> None:
    import os
    import shutil

    bench = tmp_path / "bench"
    bench.mkdir()
    setup = bench / "setup_bench_env.sh"
    shutil.copyfile(REPOSITORY / "bench/setup_bench_env.sh", setup)
    for service in ("auth-service", "commerce-service"):
        target = tmp_path / service / "target"
        target.mkdir(parents=True)
        (target / f"{service}-0.0.1-SNAPSHOT.jar").write_bytes(b"fixture jar")
    (tmp_path / ".env").write_text(
        "MYSQL_COMMERCE_APP_PASSWORD=fixture\nMYSQL_AUTH_APP_PASSWORD=fixture\n"
        "MYSQL_BOOTSTRAP_PASSWORD=fixture\nREDIS_COMMERCE_PASSWORD=fixture\n"
    )
    commands = tmp_path / "commands"
    commands.mkdir()
    stub = """#!/usr/bin/env bash
set -eu
name="${0##*/}"
printf '%s %s\\n' "$name" "$*" >> "$COMMAND_LOG"
case "$name:$1" in
 git:rev-parse) printf 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\n' ;;
 git:status) ;;
 openssl:dgst) printf 'SHA256(fixture)= fixture-digest\\n' ;;
 docker:port) printf '127.0.0.1:33060\\n' ;;
 docker:info) printf '8\\n' ;;
 docker:ps) printf 'owned-prior-cid\\n' ;;
 docker:inspect) printf 'true\\n' ;;
 *) echo 'Unexpected external command' >&2; exit 97 ;;
esac
"""
    for name in ("git", "openssl", "docker", "mysql", "uv"):
        executable = commands / name
        executable.write_text(stub)
        executable.chmod(0o755)
    log = tmp_path / "commands.txt"
    result = subprocess.run(
        ["/bin/bash", str(setup)],
        env=os.environ | {"PATH": f"{commands}:{os.environ['PATH']}", "COMMAND_LOG": str(log)},
        text=True,
        capture_output=True,
    )
    assert result.returncode == 1
    assert "stop citybuddy-bench-auth before setup; no fixture changed" in result.stderr
    calls = log.read_text().splitlines()
    assert not any(
        line.startswith(("mysql ", "openssl genpkey", "openssl pkey", "uv ")) for line in calls
    )
    assert not (bench / ".run").exists()
