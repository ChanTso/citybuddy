import json
import re
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
    assert set(callback["responses"]) == {"200", "400", "401", "403", "404", "409", "503"}
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
    fault_inventory = (
        ROOT
        / "commerce-service/src/test/java/io/citybuddy/commerce/payment"
        / "EvaluationPaymentFaultInventoryCommand.java"
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
    evaluation_service = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationViewService.java"
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
        ) : service.index("private void requireCallbackReplay")
    ]
    assert ".resolveReplayLocked(" in committed_replay
    assert "EVALUATION_CALLBACK_REPLAY" in committed_replay
    assert "PRODUCTION_CALLBACK_REPLAY" in committed_replay
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
    ledger_view = evaluation_view[
        evaluation_view.index(
            "private List<PaymentLedgerTruth> paymentLedgerTruths"
        ) : evaluation_view.index("private List<SucceededCallbackTruth> succeededCallbackTruths")
    ]
    assert "LIMIT %d" in ledger_view
    assert "EvaluationPaymentCommittedFaces.MAXIMUM_LEDGER_CLOSURE_ROWS + 1" in ledger_view
    for attempt_projection in (
        "a.intent_hash AS attempt_intent_hash",
        "a.refunded_amount_minor AS attempt_refunded_amount_minor",
        "a.succeeded_at AS attempt_succeeded_at",
    ):
        assert attempt_projection in evaluation_view
    assert "paymentTruth.resolveSnapshot(" in evaluation_view
    assert "EVALUATION_STATE" in evaluation_service
    assert "EVALUATION_AUDIT" in evaluation_service
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
        "evaluation_owner_handle",
        "movement_id",
    ):
        assert f'"{residual_column}",' in committed_faces
    assert '"request_idempotency_key",' in committed_faces
    assert (
        "orderId,\n      String requestIdempotencyKey,\n      long amountMinor" in committed_faces
    )
    assert "+ requestIdempotencyKey" in committed_faces
    for seckill_content_column in ("transaction_event_id", "quantity"):
        assert f'"{seckill_content_column}",' in committed_faces
    assert "residualColumnDispositions" in committed_faces
    assert "participatingColumns()" in committed_faces
    for responsibility in (
        "AUTHORITATIVE_ROOT",
        "HASH_COMMITTED",
        "ORIGIN_COMMITTED",
        "DERIVED_REPLICA",
        "DATABASE_CONSTRAINED",
        "CORRELATED_GROUP",
        "OWNER_ACCEPTED_RESIDUAL",
    ):
        assert responsibility in committed_faces
    assert "CorrelatedContentGroupId.PAYMENT_EVENT_TIME" in committed_faces
    assert "columnResponsibilities()" in fault_inventory
    assert "responsibility().applicableScopes()" in fault_inventory
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
    assert "attempt-request-key" in integration
    assert "assert_equal 55" in integration
    assert "assert_equal 55" in integration
    assert "assert_equal 1485" in integration
    assert "payment_transformation_classification" in integration
    assert "payment_observed_transformation_classification" in integration
    assert "EQUIVALENCE_PRESERVING" in integration
    assert "concealed-authorization=$payment_start_visibility_cell_count" in integration
    assert "full PAYMENT_EVENT_TIME group shift" in integration
    assert "full PAYMENT_EVENT_TIME group shift that breaks audit relative order" in integration
    assert "production callback four-face physical corruption label matrix" in integration
    assert (
        "evaluation state/audit ledger closure exceeds the physical acquisition bound"
        in integration
    )

    contracts = (ROOT / "docs/CONTRACTS.md").read_text(encoding="utf-8")
    residual_decision = contracts[
        contracts.index(
            "The shared definition distinguishes exact/invariant-backed participating columns"
        ) : contracts.index(
            "**Resolved Level 3 route decision — 2026-07-23 (terminal portfolio route"
        )
    ]
    assert "The start-command `request_idempotency_key` is canonical business intent" in (
        residual_decision
    )
    assert "owner rejected treating it as an internal residual" in residual_decision
    assert "Three internal-only residual dispositions" not in residual_decision
    assert "Two internal-only residual dispositions" in residual_decision
    assert "CB-116 committed payment event time" in residual_decision
    assert "single `PAYMENT_EVENT_TIME` correlated content group" in residual_decision
    assert "no independent absolute-microsecond commitment" in residual_decision


