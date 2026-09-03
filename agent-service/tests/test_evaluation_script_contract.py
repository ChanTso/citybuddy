from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parents[2]
INTEGRATION_SCRIPT = REPOSITORY / "scripts" / "test_evaluation_sandbox_integration.sh"
LEADING_DASH_SESSION = "-leading-dash-session"
TRACE_ID = "00000000-0000-0000-0000-000000000150"
TURN_ID = "00000000-0000-0000-0000-000000000151"
TIMESTAMP = "2026-08-04T00:00:00Z"
MODELED_OUTCOMES = (
    "completed",
    "budget_exhausted",
    "provider_denied",
    "retrieval_denied",
    "action_pending",
)


def run_helper(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, *arguments],
        cwd=REPOSITORY,
        check=False,
        capture_output=True,
        text=True,
    )


def context_event(sequence: int) -> dict[str, object]:
    return {
        "sequence": sequence,
        "eventKind": "CONTEXT_WINDOW",
        "outcome": "low",
        "reference": "session-context-v1",
        "context": {
            "policyVersion": "session-context-v1",
            "tokenEstimator": "utf8-bytes-v1",
            "tokenBudget": 6144,
            "tokenWatermark": "low",
            "candidateTokens": 0,
            "includedTokens": 0,
            "loadedTurnCount": 0,
            "includedTurnIds": [],
            "omittedLoadedTurnCount": 0,
            "olderTurnsAvailable": False,
        },
        "occurredAt": TIMESTAMP,
    }


def routing_event(sequence: int, routing: dict[str, object] | None = None) -> dict[str, object]:
    event: dict[str, object] = {
        "sequence": sequence,
        "eventKind": "ROUTING_DECISION",
        "outcome": "standard",
        "attemptLimit": 16,
        "occurredAt": TIMESTAMP,
    }
    if routing is not None:
        event["routing"] = routing
    return event


def terminal_events(outcome: str, start: int) -> list[dict[str, object]]:
    return [
        {
            "sequence": start + offset,
            "eventKind": event_kind,
            "outcome": outcome,
            "occurredAt": TIMESTAMP,
        }
        for offset, event_kind in enumerate(
            ("AGENT_OUTCOME", "ASSISTANT_RESPONSE", "TURN_COMPLETED")
        )
    ]


def write_evidence(
    path: Path,
    *,
    outcome: str,
    events: list[dict[str, object]],
    session_id: str = "sandbox-session",
) -> None:
    path.write_text(
        json.dumps(
            {
                "schemaVersion": "agent-evidence-v1",
                "traceId": TRACE_ID,
                "sessionId": session_id,
                "turnId": TURN_ID,
                "terminalOutcome": outcome,
                "events": events,
                "feedback": [],
            }
        ),
        encoding="utf-8",
    )


def test_evaluation_helpers_accept_bound_leading_dash_session(tmp_path: Path) -> None:
    evidence_path = tmp_path / "evidence.json"
    write_evidence(
        evidence_path,
        outcome="completed",
        session_id=LEADING_DASH_SESSION,
        events=[
            {"sequence": 1, "eventKind": "USER_INPUT", "occurredAt": TIMESTAMP},
            context_event(2),
            routing_event(3),
            *terminal_events("completed", 4),
        ],
    )
    evidence = run_helper(
        "scripts/check_agent_evaluation_evidence.py",
        str(evidence_path),
        "--trace",
        TRACE_ID,
        f"--session={LEADING_DASH_SESSION}",
        "--outcome",
        "completed",
    )
    assert evidence.returncode == 0, evidence.stderr

    audit_path = tmp_path / "audit.json"
    audit_path.write_text(
        json.dumps(
            {
                "entries": [
                    {
                        "sequence": 1,
                        "auditReferenceId": "audit-reference",
                        "sandboxId": "sandbox-main",
                        "supportSessionId": LEADING_DASH_SESSION,
                        "traceId": TRACE_ID,
                        "operationId": "operation",
                        "entityType": "PRODUCT_FIXTURE",
                        "entityId": "product",
                        "entityVersion": 1,
                        "outcome": "OBSERVED",
                        "createdAt": TIMESTAMP,
                    }
                ],
                "nextCursor": None,
            }
        ),
        encoding="utf-8",
    )
    audit = run_helper(
        "scripts/check_evaluation_views.py",
        "audit",
        str(audit_path),
        "--sandbox",
        "sandbox-main",
        f"--session={LEADING_DASH_SESSION}",
        "--count",
        "1",
        "--trace",
        TRACE_ID,
    )
    assert audit.returncode == 0, audit.stderr


