"""Assemble one warm-history result from the runner's raw evidence."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, cast

FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
SETUP_NONCE = re.compile(r"^[0-9a-f]{32}$")
HISTORY_CASES = {"empty", "one-short", "max-count", "high-pressure"}
CONTRACT_COUNT_FIELDS = (
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
)


def read_json(path: Path) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return document


def read_contract(path: Path) -> tuple[dict[str, str], dict[str, str]]:
    metadata: dict[str, str] = {}
    header: list[str] | None = None
    row: list[str] | None = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("boundary_turns\t"):
            header = line.split("\t")
        elif header is not None and row is None:
            row = line.split("\t")
        elif "=" in line and "\t" not in line:
            key, value = line.split("=", 1)
            metadata[key] = value
    if header is None or row is None or len(header) != len(row):
        raise ValueError("Warm-history SQL contract has no complete result row")
    if metadata.get("contract_status") != "pass":
        raise ValueError("Warm-history SQL contract did not pass")
    return metadata, dict(zip(header, row, strict=True))


def metric(summary: dict[str, Any], name: str) -> dict[str, Any]:
    value = summary["metrics"][name]
    return cast(dict[str, Any], value.get("values", value))


def metric_count(summary: dict[str, Any], name: str) -> int:
    if name not in summary["metrics"]:
        return 0
    return int(metric(summary, name)["count"])


def csv_integers(value: str) -> list[int]:
    return [] if value == "missing" else [int(item) for item in value.split(",")]


def csv_strings(value: str) -> list[str]:
    return [] if value == "missing" else value.split(",")


def csv_booleans(value: str) -> list[bool]:
    result: list[bool] = []
    for item in csv_strings(value):
        if item not in {"true", "false"}:
            raise ValueError("olderTurnsAvailable SQL evidence is not boolean")
        result.append(item == "true")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--setup-environment", type=Path, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--rate", type=int, required=True)
    parser.add_argument("--duration", type=int, required=True)
    parser.add_argument("--run-started-at", required=True)
    parser.add_argument("--run-completed-at", required=True)
    parser.add_argument("--k6-image-reference", required=True)
    parser.add_argument("--k6-image-id", required=True)
    parser.add_argument("--k6-version", required=True)
    parser.add_argument("--artifact-prefix", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    fixture = read_json(args.fixture)
    setup_environment = read_json(args.setup_environment)
    summary = read_json(args.summary)
    contract_metadata, contract = read_contract(args.contract)

    commit = fixture.get("citybuddyCommit")
    nonce = fixture.get("setupNonce")
    history_case = fixture.get("case")
    if not isinstance(commit, str) or FULL_SHA.fullmatch(commit) is None:
        raise ValueError("Fixture does not carry a full CityBuddy commit SHA")
    if not isinstance(nonce, str) or SETUP_NONCE.fullmatch(nonce) is None:
        raise ValueError("Fixture does not carry a valid setup nonce")
    if history_case not in HISTORY_CASES:
        raise ValueError("Fixture carries an unknown warm-history case")
    if (
        setup_environment.get("citybuddyCommit") != commit
        or setup_environment.get("setupNonce") != nonce
        or contract_metadata.get("citybuddy_commit") != commit
        or contract_metadata.get("setup_nonce") != nonce
        or contract_metadata.get("case") != history_case
        or contract_metadata.get("expected_tool_profile") != "read"
    ):
        raise ValueError("Warm-history fixture, runtime, and SQL evidence boundaries disagree")
    if args.rate < 1 or args.duration < 1:
        raise ValueError("Warm-history rate and duration must be positive")

    history = fixture["history"]
    base_window = setup_environment["setupWindowUtc"]
    fixture_window = fixture["fixtureSetupWindowUtc"]
    prefix = args.artifact_prefix

    summary["citybuddyCommit"] = commit
    summary["warmHistory"] = {
        "case": history_case,
        "durationSeconds": args.duration,
        "k6ImageId": args.k6_image_id,
        "k6ImageReference": args.k6_image_reference,
        "k6Version": args.k6_version,
        "ratePerSecond": args.rate,
        "runWindowUtc": {
            "startedAt": args.run_started_at,
            "completedAt": args.run_completed_at,
        },
        "setupNonce": nonce,
    }
    summary_temporary = args.summary.with_suffix(args.summary.suffix + ".tmp")
    summary_temporary.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    summary_temporary.replace(args.summary)

    route_context: dict[str, object] = {
        "artifact": f"{prefix}_contract.tsv",
        **{field: int(contract[field]) for field in CONTRACT_COUNT_FIELDS},
        "actualToolProfiles": csv_strings(contract["actual_tool_profiles"]),
        "persistedTurnCounts": csv_integers(contract["persisted_turn_counts"]),
        "candidateTurnCounts": csv_integers(contract["candidate_turn_counts"]),
        "loadedTurnCounts": csv_integers(contract["loaded_turn_counts"]),
        "includedTurnCounts": csv_integers(contract["included_turn_counts"]),
        "olderTurnsAvailable": csv_booleans(contract["older_turn_values"]),
        "tokenWatermarks": csv_strings(contract["token_watermarks"]),
        "candidateTokens": csv_integers(contract["candidate_token_counts"]),
        "includedTokens": csv_integers(contract["included_token_counts"]),
        "tokenBudgets": csv_integers(contract["token_budgets"]),
        "omittedLoadedTurnCounts": csv_integers(contract["omitted_loaded_turn_counts"]),
        "contractStatus": "pass",
        "evidenceRole": "workload and context contract only, not performance attribution",
    }
    http_failed = metric(summary, "http_req_failed")
    result = {
        "formatVersion": "citybuddy-agent-warm-history-result-v1",
        "citybuddyCommit": commit,
        "setupNonce": nonce,
        "label": args.label,
        "case": history_case,
        "configuration": {
            "arrivalModel": "constant-arrival-rate",
            "ratePerSecond": args.rate,
            "durationSeconds": args.duration,
            "gracefulStopSeconds": 45,
            "targetSessionCount": fixture["targetSessionCount"],
            "k6ImageReference": args.k6_image_reference,
            "k6ImageId": args.k6_image_id,
            "k6Version": args.k6_version,
        },
        "baseSetupWindowUtc": base_window,
        "fixtureSetupWindowUtc": fixture_window,
        "setupWindowUtc": {
            "startedAt": base_window["startedAt"],
            "completedAt": fixture_window["completedAt"],
        },
        "runWindowUtc": {
            "startedAt": args.run_started_at,
            "completedAt": args.run_completed_at,
        },
        "workload": {
            "exactMessage": "hello, can you tell me about delivery times",
            "expectedToolProfile": "read",
            "formalRequestsPerSession": 1,
        },
        "history": {**history, "trimmed": history["omittedLoadedTurnCount"] > 0},
        "routeContextEvidence": route_context,
        "counts": {
            "nominalOffered": args.rate * args.duration,
            "completed": metric_count(summary, "iterations"),
            "k6Dropped": metric_count(summary, "dropped_iterations"),
            "httpErrors": int(http_failed["passes"]),
            "completedTurns": int(contract["completed_turns"]),
            "failedTurns": int(contract["failed_turns"]),
            "processingTurns": int(contract["processing_turns"]),
        },
        "rawArtifacts": [
            f"{prefix}_summary.json",
            f"{prefix}_console.txt",
            f"{prefix}_points.json",
            f"{prefix}_contract.tsv",
            f"{prefix}_fixture.json",
            f"{prefix}_setup_environment.json",
        ],
    }
    temporary = args.out.with_suffix(args.out.suffix + ".tmp")
    temporary.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(args.out)


if __name__ == "__main__":
    main()
