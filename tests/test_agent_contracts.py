import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]


def contract() -> dict[str, Any]:
    payload = json.loads((ROOT / "agent-service/openapi.json").read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("Agent OpenAPI contract must be an object")
    return payload


def test_chat_contract_fixes_identity_correlation_and_bounded_body() -> None:
    operation = contract()["paths"]["/api/chat"]["post"]

    assert operation["security"] == [{"directUserBearer": []}]
    headers = {item["name"]: item for item in operation["parameters"]}
    assert set(headers) == {"X-Session-Id", "Idempotency-Key"}
    assert all(item["in"] == "header" and item["required"] for item in headers.values())
    request = operation["requestBody"]["content"]["application/json"]["schema"]
    assert request["additionalProperties"] is False
    assert request["required"] == ["message"]
    assert request["properties"]["message"] == {
        "type": "string",
        "minLength": 1,
        "maxLength": 4000,
    }


def test_chat_response_is_allowlisted_and_server_ids_are_read_only() -> None:
    operation = contract()["paths"]["/api/chat"]["post"]
    response = operation["responses"]["200"]["content"]["application/json"]["schema"]

    assert response["additionalProperties"] is False
    assert set(response["properties"]) == {
        "conversationId",
        "traceId",
        "turnId",
        "reply",
        "outcome",
        "citations",
        "actionReceipt",
    }
    for name in ("conversationId", "traceId", "turnId"):
        assert response["properties"][name]["readOnly"] is True
    assert response["properties"]["outcome"]["enum"] == [
        "completed",
        "budget_exhausted",
        "provider_denied",
        "retrieval_denied",
        "action_pending",
        "action_declined",
        "action_expired",
        "action_clarification",
        "action_completed",
    ]
    citation = contract()["components"]["schemas"]["RetrievalCitation"]
    assert citation["additionalProperties"] is False
    assert set(citation["properties"]) == {
        "sourceId",
        "chunkId",
        "sourceVersion",
        "docType",
        "title",
    }
    assert response["properties"]["citations"]["maxItems"] == 3
    assert set(operation["responses"]) == {
        "200",
        "401",
        "403",
        "409",
        "422",
        "502",
        "503",
    }


def test_stream_contract_fixes_headers_event_names_and_allowlisted_payloads() -> None:
    payload = contract()
    operation = payload["paths"]["/api/chat/stream"]["post"]

    assert operation["security"] == [{"directUserBearer": []}]
    assert {item["name"] for item in operation["parameters"]} == {
        "X-Session-Id",
        "Idempotency-Key",
    }
    request = operation["requestBody"]["content"]["application/json"]["schema"]
    assert request["additionalProperties"] is False
    assert request["required"] == ["message"]
    stream = operation["responses"]["200"]["content"]["text/event-stream"]
    assert set(stream["x-sse-events"]) == {"token", "action_receipt", "done", "error"}
    expected_fields = {
        "SseTokenData": {"sequence", "text"},
        "SseActionReceiptData": {"sequence", "receiptId", "status"},
        "SseDoneData": {"sequence", "conversationId", "traceId", "turnId", "outcome"},
        "SseErrorData": {"sequence", "code"},
    }
    for schema_name, fields in expected_fields.items():
        schema = payload["components"]["schemas"][schema_name]
        assert schema["additionalProperties"] is False
        assert set(schema["properties"]) == fields
        assert set(schema["required"]) == fields
    assert (
        "non-authoritative explanation"
        in payload["components"]["schemas"]["SseTokenData"]["description"]
    )
    assert (
        "only public action-status carrier"
        in payload["components"]["schemas"]["SseActionReceiptData"]["description"]
    )
    assert payload["components"]["schemas"]["SseDoneData"]["properties"]["outcome"]["enum"] == [
        "completed",
        "action_pending",
        "action_declined",
        "action_expired",
        "action_clarification",
        "action_completed",
    ]

    source = (ROOT / "agent-service/src/citybuddy_agent/sse.py").read_text(encoding="utf-8")
    assert "MAX_PUBLIC_EVENTS" in source
    assert "is_disconnected" in source
    assert "create_task" not in source
    assert "Thread" not in source


def test_feedback_contract_has_no_body_owner_and_returns_server_identity() -> None:
    payload = contract()
    operation = payload["paths"]["/api/feedback"]["post"]
    request = payload["components"]["schemas"]["FeedbackRequest"]
    response = payload["components"]["schemas"]["FeedbackResponse"]

    assert operation["security"] == [{"directUserBearer": []}]
    assert {item["name"] for item in operation["parameters"]} == {
        "X-Session-Id",
        "Idempotency-Key",
    }
    assert request["additionalProperties"] is False
    assert set(request["properties"]) == {"traceId", "rating", "comment"}
    assert "userSubject" not in request["properties"]
    assert "sessionId" not in request["properties"]
    assert response["additionalProperties"] is False
    assert set(response["properties"]) == {"feedbackId", "traceId", "rating"}
    assert response["properties"]["feedbackId"]["readOnly"] is True


def test_support_truth_schema_and_runtime_grants_fix_order_and_append_only_evidence() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/agent/V003__support_conversation_lifecycle.sql"
    ).read_text(encoding="utf-8")
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")

    assert "UNIQUE KEY uq_support_conversation_session (session_id)" in migration
    assert "UNIQUE KEY uq_support_turn_correlation (session_id, correlation_key)" in migration
    assert "UNIQUE KEY uq_support_turn_position (conversation_id, turn_sequence)" in migration
    assert "UNIQUE KEY uq_support_event_trace_sequence (trace_id, sequence)" in migration
    assert "UNIQUE KEY uq_support_event_turn_sequence (turn_id, sequence)" in migration
    assert "FOREIGN KEY (turn_id, trace_id, session_id, user_subject)" in migration
    assert "(sequence = 1 AND event_type = 'USER_INPUT')" in migration
    assert (
        "GRANT SELECT, INSERT, UPDATE ON cs_db.support_conversation TO 'agent_app'@'%';" in grants
    )
    assert "GRANT SELECT, INSERT, UPDATE ON cs_db.support_turn TO 'agent_app'@'%';" in grants
    assert "GRANT SELECT, INSERT ON cs_db.support_event TO 'agent_app'@'%';" in grants
    assert "UPDATE ON cs_db.support_event" not in grants
    assert "DELETE ON cs_db.support_event" not in grants


