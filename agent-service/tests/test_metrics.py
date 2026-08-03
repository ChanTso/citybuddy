from __future__ import annotations

import ast
import json
import re
import tomllib
from pathlib import Path
from typing import Any, cast

import pytest
from citybuddy_agent.__main__ import _strict_bool
from citybuddy_agent.application import AgentSettings, create_app
from citybuddy_agent.metrics import (
    FAQ_LABELS,
    OPERATION_BUCKETS,
    OPERATION_LABELS,
    PROVIDER_LABELS,
    BackendDecision,
    FaqLevel,
    FaqResult,
    MetricsRuntime,
    NoopCityBuddyMetrics,
    Operation,
    OperationOutcome,
    PrometheusCityBuddyMetrics,
    ProviderOutcome,
    ProviderRole,
    SafeCityBuddyMetrics,
    TraceExportOutcome,
    create_metrics_runtime,
)
from fastapi.testclient import TestClient

REPOSITORY = Path(__file__).resolve().parents[2]
INVENTORY_PATH = REPOSITORY / "observability" / "metrics-v1.json"
METRIC_FIELDS = {
    "name",
    "type",
    "unit",
    "description",
    "labelNames",
    "allowedLabelSets",
    "producerIds",
    "cb152Usage",
}
FORMULA_FIELDS = {
    "name",
    "numerator",
    "denominator",
    "excludedOutcomes",
}


def inventory() -> dict[str, Any]:
    return cast(dict[str, Any], json.loads(INVENTORY_PATH.read_text("utf-8")))


def allowed(metric: dict[str, Any]) -> set[tuple[str, ...]]:
    names = metric["labelNames"]
    return {tuple(label[name] for name in names) for label in metric["allowedLabelSets"]}


def sample_lines(payload: str) -> list[str]:
    return [line for line in payload.splitlines() if line and not line.startswith("#")]


def test_inventory_schema_runtime_labels_and_cardinality_are_closed() -> None:
    document = inventory()

    assert set(document) == {"schemaVersion", "metrics", "formulas"}
    assert document["schemaVersion"] == "citybuddy-agent-metrics-v1"
    assert len(document["metrics"]) == 6
    assert all(set(metric) == METRIC_FIELDS for metric in document["metrics"])
    assert all(set(formula) == FORMULA_FIELDS for formula in document["formulas"])
    assert {metric["type"] for metric in document["metrics"]} == {"counter", "histogram"}
    names = [metric["name"] for metric in document["metrics"]]
    assert len(names) == len(set(names))
    producers = [producer for metric in document["metrics"] for producer in metric["producerIds"]]
    assert len(producers) == len(set(producers))
    for metric in document["metrics"]:
        labels = metric["allowedLabelSets"]
        assert labels
        assert all(list(label) == metric["labelNames"] for label in labels)
        assert len(labels) == len({tuple(label.items()) for label in labels})

    by_name = {metric["name"]: metric for metric in document["metrics"]}
    operation_labels = {(operation.value, outcome.value) for operation, outcome in OPERATION_LABELS}
    assert allowed(by_name["citybuddy_agent_operation_requests_total"]) == operation_labels
    assert allowed(by_name["citybuddy_agent_operation_duration_seconds"]) == operation_labels
    assert allowed(by_name["citybuddy_agent_faq_cache_lookups_total"]) == {
        (level.value, result.value) for level, result in FAQ_LABELS
    }
    assert allowed(by_name["citybuddy_knowledge_backend_decisions_total"]) == {
        (decision.value,) for decision in BackendDecision
    }
    assert allowed(by_name["citybuddy_agent_model_request_attempts_total"]) == {
        (role.value, outcome.value) for role, outcome in PROVIDER_LABELS
    }
    assert allowed(by_name["citybuddy_agent_trace_exports_total"]) == {
        (outcome.value,) for outcome in TraceExportOutcome
    }

    logical_upper_bound = sum(len(metric["allowedLabelSets"]) for metric in document["metrics"])
    exported_upper_bound = sum(
        len(metric["allowedLabelSets"])
        * (len(OPERATION_BUCKETS) + 3 if metric["type"] == "histogram" else 1)
        for metric in document["metrics"]
    )
    assert logical_upper_bound == 110
    assert exported_upper_bound == 602


