from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parents[1]
RUNNER = REPOSITORY / "bench/agent/run_warm_history.sh"
K6_SCRIPT = REPOSITORY / "bench/agent/k6/warm_history.js"
SUMMARIZER = REPOSITORY / "bench/agent/summarize_warm_history.py"


@pytest.mark.parametrize(
    ("arguments", "diagnostic"),
    [
        (
            (),
            "Usage: run_warm_history.sh "
            "{empty|one-short|max-count|high-pressure} RATE DURATION_SECONDS LABEL",
        ),
        (
            ("unknown", "1", "1", "probe"),
            "Unknown warm-history case 'unknown'; expected one of: "
            "empty one-short max-count high-pressure.",
        ),
        (("empty", "0", "1", "probe"), "RATE must be a positive integer."),
        (
            ("empty", "1", "0", "probe"),
            "DURATION_SECONDS must be a positive integer.",
        ),
        (
            ("empty", "1", "1", "../probe"),
            "LABEL must be 1-64 characters from [A-Za-z0-9._-] and start with an alphanumeric.",
        ),
    ],
)
def test_runner_rejects_invalid_cli_before_external_commands(
    tmp_path: Path, arguments: tuple[str, ...], diagnostic: str
) -> None:
    empty_path = tmp_path / "empty-path"
    empty_path.mkdir()
    environment = dict(os.environ)
    environment.pop("BASH_ENV", None)
    environment["PATH"] = str(empty_path)

    result = subprocess.run(
        ["/bin/bash", str(RUNNER), *arguments],
        cwd=tmp_path,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 2
    assert result.stdout == ""
    assert result.stderr == f"{diagnostic}\n"


def test_runner_refuses_existing_output_before_staging_or_fixture(tmp_path: Path) -> None:
    copied_runner = tmp_path / "bench/agent/run_warm_history.sh"
    copied_runner.parent.mkdir(parents=True)
    shutil.copyfile(RUNNER, copied_runner)
    results = tmp_path / "bench/results"
    results.mkdir()
    existing = results / "agent_warm_history_probe_summary.json"
    existing.write_text("preserve\n", encoding="utf-8")

    result = subprocess.run(
        ["/bin/bash", str(copied_runner), "empty", "1", "1", "probe"],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 1
    assert result.stdout == ""
    assert result.stderr == (
        f"Refusing to overwrite existing warm-history benchmark output: {existing}\n"
    )
    assert existing.read_text(encoding="utf-8") == "preserve\n"
    assert not (tmp_path / "bench/.run").exists()


def test_failed_runtime_gate_keeps_the_staged_snapshot_unpublished(tmp_path: Path) -> None:
    copied_runner = tmp_path / "bench/agent/run_warm_history.sh"
    copied_runner.parent.mkdir(parents=True)
    shutil.copyfile(RUNNER, copied_runner)
    (copied_runner.parent / "setup_environment_gate.sh").write_text(
        "verify_agent_setup_environment() { return 37; }\n", encoding="utf-8"
    )
    run_dir = tmp_path / "bench/.run"
    run_dir.mkdir()
    (run_dir / "agent_setup_environment.json").write_text("{}\n", encoding="utf-8")

    result = subprocess.run(
        ["/bin/bash", str(copied_runner), "empty", "1", "1", "gate-probe"],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 37
    staging = list(run_dir.glob("agent-warm-history.*"))
    assert len(staging) == 1
    assert (staging[0] / "agent_warm_history_gate-probe_setup_environment.json").exists()
    assert f"Unpublished warm-history diagnostics remain in {staging[0]}" in result.stderr
    assert not (tmp_path / "bench/results").exists()


def test_runner_orders_runtime_gates_staging_and_publication() -> None:
    source = RUNNER.read_text(encoding="utf-8")

    overwrite = source.index("Refusing to overwrite existing warm-history benchmark output")
    staging = source.index('staging_dir="$(mktemp -d')
    snapshot = source.index('cp "$live_setup_environment" "$setup_environment_path"')
    pre_gate = source.index(
        'verify_agent_setup_environment "$setup_environment_path" "before warm-history fixture"'
    )
    fixture = source.index("docker run --rm --name citybuddy-bench-warm-fixture")
    k6 = source.index('k6_container_id="$(docker run --detach --name citybuddy-bench-k6')
    wait = source.index('k6_status="$(docker wait "$k6_container_id")"')
    cleanup = source.index("cleanup_k6_container\n", wait)
    sql_contract = source.index("WITH measured_turns AS (")
    summarize = source.index("uv run python bench/agent/summarize_warm_history.py")
    publish = source.index("publish_agent_results")

    assert overwrite < staging < snapshot < pre_gate < fixture < k6
    assert k6 < wait < cleanup < sql_contract < summarize < publish
    assert '"after warm history"' in source[publish:]
    assert '"$setup_environment_name" "${result_names[@]}"' in source[publish:]
    assert "Unpublished warm-history diagnostics remain in $staging_dir" in source
    assert 'docker rm -f "$container_id"' in source
    assert "trap 'exit 130' INT" in source


def test_k6_uses_one_fixed_arrival_request_per_bounded_session() -> None:
    source = K6_SCRIPT.read_text(encoding="utf-8")

    assert "executor: 'constant-arrival-rate'" in source
    assert "const HISTORY_CASE = __ENV.HISTORY_CASE;" in source
    assert "requiredPositiveInteger('RATE')" in source
    assert "requiredPositiveInteger('DURATION_SECONDS')" in source
    assert "requiredPositiveInteger('TARGET_SESSION_COUNT')" in source
    assert "__ENV.HISTORY_CASE ||" not in source
    assert "__ENV.RATE ||" not in source
    assert "__ENV.DURATION_SECONDS ||" not in source
    assert source.index("if (!HISTORY_CASE") < source.index("new SharedArray")
    assert source.count("http.post(") == 1
    assert "const index = exec.scenario.iterationInTest;" in source
    assert "index >= TARGET_SESSION_COUNT || index >= pool.length" in source
    assert "% pool.length" not in source
    assert "JSON.stringify({ message: MESSAGE })" in source
    assert "const MESSAGE = 'hello, can you tell me about delivery times';" in source
    assert "'X-Session-Id': entry.sessionId" in source
    assert "`${RUN_ID}-warm-${HISTORY_CASE}-${index}`" in source


def test_summarizer_records_provenance_history_context_and_counts(tmp_path: Path) -> None:
    commit = "a" * 40
    nonce = "b" * 32
    fixture = tmp_path / "fixture.json"
    setup_environment = tmp_path / "setup.json"
    summary = tmp_path / "summary.json"
    contract = tmp_path / "contract.tsv"
    output = tmp_path / "result.json"
    fixture.write_text(
        json.dumps(
            {
                "formatVersion": "citybuddy-agent-warm-history-fixture-v1",
                "citybuddyCommit": commit,
                "setupNonce": nonce,
                "case": "high-pressure",
                "fixtureSetupWindowUtc": {
                    "startedAt": "2026-08-31T01:01:00.000000Z",
                    "completedAt": "2026-08-31T01:01:02.000000Z",
                },
                "targetSessionCount": 120,
                "history": {
                    "persistedTurnCount": 17,
                    "candidateTurnCount": 17,
                    "loadedTurnCount": 16,
                    "includedTurnCount": 1,
                    "olderTurnsAvailable": True,
                    "tokenEstimator": "utf8-bytes-v1",
                    "tokenBudget": 6144,
                    "tokenWatermark": "high",
                    "candidateTokens": 68224,
                    "includedTokens": 4264,
                    "omittedLoadedTurnCount": 15,
                    "trimAction": "omit-oldest-whole-turns",
                },
                "sessionBoundary": {
                    "count": 120,
                    "distinctCount": 120,
                    "minimumPersistedTurnCount": 17,
                    "maximumPersistedTurnCount": 17,
                    "firstTurnSequence": 1,
                    "lastTurnSequence": 17,
                },
            }
        )
        + "\n",
        encoding="utf-8",
    )
    setup_environment.write_text(
        json.dumps(
            {
                "formatVersion": "citybuddy-agent-setup-environment-v1",
                "citybuddyCommit": commit,
                "setupNonce": nonce,
                "setupWindowUtc": {
                    "startedAt": "2026-08-31T01:00:00Z",
                    "completedAt": "2026-08-31T01:00:30Z",
                },
            }
        )
        + "\n",
        encoding="utf-8",
    )
    summary.write_text(
        json.dumps(
            {
                "metrics": {
                    "iterations": {"count": 101},
                    "dropped_iterations": {"count": 2},
                    "http_req_failed": {"passes": 3, "fails": 98, "value": 0.03},
                    "http_req_duration": {
                        "med": 10.0,
                        "p(95)": 20.0,
                        "p(99)": 30.0,
                        "max": 40.0,
                    },
                }
            }
        )
        + "\n",
        encoding="utf-8",
    )
    header = "\t".join(
        (
            "boundary_turns",
            "completed_turns",
            "failed_turns",
            "processing_turns",
            "distinct_sessions",
            "max_requests_per_session",
            "matching_profile_turns",
            "matching_context_turns",
            "routing_events",
            "context_events",
            "actual_tool_profiles",
            "persisted_turn_counts",
            "candidate_turn_counts",
            "loaded_turn_counts",
            "included_turn_counts",
            "older_turn_values",
            "token_watermarks",
            "candidate_token_counts",
            "included_token_counts",
            "token_budgets",
            "omitted_loaded_turn_counts",
        )
    )
    row = "\t".join(
        (
            "101",
            "98",
            "2",
            "1",
            "101",
            "1",
            "98",
            "98",
            "98",
            "98",
            "read",
            "17",
            "17",
            "16",
            "1",
            "true",
            "high",
            "68224",
            "4264",
            "6144",
            "15",
        )
    )
    contract.write_text(
        "\n".join(
            (
                f"citybuddy_commit={commit}",
                f"setup_nonce={nonce}",
                "case=high-pressure",
                "expected_tool_profile=read",
                header,
                row,
                "contract_status=pass",
            )
        )
        + "\n",
        encoding="utf-8",
    )

    subprocess.run(
        [
            sys.executable,
            str(SUMMARIZER),
            "--fixture",
            str(fixture),
            "--summary",
            str(summary),
            "--contract",
            str(contract),
            "--setup-environment",
            str(setup_environment),
            "--label",
            "high-probe_",
            "--rate",
            "10",
            "--duration",
            "10",
            "--run-started-at",
            "2026-08-31T01:02:00.000000000Z",
            "--run-completed-at",
            "2026-08-31T01:02:10.000000000Z",
            "--k6-image-reference",
            f"grafana/k6@sha256:{'c' * 64}",
            "--k6-image-id",
            f"sha256:{'d' * 64}",
            "--k6-version",
            "k6 v2.2.0",
            "--artifact-prefix",
            "bench/results/agent_warm_history_high_probe_",
            "--out",
            str(output),
        ],
        check=True,
    )

    result = json.loads(output.read_text(encoding="utf-8"))
    assert result["citybuddyCommit"] == commit
    assert result["setupNonce"] == nonce
    assert result["case"] == "high-pressure"
    assert result["baseSetupWindowUtc"] == {
        "startedAt": "2026-08-31T01:00:00Z",
        "completedAt": "2026-08-31T01:00:30Z",
    }
    assert result["setupWindowUtc"] == {
        "startedAt": "2026-08-31T01:00:00Z",
        "completedAt": "2026-08-31T01:01:02.000000Z",
    }
    assert result["runWindowUtc"]["completedAt"] == "2026-08-31T01:02:10.000000000Z"
    assert result["history"]["candidateTurnCount"] == 17
    assert result["history"]["includedTurnCount"] == 1
    assert result["history"]["trimmed"] is True
    assert result["routeContextEvidence"]["actualToolProfiles"] == ["read"]
    assert result["routeContextEvidence"]["olderTurnsAvailable"] == [True]
    assert result["counts"] == {
        "nominalOffered": 100,
        "completed": 101,
        "k6Dropped": 2,
        "httpErrors": 3,
        "completedTurns": 98,
        "failedTurns": 2,
        "processingTurns": 1,
    }
    assert (
        "bench/results/agent_warm_history_high_probe__setup_environment.json"
        in result["rawArtifacts"]
    )
    assert (
        result["routeContextEvidence"]["artifact"]
        == "bench/results/agent_warm_history_high_probe__contract.tsv"
    )
    stamped_summary = json.loads(summary.read_text(encoding="utf-8"))
    assert stamped_summary["citybuddyCommit"] == commit
    assert stamped_summary["warmHistory"]["case"] == "high-pressure"
