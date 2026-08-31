from __future__ import annotations

import os
import subprocess
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[1]
GATE = REPOSITORY / "bench/agent/setup_environment_gate.sh"


def _publish(staging: Path, results: Path, gate_status: int) -> subprocess.CompletedProcess[str]:
    environment = dict(os.environ)
    environment["GATE_STATUS"] = str(gate_status)
    return subprocess.run(
        [
            "/bin/bash",
            "-c",
            """
            repo_root="$1"
            source "$2"
            verify_agent_setup_environment() { return "$GATE_STATUS"; }
            publish_agent_results "$3/environment.json" "after test" "$3" "$4" \
              environment.json measurements.txt
            """,
            "publish-test",
            str(REPOSITORY),
            str(GATE),
            str(staging),
            str(results),
        ],
        cwd=REPOSITORY,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )


def _stage(tmp_path: Path) -> tuple[Path, Path]:
    staging = tmp_path / "staging"
    results = tmp_path / "results"
    staging.mkdir()
    (staging / "measurements.txt").write_text("numbers\n", encoding="utf-8")
    (staging / "environment.json").write_text("{}\n", encoding="utf-8")
    return staging, results


def test_failed_postcondition_keeps_formal_results_unpublished(tmp_path: Path) -> None:
    staging, results = _stage(tmp_path)

    result = _publish(staging, results, 37)

    assert result.returncode == 37
    assert not results.exists()
    assert (staging / "measurements.txt").read_text(encoding="utf-8") == "numbers\n"
    assert (staging / "environment.json").exists()


def test_successful_postcondition_publishes_complete_result_bundle(tmp_path: Path) -> None:
    staging, results = _stage(tmp_path)

    result = _publish(staging, results, 0)

    assert result.returncode == 0, result.stderr
    assert not staging.exists()
    assert (results / "measurements.txt").read_text(encoding="utf-8") == "numbers\n"
    assert (results / "environment.json").read_text(encoding="utf-8") == "{}\n"


def test_publication_refuses_an_existing_target_without_moving_staged_files(
    tmp_path: Path,
) -> None:
    staging, results = _stage(tmp_path)
    results.mkdir()
    (results / "measurements.txt").write_text("existing\n", encoding="utf-8")

    result = _publish(staging, results, 0)

    assert result.returncode != 0
    assert "refused to overwrite" in result.stderr
    assert (results / "measurements.txt").read_text(encoding="utf-8") == "existing\n"
    assert (staging / "measurements.txt").read_text(encoding="utf-8") == "numbers\n"
    assert (staging / "environment.json").read_text(encoding="utf-8") == "{}\n"
