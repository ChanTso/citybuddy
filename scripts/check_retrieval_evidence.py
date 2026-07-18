"""Real CB-091 Elasticsearch, reranker, MySQL atomicity, replay, and grant probe."""

from __future__ import annotations

import argparse
import json
import uuid
from collections.abc import Callable
from functools import partial
from typing import Any

import httpx
import pymysql
from citybuddy_agent.agent_control import (
    BoundedAgent,
    LiteLlmClient,
    ModelRouter,
    ProviderCircuits,
    ProviderRoute,
    RuleRouter,
    ToolAdapter,
)
from citybuddy_agent.application import AgentSettings, MysqlSessionStore, OboClient
from citybuddy_agent.conversation import CorrelationConflictError, MysqlConversationStore
from citybuddy_agent.knowledge import ElasticsearchKnowledgeSearch
from citybuddy_agent.retrieval import RetrievalDecision, load_calibration


def connection(
    args: argparse.Namespace,
    user: str,
    password: str,
    *,
    autocommit: bool = True,
) -> pymysql.Connection[pymysql.cursors.Cursor]:
    return pymysql.connect(
        host=args.mysql_host,
        port=args.mysql_port,
        user=user,
        password=password,
        database="cs_db",
        autocommit=autocommit,
    )


def query_one(
    args: argparse.Namespace,
    statement: str,
    values: tuple[object, ...] = (),
) -> tuple[Any, ...]:
    with connection(args, "agent_app", args.agent_password) as database:
        with database.cursor() as cursor:
            cursor.execute(statement, values)
            row = cursor.fetchone()
    if row is None:
        raise AssertionError("Expected one durable retrieval row")
    return row


def expect_denied(operation: Callable[[], object], *codes: int) -> None:
    try:
        operation()
    except pymysql.MySQLError as error:
        if error.args[0] not in codes:
            raise AssertionError(f"Unexpected MySQL denial: {error.args[0]}") from error
    else:
        raise AssertionError("Expected MySQL operation to fail closed")


def make_agent(
    settings: AgentSettings,
    sessions: MysqlSessionStore,
    *,
    model_url: str,
    attempt_limit: int,
) -> BoundedAgent:
    circuits = ProviderCircuits(
        minimum_requests=2,
        open_seconds=0.2,
        half_open_probes=1,
    )
    model = LiteLlmClient(model_url, circuits)
    return BoundedAgent(
        RuleRouter(),
        ModelRouter(
            (
                ProviderRoute("support-standard-primary", "primary"),
                ProviderRoute("support-standard-fallback", "fallback"),
            ),
            attempt_limit,
            ProviderRoute("support-reranker-standard", "reranker"),
        ),
        model,
        ToolAdapter(
            "http://commerce-must-not-be-used",
            OboClient(settings, sessions),
            ElasticsearchKnowledgeSearch(settings.elasticsearch_url),
            model,
            load_calibration(),
        ),
    )


