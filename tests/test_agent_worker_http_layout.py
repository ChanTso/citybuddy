from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPOSITORY))
from bench.agent.analyze_agent_ladder import dropped_by_rate  # noqa: E402

ANALYZER = REPOSITORY / "bench/agent/analyze_agent_ladder.py"
K6 = REPOSITORY / "bench/agent/k6/agent_paths.js"


def test_summary_calculator_prints_known_per_rate_vector(tmp_path: Path) -> None:
    summary = {
        "metrics": {
            "agent_started_iterations{rate:60}": {"values": {"count": 100}},
            "agent_finished_iterations{rate:60}": {"values": {"count": 95}},
            "agent_served_iterations{rate:60}": {"values": {"count": 90}},
            "agent_nonserved_iterations{rate:60}": {"values": {"count": 5}},
            "dropped_iterations": {"values": {"count": 160}},
            "dropped_iterations{scenario:rate_60}": {"values": {"count": 10}},
            "agent_http_5xx{rate:60}": {"values": {"count": 3}},
            "agent_http_errors{rate:60}": {"values": {"count": 5}},
            "http_req_duration{rate:60}": {
                "values": {"count": 95, "med": 12.5, "p(95)": 20, "p(99)": 30, "max": 40}
            },
            "agent_started_iterations{rate:75}": {"values": {"count": 0}},
            "agent_finished_iterations{rate:75}": {"values": {"count": 0}},
            "agent_served_iterations{rate:75}": {"values": {"count": 0}},
            "agent_nonserved_iterations{rate:75}": {"values": {"count": 0}},
            "dropped_iterations{scenario:rate_75}": {"values": {"count": 150}},
            "agent_http_5xx{rate:75}": {"values": {"count": 0}},
            "agent_http_errors{rate:75}": {"values": {"count": 0}},
            "http_req_duration{rate:75}": {
                "values": {"count": 0, "med": 0, "p(95)": 0, "p(99)": 0, "max": 0}
            },
        }
    }
    path = tmp_path / "summary.json"
    path.write_text(json.dumps(summary), encoding="utf-8")

    completed = subprocess.run(
        [
            sys.executable,
            str(ANALYZER),
            "--summary",
            str(path),
            "--label",
            "known",
            "--rates",
            "60,75",
            "--step-seconds",
            "2",
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    assert completed.stdout.splitlines()[-1] == (
        "75 150 0 0 0 0 150 0 0 0 0.00 none none none none"
    )
    assert completed.stdout.splitlines()[-2] == (
        "60 120 100 95 90 5 10 5 3 5 47.50 12.5 20.0 30.0 40.0"
    )


def test_summary_calculator_rejects_unattributed_aggregate_drops() -> None:
    summary = {
        "metrics": {
            "dropped_iterations": {"values": {"count": 10}},
            "dropped_iterations{rate:60}": {"values": {"count": 0}},
        }
    }

    with pytest.raises(ValueError, match="missing dropped-iteration metric") as error:
        dropped_by_rate(summary, [60])
    assert "dropped_iterations{scenario:rate_60}" in str(error.value)


def test_summary_calculator_rejects_mismatched_scenario_drop_total() -> None:
    summary = {
        "metrics": {
            "dropped_iterations": {"values": {"count": 10}},
            "dropped_iterations{scenario:rate_60}": {"values": {"count": 9}},
        }
    }
    with pytest.raises(
        ValueError,
        match="dropped_iterations aggregate 10 does not match scenario-tagged total 9",
    ):
        dropped_by_rate(summary, [60])


def test_k6_summary_carries_each_primary_per_rate_metric() -> None:
    source = K6.read_text(encoding="utf-8")
    custom_threshold_metrics = source.split("for (const metric of [", maxsplit=1)[1].split(
        "]) {", maxsplit=1
    )[0]

    for metric in (
        "agent_started_iterations",
        "agent_finished_iterations",
        "agent_served_iterations",
        "agent_nonserved_iterations",
        "agent_http_5xx",
        "agent_http_errors",
        "dropped_iterations",
        "http_req_duration",
    ):
        assert metric in source
    for metric in (
        "agent_started_iterations",
        "agent_finished_iterations",
        "agent_served_iterations",
        "agent_nonserved_iterations",
        "agent_http_5xx",
        "agent_http_errors",
    ):
        assert metric in custom_threshold_metrics
    assert "dropped_iterations" not in custom_threshold_metrics
    assert "thresholds[`${metric}{rate:${rate}}`]" in source
    assert "thresholds[`dropped_iterations{scenario:rate_${rate}}`]" in source
    assert "thresholds[`http_req_duration{rate:${rate}}`]" in source
    assert "summaryTrendStats: ['count', 'med', 'p(95)', 'p(99)', 'max']" in source
    assert "PATH_NAME === 'prepare' ? 'action_pending' : 'completed'" in source
