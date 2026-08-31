from __future__ import annotations

import os
import re
import subprocess
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parents[1]
K6_SCRIPT = REPOSITORY / "bench/agent/k6/agent_paths.js"
LADDER_RUNNER = REPOSITORY / "bench/agent/run_agent_ladder.sh"
SETUP = REPOSITORY / "bench/agent/setup_agent_bench.sh"


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


def test_agent_and_cpu_sampling_use_the_same_dedicated_elasticsearch() -> None:
    setup = SETUP.read_text(encoding="utf-8")
    runner = LADDER_RUNNER.read_text(encoding="utf-8")
    endpoint = re.search(r"AGENT_ELASTICSEARCH_URL=http://([^:/]+):9200", setup)
    assert endpoint is not None
    container = endpoint.group(1)

    sampled_targets_start = runner.index("sampled_targets=(")
    sampled_targets_end = runner.index(")\nwhile", sampled_targets_start)
    sampled_targets = runner[sampled_targets_start:sampled_targets_end]
    sampled_names_start = runner.index("sampled_names=(")
    sampled_names_end = runner.index(")\nsampled_targets", sampled_names_start)
    sampled_names = runner[sampled_names_start:sampled_names_end]

    assert container == "citybuddy-bench-elasticsearch"
    assert f'.containers["{container}"].id' in sampled_targets
    assert container in sampled_names
    assert '"$mysql_container_id"' in sampled_targets
    assert "citybuddy-mysql-1" not in sampled_targets
    assert "citybuddy-elasticsearch-1" not in sampled_targets
    assert "citybuddy-elasticsearch-1" not in sampled_names


def test_agent_ladder_uses_and_records_a_digest_pinned_k6_image() -> None:
    runner = LADDER_RUNNER.read_text(encoding="utf-8")
    pinned = re.search(
        r'^K6_IMAGE_REFERENCE="grafana/k6@sha256:([0-9a-f]{64})"$', runner, re.MULTILINE
    )

    assert pinned is not None
    assert "grafana/k6:latest" not in runner
    assert '--entrypoint k6 "$k6_image_id"' in runner
    assert "k6_image_reference=%s" in runner
    assert "k6_image_id=%s" in runner
    assert "k6_version=%s" in runner
