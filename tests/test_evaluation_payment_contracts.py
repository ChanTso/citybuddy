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

    assert "chk_standard_order_eval_binding" in migration
    assert "chk_mock_payment_callback_eval_context" in migration
    assert "PAYMENT_CALLBACK" in migration
    assert "fenceSandbox(request.sandboxId());" in service
    assert service.index("fenceSandbox(request.sandboxId());") < service.index(
        "findAttemptByCorrelationForUpdate"
    )
    assert "monotonicEvaluationAuditCreatedAt" in service
    assert service.index("fenceSandbox(request.sandboxId());") < service.index(
        "monotonicEvaluationAuditCreatedAt"
    )
    for field in ("sandboxId()", "supportSessionId()", "traceId()", "operationId()"):
        assert field in authenticator
    assert "insertPaymentAuditReference" in repository
    assert "sandbox_id <=> ?" in repository
    assert "findEvaluationOrderForUpdate" in repository
    assert "WHERE order_id = ? AND sandbox_id = ?" in repository
    assert "findEvaluationAttemptByCorrelationForUpdate" in repository
    assert "AND sandbox_id = ? FOR UPDATE" in repository
    assert "l.product_id = o.product_id" in (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationViewRepository.java"
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
