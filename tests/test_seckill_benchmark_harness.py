import json
import re
import subprocess
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[1]
LADDER = REPOSITORY / "bench/run_ladder.sh"
CORRECTNESS = REPOSITORY / "bench/run_correctness.sh"
ANALYZER = REPOSITORY / "bench/analyze_ladder.py"
ROOT_README = REPOSITORY / "README.md"


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


def test_root_evidence_table_keeps_deadlock_and_legacy_rate_denominators_distinct() -> None:
    readme = ROOT_README.read_text(encoding="utf-8")

    assert "6,202 HTTP 500s in 23,254 requests" in readme
    assert "12,404 matching text lines, not an exact deadlock-event count" in readme
    assert "legacy first-to-last-completion-span density was 799.9/s" in readme
    assert "roughly 6,200 deadlock events" not in readme
    assert "799.9 req/s" not in readme