def test_registry_allows_only_inventory_series_and_has_no_created_or_default_collectors() -> None:
    metrics = PrometheusCityBuddyMetrics()
    for operation, outcome in OPERATION_LABELS:
        metrics.observe_operation(operation, outcome, 0.125)
    for level, result in FAQ_LABELS:
        metrics.record_faq_lookup(level, result)
    for decision in BackendDecision:
        metrics.record_backend_decision(decision)
    for role, provider_outcome in PROVIDER_LABELS:
        metrics.record_provider_attempt(role, provider_outcome)
    for trace_outcome in TraceExportOutcome:
        metrics.record_trace_export(trace_outcome)

    payload = metrics.render().decode("utf-8")
    assert len(sample_lines(payload)) == 602
    assert "_created" not in payload
    assert not re.search(r"^(?:python_gc|process_|python_info|platform)", payload, re.MULTILINE)
    for secret in (
        "secret-token",
        "00000000-0000-0000-0000-000000000001",
        "session-private",
        "turn-private",
        "order-private",
        "prompt-private",
        "reply-private",
    ):
        assert secret not in payload


def test_unknown_outcome_converges_to_error_without_dynamic_series() -> None:
    metrics = PrometheusCityBuddyMetrics()

    metrics.observe_operation(
        Operation.CHAT_TURN,
        cast(OperationOutcome, "future-private-outcome"),
        0.01,
    )
    metrics.record_provider_attempt(
        ProviderRole.PRIMARY,
        cast(ProviderOutcome, "future-provider-outcome"),
    )
    metrics.record_faq_lookup(FaqLevel.MAPPING, cast(FaqResult, "future-cache-outcome"))

    payload = metrics.render().decode("utf-8")
    assert 'operation="chat_turn",outcome="error"' in payload
    assert 'outcome="error",role="primary"' in payload
    assert "future-private-outcome" not in payload
    assert "future-provider-outcome" not in payload
    assert "future-cache-outcome" not in payload


class ExplodingMetrics(NoopCityBuddyMetrics):
    def observe_operation(
        self, operation: Operation, outcome: OperationOutcome, duration_seconds: float
    ) -> None:
        raise RuntimeError("private recorder failure")

    def record_faq_lookup(self, level: FaqLevel, result: FaqResult) -> None:
        raise RuntimeError("private recorder failure")

    def record_backend_decision(self, decision: BackendDecision) -> None:
        raise RuntimeError("private recorder failure")

    def record_provider_attempt(self, role: ProviderRole, outcome: ProviderOutcome) -> None:
        raise RuntimeError("private recorder failure")

    def record_trace_export(self, outcome: TraceExportOutcome) -> None:
        raise RuntimeError("private recorder failure")


def test_safe_and_noop_recorders_cannot_affect_control_flow() -> None:
    safe = SafeCityBuddyMetrics(ExplodingMetrics())

    safe.observe_operation(Operation.CHAT_TURN, OperationOutcome.SUCCESS, 0.1)
    safe.record_faq_lookup(FaqLevel.MAPPING, FaqResult.HIT)
    safe.record_backend_decision(BackendDecision.CACHE_SERVED)
    safe.record_provider_attempt(ProviderRole.PRIMARY, ProviderOutcome.SUCCESS)
    safe.record_trace_export(TraceExportOutcome.SENT)
    runtime = create_metrics_runtime(False)
    assert isinstance(runtime.recorder, NoopCityBuddyMetrics)
    assert not hasattr(runtime.recorder, "_registry")


