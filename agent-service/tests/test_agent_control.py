import json
from typing import Any

import httpx
import pytest
from citybuddy_agent.agent_control import (
    CATALOG_PRODUCT_SPEC,
    AgentEvent,
    AttemptBudget,
    CatalogProductInput,
    CircuitOpen,
    LiteLlmClient,
    ModelRouter,
    ProviderCircuits,
    ProviderFailure,
    ProviderRoute,
    RuleRouter,
    ToolAdapter,
)


def plan() -> Any:
    return ModelRouter(
        (
            ProviderRoute("support-standard-primary", "provider-a"),
            ProviderRoute("support-standard-fallback", "provider-b"),
        ),
        8,
    ).plan(RuleRouter().signals("product price"))


def completion(content: str = "bounded response") -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def test_rule_and_model_routers_keep_signals_separate_from_tier_policy() -> None:
    signals = RuleRouter().signals("Please refund my order and explain the product price")
    selected = plan()

    assert signals.high_risk is True
    assert signals.private_action is True
    assert signals.public_faq is True
    assert selected.tier == "standard"
    assert [route.role_alias for route in selected.routes] == [
        "support-standard-primary",
        "support-standard-fallback",
    ]


def test_litellm_transient_retry_and_same_tier_fallback_share_one_budget(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    responses = [httpx.Response(503), httpx.Response(503), completion()]
    requests: list[dict[str, Any]] = []

    def post(*args: Any, **kwargs: Any) -> httpx.Response:
        requests.append(kwargs["json"])
        return responses.pop(0)

    monkeypatch.setattr(httpx, "post", post)
    events: list[AgentEvent] = []
    budget = AttemptBudget(8, events)
    client = LiteLlmClient(
        "https://proxy.test",
        ProviderCircuits(minimum_requests=2, open_seconds=10, half_open_probes=1),
    )

    reply = client.complete(plan(), [{"role": "user", "content": "hello"}], [], budget, events)

    assert reply.content == "bounded response"
    assert budget.used == 3
    assert [request["model"] for request in requests] == [
        "support-standard-primary",
        "support-standard-primary",
        "support-standard-fallback",
    ]
    assert {
        event.payload.get("provider") for event in events if event.event_type == "MODEL_OUTCOME"
    } == {
        "provider-a",
        "provider-b",
    }


def test_litellm_does_not_retry_non_transient_provider_denial(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = 0

    def post(*args: Any, **kwargs: Any) -> httpx.Response:
        nonlocal calls
        del args, kwargs
        calls += 1
        return httpx.Response(400)

    monkeypatch.setattr(httpx, "post", post)
    events: list[AgentEvent] = []
    budget = AttemptBudget(8, events)
    client = LiteLlmClient(
        "https://proxy.test",
        ProviderCircuits(minimum_requests=2, open_seconds=10, half_open_probes=1),
    )

    with pytest.raises(ProviderFailure) as denied:
        client.complete(plan(), [{"role": "user", "content": "hello"}], [], budget, events)

    assert denied.value.transient is False
    assert calls == 1
    assert budget.used == 1


def test_provider_circuits_are_isolated_bounded_and_half_open() -> None:
    now = [100.0]
    events: list[AgentEvent] = []
    circuits = ProviderCircuits(
        minimum_requests=2,
        open_seconds=5,
        half_open_probes=1,
        clock=lambda: now[0],
    )
    circuits.transient_failure("provider-a", events)
    circuits.admit("provider-a", events)
    circuits.transient_failure("provider-a", events)

    with pytest.raises(CircuitOpen):
        circuits.admit("provider-a", events)
    circuits.admit("provider-b", events)
    now[0] = 106.0
    circuits.admit("provider-a", events)
    with pytest.raises(CircuitOpen):
        circuits.admit("provider-a", events)
    circuits.success("provider-a", events)
    circuits.admit("provider-a", events)


class RecordingObo:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, str, str]] = []

    def exchange(self, direct_token: str, subject: str, session_id: str, scope: str) -> str:
        self.calls.append((direct_token, subject, session_id, scope))
        return "signed-obo"


