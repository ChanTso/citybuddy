import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_evaluation_sandbox_openapi_is_profile_bound_bounded_and_redacted() -> None:
    contract = json.loads(
        (ROOT / "commerce-service/src/main/resources/openapi.json").read_text(encoding="utf-8")
    )
    reset = contract["paths"]["/api/eval/reset"]["post"]
    complete = contract["paths"]["/api/eval/sandboxes/{sandboxId}/complete"]["post"]
    liveness = contract["paths"]["/internal/eval/sandboxes/{sandboxId}/liveness"]["post"]

    assert reset["x-citybuddy-profile"] == "evaluation"
    assert complete["x-citybuddy-profile"] == "evaluation"
    assert liveness["x-citybuddy-profile"] == "evaluation"
    assert reset["security"] == [{"evaluationManagementBasic": []}]
    assert complete["security"] == [{"evaluationManagementBasic": []}]
    assert liveness["security"] == [{"directUserBearer": []}]
    assert set(reset["responses"]) == {"200", "400", "401", "409", "502", "503"}

    schemas = contract["components"]["schemas"]
    request = schemas["EvaluationResetRequest"]
    fixture = schemas["EvaluationProductFixture"]
    response = schemas["EvaluationResetResponse"]
    assert request["additionalProperties"] is False
    assert request["properties"]["ttlSeconds"] == {
        "type": "integer",
        "minimum": 60,
        "maximum": 3600,
    }
    assert request["properties"]["products"]["minItems"] == 1
    assert request["properties"]["products"]["maxItems"] == 16
    assert fixture["additionalProperties"] is False
    assert response["additionalProperties"] is False
    assert set(response["properties"]) == {"sandboxId", "testUserHandle"}
    serialized = json.dumps(response)
    for private_name in (
        "password",
        "credential",
        "accessToken",
        "subject",
        "caseCorrelation",
        "idempotencyKey",
        "expiresAt",
    ):
        assert private_name not in serialized


def test_registry_cleanup_and_fixture_sql_are_sandbox_scoped() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/commerce/V010__evaluation_sandbox_lifecycle.sql"
    ).read_text(encoding="utf-8")
    repository = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationSandboxRepository.java"
    ).read_text(encoding="utf-8")
    audit = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationCommerceAuditService.java"
    ).read_text(encoding="utf-8")

    assert "PRIMARY KEY (sandbox_id)" in migration
    assert "UNIQUE KEY uq_eval_sandbox_case (case_correlation)" in migration
    assert "UNIQUE KEY uq_eval_sandbox_reset_key (reset_idempotency_key)" in migration
    assert "KEY ix_eval_sandbox_cleanup (cleanup_due_at, lifecycle_state, sandbox_id)" in migration
    assert "fixture_count BETWEEN 1 AND 16" in migration
    assert "requested_ttl_seconds BETWEEN 60 AND 3600" in migration
    assert "LIMIT ? FOR UPDATE SKIP LOCKED" in repository
    assert "DELETE FROM eval_sandbox_product_fixture WHERE sandbox_id = ?" in repository
    assert "WHERE sandbox_id = ? AND product_id = ?" in audit


def test_evaluation_paths_are_explicitly_profile_gated_and_do_not_enable_later_slices() -> None:
    controller = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationSandboxController.java"
    ).read_text(encoding="utf-8")
    configuration = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationSandboxConfiguration.java"
    ).read_text(encoding="utf-8")
    authorizer = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/catalog/DirectUserAuthorizer.java"
    ).read_text(encoding="utf-8")
    changed_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ROOT / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation").glob(
            "*.java"
        )
    )

    assert '@Profile("evaluation")' in controller
    assert '@Profile("evaluation")' in configuration
    assert "authorizeEvaluation" in controller
    assert "evaluationProfile && evaluationAllowed" in authorizer
    for forbidden in (
        "/api/eval/evidence",
        "ServiceEval",
        "sandbox callback",
    ):
        assert forbidden not in changed_sources


def test_exact_runtime_grants_preserve_truth_ownership() -> None:
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")
    assert (
        "GRANT SELECT, INSERT, UPDATE ON commerce_db.eval_sandbox TO 'commerce_app'@'%';"
    ) in grants
    assert (
        "GRANT SELECT, INSERT, UPDATE, DELETE ON "
        "commerce_db.eval_sandbox_product_fixture TO 'commerce_app'@'%';"
    ) in grants
    assert (
        "GRANT SELECT, INSERT ON commerce_db.eval_sandbox_effect_stub TO 'commerce_app'@'%';"
    ) in grants
    assert "eval_sandbox TO 'auth_app'" not in grants
    assert "eval_sandbox TO 'agent_app'" not in grants
    assert "auth_eval_test_principal TO 'commerce_app'" not in grants
