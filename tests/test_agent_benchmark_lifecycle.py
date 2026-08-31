from __future__ import annotations

import os
import shutil
import subprocess
import textwrap
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[1]
LADDER = REPOSITORY / "bench/agent/run_agent_ladder.sh"
ORCHESTRATOR = REPOSITORY / "bench/agent/run_worker_http_layout.sh"
COMMIT = "1" * 40


def _write_executable(path: Path, content: str) -> None:
    path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")
    path.chmod(0o755)


def test_ladder_owns_k6_before_start_and_cleans_it_on_every_exit() -> None:
    source = LADDER.read_text(encoding="utf-8")

    create = source.index('k6_container_id="$(docker create --name citybuddy-bench-k6')
    start = source.index('docker start "$k6_container_id"')
    sampling = source.index("while [ \"$(docker inspect -f '{{.State.Running}}'")
    normal_cleanup = source.index("if ! cleanup_k6_container", sampling)

    assert create < start < sampling < normal_cleanup
    assert 'docker rm -f "$container_id"' in source
    assert "resolve_owned_k6_container" in source
    assert "candidate_nonce" in source and "candidate_commit" in source
    assert "candidate_owner" in source and "citybuddy.bench.ladder-owner" in source
    assert "Refusing to replace existing container citybuddy-bench-k6" in source
    assert "docker rm -f citybuddy-bench-k6" not in source
    assert 'capture_k6_diagnostics "$status"' in source
    assert "trap report_unpublished_result EXIT" in source
    assert "trap 'exit 130' INT" in source


def test_ladder_calculates_only_from_the_retained_k6_summary() -> None:
    source = LADDER.read_text(encoding="utf-8")

    assert '--summary "$summary_path"' in source
    assert "--points" not in source
    assert 'run --summary-export="/out/$summary_name"' in source
    assert '--out "json=' not in source
    assert "cpu_by_step" not in source
    assert "contract_status" not in source


def test_publication_rollback_removes_the_completion_marker_first() -> None:
    source = ORCHESTRATOR.read_text(encoding="utf-8")

    assert 'publication_names+=("$completion_name")' in source
    assert "for ((index = ${#publication_names[@]} - 1; index >= 0; index--))" in source


def _prepare_worker_runner(tmp_path: Path) -> tuple[Path, dict[str, str], Path, Path]:
    runner = tmp_path / "bench/agent/run_worker_http_layout.sh"
    runner.parent.mkdir(parents=True)
    shutil.copyfile(ORCHESTRATOR, runner)
    runner.chmod(0o755)
    results = tmp_path / "bench/results"
    results.mkdir()
    run_dir = tmp_path / "bench/.run"
    run_dir.mkdir()
    (tmp_path / ".env").write_text("MYSQL_BOOTSTRAP_PASSWORD=root\n", encoding="utf-8")

    fake_bin = tmp_path / "fake-bin"
    fake_bin.mkdir()
    git_state = tmp_path / "git-state"
    git_state.write_text(f"{COMMIT}\n", encoding="utf-8")
    mysql_state = tmp_path / "mysql-state"
    mysql_state.write_text("151\n", encoding="utf-8")
    publication_trip = tmp_path / "publication-trip"

    _write_executable(
        fake_bin / "git",
        """
        #!/bin/sh
        case "$1" in
          status) exit 0 ;;
          rev-parse) sed -n 1p "$GIT_STATE" ;;
          *) exit 1 ;;
        esac
        """,
    )
    _write_executable(
        fake_bin / "docker",
        """
        #!/bin/sh
        case "$1" in
          inspect) printf '%064d\n' 1 ;;
          port) printf '127.0.0.1:3306\n' ;;
          version) printf '29.5.3\n' ;;
          info)
            case "$*" in
              *NCPU*) printf '8\n' ;;
              *MemTotal*) printf '14638391296\n' ;;
              *) exit 1 ;;
            esac
            ;;
          *) exit 1 ;;
        esac
        """,
    )
    _write_executable(
        fake_bin / "mysql",
        r"""
        #!/bin/sh
        case "$*" in
          *"SHOW GLOBAL VARIABLES LIKE 'max_connections'"*)
            printf 'Variable_name\tValue\nmax_connections\t%s\n' "$(sed -n 1p "$MYSQL_STATE")"
            ;;
          *"SET GLOBAL max_connections = "*)
            value=$(
              printf '%s\n' "$*" \
                | sed -n 's/.*SET GLOBAL max_connections = \([0-9][0-9]*\).*/\1/p'
            )
            printf '%s\n' "$value" > "$MYSQL_STATE"
            ;;
          *) exit 1 ;;
        esac
        """,
    )
    _write_executable(
        fake_bin / "mv",
        """
        #!/bin/sh
        destination=''
        for argument in "$@"; do destination=$argument; done
        if [ "$TRIP_PUBLICATION" = 1 ] \
          && [ ! -e "$PUBLICATION_TRIP" ] \
          && printf '%s\n' "$destination" | grep -q '/bench/results/'; then
          : > "$PUBLICATION_TRIP"
          printf '%040d\n' 2 > "$GIT_STATE"
        fi
        exec /bin/mv "$@"
        """,
    )
    _write_executable(
        tmp_path / "bench/setup_bench_env.sh",
        """
        #!/bin/sh
        mkdir -p bench/.run
        printf 'BENCH_USERS=%s\n' "$BENCH_USERS" > bench/.run/bench.env
        """,
    )
    _write_executable(
        runner.parent / "setup_agent_bench.sh",
        """
        #!/bin/sh
        exit 0
        """,
    )
    _write_executable(
        runner.parent / "run_agent_ladder.sh",
        """
        #!/bin/sh
        count=$(( $(sed -n 1p "$LADDER_COUNT") + 1 ))
        printf '%s\n' "$count" > "$LADDER_COUNT"
        if [ "${FAIL_LADDER_ON:-0}" -eq "$count" ]; then exit 29; fi
        mkdir -p "$AGENT_RESULTS_DIR"
        printf 'summary for %s\n' "$LABEL" > "$AGENT_RESULTS_DIR/agent_${LABEL}_summary.json"
        """,
    )

    ladder_count = tmp_path / "ladder-count"
    ladder_count.write_text("0\n", encoding="utf-8")

    environment = dict(os.environ)
    environment.update(
        {
            "GIT_STATE": str(git_state),
            "MYSQL_STATE": str(mysql_state),
            "LADDER_COUNT": str(ladder_count),
            "PATH": f"{fake_bin}:{environment['PATH']}",
            "PUBLICATION_TRIP": str(publication_trip),
        }
    )
    return runner, environment, results, mysql_state