def execute(
    *,
    store: MysqlConversationStore,
    agent: BoundedAgent,
    session_id: str,
    message: str,
    correlation_key: str,
) -> tuple[RetrievalDecision, str, str]:
    start = store.begin_turn(
        session_id=session_id,
        subject="cb091-user",
        sandbox_id=None,
        correlation_key=correlation_key,
        message=message,
    )
    if start.replay is not None:
        raise AssertionError("Fresh retrieval fixture unexpectedly replayed")
    result = agent.run(
        message=message,
        direct_token="direct-token-must-not-be-forwarded",
        subject="cb091-user",
        session_id=session_id,
        trace_id=start.trace_id,
        turn_id=start.turn_id,
    )
    if result.retrieval_decision is None:
        raise AssertionError("Retrieval turn omitted its decision")
    completed = store.complete_turn(
        start=start,
        response_text=result.response_text,
        outcome=result.outcome,
        events=result.events,
        retrieval_decision=result.retrieval_decision,
    )
    if completed.outcome != result.outcome:
        raise AssertionError("Terminal outcome diverged during atomic commit")
    return result.retrieval_decision, start.trace_id, start.turn_id


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mysql-host", required=True)
    parser.add_argument("--mysql-port", required=True, type=int)
    parser.add_argument("--agent-password", required=True)
    parser.add_argument("--mysql-root-password", required=True)
    parser.add_argument("--auth-password", required=True)
    parser.add_argument("--commerce-password", required=True)
    parser.add_argument("--elasticsearch-url", required=True)
    parser.add_argument("--model-url", required=True)
    args = parser.parse_args()

    settings = AgentSettings(
        identity_enabled=True,
        mysql_host=args.mysql_host,
        mysql_port=args.mysql_port,
        mysql_password=args.agent_password,
        elasticsearch_url=args.elasticsearch_url,
        model_proxy_url=args.model_url,
        commerce_tools_url="http://commerce-must-not-be-used",
        attempt_budget=12,
    )
    sessions = MysqlSessionStore(settings)
    store = MysqlConversationStore(settings)
    session_id = sessions.create("cb091-user")
    agent = make_agent(settings, sessions, model_url=args.model_url, attempt_limit=12)

    decisions: dict[str, RetrievalDecision] = {}
    traces: dict[str, str] = {}
    turns: dict[str, str] = {}
    expected = {
        "retrieval-sufficient refund policy": ("SUFFICIENT", "sufficient", "completed"),
        "retrieval-insufficient refund policy": (
            "INSUFFICIENT",
            "below_threshold",
            "retrieval_denied",
        ),
        "retrieval-ambiguous refund policy": (
            "INSUFFICIENT",
            "ambiguous_margin",
            "retrieval_denied",
        ),
        "retrieval-malformed refund policy": (
            "INSUFFICIENT",
            "reranker_denied",
            "retrieval_denied",
        ),
        "retrieval-transient refund policy": ("SUFFICIENT", "sufficient", "completed"),
        "retrieval-timeout refund policy": (
            "INSUFFICIENT",
            "reranker_denied",
            "retrieval_denied",
        ),
    }
    for sequence, (message, expectation) in enumerate(expected.items(), start=1):
        decision, trace_id, turn_id = execute(
            store=store,
            agent=agent,
            session_id=session_id,
            message=message,
            correlation_key=f"cb091-{sequence}",
        )
        durable = query_one(
            args,
            "SELECT sufficiency_outcome, reason_code, index_version, calibration_version, "
            "candidate_count, evidence_count FROM retrieval_decision WHERE trace_id = %s",
            (trace_id,),
        )
        turn = query_one(args, "SELECT outcome FROM support_turn WHERE turn_id = %s", (turn_id,))
        actual = (decision.outcome, decision.reason, turn[0])
        if actual != expectation:
            raise AssertionError(
                f"Deterministic retrieval outcome diverged for {message}: "
                f"expected {expectation}, got {actual}"
            )
        if durable[:4] != (
            decision.outcome,
            decision.reason,
            "knowledge_docs_v1",
            "cb091-calibration-v1",
        ):
            raise AssertionError("Durable retrieval binding diverged")
        if durable[4:] != (decision.candidate_count, len(decision.evidence)):
            raise AssertionError("Durable bounded counts diverged")
        decisions[message] = decision
        traces[message] = trace_id
        turns[message] = turn_id

    sufficient_message = "retrieval-sufficient refund policy"
    sufficient = decisions[sufficient_message]
    if len(sufficient.evidence) < 2:
        raise AssertionError("Real hybrid retrieval did not provide bounded rerank evidence")
    stored_sources = query_one(
        args,
        "SELECT GROUP_CONCAT(CONCAT(evidence.source_id, ':', evidence.source_version, ':', "
        "evidence.chunk_id) ORDER BY evidence.evidence_rank SEPARATOR ',') "
        "FROM retrieval_decision decision JOIN retrieval_evidence evidence "
        "ON evidence.decision_id = decision.decision_id WHERE decision.trace_id = %s",
        (traces[sufficient_message],),
    )[0]
    expected_sources = ",".join(
        f"{item.source_id}:{item.source_version}:{item.chunk_id}" for item in sufficient.evidence
    )
    if stored_sources != expected_sources:
        raise AssertionError("Stored evidence did not match the exact real-index selection")

    counts_before = httpx.get(f"{args.model_url}/fixture/counts", timeout=2).json()
    replay = store.begin_turn(
        session_id=session_id,
        subject="cb091-user",
        sandbox_id=None,
        correlation_key="cb091-1",
        message=sufficient_message,
    )
    counts_after = httpx.get(f"{args.model_url}/fixture/counts", timeout=2).json()
    if replay.replay is None or counts_after != counts_before:
        raise AssertionError("Same-intent replay reran Elasticsearch or the reranker")
    if replay.replay.retrieval_evidence != sufficient.evidence:
        raise AssertionError("Replay did not return the stored sufficient evidence")
    try:
        store.begin_turn(
            session_id=session_id,
            subject="cb091-user",
            sandbox_id=None,
            correlation_key="cb091-1",
            message="conflicting request",
        )
    except CorrelationConflictError:
        pass
    else:
        raise AssertionError("Conflicting replay did not fail closed")

    # Admit the initial model request, alias and mapping checks, and all four
    # original/rewrite recall legs; exhaust exactly at the reranker boundary.
    budget_agent = make_agent(settings, sessions, model_url=args.model_url, attempt_limit=7)
    budget_decision, budget_trace, _ = execute(
        store=store,
        agent=budget_agent,
        session_id=session_id,
        message="retrieval-sufficient budget-bound refund policy",
        correlation_key="cb091-budget",
    )
    budget_reason = query_one(
        args,
        "SELECT reason_code FROM retrieval_decision WHERE trace_id = %s",
        (budget_trace,),
    )[0]
    if budget_decision.reason != "reranker_denied" or budget_reason != "reranker_denied":
        raise AssertionError("Shared attempt-budget exhaustion did not fail closed")

    event_payloads = query_one(
        args,
        "SELECT GROUP_CONCAT(CAST(payload_json AS CHAR) SEPARATOR '') "
        "FROM support_event WHERE trace_id = %s",
        (traces[sufficient_message],),
    )[0]
    for forbidden in (
        sufficient_message,
        "direct-token-must-not-be-forwarded",
        sufficient.evidence[0].excerpt,
    ):
        if forbidden in event_payloads:
            raise AssertionError("Private or public evidence text leaked into event logs")

    decision_id = query_one(
        args,
        "SELECT decision_id FROM retrieval_decision WHERE trace_id = %s",
        (traces[sufficient_message],),
    )[0]

    def duplicate_rank() -> None:
        with connection(args, "agent_app", args.agent_password) as database:
            with database.cursor() as cursor:
                item = sufficient.evidence[0]
                cursor.execute(
                    "INSERT INTO retrieval_evidence "
                    "(evidence_id, decision_id, evidence_rank, source_id, chunk_id, "
                    "source_version, doc_type, title, excerpt, rerank_score) "
                    "VALUES (%s, %s, 1, %s, %s, %s, %s, %s, %s, %s)",
                    (
                        str(uuid.uuid4()),
                        decision_id,
                        "conflict-source",
                        item.chunk_id,
                        item.source_version,
                        item.doc_type,
                        item.title,
                        item.excerpt,
                        item.score,
                    ),
                )

    expect_denied(duplicate_rank, 1062)

    def forbidden_agent_write(statement: str) -> None:
        with connection(args, "agent_app", args.agent_password) as database:
            with database.cursor() as cursor:
                cursor.execute(statement)

    expect_denied(
        lambda: forbidden_agent_write(
            f"UPDATE retrieval_decision SET reason_code = 'reranker_denied' "
            f"WHERE decision_id = '{decision_id}'"
        ),
        1142,
    )
    expect_denied(
        lambda: forbidden_agent_write(
            f"DELETE FROM retrieval_evidence WHERE decision_id = '{decision_id}'"
        ),
        1142,
    )
    expect_denied(lambda: forbidden_agent_write("CREATE TABLE cb091_forbidden (id INT)"), 1142)

    for runtime, password in (
        ("auth_app", args.auth_password),
        ("commerce_app", args.commerce_password),
    ):
        expect_denied(
            partial(query_forbidden_runtime, args, runtime, password),
            1044,
            1142,
        )

    fault_message = "retrieval-sufficient controlled-failure refund policy"
    fault_start = store.begin_turn(
        session_id=session_id,
        subject="cb091-user",
        sandbox_id=None,
        correlation_key="cb091-fault",
        message=fault_message,
    )
    # Isolate the atomic-write fault from the intentionally opened circuit in
    # the earlier timeout scenario; this fixture must reach evidence row two.
    fault_agent = make_agent(settings, sessions, model_url=args.model_url, attempt_limit=12)
    fault_result = fault_agent.run(
        message=fault_message,
        direct_token="direct-token-must-not-be-forwarded",
        subject="cb091-user",
        session_id=session_id,
        trace_id=fault_start.trace_id,
        turn_id=fault_start.turn_id,
    )
    if fault_result.retrieval_decision is None or len(fault_result.retrieval_decision.evidence) < 2:
        raise AssertionError("Controlled rollback fixture lacks two evidence rows")
    trigger_sql = (
        "CREATE TRIGGER cb091_fail_second_evidence BEFORE INSERT ON retrieval_evidence "
        "FOR EACH ROW BEGIN IF NEW.evidence_rank = 2 THEN SIGNAL SQLSTATE '45000' "
        "SET MESSAGE_TEXT = 'cb091 controlled rollback'; END IF; END"
    )
    with connection(args, "root", args.mysql_root_password) as migration_database:
        with migration_database.cursor() as cursor:
            cursor.execute("DROP TRIGGER IF EXISTS cb091_fail_second_evidence")
            cursor.execute(trigger_sql)
    try:
        store.complete_turn(
            start=fault_start,
            response_text=fault_result.response_text,
            outcome=fault_result.outcome,
            events=fault_result.events,
            retrieval_decision=fault_result.retrieval_decision,
        )
    except pymysql.MySQLError as error:
        if error.args[0] != 1644:
            raise
    else:
        raise AssertionError("Controlled second-evidence failure unexpectedly committed")
    finally:
        with connection(args, "root", args.mysql_root_password) as migration_database:
            with migration_database.cursor() as cursor:
                cursor.execute("DROP TRIGGER cb091_fail_second_evidence")
    decision_count = query_one(
        args,
        "SELECT COUNT(*) FROM retrieval_decision WHERE trace_id = %s",
        (fault_start.trace_id,),
    )[0]
    event_count = query_one(
        args,
        "SELECT COUNT(*) FROM support_event WHERE trace_id = %s",
        (fault_start.trace_id,),
    )[0]
    state = query_one(
        args,
        "SELECT state FROM support_turn WHERE turn_id = %s",
        (fault_start.turn_id,),
    )[0]
    if (decision_count, event_count, state) != (0, 1, "PROCESSING"):
        raise AssertionError("Atomic rollback left partial evidence or terminal events")
    store.fail_turn(start=fault_start, failure_code="agent_execution_failed")

    association_start = store.begin_turn(
        session_id=session_id,
        subject="cb091-user",
        sandbox_id=None,
        correlation_key="cb091-association",
        message="association rejection fixture",
    )

    def mismatched_association() -> None:
        with connection(args, "agent_app", args.agent_password) as database:
            with database.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO retrieval_decision "
                    "(decision_id, turn_id, trace_id, session_id, user_subject, index_version, "
                    "calibration_version, sufficiency_outcome, reason_code, candidate_count, "
                    "evidence_count) VALUES (%s, %s, %s, %s, %s, "
                    "'knowledge_docs_v1', 'cb091-calibration-v1', 'INSUFFICIENT', "
                    "'empty_candidates', 0, 0)",
                    (
                        str(uuid.uuid4()),
                        association_start.turn_id,
                        association_start.trace_id,
                        "wrong-session",
                        "wrong-owner",
                    ),
                )

    expect_denied(mismatched_association, 1452)
    store.fail_turn(start=association_start, failure_code="association_rejected")

    print(
        json.dumps(
            {
                "atomicRollback": "passed",
                "calibrationVersion": "cb091-calibration-v1",
                "indexVersion": "knowledge_docs_v1",
                "outcomes": len(expected) + 1,
                "replayWithoutExecution": True,
                "runtimeIsolation": "passed",
                "storedEvidenceCount": len(sufficient.evidence),
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


def query_forbidden_runtime(args: argparse.Namespace, runtime: str, password: str) -> None:
    with connection(args, runtime, password) as database:
        with database.cursor() as cursor:
            cursor.execute("SELECT * FROM retrieval_decision LIMIT 1")


if __name__ == "__main__":
    main()