def test_bounded_agent_migration_preserves_terminal_and_append_only_truth() -> None:
    migration = (ROOT / "infra/mysql/migrations/agent/V004__bounded_agent_evidence.sql").read_text(
        encoding="utf-8"
    )

    for event_type in (
        "ROUTING_DECISION",
        "BUDGET_CHARGED",
        "CIRCUIT_OUTCOME",
        "MODEL_OUTCOME",
        "TOOL_LIFECYCLE",
        "TOOL_DENIED",
        "AGENT_OUTCOME",
        "TURN_FAILED",
    ):
        assert f"'{event_type}'" in migration
    assert "sequence > 0" in migration
    assert "(sequence = 1 AND event_type = 'USER_INPUT')" in migration
    assert "(sequence > 1 AND event_type <> 'USER_INPUT')" in migration
    assert "outcome IN ('completed', 'budget_exhausted', 'provider_denied')" in migration
    assert "ADD COLUMN processing_deadline_at TIMESTAMP(6) NULL AFTER state" in migration
    assert "state = 'PROCESSING'" in migration
    assert "processing_deadline_at IS NOT NULL" in migration
    assert "processing_deadline_at IS NULL" in migration


def test_feedback_schema_and_grants_are_owner_bound_and_append_only() -> None:
    migration = (ROOT / "infra/mysql/migrations/agent/V005__support_feedback.sql").read_text(
        encoding="utf-8"
    )
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")

    assert "UNIQUE KEY uq_support_feedback_intent (session_id, idempotency_key)" in migration
    assert (
        "UNIQUE KEY uq_support_turn_feedback_binding (trace_id, session_id, user_subject)"
        in migration
    )
    assert "FOREIGN KEY (trace_id, session_id, user_subject)" in migration
    assert "REFERENCES support_turn (trace_id, session_id, user_subject)" in migration
    assert "rating IN ('POSITIVE', 'NEGATIVE')" in migration
    assert "GRANT SELECT, INSERT ON cs_db.support_feedback TO 'agent_app'@'%';" in grants
    assert "UPDATE ON cs_db.support_feedback" not in grants
    assert "DELETE ON cs_db.support_feedback" not in grants

    source = (ROOT / "agent-service/src/citybuddy_agent/feedback.py").read_text(encoding="utf-8")
    assert "FROM support_conversation " in source
    assert '"WHERE session_id = %s FOR UPDATE"' in source
    assert '"SELECT user_subject, sandbox_id FROM support_session "' in source
    assert (
        'support_session "\n                        "WHERE session_id = %s FOR UPDATE' not in source
    )
    assert '"AND idempotency_key = %s FOR UPDATE"' not in source


