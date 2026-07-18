import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_evaluation_identity_openapi_is_profile_bound_bounded_and_redacted() -> None:
    payload = json.loads(
        (ROOT / "auth-service/src/main/resources/openapi.json").read_text(encoding="utf-8")
    )
    paths = payload["paths"]
    provision = paths["/internal/eval/test-principals/provision"]["post"]
    revoke = paths["/internal/eval/test-principals/{handle}/revoke"]["post"]
    issue = paths["/auth/eval/test-token"]["post"]

    assert provision["x-citybuddy-profile"] == "evaluation"
    assert revoke["x-citybuddy-profile"] == "evaluation"
    assert issue["x-citybuddy-profile"] == "evaluation"
    assert provision["security"] == [{"commerceEvaluationBasic": []}]
    assert revoke["security"] == [{"commerceEvaluationBasic": []}]
    assert issue["security"] == [{"evaluatorBasic": []}]

    schemas = payload["components"]["schemas"]
    request = schemas["EvaluationProvisionRequest"]
    response = schemas["EvaluationProvisionResponse"]
    assert request["additionalProperties"] is False
    assert set(request["properties"]) == {
        "sandboxId",
        "caseCorrelation",
        "testUserLabel",
        "ttlSeconds",
    }
    assert request["properties"]["ttlSeconds"] == {
        "type": "integer",
        "minimum": 60,
        "maximum": 3600,
    }
    assert response["additionalProperties"] is False
    assert set(response["properties"]) == {"handle", "expiresAt"}
    assert set(schemas["EvaluationRevokeResponse"]["properties"]) == {"handle", "state"}
    assert set(schemas["EvaluationTestTokenRequest"]["properties"]) == {"handle"}
    serialized_responses = json.dumps(
        {
            "provision": response,
            "revoke": schemas["EvaluationRevokeResponse"],
            "token": schemas["TokenResponse"],
        }
    )
    for private_name in (
        "subject",
        "testUserLabel",
        "caseCorrelation",
        "idempotencyKey",
        "credential",
        "password",
    ):
        assert private_name not in serialized_responses


def test_evaluation_routes_and_record_stay_inside_auth_profile_boundary() -> None:
    controller = (
        ROOT
        / "auth-service/src/main/java/io/citybuddy/auth/identity/EvaluationIdentityController.java"
    ).read_text(encoding="utf-8")
    repository = (
        ROOT / "auth-service/src/main/java/io/citybuddy/auth/identity/AuthRepository.java"
    ).read_text(encoding="utf-8")
    migration = (
        ROOT / "infra/mysql/migrations/auth/V003__evaluation_test_principal.sql"
    ).read_text(encoding="utf-8")
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")

    assert '@Profile("evaluation")' in controller
    assert (
        '@ConditionalOnProperty(name = "citybuddy.identity.enabled", havingValue = "true")'
        in controller
    )
    assert "/internal/eval/test-principals/provision" in controller
    assert "/internal/eval/test-principals/{handle}/revoke" in controller
    assert "/auth/eval/test-token" in controller
    assert "auth_eval_test_principal" in repository
    for forbidden in ("evaluation_sandbox", "sandbox_registry", "support_session"):
        assert forbidden not in repository

    assert "UNIQUE KEY uq_auth_eval_handle (opaque_handle)" in migration
    assert "UNIQUE KEY uq_auth_eval_sandbox_case (sandbox_id, case_correlation)" in migration
    assert "UNIQUE KEY uq_auth_eval_provision_key (provision_idempotency_key)" in migration
    assert "ttl_seconds BETWEEN 60 AND 3600" in migration
    assert "state ENUM('PROVISIONED', 'REVOKED')" in migration
    assert (
        "GRANT SELECT, INSERT, UPDATE ON commerce_db.auth_eval_test_principal TO 'auth_app'@'%';"
        in grants
    )
    assert "auth_eval_test_principal TO 'commerce_app'" not in grants
    assert "auth_eval_test_principal TO 'agent_app'" not in grants


def test_sandbox_claim_can_only_flow_from_evaluation_direct_token_to_obo() -> None:
    keyset = (
        ROOT / "auth-service/src/main/java/io/citybuddy/auth/identity/AuthKeySet.java"
    ).read_text(encoding="utf-8")
    controller = (
        ROOT / "auth-service/src/main/java/io/citybuddy/auth/identity/AuthController.java"
    ).read_text(encoding="utf-8")

    assert 'EVALUATION_DIRECT_TYPE = "eval_direct_user"' in keyset
    assert 'builder.claim("sandbox", sandboxId)' in keyset
    assert 'claims.getClaim("sandbox")' in keyset
    assert 'claims.getClaim("eval_sandbox") == null' in keyset
    assert "sandbox.equals(sandboxHeader)" in keyset
    assert "principal.sandboxId()" in controller
    assert "principal.expiresAt()" in controller
    assert "isActiveEvaluationSubject" in controller