def test_agent_evidence_checker_accepts_legacy_and_closed_routing(tmp_path: Path) -> None:
    valid_routing = {
        "refundContext": True,
        "refundContextSource": "session",
        "chitchat": False,
        "toolProfile": "read",
        "sessionPropagationEnabled": False,
    }
    cases = (
        ("legacy", None, True),
        ("closed", valid_routing, True),
        ("extra", {**valid_routing, "privateSignal": True}, False),
    )

    for name, routing, accepted in cases:
        route = routing_event(3, routing)
        evidence_path = tmp_path / f"routing-{name}.json"
        write_evidence(
            evidence_path,
            outcome="completed",
            events=[
                {"sequence": 1, "eventKind": "USER_INPUT", "occurredAt": TIMESTAMP},
                context_event(2),
                route,
                *terminal_events("completed", 4),
            ],
        )

        result = run_helper(
            "scripts/check_agent_evaluation_evidence.py",
            str(evidence_path),
            "--trace",
            TRACE_ID,
            "--session=sandbox-session",
            "--outcome",
            "completed",
        )

        assert (result.returncode == 0) is accepted, result.stderr


@pytest.mark.parametrize("outcome", MODELED_OUTCOMES)
def test_agent_evidence_checker_requires_exact_modeled_prefix(tmp_path: Path, outcome: str) -> None:
    evidence_path = tmp_path / f"modeled-{outcome}.json"
    write_evidence(
        evidence_path,
        outcome=outcome,
        events=[
            {"sequence": 1, "eventKind": "USER_INPUT", "occurredAt": TIMESTAMP},
            context_event(2),
            routing_event(3),
            *terminal_events(outcome, 4),
        ],
    )

    result = run_helper(
        "scripts/check_agent_evaluation_evidence.py",
        str(evidence_path),
        "--trace",
        TRACE_ID,
        "--session=sandbox-session",
        "--outcome",
        outcome,
    )

    assert result.returncode == 0, result.stderr


@pytest.mark.parametrize(
    ("name", "prefix"),
    (
        ("missing", []),
        ("reversed", [routing_event(2), context_event(3)]),
        (
            "late",
            [
                {"sequence": 2, "eventKind": "MODEL_OUTCOME", "occurredAt": TIMESTAMP},
                context_event(3),
                routing_event(4),
            ],
        ),
        ("duplicate-route", [context_event(2), routing_event(3), routing_event(4)]),
    ),
)
def test_agent_evidence_checker_rejects_invalid_modeled_prefix(
    tmp_path: Path, name: str, prefix: list[dict[str, object]]
) -> None:
    evidence_path = tmp_path / f"modeled-{name}.json"
    write_evidence(
        evidence_path,
        outcome="completed",
        events=[
            {"sequence": 1, "eventKind": "USER_INPUT", "occurredAt": TIMESTAMP},
            *prefix,
            *terminal_events("completed", len(prefix) + 2),
        ],
    )

    result = run_helper(
        "scripts/check_agent_evaluation_evidence.py",
        str(evidence_path),
        "--trace",
        TRACE_ID,
        "--session=sandbox-session",
        "--outcome",
        "completed",
    )

    assert result.returncode != 0


def test_agent_evidence_checker_retains_failed_and_local_action_shapes(tmp_path: Path) -> None:
    failed_events = [
        {"sequence": 1, "eventKind": "USER_INPUT", "occurredAt": TIMESTAMP},
        {"sequence": 2, "eventKind": "TURN_FAILED", "occurredAt": TIMESTAMP},
    ]
    evidence_path = tmp_path / "failed.json"
    write_evidence(evidence_path, outcome="failed", events=failed_events)
    failed = run_helper(
        "scripts/check_agent_evaluation_evidence.py",
        str(evidence_path),
        "--trace",
        TRACE_ID,
        "--session=sandbox-session",
        "--outcome",
        "failed",
    )
    assert failed.returncode == 0, failed.stderr

    for outcome, action_event in (
        ("action_clarification", None),
        ("action_declined", "ACTION_DECLINED"),
        ("action_expired", "ACTION_EXPIRED"),
    ):
        events = [{"sequence": 1, "eventKind": "USER_INPUT", "occurredAt": TIMESTAMP}]
        if action_event is not None:
            events.append({"sequence": 2, "eventKind": action_event, "occurredAt": TIMESTAMP})
        events.extend(terminal_events(outcome, len(events) + 1))
        evidence_path = tmp_path / f"local-{outcome}.json"
        write_evidence(evidence_path, outcome=outcome, events=events)

        result = run_helper(
            "scripts/check_agent_evaluation_evidence.py",
            str(evidence_path),
            "--trace",
            TRACE_ID,
            "--session=sandbox-session",
            "--outcome",
            outcome,
        )

        assert result.returncode == 0, result.stderr