def test_retrieval_evidence_schema_is_turn_bound_atomic_and_append_only() -> None:
    migration = (ROOT / "infra/mysql/migrations/agent/V006__retrieval_evidence.sql").read_text(
        encoding="utf-8"
    )
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")
    source = (ROOT / "agent-service/src/citybuddy_agent/conversation.py").read_text(
        encoding="utf-8"
    )

    assert "UNIQUE KEY uq_retrieval_decision_turn (turn_id)" in migration
    assert "FOREIGN KEY (turn_id, trace_id, session_id, user_subject)" in migration
    assert "UNIQUE KEY uq_retrieval_evidence_rank (decision_id, evidence_rank)" in migration
    assert "sufficiency_outcome IN ('SUFFICIENT', 'INSUFFICIENT')" in migration
    assert "'retrieval_denied'" in migration
    assert "'RETRIEVAL_DECISION'" in migration
    for table in ("retrieval_decision", "retrieval_evidence"):
        assert f"GRANT SELECT, INSERT ON cs_db.{table} TO 'agent_app'@'%';" in grants
        assert f"UPDATE ON cs_db.{table}" not in grants
        assert f"DELETE ON cs_db.{table}" not in grants
    assert (
        source.index("for event in events:")
        < source.index("self._insert_retrieval_decision(")
        < source.index("UPDATE support_turn SET state = 'COMPLETED'")
    )


