from __future__ import annotations

import os
import re
import subprocess
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parents[1]
K6_SCRIPT = REPOSITORY / "bench/agent/k6/agent_paths.js"
LADDER_RUNNER = REPOSITORY / "bench/agent/run_agent_ladder.sh"


def test_agent_paths_define_exact_workload_contract() -> None:
    source = K6_SCRIPT.read_text(encoding="utf-8")
    workloads_start = source.index("const WORKLOADS = Object.freeze({")
    workloads_end = source.index("const PATH_NAME = __ENV.PATH_NAME;")
    workloads = source[workloads_start:workloads_end]

    assert re.findall(r"^  ([a-z]+): Object\.freeze\(\{$", workloads, re.MULTILINE) == [
        "greeting",
        "chat",
        "retrieval",
        "prepare",
    ]
    expected = {
        "greeting": ("message: () => 'hello',", "expectedToolProfile: 'none',"),
        "chat": (
            "message: () => 'hello, can you tell me about delivery times',",
            "expectedToolProfile: 'read',",
        ),
        "retrieval": (
            "message: () => 'retrieval-sufficient what does the refund policy cover',",
            "expectedToolProfile: 'all',",
        ),
        "prepare": (
            "message: (entry) => `action-prepare refund my order ${entry.orderId}`,",
            "expectedToolProfile: 'all',",
        ),
    }
    for selector, fragments in expected.items():
        start = workloads.index(f"  {selector}: Object.freeze({{")
        end = workloads.index("  }),", start)
        block = workloads[start:end]
        assert all(fragment in block for fragment in fragments)

    missing_validation = source.index("if (!PATH_NAME) {")
    unknown_validation = source.index(
        "if (!Object.prototype.hasOwnProperty.call(WORKLOADS, PATH_NAME)) {"
    )
    assert "const PATH_NAME = __ENV.PATH_NAME ||" not in source
    assert missing_validation < source.index("const RATES =")
    assert unknown_validation < source.index("const pool = new SharedArray")
    assert "return WORKLOAD.message(entry);" in source


@pytest.mark.parametrize(
    ("arguments", "diagnostic"),
    [
        ((), "Usage: run_agent_ladder.sh {greeting|chat|retrieval|prepare}"),
        (
            ("unknown",),
            "Unknown agent path 'unknown'; expected one of: greeting chat retrieval prepare.",
        ),
    ],
)
def test_ladder_rejects_invalid_path_before_external_commands(
    tmp_path: Path, arguments: tuple[str, ...], diagnostic: str
) -> None:
    empty_path = tmp_path / "empty-path"
    empty_path.mkdir()
    environment = dict(os.environ)
    environment.pop("BASH_ENV", None)
    environment["PATH"] = str(empty_path)

    result = subprocess.run(
        ["/bin/bash", str(LADDER_RUNNER), *arguments],
        cwd=tmp_path,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 2
    assert result.stdout == ""
    assert result.stderr == f"{diagnostic}\n"

    source = LADDER_RUNNER.read_text(encoding="utf-8")
    assert source.index('case "$PATH_NAME" in') < source.index('repo_root="')