def test_tool_adapter_enforces_server_owned_spec_and_bounded_model_view(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    obo = RecordingObo()
    requests: list[dict[str, Any]] = []

    def post(*args: Any, **kwargs: Any) -> httpx.Response:
        requests.append(kwargs)
        return httpx.Response(
            200,
            json={
                "productId": "product-1",
                "name": "Tea",
                "priceMinor": 500,
                "currency": "CNY",
                "available": True,
                "publicationVersion": 2,
            },
        )

    monkeypatch.setattr(httpx, "post", post)
    adapter = ToolAdapter("https://commerce.test", obo)
    events: list[AgentEvent] = []
    budget = AttemptBudget(4, events)

    result = adapter.execute(
        name=CATALOG_PRODUCT_SPEC.name,
        serialized_arguments=json.dumps({"productId": "product-1"}),
        direct_token="direct",
        subject="user-1",
        session_id="session-1",
        budget=budget,
        events=events,
    )

    assert result.outcome == "ok"
    assert set(result.model_view) == {
        "productId",
        "name",
        "priceMinor",
        "currency",
        "available",
        "publicationVersion",
    }
    assert obo.calls == [("direct", "user-1", "session-1", "catalog:read")]
    assert budget.used == 2
    assert requests[0]["timeout"] == 1.0
    assert requests[0]["json"] == {"productId": "product-1"}


@pytest.mark.parametrize(
    ("name", "arguments", "reason"),
    [
        ("unknown", "{}", "unknown_tool"),
        (CATALOG_PRODUCT_SPEC.name, '{"productId":"p","scope":"catalog:*"}', "invalid_arguments"),
        (CATALOG_PRODUCT_SPEC.name, '{"productId":', "invalid_arguments"),
    ],
)
def test_tool_adapter_rejects_model_widening_before_io(
    name: str, arguments: str, reason: str
) -> None:
    obo = RecordingObo()
    events: list[AgentEvent] = []
    budget = AttemptBudget(4, events)

    result = ToolAdapter("https://commerce.test", obo).execute(
        name=name,
        serialized_arguments=arguments,
        direct_token="direct",
        subject="user-1",
        session_id="session-1",
        budget=budget,
        events=events,
    )

    assert result.model_view == {"outcome": "deny_with_feedback", "reason": reason}
    assert obo.calls == []
    assert budget.used == 0


def test_tool_timeout_is_structured_and_unexpected_failure_remains_visible(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    obo = RecordingObo()
    events: list[AgentEvent] = []

    def timeout(*args: Any, **kwargs: Any) -> httpx.Response:
        del args, kwargs
        raise httpx.ReadTimeout("bounded timeout")

    monkeypatch.setattr(httpx, "post", timeout)
    result = ToolAdapter("https://commerce.test", obo).execute(
        name=CATALOG_PRODUCT_SPEC.name,
        serialized_arguments='{"productId":"product-1"}',
        direct_token="direct",
        subject="user-1",
        session_id="session-1",
        budget=AttemptBudget(4, events),
        events=events,
    )
    assert result.model_view["reason"] == "timeout"

    monkeypatch.setattr(httpx, "post", lambda *args, **kwargs: httpx.Response(500))
    with pytest.raises(RuntimeError, match="Unexpected commerce tool failure"):
        ToolAdapter("https://commerce.test", obo).execute(
            name=CATALOG_PRODUCT_SPEC.name,
            serialized_arguments='{"productId":"product-1"}',
            direct_token="direct",
            subject="user-1",
            session_id="session-1",
            budget=AttemptBudget(4, []),
            events=[],
        )


def test_toolspec_schema_forbids_unknown_fields() -> None:
    schema = CatalogProductInput.model_json_schema(by_alias=True)
    assert schema["additionalProperties"] is False
    assert CATALOG_PRODUCT_SPEC.scope == "catalog:read"
    assert CATALOG_PRODUCT_SPEC.risk == "read"
    assert CATALOG_PRODUCT_SPEC.idempotency == "read-only"