def test_only_metrics_module_imports_or_uses_prometheus_primitives() -> None:
    source_root = REPOSITORY / "agent-service" / "src" / "citybuddy_agent"
    forbidden_names = {"Counter", "Histogram", "CollectorRegistry", "generate_latest"}
    metric_methods = {
        "observe_operation",
        "record_faq_lookup",
        "record_backend_decision",
        "record_provider_attempt",
        "record_trace_export",
    }
    for path in source_root.glob("*.py"):
        if path.name == "metrics.py":
            continue
        tree = ast.parse(path.read_text("utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import | ast.ImportFrom):
                modules = (
                    [alias.name for alias in node.names]
                    if isinstance(node, ast.Import)
                    else [node.module or ""]
                )
                assert all(not module.startswith("prometheus_client") for module in modules)
            if isinstance(node, ast.Name):
                assert node.id not in forbidden_names
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                assert node.func.attr != "labels"
            forbidden_expression: ast.AST | None = None
            if isinstance(node, ast.Assign):
                forbidden_expression = node.value
            elif isinstance(node, ast.AnnAssign):
                forbidden_expression = node.value
            elif isinstance(node, ast.Return):
                forbidden_expression = node.value
            elif isinstance(node, ast.If | ast.While):
                forbidden_expression = node.test
            if forbidden_expression is not None:
                for child in ast.walk(forbidden_expression):
                    if (
                        isinstance(child, ast.Call)
                        and isinstance(child.func, ast.Attribute)
                        and child.func.attr in metric_methods
                    ):
                        raise AssertionError(
                            f"Metric return value participates in control flow in {path}"
                        )


def test_internal_endpoint_is_disabled_by_default_custom_only_and_not_openapi() -> None:
    disabled = create_app()
    with TestClient(disabled) as client:
        assert client.get("/internal/metrics/prometheus").status_code == 404

    enabled = create_app(AgentSettings(metrics_enabled=True))
    with TestClient(enabled) as client:
        response = client.get("/internal/metrics/prometheus")
        openapi = client.get("/openapi.json").json()
        assert response.status_code == 200
        assert response.headers["cache-control"] == "no-store"
        assert response.headers["content-type"].startswith("text/plain")
        assert "/internal/metrics/prometheus" not in openapi["paths"]
        assert "/api/chat" not in openapi["paths"]
        assert "citybuddy_agent_operation_requests" in response.text
        assert "python_gc" not in response.text
        assert "process_" not in response.text


def test_render_failure_is_local_to_internal_endpoint() -> None:
    def explode() -> bytes:
        raise RuntimeError("private render failure")

    app = create_app(
        AgentSettings(metrics_enabled=True),
        metrics_runtime=MetricsRuntime(NoopCityBuddyMetrics(), explode),
    )
    with TestClient(app) as client:
        assert client.get("/internal/metrics/prometheus").status_code == 503
        assert client.get("/openapi.json").status_code == 200


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        (None, False),
        ("", False),
        ("false", False),
        ("FALSE", False),
        ("true", True),
        ("TrUe", True),
    ],
)
def test_metrics_boolean_is_strict(
    monkeypatch: pytest.MonkeyPatch, value: str | None, expected: bool
) -> None:
    if value is None:
        monkeypatch.delenv("CITYBUDDY_METRICS_ENABLED", raising=False)
    else:
        monkeypatch.setenv("CITYBUDDY_METRICS_ENABLED", value)
    assert _strict_bool("CITYBUDDY_METRICS_ENABLED") is expected


def test_metrics_boolean_rejects_other_nonempty_values(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CITYBUDDY_METRICS_ENABLED", "yes")
    with pytest.raises(ValueError, match="must be true or false"):
        _strict_bool("CITYBUDDY_METRICS_ENABLED")


def test_dependency_boundary_adds_only_prometheus_to_agent_and_preserves_otel_owner() -> None:
    agent = tomllib.loads((REPOSITORY / "agent-service" / "pyproject.toml").read_text("utf-8"))
    indexer = tomllib.loads(
        (REPOSITORY / "knowledge-indexer" / "pyproject.toml").read_text("utf-8")
    )
    dependencies = agent["project"]["dependencies"]
    assert dependencies.count("prometheus-client==0.25.0") == 1
    assert not any("opentelemetry" in dependency.casefold() for dependency in dependencies)
    assert indexer["project"]["dependencies"] == [
        "redis==5.2.1",
        "rocketmq-python-client==5.1.1",
    ]
    lock = (REPOSITORY / "uv.lock").read_text("utf-8")
    assert 'name = "rocketmq-python-client"\nversion = "5.1.1"' in lock
    assert 'name = "opentelemetry-api"\nversion = "1.43.0"' in lock
    assert 'name = "opentelemetry-sdk"\nversion = "1.43.0"' in lock
    for path in (REPOSITORY / "agent-service" / "src").rglob("*.py"):
        assert "opentelemetry" not in path.read_text("utf-8").casefold()