def test_final_checkout_change_rolls_back_every_artifact_and_allows_rerun(
    tmp_path: Path,
) -> None:
    runner, environment, results, mysql_state = _prepare_worker_runner(tmp_path)

    failed_environment = environment | {"TRIP_PUBLICATION": "1"}
    failed = subprocess.run(
        ["/bin/bash", str(runner), "baseline"],
        cwd=tmp_path,
        env=failed_environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert failed.returncode != 0
    assert "measured checkout changed" in failed.stderr
    assert list(results.iterdir()) == []
    assert mysql_state.read_text(encoding="utf-8") == "151\n"

    Path(environment["GIT_STATE"]).write_text(f"{COMMIT}\n", encoding="utf-8")
    completed = subprocess.run(
        ["/bin/bash", str(runner), "baseline"],
        cwd=tmp_path,
        env=environment | {"TRIP_PUBLICATION": "0"},
        check=False,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr
    assert len(list(results.glob("*_summary.json"))) == 4
    assert len(list(results.glob("*_mysql_*.txt"))) == 2
    descriptors = list(results.glob("*_baseline_experiment.txt"))
    assert len(descriptors) == 1
    assert mysql_state.read_text(encoding="utf-8") == "151\n"


def test_factorial_retries_the_complete_block_and_records_the_block(tmp_path: Path) -> None:
    runner, environment, results, mysql_state = _prepare_worker_runner(tmp_path)
    (tmp_path / "bench/.run/bench.env").write_text("BENCH_USERS=10000\n", encoding="utf-8")

    completed = subprocess.run(
        ["/bin/bash", str(runner), "factorial"],
        cwd=tmp_path,
        env=environment | {"TRIP_PUBLICATION": "0", "FAIL_LADDER_ON": "2"},
        check=False,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr
    names = {path.name for path in results.glob("*_summary.json")}
    assert any("_b1a1p1_1s_" in name for name in names)
    assert any("_b1a2p1_1s_" in name for name in names)
    assert any("_b1a2p4_2pa_" in name for name in names)
    descriptor = next(results.glob("*_factorial_experiment.txt"))
    assert "retry_blocks=1\n" in descriptor.read_text(encoding="utf-8")
    assert mysql_state.read_text(encoding="utf-8") == "151\n"
