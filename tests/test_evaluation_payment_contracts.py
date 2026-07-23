import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_mock_payment_callback_contract_is_bounded_signed_and_sandbox_exact() -> None:
    contract = json.loads(
        (ROOT / "commerce-service/src/main/resources/openapi.json").read_text(encoding="utf-8")
    )
    paths = contract["paths"]
    start = paths["/api/orders/{orderId}/mock-payment"]["post"]
    callback = paths["/internal/mock-payments/callback"]["post"]
    schemas = contract["components"]["schemas"]

    assert start["security"] == [{"directUserBearer": []}]
    assert callback["security"] == [{"mockPaymentKeyId": [], "mockPaymentSignature": []}]
    assert {parameter["name"] for parameter in callback["parameters"]} == {
        "X-Mock-Payment-Timestamp",
        "Idempotency-Key",
    }
    request = schemas["MockPaymentCallbackRequest"]
    assert request["additionalProperties"] is False
    assert request["dependentRequired"] == {
        "sandboxId": ["supportSessionId", "traceId", "operationId"],
        "supportSessionId": ["sandboxId", "traceId", "operationId"],
        "traceId": ["sandboxId", "supportSessionId", "operationId"],
        "operationId": ["sandboxId", "supportSessionId", "traceId"],
    }
    assert request["properties"]["operationId"]["pattern"] == "^[0-9a-f]{64}$"
    assert schemas["MockPaymentRequest"]["additionalProperties"] is False
    assert "userSubject" not in schemas["MockPaymentRequest"]["properties"]
    serialized = json.dumps(
        {
            "request": request,
            "startResult": schemas["MockPaymentResult"],
            "callbackResult": schemas["MockPaymentCallbackResult"],
            "error": schemas["PaymentError"],
        }
    )
    for forbidden in ("secret", "signature", "credential", "accessToken", "password", "SQL"):
        assert forbidden.lower() not in serialized.lower()


