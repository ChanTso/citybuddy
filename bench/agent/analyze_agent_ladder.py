"""Print per-rate counts and latency from a k6 summary export."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, cast


def metric_values(summary: dict[str, Any], name: str) -> dict[str, Any]:
    metric = summary["metrics"][name]
    return cast(dict[str, Any], metric.get("values", metric))


def counter(summary: dict[str, Any], name: str, rate: int) -> int:
    return int(metric_values(summary, f"{name}{{rate:{rate}}}")["count"])


def dropped_by_rate(summary: dict[str, Any], rates: list[int]) -> dict[int, int]:
    try:
        aggregate = int(metric_values(summary, "dropped_iterations")["count"])
        dropped = {
            rate: int(
                metric_values(summary, f"dropped_iterations{{scenario:rate_{rate}}}")[
                    "count"
                ]
            )
            for rate in rates
        }
    except KeyError as error:
        raise ValueError(f"missing dropped-iteration metric: {error.args[0]}") from error
    attributed = sum(dropped.values())
    if attributed != aggregate:
        raise ValueError(
            "dropped_iterations aggregate "
            f"{aggregate} does not match scenario-tagged total {attributed}"
        )
    return dropped


def render(value: object) -> str:
    return "none" if value is None else f"{float(str(value)):.1f}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--rates", required=True)
    parser.add_argument("--step-seconds", type=int, required=True)
    args = parser.parse_args()
    if re.fullmatch(r"[1-9][0-9]*(,[1-9][0-9]*)*", args.rates) is None:
        raise ValueError("Rates must be comma-separated positive integers")
    if args.step_seconds < 1:
        raise ValueError("Step duration must be positive")

    rates = [int(value) for value in args.rates.split(",")]
    summary = json.loads(args.summary.read_text(encoding="utf-8"))
    dropped = dropped_by_rate(summary, rates)
    print(f"\n=== {args.label} ({args.step_seconds}s steps) ===")
    print(
        "rate nominal_offered started finished served nonserved dropped interrupted 5xx errors "
        "finished/s p50_ms p95_ms p99_ms max_ms"
    )
    for rate in rates:
        started = counter(summary, "agent_started_iterations", rate)
        finished = counter(summary, "agent_finished_iterations", rate)
        served = counter(summary, "agent_served_iterations", rate)
        nonserved = counter(summary, "agent_nonserved_iterations", rate)
        status_5xx = counter(summary, "agent_http_5xx", rate)
        errors = counter(summary, "agent_http_errors", rate)
        latency = metric_values(summary, f"http_req_duration{{rate:{rate}}}")
        latency_values = (
            [latency.get(name) for name in ("med", "p(95)", "p(99)", "max")]
            if int(latency["count"]) > 0
            else [None, None, None, None]
        )
        print(
            rate,
            rate * args.step_seconds,
            started,
            finished,
            served,
            nonserved,
            dropped[rate],
            started - finished,
            status_5xx,
            errors,
            f"{finished / args.step_seconds:.2f}",
            *(render(value) for value in latency_values),
        )


if __name__ == "__main__":
    main()