def test_terminal_payment_callers_use_the_shared_complete_closure() -> None:
    service = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment/MockPaymentService.java"
    ).read_text(encoding="utf-8")
    resolver = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
        / "CommittedPaymentTruthResolver.java"
    ).read_text(encoding="utf-8")
    repository = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
        / "MockPaymentRepository.java"
    ).read_text(encoding="utf-8")
    visibility = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
        / "PaymentStartOrderVisibility.java"
    ).read_text(encoding="utf-8")
    visibility_test = (
        ROOT
        / "commerce-service/src/test/java/io/citybuddy/commerce/payment"
        / "PaymentStartOrderVisibilityTest.java"
    ).read_text(encoding="utf-8")
    sandbox_repository = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationSandboxRepository.java"
    ).read_text(encoding="utf-8")
    controller = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
        / "MockPaymentController.java"
    ).read_text(encoding="utf-8")
    evaluation_repository = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationViewRepository.java"
    ).read_text(encoding="utf-8")
    evaluation_service = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationViewService.java"
    ).read_text(encoding="utf-8")
    evaluation_reasons = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationRejectionReason.java"
    ).read_text(encoding="utf-8")
    integration = (ROOT / "scripts/test_evaluation_sandbox_integration.sh").read_text(
        encoding="utf-8"
    )

    expected_callers = {
        "PAYMENT_START_REPLAY",
        "PRODUCTION_CALLBACK_REPLAY",
        "EVALUATION_CALLBACK_REPLAY",
        "DIRECT_REFUND_ELIGIBILITY",
        "ACTION_PREPARE_CONFIRM_AND_RECEIPT_REPLAY",
        "REFUND_LIFECYCLE",
        "REFUND_RECONCILIATION",
        "EVALUATION_STATE",
        "EVALUATION_AUDIT",
    }
    for caller in expected_callers:
        assert f"    {caller}(" in resolver

    start_once = service[
        service.index("private MockPaymentResult startOnce") : service.index(
            "private MockPaymentCallbackResult callbackOnce"
        )
    ]
    assert start_once.index("resolveStartCommandLocked(context)") < start_once.index(
        "fenceSandbox(context.sandboxId());"
    )
    assert "findAttemptByRequest" not in start_once
    assert "findOrderForUpdate" not in start_once
    assert "order.status()" not in start_once
    assert "committedStartResult(committed.truth())" in start_once
    assert "pendingStartResult(pending.truth(), true)" in start_once
    assert "ConcealedStart" in start_once
    assert "CreateEligible" in start_once
    assert "PendingReplay" in start_once
    assert "CommittedReplay" in start_once
    assert "PendingPaymentTruth" in resolver
    assert "implements PaymentStartReplayResolution" in resolver
    assert "sealed interface StartCommandResolution" in resolver
    assert "record StartCommandContext" in resolver
    assert "private static MockPaymentResult result(" not in service
    assert "private static MockPaymentCallbackResult callbackResult(" not in service
    assert "committedCallbackResult(committed, false)" in service
    assert "committedCallbackResult(committed, true)" in service

    assert "CommittedPaymentCaller caller" in evaluation_repository
    assert "paymentTruth.resolveSnapshot(caller, attempt)" in evaluation_repository
    assert "EVALUATION_STATE" in evaluation_service
    assert "EVALUATION_AUDIT" in evaluation_service
    assert "classifyAuditOrigin" in evaluation_repository
    assert (
        "reference.entityType()"
        not in evaluation_repository[
            evaluation_repository.index(
                "static AuditOrigin classifyAuditOrigin"
            ) : evaluation_repository.index("private static boolean hasProductRoot")
        ]
    )
    for reason in (
        "STATE_COMMITTED_PAYMENT_TRUTH_INCONSISTENT",
        "AUDIT_COMMITTED_PAYMENT_TRUTH_INCONSISTENT",
        "STATE_EVALUATION_AUDIT_TRUTH_INCONSISTENT",
        "AUDIT_EVALUATION_AUDIT_TRUTH_INCONSISTENT",
    ):
        assert reason in evaluation_reasons
        assert reason in integration
    assert "resolveOrderIdentityLocked" not in resolver
    start_resolution = resolver[
        resolver.index("public StartCommandResolution resolveStartCommandLocked") : resolver.index(
            "public Optional<CommittedPaymentTruth> resolveReplayLocked"
        )
    ]
    assert "observeStartAttemptCommandLocator(context)" in start_resolution
    assert "observeStartOwnedOrderLocator(context)" in start_resolution
    assert start_resolution.index("observeStartAttemptCommandLocator(context)") < (
        start_resolution.index("observeStartOwnedOrderLocator(context)")
    )
    assert start_resolution.index("commandAttempts.isEmpty() && visibleOrders.isEmpty()") < (
        start_resolution.index("enumerateAttemptByOrderClosure(context.orderId(), LOCK)")
    )
    assert "enumerateStartAttemptVisibility" in repository
    assert "enumerateStartOrderVisibility" in repository
    start_order_visibility = repository[
        repository.index("List<PaymentStartOrderVisibility.Classification>") : repository.index(
            "public Optional<OrderTruth> findEvaluationOrderForUpdate"
        )
    ]
    assert start_order_visibility.count("jdbc.query(") == 1
    assert 'standardOrderByIdSql("") + " AND sandbox_id = ?"' in start_order_visibility
    assert "catch (" not in start_order_visibility
    binding = repository[
        repository.index("public void bindEvaluationOrderOwner") : repository.index(
            "public Optional<AttemptRecord> findAttemptByOrderForUpdate"
        )
    ]
    assert "EvaluationOwnerBindingProof proof" in binding
    assert "fixtureOwner" not in binding
    assert "proof.ownerHandle()" in binding
    assert "proof.existingFixtureOwnerSubject()" in binding
    assert "Optional<PaymentStartOrderVisibility.EvaluationOwnerBindingProof>" in resolver
    assert "resolveStartCommandLocked(context)" in service
    assert "rebound.bindingProof().isPresent()" in service
    assert "sealed interface Classification" in visibility
    assert "DirectOwner" in visibility
    assert "BindableFixture" in visibility
    assert "Concealed" in visibility
    assert "tryFixtureOwner(order.evaluationOwnerHandle())" in visibility
    assert "catch (" not in visibility
    assert "return tryFixtureOwner(ownerHandle)" in sandbox_repository
    assert 'Optional.of("eval-handle:" + ownerHandle)' in sandbox_repository
    assert "exception.status() == 403" in controller
    assert "exception.status() == 409" in controller
    assert "exception.status() == 503" in controller
    assert "COMMITTED_PAYMENT_TRUTH_INCONSISTENT" in controller
    assert "DEPENDENCY_OBSERVATION_INDETERMINATE" in controller
    assert '"resolveStartCommandLocked"' in resolver
    assert "OwnershipVisibilityLocator.START_ATTEMPT_COMMAND" in resolver
    assert "OwnershipVisibilityLocator.START_OWNED_ORDER" in resolver
    assert (
        'WHERE "\n            + keys.get(2)\n            + " = ? AND user_subject = ?"'
        in repository
    )
    assert "standardOwnedOrderByIdSql" in repository
    assert "seckillOwnedOrderByIdSql" in repository
    for inventory_field in (
        "TrustBoundary",
        "canonicalRequestLocators",
        "ownershipVisibilityLocators",
        "concealedResponseFamily",
        "refundAccumulatorPolicy",
        "committedBeforeLiveness",
    ):
        assert inventory_field in resolver
    assert "RefundAccumulatorPolicy.RECONCILIATION_DERIVED" in resolver
    for observer in (
        "payment-start-classification",
        "payment-callback-classification",
        "payment-state-classification",
        "payment-audit-classification",
    ):
        assert observer in integration
    for visibility_evidence in (
        "payment_caller_visibility_sql",
        "CONCEALED_BY_AUTHORIZATION",
        "payment-start-visibility-unknown.json",
        "payment-start-visibility-other.json",
        "refresh_payment_observer_credentials",
        "payment_observer_credentials_issued_at",
    ):
        assert visibility_evidence in integration
    for start_visibility_evidence in (
        "payment_start_visibility_fields",
        "payment_start_visibility_fault_sql",
        "payment-start visibility matrix covers four singles and six pairs",
        "payment-start true other owner",
        "payment-start malformed fixture handle",
        "payment-start valid handle with mismatched fixture subject",
        "valid unbound fixture owner is visible before binding",
        "direct owner visibility does not parse malformed fixture provenance",
        "visible attempt replay does not reclassify malformed fixture provenance",
    ):
        assert start_visibility_evidence in integration
    assert "classify(FIXTURE_OWNER, SANDBOX, null)" in visibility_test
    assert '"A".repeat(44)' in visibility_test
    for field in (
        "order_id",
        "sandbox_id",
        "user_subject",
        "evaluation_owner_handle",
    ):
        assert field in visibility
        assert field in integration
    classifier_inventory = re.search(
        r"CLASSIFIED_COLUMNS\s*=\s*Set\.of\((.*?)\);",
        visibility,
        re.DOTALL,
    )
    assert classifier_inventory is not None
    classifier_fields = set(re.findall(r'"([^"]+)"', classifier_inventory.group(1)))
    matrix_inventory = re.search(
        r"payment_start_visibility_fields=\(\n(.*?)\n\)",
        integration,
        re.DOTALL,
    )
    assert matrix_inventory is not None
    matrix_fields = set(re.findall(r"^\s+([a-z_]+)\s*$", matrix_inventory.group(1), re.MULTILINE))
    assert matrix_fields == classifier_fields
    assert "CallerColumnRole.VISIBILITY_INPUT" in (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
        / "EvaluationPaymentCommittedFaces.java"
    ).read_text(encoding="utf-8")
    assert "CallerColumnRole.BINDING_PROVENANCE" in (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
        / "EvaluationPaymentCommittedFaces.java"
    ).read_text(encoding="utf-8")


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