def test_evaluation_script_binds_every_opaque_session_option() -> None:
    script = INTEGRATION_SCRIPT.read_text(encoding="utf-8")
    logical_commands = script.replace("\\\n", " ").splitlines()
    evidence_calls = [
        command
        for command in logical_commands
        if "python scripts/check_agent_evaluation_evidence.py" in command
    ]
    audit_calls = [
        command
        for command in logical_commands
        if "python scripts/check_evaluation_views.py audit" in command
    ]
    token_calls = [
        command
        for command in logical_commands
        if "python scripts/check_evaluation_token.py" in command and "edge_session" in command
    ]

    assert len(evidence_calls) == 6
    assert len(audit_calls) == 3
    calls = evidence_calls + audit_calls
    assert all(re.search(r'--session="\$(?:cb122_session|session_id)"', call) for call in calls)
    assert all(not re.search(r'--session\s+"\$', call) for call in calls)
    assert len(token_calls) == 1
    assert '--session="$edge_session"' in token_calls[0]

    edge_start = script.index("edge_sessions=(")
    auth_restart = script.rfind("start_auth evaluation", 0, edge_start)
    current_expiry = script.rfind('direct_expiry="', 0, edge_start)
    assert auth_restart < current_expiry < edge_start
    assert 'grep -Fq -- "$invalid_support_session"' in script


def test_support_session_producer_consumer_inventory_is_closed_without_normalization() -> None:
    agent = (REPOSITORY / "agent-service/src/citybuddy_agent/application.py").read_text(
        encoding="utf-8"
    )
    auth_controller = (
        REPOSITORY / "auth-service/src/main/java/io/citybuddy/auth/identity/AuthController.java"
    ).read_text(encoding="utf-8")
    auth_keys = (
        REPOSITORY / "auth-service/src/main/java/io/citybuddy/auth/identity/AuthKeySet.java"
    ).read_text(encoding="utf-8")
    support_session = (
        REPOSITORY
        / "commerce-service/src/main/java/io/citybuddy/commerce/identity/SupportSessionId.java"
    ).read_text(encoding="utf-8")
    evaluation_parser = (
        REPOSITORY / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation/"
        "EvaluationRequestParser.java"
    ).read_text(encoding="utf-8")
    view_parser = (
        REPOSITORY / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation/"
        "EvaluationViewRequestParser.java"
    ).read_text(encoding="utf-8")
    action = (
        REPOSITORY
        / "commerce-service/src/main/java/io/citybuddy/commerce/action/ActionService.java"
    ).read_text(encoding="utf-8")
    payment = (
        REPOSITORY
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment/MockPaymentService.java"
    ).read_text(encoding="utf-8")

    assert "session_id = secrets.token_urlsafe(32)" in agent
    assert 'Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")' in support_session
    assert 'Pattern.compile("^[A-Za-z0-9_-]{43}$")' in support_session
    assert "return value;" in evaluation_parser
    assert "TOOL_SUPPORT_SESSION_INVALID" in evaluation_parser
    assert "return EvaluationRequestParser.supportSession(value);" in view_parser
    assert action.count("SupportSessionId.isValid(") == 2
    assert "context.supportSessionId().strip()" not in action
    assert "SupportSessionId.isValid(request.supportSessionId())" in payment
    assert "matches(BOUNDED_CONTEXT, request.sandboxId())" in payment
    assert "matches(BOUNDED_CONTEXT, request.traceId())" in payment
    assert "request.sessionId()," in auth_controller
    assert '.claim("session", sessionId)' in auth_keys
