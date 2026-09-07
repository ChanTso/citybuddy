from datetime import UTC, datetime
from typing import Any

import pytest
from citybuddy_agent.evaluation import (
    EvaluationEvidenceInvalid,
    MysqlEvaluationEvidenceStore,
)


def routing_evidence_payload(
    *,
    refund_context: bool,
    refund_context_source: str,
    chitchat: bool,
    tool_profile: str,
    session_propagation_enabled: object,
) -> dict[str, object]:
    return {
        "signals": {
            "refundContext": refund_context,
            "refundContextSource": refund_context_source,
            "chitchat": chitchat,
        },
        "tier": "standard",
        "attemptLimit": 16,
        "toolProfile": tool_profile,
        "sessionPropagationEnabled": session_propagation_enabled,
    }


@pytest.mark.parametrize(
    (
        "refund_context_source",
        "chitchat",
        "tool_profile",
        "session_propagation_enabled",
    ),
    [
        ("current", False, "all", False),
        ("session", False, "all", True),
        ("session", False, "read", False),
        ("session", True, "none", False),
    ],
)
def test_routing_evidence_projects_closed_current_and_session_decisions(
    refund_context_source: str,
    chitchat: bool,
    tool_profile: str,
    session_propagation_enabled: bool,
) -> None:
    projected = object.__new__(MysqlEvaluationEvidenceStore)._project_event(  # noqa: SLF001
        1,
        "ROUTING_DECISION",
        routing_evidence_payload(
            refund_context=True,
            refund_context_source=refund_context_source,
            chitchat=chitchat,
            tool_profile=tool_profile,
            session_propagation_enabled=session_propagation_enabled,
        ),
        datetime(2026, 9, 1, tzinfo=UTC),
    )

    assert projected.outcome == "standard"
    assert projected.attempt_limit == 16
    assert projected.routing is not None
    assert projected.routing.model_dump(by_alias=True) == {
        "refundContext": True,
        "refundContextSource": refund_context_source,
        "chitchat": chitchat,
        "toolProfile": tool_profile,
        "sessionPropagationEnabled": session_propagation_enabled,
    }


def test_legacy_routing_evidence_without_session_flag_remains_readable() -> None:
    payload = routing_evidence_payload(
        refund_context=True,
        refund_context_source="session",
        chitchat=False,
        tool_profile="all",
        session_propagation_enabled=True,
    )
    del payload["sessionPropagationEnabled"]

    projected = object.__new__(MysqlEvaluationEvidenceStore)._project_event(  # noqa: SLF001
        1,
        "ROUTING_DECISION",
        payload,
        datetime(2026, 9, 1, tzinfo=UTC),
    )

    assert projected.outcome == "standard"
    assert projected.attempt_limit == 16
    assert projected.routing is None
    assert "routing" not in projected.model_dump(by_alias=True, exclude_none=True)


@pytest.mark.parametrize(
    "payload",
    [
        routing_evidence_payload(
            refund_context=True,
            refund_context_source="session",
            chitchat=False,
            tool_profile="read",
            session_propagation_enabled="false",
        ),
        routing_evidence_payload(
            refund_context=True,
            refund_context_source="none",
            chitchat=False,
            tool_profile="read",
            session_propagation_enabled=False,
        ),
        routing_evidence_payload(
            refund_context=True,
            refund_context_source="session",
            chitchat=False,
            tool_profile="all",
            session_propagation_enabled=False,
        ),
        {
            **routing_evidence_payload(
                refund_context=True,
                refund_context_source="session",
                chitchat=False,
                tool_profile="read",
                session_propagation_enabled=False,
            ),
            "signals": {
                "refundContext": True,
                "refundContextSource": "session",
                "chitchat": False,
                "unexpected": True,
            },
        },
    ],
)
def test_new_routing_evidence_rejects_non_strict_or_inconsistent_payloads(
    payload: dict[str, Any],
) -> None:
    with pytest.raises(EvaluationEvidenceInvalid):
        object.__new__(MysqlEvaluationEvidenceStore)._project_event(  # noqa: SLF001
            1,
            "ROUTING_DECISION",
            payload,
            datetime(2026, 9, 1, tzinfo=UTC),
        )


def test_context_evidence_rejects_a_non_uuid_turn_reference() -> None:
    payload = historical_context_payload()
    payload["includedTurnIds"] = ["00000000-0000-0000-0000-00000000000g"]

    with pytest.raises(EvaluationEvidenceInvalid):
        object.__new__(MysqlEvaluationEvidenceStore)._project_event(  # noqa: SLF001
            1,
            "CONTEXT_WINDOW",
            payload,
            datetime.now(UTC),
        )


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("candidateTokens", "0"),
        ("includedTokens", False),
        ("loadedTurnCount", "1"),
        ("olderTurnsAvailable", "false"),
    ],
)
def test_context_evidence_rejects_coerced_scalar_types(field: str, value: object) -> None:
    payload = historical_context_payload()
    payload[field] = value

    with pytest.raises(EvaluationEvidenceInvalid):
        object.__new__(MysqlEvaluationEvidenceStore)._project_event(  # noqa: SLF001
            1,
            "CONTEXT_WINDOW",
            payload,
            datetime.now(UTC),
        )


def historical_context_payload() -> dict[str, object]:
    return {
        "policyVersion": "session-context-v1",
        "tokenEstimator": "utf8-bytes-v1",
        "tokenBudget": 6144,
        "tokenWatermark": "low",
        "candidateTokens": 15,
        "includedTokens": 15,
        "loadedTurnCount": 1,
        "includedTurnIds": ["00000000-0000-0000-0000-000000000001"],
        "omittedLoadedTurnCount": 0,
        "olderTurnsAvailable": False,
    }


def test_historical_context_remains_readable_without_a_model_producer() -> None:
    projected = object.__new__(MysqlEvaluationEvidenceStore)._project_event(
        1, "CONTEXT_WINDOW", historical_context_payload(), datetime.now(UTC)
    )
    assert projected.context is not None
    assert projected.context.included_turn_ids == ("00000000-0000-0000-0000-000000000001",)