def test_payment_schema_and_code_keep_production_and_evaluation_truth_separate() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/commerce/V012__evaluation_mock_payment_callback.sql"
    ).read_text(encoding="utf-8")
    service = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment/MockPaymentService.java"
    ).read_text(encoding="utf-8")
    authenticator = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
        / "MockPaymentCallbackAuthenticator.java"
    ).read_text(encoding="utf-8")
    repository = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment/MockPaymentRepository.java"
    ).read_text(encoding="utf-8")
    committed_faces = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
        / "EvaluationPaymentCommittedFaces.java"
    ).read_text(encoding="utf-8")
    committed_resolver = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
        / "CommittedPaymentTruthResolver.java"
    ).read_text(encoding="utf-8")
    evaluation_view = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationViewRepository.java"
    ).read_text(encoding="utf-8")

    assert "chk_standard_order_eval_binding" in migration
    assert "chk_mock_payment_callback_eval_context" in migration
    assert "PAYMENT_CALLBACK" in migration
    callback_once = service[
        service.index("private MockPaymentCallbackResult callbackOnce") : service.index(
            "private MockPaymentCallbackResult resolveCommittedStandardCallback"
        )
    ]
    assert callback_once.index("findEvaluationAttemptByCorrelationForUpdate") < callback_once.index(
        "resolveCommittedEvaluationCallback"
    )
    assert callback_once.index("resolveCommittedEvaluationCallback") < callback_once.index(
        "fenceSandbox(request.sandboxId());"
    )
    committed_replay = service[
        service.index(
            "private MockPaymentCallbackResult resolveCommittedEvaluationCallback"
        ) : service.index("private MockPaymentRepository.AttemptRecord requireSucceededTruth")
    ]
    assert "truth.resolveReplayLocked(attempt, idempotencyKey, request)" in committed_replay
    assert (
        "requireSingleEqual(\n        callbacks, canonical.callback(), "
        '"Callback replay key closure is inconsistent")' in committed_resolver
    )
    for forbidden_private_face in (
        "findCallbackByCorrelation",
        "findCallbackByAttempt",
        "findEvaluationOrderForUpdate",
        "evaluationPaymentMovementFaceCardinality",
        "evaluationPaymentAuditFaceCardinality",
    ):
        assert forbidden_private_face not in committed_replay
    for shared_replay_enumerator in (
        "enumerateAttemptReplayClosure",
        "enumerateOrderClosure",
        "enumerateCallbackReplayClosure",
        "enumerateLedgerReplayClosure",
        "enumerateAuditReplayClosure",
    ):
        assert shared_replay_enumerator in committed_resolver
    assert "peer.sequence_id < audit.sequence_id" in repository
    assert "peer.sequence_id > audit.sequence_id" in repository
    assert "Committed payment truth is inconsistent" in service
    assert "monotonicEvaluationAuditCreatedAt" in service
    assert service.index("fenceSandbox(request.sandboxId());") < service.index(
        "monotonicEvaluationAuditCreatedAt"
    )
    for field in ("sandboxId()", "supportSessionId()", "traceId()", "operationId()"):
        assert field in authenticator
    assert "insertPaymentAuditReference" in repository
    assert "sandbox_id <=> ?" in repository
    assert "findEvaluationOrderForUpdate" in repository
    assert "EvaluationPaymentCommittedFaces.standardOrderByIdSql" in repository
    assert "EvaluationPaymentCommittedFaces.seckillOrderByIdSql" in repository
    assert "filter(row -> sandboxId.equals(row.sandboxId()))" in repository
    assert "findEvaluationAttemptByCorrelationForUpdate" in repository
    assert "+ attemptTable()" in repository
    assert "WHERE callback_correlation_id = ? FOR UPDATE" in repository
    assert "filter(attempt -> sandboxId.equals(attempt.sandboxId()))" in repository
    audit_cardinality = repository[
        repository.index("public int evaluationPaymentAuditFaceCardinality") : repository.index(
            "private Optional<AttemptRecord> queryAttempt"
        )
    ]
    assert "WHERE entity_id = ?" in audit_cardinality
    assert "OR (sandbox_id = ?" in audit_cardinality
    assert "support_session_id = ? " in audit_cardinality
    assert "AND trace_id = ? AND operation_id = ?" in audit_cardinality
    assert "support_session_id = ? OR" not in audit_cardinality
    assert "l.product_id = o.product_id" in evaluation_view

    for face in ("CALLBACK", "ATTEMPT", "ORDER", "LEDGER", "AUDIT"):
        assert f"public static final FaceDefinition {face}" in committed_faces
    assert 'table(\n              "standard_order"' in committed_faces
    assert 'table(\n              "seckill_order"' in committed_faces
    assert "orderFaceUnionSql()" in committed_faces
    assert "standardOrderByIdSql(String lockClause)" in committed_faces
    assert "seckillOrderByIdSql(String lockClause)" in committed_faces
    assert "EvaluationPaymentCommittedFaces.orderFaceUnionSql()" in evaluation_view
    assert "EvaluationPaymentCommittedFaces.evaluationOrderKeysBySandboxSql()" in evaluation_view
    for attempt_projection in (
        "a.intent_hash AS attempt_intent_hash",
        "a.refunded_amount_minor AS attempt_refunded_amount_minor",
        "a.succeeded_at AS attempt_succeeded_at",
    ):
        assert attempt_projection in evaluation_view
    assert "paymentTruth.resolveSnapshot(attempt)" in evaluation_view
    for exact_attempt_assertion in (
        "attempt.succeededAt().equals(callback.createdAt())",
        "callback.intentHash().equals(callbackIntentHash(attempt, callback))",
    ):
        assert exact_attempt_assertion in committed_resolver
    assert ".intentHash()" in committed_resolver
    assert "attempt.refundedAmountMinor() != 0" in evaluation_view
    assert "EvaluationPaymentCommittedFaces.attemptIntentHash" in service
    assert 'sandboxId == null ? "" : sandboxId' in committed_faces
    for residual_column in (
        "request_idempotency_key",
        "evaluation_owner_handle",
        "movement_id",
    ):
        assert f'"{residual_column}",' in committed_faces
    assert "residualColumnDispositions" in committed_faces
    assert "participatingColumns()" in committed_faces
    callback_order_closure = repository[
        repository.index("private Optional<OrderTruth> findOrder") : repository.index(
            "public Optional<AttemptRecord> findAttemptByRequestForUpdate"
        )
    ]
    view_order_closure = (
        evaluation_view[
            evaluation_view.index("private static String paymentViewSql") : evaluation_view.index(
                "public List<AuditReference> audit"
            )
        ]
        + evaluation_view[
            evaluation_view.index(
                "private List<PaidOrderTruth> paidOrderTruths"
            ) : evaluation_view.index("private List<PaymentLedgerTruth> paymentLedgerTruths")
        ]
    )
    assert "paymentFaceCardinalitiesConsistent" not in evaluation_view
    for closure in (callback_order_closure, view_order_closure):
        assert "FROM standard_order" not in closure
        assert "FROM seckill_order" not in closure

    integration = (ROOT / "scripts/test_evaluation_sandbox_integration.sh").read_text(
        encoding="utf-8"
    )
    for independent_fault in (
        "audit-sequence",
        "audit-anchor",
        "callback-created-at",
        "attempt-intent",
        "attempt-refunded-amount",
        "attempt-state-version",
        "attempt-succeeded-at",
        "order-state-version",
    ):
        assert independent_fault in integration
    assert "assert_equal 49" in integration


def test_auth_provision_response_remains_minimally_disclosing() -> None:
    contract = json.loads(
        (ROOT / "auth-service/src/main/resources/openapi.json").read_text(encoding="utf-8")
    )
    response = contract["components"]["schemas"]["EvaluationProvisionResponse"]
    assert set(response["properties"]) == {"handle", "expiresAt"}
    assert "userSubject" not in json.dumps(response)


def test_all_audit_inserts_use_the_shared_typed_writer() -> None:
    writers = []
    java_root = ROOT / "commerce-service/src/main/java"
    for path in java_root.rglob("*.java"):
        if "INSERT INTO eval_commerce_audit_reference" in path.read_text(encoding="utf-8"):
            writers.append(path.name)

    assert writers == ["EvaluationAuditReferenceWriter.java"]