def test_action_projection_schema_is_single_pending_atomic_and_least_privileged() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/agent/V007__action_receipt_projection.sql"
    ).read_text(encoding="utf-8")
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")
    source = (ROOT / "agent-service/src/citybuddy_agent/conversation.py").read_text(
        encoding="utf-8"
    )
    mysql_source = source[source.index("class MysqlConversationStore:") :]

    assert "GENERATED ALWAYS AS" in migration
    assert "CASE WHEN state IN ('PENDING', 'CONFIRMING') THEN session_id ELSE NULL END" in migration
    assert "UNIQUE KEY uq_pending_action_reference_active_session" in migration
    assert "UNIQUE KEY uq_action_receipt_projection_pending" in migration
    assert "UNIQUE KEY uq_action_receipt_projection_confirmation_turn" in migration
    assert "published_event_sequence INT UNSIGNED NOT NULL" in migration
    assert (
        "GRANT SELECT, INSERT, UPDATE (state, confirmation_turn_id, "
        "confirmation_trace_id, resolved_at) "
        "ON cs_db.pending_action_reference TO 'agent_app'@'%';" in grants
    )
    assert "GRANT SELECT, INSERT ON cs_db.action_receipt_projection TO 'agent_app'@'%';" in grants
    assert "UPDATE ON cs_db.action_receipt_projection" not in grants
    assert "DELETE ON cs_db.action_receipt_projection" not in grants
    assert "GRANT SELECT, INSERT ON cs_db.support_event TO 'agent_app'@'%';" in grants
    assert "UPDATE ON cs_db.support_event" not in grants
    assert "DELETE ON cs_db.support_event" not in grants
    assert (
        "\"WHERE turn_id = %s AND event_type = 'ACTION_PREPARED' \"\n"
        '            "LIMIT 2 FOR SHARE"'
    ) in source
    preparation_lock = mysql_source[
        mysql_source.index("    def _lock_pending_preparation_anchor(") : mysql_source.index(
            "    def _lock_matching_pending(",
            mysql_source.index("    def _lock_pending_preparation_anchor("),
        )
    ]
    assert "WHERE turn_id = %s AND event_type = 'ACTION_PREPARED'" in preparation_lock
    assert "LIMIT 2 FOR SHARE" in preparation_lock
    assert "pending.source_turn_id" in preparation_lock
    assert "FOR SHARE" in preparation_lock
    assert "FOR UPDATE" not in preparation_lock
    confirmation_claim = mysql_source[
        mysql_source.index("    def begin_or_resume_confirmation_turn(") : mysql_source.index(
            "    def replay_turn(", mysql_source.index("    def begin_or_resume_confirmation_turn(")
        )
    ]
    assert confirmation_claim.index(
        "FROM pending_action_reference WHERE pending_action_id = %s FOR UPDATE"
    ) < confirmation_claim.index("self._lock_pending_preparation_anchor(")
    for method_name, next_method, mutable_turn_lock in (
        ("complete_action_decline", "complete_action_expired", "_lock_executable_turn("),
        ("complete_action_expired", "complete_action_receipt", "_lock_executable_turn("),
        ("complete_action_receipt", "fail_turn", "FROM support_turn WHERE turn_id = %s FOR UPDATE"),
    ):
        method = mysql_source[
            mysql_source.index(f"    def {method_name}(") : mysql_source.index(
                f"    def {next_method}(",
                mysql_source.index(f"    def {method_name}("),
            )
        ]
        assert method.index(mutable_turn_lock) < method.index("_lock_matching_pending(")
    receipt_method_start = source.rindex("    def complete_action_receipt(")
    receipt_method_end = source.index("    def fail_turn(", receipt_method_start)
    receipt_method = source[receipt_method_start:receipt_method_end]
    assert (
        receipt_method.index("INSERT INTO action_receipt_projection")
        < receipt_method.index('event=AgentEvent(\n                            "ACTION_RECEIPT"')
        < receipt_method.index("self._finish_turn(")
    )


def test_commerce_tool_contract_is_exact_obo_and_bounded_view() -> None:
    commerce = json.loads(
        (ROOT / "commerce-service/src/main/resources/openapi.json").read_text(encoding="utf-8")
    )
    operation = commerce["paths"]["/internal/tools/catalog.product.get"]["post"]
    request = commerce["components"]["schemas"]["CatalogProductToolInput"]
    response = commerce["components"]["schemas"]["CatalogProductToolOutput"]

    assert operation["security"] == [{"agentOboBearer": []}]
    assert request["additionalProperties"] is False
    assert request["required"] == ["productId"]
    assert set(response["properties"]) == {
        "productId",
        "name",
        "priceMinor",
        "currency",
        "available",
        "publicationVersion",
    }
    assert "description" not in response["properties"]
    assert "stockQuantity" not in response["properties"]


def test_application_uses_role_aliases_without_concrete_provider_models() -> None:
    sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ROOT / "agent-service/src/citybuddy_agent").glob("*.py")
    )

    assert "support-standard-primary" in sources
    assert "support-standard-fallback" in sources
    assert "gpt-" not in sources.casefold()
    assert "claude-" not in sources.casefold()
    assert "gemini-" not in sources.casefold()
