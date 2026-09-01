from __future__ import annotations

import os
import re
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPOSITORY))
from bench.agent import build_warm_history_fixture as warm_history  # noqa: E402

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

    assert container == "citybuddy-bench-elasticsearch"
    assert f'.containers["{container}"].id' in sampled_targets
    assert '"$mysql_container_id"' in sampled_targets
    assert "citybuddy-mysql-1" not in sampled_targets
    assert "citybuddy-elasticsearch-1" not in sampled_targets
    assert "{{.Name}}" in runner


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


@pytest.mark.parametrize(
    (
        "case_name",
        "persisted_count",
        "candidate_count",
        "loaded_sequences",
        "included_sequences",
        "older_turns_available",
        "watermark",
    ),
    [
        ("empty", 0, 0, [], [], False, "low"),
        ("one-short", 1, 1, [1], [1], False, "low"),
        ("max-count", 17, 17, list(range(2, 18)), list(range(2, 18)), True, "low"),
        ("high-pressure", 17, 17, list(range(2, 18)), [17], True, "high"),
    ],
)
def test_warm_history_cases_match_the_real_loader_and_context_bounds(
    case_name: str,
    persisted_count: int,
    candidate_count: int,
    loaded_sequences: list[int],
    included_sequences: list[int],
    older_turns_available: bool,
    watermark: str,
) -> None:
    plan = warm_history.build_fixture_plan(warm_history.parse_case(case_name))

    assert [turn.turn_sequence for turn in plan.persisted_turns] == list(
        range(1, persisted_count + 1)
    )
    assert len(plan.query_candidates) == candidate_count
    assert [turn.turn_sequence for turn in plan.loaded_history.turns] == loaded_sequences
    assert plan.loaded_history.older_turns_available is older_turns_available
    assert [turn.turn_sequence for turn in plan.context_window.turns] == included_sequences
    assert plan.context_window.loaded_turn_count == len(loaded_sequences)
    assert plan.context_window.older_turns_available is older_turns_available
    assert plan.context_window.pressure == watermark
    assert plan.tool_profile == "read"


def test_high_pressure_fixture_reaches_the_exact_ascii_limits_and_trims_whole_turns() -> None:
    plan = warm_history.build_fixture_plan("high-pressure")

    assert all(
        len(turn.user_text) == 4000
        and len(turn.assistant_text) == 256
        and turn.user_text.isascii()
        and turn.assistant_text.isascii()
        for turn in plan.persisted_turns
    )
    assert plan.context_window.candidate_tokens == 68_224
    assert plan.context_window.included_tokens == 4_264
    assert plan.context_window.evidence()["omittedLoadedTurnCount"] == 15
    assert [turn.turn_sequence for turn in plan.context_window.turns] == [17]


@pytest.mark.parametrize("case_name", warm_history.CASE_NAMES)
def test_warm_history_text_is_safe_and_the_fixed_delivery_request_stays_read(
    case_name: warm_history.CaseName,
) -> None:
    plan = warm_history.build_fixture_plan(case_name)

    warm_history.validate_safe_text(warm_history.CURRENT_MESSAGE)
    for turn in plan.persisted_turns:
        warm_history.validate_safe_text(turn.user_text)
        warm_history.validate_safe_text(turn.assistant_text)
    assert plan.tool_profile == "read"


def test_warm_history_pool_selection_rejects_short_or_repeated_targets(tmp_path: Path) -> None:
    pool = tmp_path / "pool.json"
    pool.write_text(
        '[{"sessionId":"session-1","token":"secret-1","subject":"bench-user-1"}]\n',
        encoding="utf-8",
    )
    assert warm_history.select_target_session_ids(pool, 1) == ("session-1",)
    with pytest.raises(ValueError, match="at least 2 entries"):
        warm_history.select_target_session_ids(pool, 2)

    pool.write_text('[{"sessionId":"session-1"},{"sessionId":"session-1"}]\n', encoding="utf-8")
    with pytest.raises(ValueError, match="repeats a target session id"):
        warm_history.select_target_session_ids(pool, 2)


def test_warm_history_fixture_record_contains_boundary_evidence_without_pool_secrets() -> None:
    plan = warm_history.build_fixture_plan("max-count")
    started = datetime(2026, 8, 31, 12, 0, tzinfo=UTC)
    completed = datetime(2026, 8, 31, 12, 1, tzinfo=UTC)

    document = warm_history.fixture_document(
        plan=plan,
        citybuddy_commit="a" * 40,
        setup_nonce="b" * 32,
        target_session_count=8,
        started_at=started,
        completed_at=completed,
    )

    assert document["history"] == {
        "persistedTurnCount": 17,
        "candidateTurnCount": 17,
        "loadedTurnCount": 16,
        "includedTurnCount": 16,
        "olderTurnsAvailable": True,
        "tokenEstimator": "utf8-bytes-v1",
        "tokenBudget": 6144,
        "tokenWatermark": "low",
        "candidateTokens": 768,
        "includedTokens": 768,
        "omittedLoadedTurnCount": 0,
        "trimAction": "none",
    }
    serialized = repr(document)
    assert "secret-" not in serialized
    assert "bench-user-" not in serialized
    assert document["fixtureSetupWindowUtc"] == {
        "startedAt": "2026-08-31T12:00:00.000000Z",
        "completedAt": "2026-08-31T12:01:00.000000Z",
    }
