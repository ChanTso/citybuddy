"""Bounded single-agent control, model routing, and ToolSpec mediation."""

from __future__ import annotations

import hashlib
import json
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Literal, Protocol

import httpx
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from . import http_client
from .actions import (
    ACTION_SCOPE,
    MAX_ACTION_PENDING_TTL_SECONDS,
    ActionJsonError,
    BoundedHttpResponse,
    PendingActionPayload,
    PreparedActionResponse,
    RefundActionArguments,
    action_argument_commitment,
    bounded_http_post,
    canonical_action_timestamp,
    strict_json_object,
)
from .faq_cache import FaqCache
from .knowledge import (
    KnowledgeSearch,
    KnowledgeSearchFailure,
    KnowledgeSearchInput,
    KnowledgeSearchOutput,
)
from .metrics import (
    BackendDecision,
    CityBuddyMetrics,
    FaqLevel,
    FaqResult,
    NoopCityBuddyMetrics,
    Operation,
    OperationOutcome,
    ProviderOutcome,
    ProviderRole,
    SafeCityBuddyMetrics,
)
from .retrieval import (
    RerankCandidate,
    RerankOutput,
    RerankRequest,
    RerankValidationError,
    RetrievalDecision,
    SufficiencyCalibration,
    decide_retrieval,
    insufficient_decision,
)
from .tracing import NoopTraceSink, OperationObservation, TraceSink


@dataclass(frozen=True)
class AgentEvent:
    event_type: str
    payload: dict[str, object]


@dataclass(frozen=True)
class AgentRunResult:
    response_text: str
    outcome: str
    events: tuple[AgentEvent, ...]
    retrieval_decision: RetrievalDecision | None = None
    pending_action: PendingActionPayload | None = None
    request_reasons: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if (self.outcome == "action_pending") != (self.pending_action is not None):
            raise RuntimeError("Agent action outcome and PendingAction disagree")


class AgentRunner(Protocol):
    def run(
        self,
        *,
        message: str,
        direct_token: str,
        subject: str,
        session_id: str,
        trace_id: str,
        turn_id: str,
        sandbox_id: str | None = None,
    ) -> AgentRunResult: ...


class OboExchange(Protocol):
    def exchange(
        self,
        direct_token: str,
        subject: str,
        session_id: str,
        scope: str,
        sandbox_id: str | None = None,
    ) -> str: ...


class AttemptBudgetExhausted(Exception):
    """The one turn-scoped attempt budget has no remaining capacity."""


class ProviderFailure(Exception):
    def __init__(self, *, transient: bool) -> None:
        super().__init__("Provider request failed")
        self.transient = transient


class CircuitOpen(Exception):
    """The selected provider circuit is not currently admitting work."""


TOOL_BOUNDARY_FAILURE_REASONS = frozenset(
    {
        "ACTION_PREPARATION_IDENTITY_UNAUTHENTICATED",
        "ACTION_PREPARATION_IDENTITY_FORBIDDEN",
        "ACTION_PREPARATION_IDENTITY_UNAVAILABLE",
        "ACTION_PREPARATION_COMMERCE_VALIDATION_REJECTED",
        "ACTION_PREPARATION_COMMERCE_UNAUTHENTICATED",
        "ACTION_PREPARATION_COMMERCE_FORBIDDEN",
        "ACTION_PREPARATION_TARGET_NOT_FOUND",
        "ACTION_PREPARATION_INTENT_CONFLICT",
        "ACTION_PREPARATION_COMMERCE_UNAVAILABLE",
        "ACTION_PREPARATION_COMMERCE_TIMEOUT",
        "ACTION_PREPARATION_COMMERCE_INDETERMINATE",
        "ACTION_PREPARATION_RESPONSE_INVALID",
        "ACTION_PREPARATION_DURABLE_TRUTH_INCONSISTENT",
        "ACTION_SANDBOX_LIVENESS_UNAVAILABLE",
        "ACTION_SANDBOX_LIVENESS_REJECTED",
        "ACTION_SESSION_PERSISTENCE_UNAVAILABLE",
        "ACTION_REPLAY_PERSISTENCE_UNAVAILABLE",
        "ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE",
        "ACTION_TURN_RESERVATION_PERSISTENCE_UNAVAILABLE",
        "ACTION_EXPIRY_PERSISTENCE_UNAVAILABLE",
        "ACTION_DECLINE_PERSISTENCE_UNAVAILABLE",
        "ACTION_CLARIFICATION_PERSISTENCE_UNAVAILABLE",
        "AGENT_TURN_COMPLETION_PERSISTENCE_UNAVAILABLE",
        "ACTION_CONFIRMATION_UNAVAILABLE",
    }
)


class ToolBoundaryFailure(Exception):
    """A sensitive-tool boundary failure with a closed server-only producer."""

    def __init__(self, *, status_code: int, reason: str, detail: str) -> None:
        if reason not in TOOL_BOUNDARY_FAILURE_REASONS:
            raise ValueError("Unregistered sensitive-tool boundary producer")
        super().__init__(detail)
        self.status_code = status_code
        self.reason = reason
        self.detail = detail


@dataclass
class AttemptBudget:
    limit: int
    events: list[AgentEvent]
    used: int = 0

    def charge(self, kind: str, target: str) -> None:
        if self.used >= self.limit:
            raise AttemptBudgetExhausted
        self.used += 1
        self.events.append(
            AgentEvent(
                "BUDGET_CHARGED",
                {"attempt": self.used, "limit": self.limit, "kind": kind, "target": target},
            )
        )


@dataclass(frozen=True)
class RoutingSignals:
    high_risk: bool
    private_action: bool
    public_faq: bool
    chitchat: bool
    complex_request: bool

    def evidence(self) -> dict[str, object]:
        return {
            "highRisk": self.high_risk,
            "privateAction": self.private_action,
            "publicFaq": self.public_faq,
            "chitchat": self.chitchat,
            "complex": self.complex_request,
        }


class RuleRouter:
    """Emit deterministic signals without choosing the final handling policy."""

    def signals(self, message: str) -> RoutingSignals:
        normalized = message.casefold()
        return RoutingSignals(
            high_risk=any(value in normalized for value in ("refund", "cancel", "payment")),
            private_action=any(value in normalized for value in ("my order", "refund", "cancel")),
            public_faq=any(value in normalized for value in ("product", "catalog", "price")),
            chitchat=normalized.strip() in {"hi", "hello", "hey"},
            complex_request=len(message) > 240,
        )


@dataclass(frozen=True)
class ProviderRoute:
    role_alias: str
    provider_key: str


@dataclass(frozen=True)
class ModelPlan:
    tier: str
    routes: tuple[ProviderRoute, ...]
    reranker_route: ProviderRoute
    attempt_limit: int


class ModelRouter:
    """Own business-tier, escalation, and budget selection."""

    def __init__(
        self,
        routes: tuple[ProviderRoute, ...],
        attempt_limit: int,
        reranker_route: ProviderRoute | None = None,
    ) -> None:
        if not routes or attempt_limit < 1:
            raise ValueError("Bounded model policy is incomplete")
        self._routes = routes
        self._attempt_limit = attempt_limit
        self._reranker_route = reranker_route or ProviderRoute(
            "support-reranker-standard", "reranker"
        )

    def plan(self, signals: RoutingSignals) -> ModelPlan:
        del signals
        return ModelPlan(
            tier="standard",
            routes=self._routes,
            reranker_route=self._reranker_route,
            attempt_limit=self._attempt_limit,
        )


class Reranker(Protocol):
    def rerank(
        self,
        plan: ModelPlan,
        request: RerankRequest,
        budget: AttemptBudget,
        events: list[AgentEvent],
    ) -> RerankOutput: ...


@dataclass
class _CircuitState:
    requests: int = 0
    failures: int = 0
    opened_until: float | None = None
    half_open_in_flight: int = 0


class ProviderCircuits:
    def __init__(
        self,
        *,
        minimum_requests: int,
        open_seconds: float,
        half_open_probes: int,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if minimum_requests < 1 or open_seconds <= 0 or half_open_probes < 1:
            raise ValueError("Circuit bounds must be positive")
        self._minimum_requests = minimum_requests
        self._open_seconds = open_seconds
        self._half_open_probes = half_open_probes
        self._clock = clock
        self._states: dict[str, _CircuitState] = {}
        self._lock = threading.Lock()

    def admit(self, provider: str, events: list[AgentEvent]) -> None:
        with self._lock:
            state = self._states.setdefault(provider, _CircuitState())
            now = self._clock()
            if state.opened_until is None:
                return
            if now < state.opened_until:
                events.append(
                    AgentEvent("CIRCUIT_OUTCOME", {"provider": provider, "state": "open"})
                )
                raise CircuitOpen
            if state.half_open_in_flight >= self._half_open_probes:
                events.append(
                    AgentEvent("CIRCUIT_OUTCOME", {"provider": provider, "state": "probe-rejected"})
                )
                raise CircuitOpen
            state.half_open_in_flight += 1
            events.append(
                AgentEvent("CIRCUIT_OUTCOME", {"provider": provider, "state": "half-open"})
            )

    def success(self, provider: str, events: list[AgentEvent]) -> None:
        with self._lock:
            state = self._states.setdefault(provider, _CircuitState())
            state.requests += 1
            state.failures = 0
            state.opened_until = None
            state.half_open_in_flight = 0
            events.append(AgentEvent("CIRCUIT_OUTCOME", {"provider": provider, "state": "closed"}))

    def transient_failure(self, provider: str, events: list[AgentEvent]) -> None:
        with self._lock:
            state = self._states.setdefault(provider, _CircuitState())
            state.requests += 1
            state.failures += 1
            state.half_open_in_flight = 0
            if (
                state.requests >= self._minimum_requests
                and state.failures >= self._minimum_requests
            ):
                state.opened_until = self._clock() + self._open_seconds
                circuit_state = "opened"
            else:
                circuit_state = "closed"
            events.append(
                AgentEvent("CIRCUIT_OUTCOME", {"provider": provider, "state": circuit_state})
            )

    def release_probe(self, provider: str) -> None:
        """Release a half-open reservation when no circuit outcome consumed it."""
        with self._lock:
            state = self._states.setdefault(provider, _CircuitState())
            if state.opened_until is not None and state.half_open_in_flight > 0:
                state.half_open_in_flight -= 1


@dataclass(frozen=True)
class ModelReply:
    content: str | None
    tool_name: str | None = None
    tool_arguments: str | None = None


class LiteLlmClient:
    """Call a LiteLLM-compatible endpoint using role aliases only."""

    def __init__(
        self,
        url: str,
        circuits: ProviderCircuits,
        metrics: CityBuddyMetrics | None = None,
    ) -> None:
        self._url = url.rstrip("/")
        self._circuits = circuits
        self._metrics = SafeCityBuddyMetrics(metrics or NoopCityBuddyMetrics())

    def complete(
        self,
        plan: ModelPlan,
        messages: list[dict[str, str]],
        tools: list[dict[str, object]],
        budget: AttemptBudget,
        events: list[AgentEvent],
    ) -> ModelReply:
        transient_retry_available = True
        last_failure: ProviderFailure | CircuitOpen | None = None
        for route_index, route in enumerate(plan.routes):
            metric_role = ProviderRole.PRIMARY if route_index == 0 else ProviderRole.FALLBACK
            attempts_for_route = 0
            while True:
                transient_failure: ProviderFailure | None = None
                admitted = False
                attempt_outcome: ProviderOutcome | None = None
                budget.charge("model_http", route.provider_key)
                attempts_for_route += 1
                try:
                    self._circuits.admit(route.provider_key, events)
                    admitted = True
                    attempt_outcome = ProviderOutcome.ERROR
                    response = http_client.post(
                        f"{self._url}/v1/chat/completions",
                        json={"model": route.role_alias, "messages": messages, "tools": tools},
                        timeout=2.0,
                    )
                    if response.status_code in {408, 429, 502, 503, 504}:
                        attempt_outcome = ProviderOutcome.TRANSIENT
                        raise ProviderFailure(transient=True)
                    if response.status_code != 200:
                        attempt_outcome = ProviderOutcome.DENIED
                        raise ProviderFailure(transient=False)
                    try:
                        payload = response.json()
                    except ValueError as exception:
                        attempt_outcome = ProviderOutcome.INVALID
                        raise ProviderFailure(transient=False) from exception
                    try:
                        reply = self._parse(payload)
                    except ProviderFailure:
                        attempt_outcome = ProviderOutcome.INVALID
                        raise
                    attempt_outcome = ProviderOutcome.SUCCESS
                    self._circuits.success(route.provider_key, events)
                    events.append(
                        AgentEvent(
                            "MODEL_OUTCOME",
                            {
                                "alias": route.role_alias,
                                "provider": route.provider_key,
                                "result": "ok",
                            },
                        )
                    )
                    return reply
                except http_client.TRANSPORT_FAILURES:
                    attempt_outcome = ProviderOutcome.TRANSIENT
                    transient_failure = ProviderFailure(transient=True)
                except CircuitOpen as failure:
                    last_failure = failure
                    break
                except ProviderFailure as failure:
                    if not failure.transient:
                        events.append(
                            AgentEvent(
                                "MODEL_OUTCOME",
                                {
                                    "alias": route.role_alias,
                                    "provider": route.provider_key,
                                    "result": "denied",
                                },
                            )
                        )
                        raise
                    transient_failure = failure
                finally:
                    if attempt_outcome is not None:
                        self._metrics.record_provider_attempt(metric_role, attempt_outcome)
                    if admitted:
                        self._circuits.release_probe(route.provider_key)
                if transient_failure is None:
                    raise RuntimeError("Transient model failure was not classified")
                self._circuits.transient_failure(route.provider_key, events)
                events.append(
                    AgentEvent(
                        "MODEL_OUTCOME",
                        {
                            "alias": route.role_alias,
                            "provider": route.provider_key,
                            "result": "transient",
                        },
                    )
                )
                last_failure = transient_failure
                if transient_retry_available and attempts_for_route == 1:
                    transient_retry_available = False
                    continue
                break
        if last_failure is None:
            raise RuntimeError("Model policy has no route")
        raise ProviderFailure(transient=isinstance(last_failure, ProviderFailure))

    def rerank(
        self,
        plan: ModelPlan,
        request: RerankRequest,
        budget: AttemptBudget,
        events: list[AgentEvent],
    ) -> RerankOutput:
        """Use one fixed role alias with one bounded same-route transient retry."""
        route = plan.reranker_route
        for attempt in range(2):
            admitted = False
            attempt_outcome: ProviderOutcome | None = None
            budget.charge("reranker_http", route.provider_key)
            try:
                self._circuits.admit(route.provider_key, events)
                admitted = True
                attempt_outcome = ProviderOutcome.ERROR
                response = http_client.post(
                    f"{self._url}/v1/chat/completions",
                    json={
                        "model": route.role_alias,
                        "messages": [
                            {
                                "role": "user",
                                "content": request.model_dump_json(by_alias=True),
                            }
                        ],
                    },
                    timeout=2.0,
                )
                if response.status_code in {408, 429, 502, 503, 504}:
                    attempt_outcome = ProviderOutcome.TRANSIENT
                    raise ProviderFailure(transient=True)
                if response.status_code != 200:
                    attempt_outcome = ProviderOutcome.DENIED
                    raise ProviderFailure(transient=False)
                try:
                    payload = response.json()
                except ValueError as exception:
                    attempt_outcome = ProviderOutcome.INVALID
                    raise ProviderFailure(transient=False) from exception
                try:
                    output = self._parse_rerank(payload)
                except ProviderFailure:
                    attempt_outcome = ProviderOutcome.INVALID
                    raise
                attempt_outcome = ProviderOutcome.SUCCESS
                self._circuits.success(route.provider_key, events)
                events.append(
                    AgentEvent(
                        "MODEL_OUTCOME",
                        {
                            "alias": route.role_alias,
                            "provider": route.provider_key,
                            "result": "rerank-ok",
                        },
                    )
                )
                return output
            except http_client.TRANSPORT_FAILURES:
                attempt_outcome = ProviderOutcome.TRANSIENT
                failure = ProviderFailure(transient=True)
            except CircuitOpen:
                failure = ProviderFailure(transient=True)
            except ProviderFailure as exception:
                failure = exception
            finally:
                if attempt_outcome is not None:
                    self._metrics.record_provider_attempt(ProviderRole.RERANKER, attempt_outcome)
                if admitted:
                    self._circuits.release_probe(route.provider_key)
            if not failure.transient:
                events.append(
                    AgentEvent(
                        "MODEL_OUTCOME",
                        {
                            "alias": route.role_alias,
                            "provider": route.provider_key,
                            "result": "rerank-denied",
                        },
                    )
                )
                raise failure
            self._circuits.transient_failure(route.provider_key, events)
            events.append(
                AgentEvent(
                    "MODEL_OUTCOME",
                    {
                        "alias": route.role_alias,
                        "provider": route.provider_key,
                        "result": "rerank-transient",
                    },
                )
            )
            if attempt == 1:
                raise failure
        raise RuntimeError("Bounded reranker loop did not terminate")

    @staticmethod
    def _parse_rerank(payload: object) -> RerankOutput:
        if not isinstance(payload, dict):
            raise ProviderFailure(transient=False)
        choices = payload.get("choices")
        if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
            raise ProviderFailure(transient=False)
        message = choices[0].get("message")
        content = message.get("content") if isinstance(message, dict) else None
        if (
            not isinstance(message, dict)
            or not isinstance(content, str)
            or len(content) > 4096
            or "tool_calls" in message
        ):
            raise ProviderFailure(transient=False)
        try:
            return RerankOutput.model_validate_json(content)
        except ValidationError as exception:
            raise ProviderFailure(transient=False) from exception

    @staticmethod
    def _parse(payload: object) -> ModelReply:
        if not isinstance(payload, dict):
            raise ProviderFailure(transient=False)
        choices = payload.get("choices")
        if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
            raise ProviderFailure(transient=False)
        message = choices[0].get("message")
        if not isinstance(message, dict):
            raise ProviderFailure(transient=False)
        tool_calls = message.get("tool_calls")
        if tool_calls is not None:
            if not isinstance(tool_calls, list) or len(tool_calls) != 1:
                raise ProviderFailure(transient=False)
            call = tool_calls[0]
            function = call.get("function") if isinstance(call, dict) else None
            if not isinstance(function, dict):
                raise ProviderFailure(transient=False)
            name = function.get("name")
            arguments = function.get("arguments")
            if not isinstance(name, str) or not isinstance(arguments, str):
                raise ProviderFailure(transient=False)
            return ModelReply(content=None, tool_name=name, tool_arguments=arguments)
        content = message.get("content")
        if not isinstance(content, str) or not content or len(content) > 256:
            raise ProviderFailure(transient=False)
        return ModelReply(content=content)


class CatalogProductInput(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    product_id: str = Field(alias="productId", min_length=1, max_length=64)


class CatalogProductOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    product_id: str = Field(alias="productId", min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=200)
    price_minor: int = Field(alias="priceMinor", ge=0)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    available: bool
    publication_version: int = Field(alias="publicationVersion", ge=1)


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    authority: Literal["commerce_obo", "elasticsearch"]
    scope: str | None
    risk: Literal["read", "sensitive"]
    timeout_seconds: float
    idempotency: Literal["read-only", "turn-action-commitment"]
    input_schema: type[BaseModel]
    output_schema: type[BaseModel]

    def model_schema(self) -> dict[str, object]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.input_schema.model_json_schema(by_alias=True),
            },
        }


CATALOG_PRODUCT_SPEC = ToolSpec(
    name="catalog.product.get",
    description="Read one published product through commerce authority.",
    authority="commerce_obo",
    scope="catalog:read",
    risk="read",
    timeout_seconds=1.0,
    idempotency="read-only",
    input_schema=CatalogProductInput,
    output_schema=CatalogProductOutput,
)

KNOWLEDGE_SEARCH_SPEC = ToolSpec(
    name="knowledge.search",
    description=(
        "Search the derived public-knowledge index with a required original query and one "
        "optional rewrite. Results are not live commerce truth."
    ),
    authority="elasticsearch",
    scope=None,
    risk="read",
    timeout_seconds=1.5,
    idempotency="read-only",
    input_schema=KnowledgeSearchInput,
    output_schema=KnowledgeSearchOutput,
)

REFUND_PREPARE_SPEC = ToolSpec(
    name="actions.refund.prepare",
    description=(
        "Prepare one refund request for explicit user confirmation. This does not execute "
        "the refund and the model cannot confirm it."
    ),
    authority="commerce_obo",
    scope=ACTION_SCOPE,
    risk="sensitive",
    timeout_seconds=3.0,
    idempotency="turn-action-commitment",
    input_schema=RefundActionArguments,
    output_schema=PreparedActionResponse,
)


@dataclass(frozen=True)
class ToolResult:
    outcome: Literal["ok", "deny_with_feedback"]
    model_view: dict[str, object]
    retrieval_decision: RetrievalDecision | None = None
    pending_action: PendingActionPayload | None = None
    server_reason: str | None = None
    operation_outcome: OperationOutcome | None = None


class ToolAdapter:
    def __init__(
        self,
        base_url: str,
        obo: OboExchange,
        knowledge: KnowledgeSearch | None = None,
        reranker: Reranker | None = None,
        calibration: SufficiencyCalibration | None = None,
        faq_cache: FaqCache | None = None,
        metrics: CityBuddyMetrics | None = None,
        trace_sink: TraceSink | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._obo = obo
        self._knowledge = knowledge
        self._reranker = reranker
        self._calibration = calibration
        self._faq_cache = faq_cache
        self._metrics = SafeCityBuddyMetrics(metrics or NoopCityBuddyMetrics())
        self._trace_sink = trace_sink or NoopTraceSink()
        self._specs = {
            CATALOG_PRODUCT_SPEC.name: CATALOG_PRODUCT_SPEC,
            KNOWLEDGE_SEARCH_SPEC.name: KNOWLEDGE_SEARCH_SPEC,
            REFUND_PREPARE_SPEC.name: REFUND_PREPARE_SPEC,
        }

    def schemas(self) -> list[dict[str, object]]:
        return [spec.model_schema() for spec in self._specs.values()]

    def execute(
        self,
        *,
        name: str,
        serialized_arguments: str,
        direct_token: str,
        subject: str,
        session_id: str,
        budget: AttemptBudget,
        events: list[AgentEvent],
        plan: ModelPlan | None = None,
        knowledge_allowed: bool = True,
        sandbox_id: str | None = None,
        trace_id: str | None = None,
        turn_id: str | None = None,
        public_query: str | None = None,
    ) -> ToolResult:
        operation = {
            KNOWLEDGE_SEARCH_SPEC.name: Operation.KNOWLEDGE_SEARCH,
            REFUND_PREPARE_SPEC.name: Operation.PENDING_ACTION_PREPARE,
        }.get(name)
        if operation is None:
            return self._execute(
                name=name,
                serialized_arguments=serialized_arguments,
                direct_token=direct_token,
                subject=subject,
                session_id=session_id,
                budget=budget,
                events=events,
                plan=plan,
                knowledge_allowed=knowledge_allowed,
                sandbox_id=sandbox_id,
                trace_id=trace_id,
                turn_id=turn_id,
                public_query=public_query,
            )
        with OperationObservation(operation, self._metrics, self._trace_sink) as observation:
            try:
                result = self._execute(
                    name=name,
                    serialized_arguments=serialized_arguments,
                    direct_token=direct_token,
                    subject=subject,
                    session_id=session_id,
                    budget=budget,
                    events=events,
                    plan=plan,
                    knowledge_allowed=knowledge_allowed,
                    sandbox_id=sandbox_id,
                    trace_id=trace_id,
                    turn_id=turn_id,
                    public_query=public_query,
                )
            except ToolBoundaryFailure as exception:
                observation.outcome = self._boundary_operation_outcome(operation, exception)
                raise
            observation.outcome = result.operation_outcome or OperationOutcome.ERROR
            return result

    def _execute(
        self,
        *,
        name: str,
        serialized_arguments: str,
        direct_token: str,
        subject: str,
        session_id: str,
        budget: AttemptBudget,
        events: list[AgentEvent],
        plan: ModelPlan | None = None,
        knowledge_allowed: bool = True,
        sandbox_id: str | None = None,
        trace_id: str | None = None,
        turn_id: str | None = None,
        public_query: str | None = None,
    ) -> ToolResult:
        spec = self._specs.get(name)
        if spec is None:
            return self._deny(name, "unknown_tool", events)
        try:
            decoded = strict_json_object(serialized_arguments.encode("utf-8"))
            arguments = spec.input_schema.model_validate(decoded)
        except (ActionJsonError, ValidationError, TypeError, UnicodeError):
            return self._deny(
                name,
                "invalid_arguments",
                events,
                operation_outcome=OperationOutcome.INVALID,
            )
        events.append(AgentEvent("TOOL_LIFECYCLE", {"tool": name, "state": "requested"}))
        if spec.authority == "elasticsearch":
            if not knowledge_allowed:
                return self._deny(
                    name,
                    "retrieval_already_decided",
                    events,
                    operation_outcome=OperationOutcome.DENIED,
                )
            if (
                (self._knowledge is None and self._faq_cache is None)
                or self._reranker is None
                or self._calibration is None
                or plan is None
                or not isinstance(arguments, KnowledgeSearchInput)
            ):
                return self._deny(
                    name,
                    "knowledge_unavailable",
                    events,
                    operation_outcome=OperationOutcome.UNAVAILABLE,
                )
            bounded_knowledge = (
                self._faq_cache.lookup(public_query)
                if self._faq_cache is not None and public_query is not None
                else None
            )
            if self._faq_cache is None or public_query is None:
                self._metrics.record_faq_lookup(FaqLevel.MAPPING, FaqResult.BYPASS)
            cache_hit = bounded_knowledge is not None
            if bounded_knowledge is None:
                if self._knowledge is None:
                    return self._deny(
                        name,
                        "knowledge_unavailable",
                        events,
                        operation_outcome=OperationOutcome.UNAVAILABLE,
                    )
                self._metrics.record_backend_decision(BackendDecision.ELASTICSEARCH_ISSUED)
                try:
                    bounded_knowledge = self._knowledge.search(arguments, budget.charge)
                except KnowledgeSearchFailure as error:
                    return self._deny(
                        name,
                        error.code,
                        events,
                        operation_outcome=OperationOutcome.UNAVAILABLE,
                    )
            else:
                self._metrics.record_backend_decision(BackendDecision.CACHE_SERVED)
            if not bounded_knowledge.results:
                decision = insufficient_decision(
                    index_version=bounded_knowledge.index_version,
                    calibration=self._calibration,
                    reason="empty_candidates",
                    candidate_count=0,
                )
                return self._retrieval_denial(name, decision, events)
            try:
                rerank_request = RerankRequest(
                    query=arguments.query,
                    rewrite=arguments.rewrite,
                    candidates=tuple(
                        RerankCandidate.from_search_result(result)
                        for result in bounded_knowledge.results
                    ),
                )
                reranked = self._reranker.rerank(plan, rerank_request, budget, events)
                decision = decide_retrieval(bounded_knowledge, reranked, self._calibration)
            except (
                AttemptBudgetExhausted,
                ProviderFailure,
                RerankValidationError,
                ValidationError,
            ):
                decision = insufficient_decision(
                    index_version=bounded_knowledge.index_version,
                    calibration=self._calibration,
                    reason="reranker_denied",
                    candidate_count=len(bounded_knowledge.results),
                )
            self._record_retrieval_decision(decision, events)
            if decision.outcome != "SUFFICIENT":
                return self._retrieval_denial(name, decision, events, record=False)
            if (
                not cache_hit
                and self._faq_cache is not None
                and public_query is not None
                and len(decision.evidence) == 1
                and decision.evidence[0].doc_type == "faq"
            ):
                evidence = decision.evidence[0]
                self._faq_cache.populate_mapping(
                    public_query, evidence.source_id, evidence.source_version
                )
            events.append(AgentEvent("TOOL_LIFECYCLE", {"tool": name, "state": "succeeded"}))
            return ToolResult(
                outcome="ok",
                model_view=decision.model_dump(by_alias=True, mode="json"),
                retrieval_decision=decision,
                operation_outcome=OperationOutcome.SUFFICIENT,
            )
        if spec.scope is None:
            raise RuntimeError("Commerce ToolSpec omitted its exact scope")
        try:
            budget.charge("identity_http", spec.scope)
            if sandbox_id is None:
                obo = self._obo.exchange(direct_token, subject, session_id, spec.scope)
            else:
                obo = self._obo.exchange(direct_token, subject, session_id, spec.scope, sandbox_id)
        except HTTPException as exception:
            if spec is REFUND_PREPARE_SPEC:
                if exception.status_code == 401:
                    return self._deny(
                        name,
                        "identity_denied",
                        events,
                        server_reason="ACTION_PREPARATION_IDENTITY_UNAUTHENTICATED",
                        operation_outcome=OperationOutcome.DENIED,
                    )
                if exception.status_code == 403:
                    return self._deny(
                        name,
                        "identity_denied",
                        events,
                        server_reason="ACTION_PREPARATION_IDENTITY_FORBIDDEN",
                        operation_outcome=OperationOutcome.DENIED,
                    )
                raise ToolBoundaryFailure(
                    status_code=503,
                    reason="ACTION_PREPARATION_IDENTITY_UNAVAILABLE",
                    detail="Action preparation unavailable",
                ) from exception
            return self._deny(name, "identity_denied", events)
        except http_client.TRANSPORT_FAILURES:
            if spec is REFUND_PREPARE_SPEC:
                raise ToolBoundaryFailure(
                    status_code=503,
                    reason="ACTION_PREPARATION_IDENTITY_UNAVAILABLE",
                    detail="Action preparation unavailable",
                ) from None
            return self._deny(name, "identity_unavailable", events)
        try:
            headers = {
                "Authorization": f"Bearer {obo}",
                "X-Support-Session-Id": session_id,
            }
            if spec is REFUND_PREPARE_SPEC:
                if trace_id is None or turn_id is None:
                    raise RuntimeError("Action preparation omitted server correlation")
                headers["X-Agent-Trace-Id"] = trace_id
                headers["X-Agent-Turn-Id"] = turn_id
            if sandbox_id is not None:
                if trace_id is None or turn_id is None:
                    raise RuntimeError("Evaluation tool call omitted server correlation")
                operation_material = "\x00".join(
                    (turn_id, name, arguments.model_dump_json(by_alias=True))
                )
                headers["X-Eval-Sandbox-Id"] = sandbox_id
                headers["X-Agent-Trace-Id"] = trace_id
                headers["X-Agent-Operation-Id"] = hashlib.sha256(
                    operation_material.encode("utf-8")
                ).hexdigest()
            response: BoundedHttpResponse | httpx.Response
            if spec is REFUND_PREPARE_SPEC:
                response = self._prepare_with_bounded_replay(
                    spec=spec,
                    arguments=arguments,
                    headers=headers,
                    name=name,
                    budget=budget,
                )
            else:
                budget.charge("tool_http", name)
                response = http_client.post(
                    f"{self._base_url}/internal/tools/{name}",
                    headers=headers,
                    json=arguments.model_dump(by_alias=True),
                    timeout=spec.timeout_seconds,
                )
        except httpx.TimeoutException:
            return self._deny(name, "timeout", events)
        except (httpx.NetworkError, httpx.ProtocolError):
            return self._deny(name, "tool_unavailable", events)
        except ActionJsonError as exception:
            raise ToolBoundaryFailure(
                status_code=502,
                reason="ACTION_PREPARATION_RESPONSE_INVALID",
                detail="Invalid action preparation response",
            ) from exception
        if spec is REFUND_PREPARE_SPEC and response.status_code in {
            400,
            401,
            403,
            404,
            408,
            409,
            422,
            429,
            502,
            503,
            504,
        }:
            reason = self._classify_prepare_rejection(response)
            if response.status_code in {400, 401, 403, 404, 409, 422}:
                return self._deny(
                    name,
                    "policy_denied",
                    events,
                    server_reason=reason,
                    operation_outcome=self._prepare_reason_outcome(reason),
                )
            raise ToolBoundaryFailure(
                status_code=429 if response.status_code == 429 else 503,
                reason=reason,
                detail=(
                    "Action preparation remains indeterminate"
                    if response.status_code == 429
                    else "Action preparation unavailable"
                ),
            )
        if response.status_code in {408, 503, 504}:
            return self._deny(name, "policy_denied", events)
        expected_statuses = {200, 201} if spec is REFUND_PREPARE_SPEC else {200}
        if response.status_code not in expected_statuses:
            raise RuntimeError("Unexpected commerce tool failure")
        try:
            document = (
                strict_json_object(response.content)
                if isinstance(response, BoundedHttpResponse)
                else response.json()
            )
            bounded = spec.output_schema.model_validate(document)
        except (ActionJsonError, ValidationError, ValueError, TypeError) as exception:
            if spec is REFUND_PREPARE_SPEC:
                raise ToolBoundaryFailure(
                    status_code=502,
                    reason="ACTION_PREPARATION_RESPONSE_INVALID",
                    detail="Invalid action preparation response",
                ) from exception
            raise RuntimeError("Invalid commerce tool response") from exception
        if spec is REFUND_PREPARE_SPEC:
            if not isinstance(arguments, RefundActionArguments) or not isinstance(
                bounded, PreparedActionResponse
            ):
                raise RuntimeError("Invalid action preparation types")
            if (response.status_code == 200) != bounded.replayed:
                raise ToolBoundaryFailure(
                    status_code=502,
                    reason="ACTION_PREPARATION_RESPONSE_INVALID",
                    detail="Invalid action preparation response",
                )
            now = datetime.now(UTC)
            if not (
                now < bounded.expires_at <= now + timedelta(seconds=MAX_ACTION_PENDING_TTL_SECONDS)
            ):
                raise ToolBoundaryFailure(
                    status_code=502,
                    reason="ACTION_PREPARATION_RESPONSE_INVALID",
                    detail="Invalid action preparation response",
                )
            if (
                bounded.order_id != arguments.order_id
                or bounded.action_type != "REFUND_REQUEST"
                or bounded.amount_minor != arguments.amount_minor
                or bounded.currency != arguments.currency
                or bounded.user_subject != subject
                or bounded.support_session_id != session_id
                or bounded.trace_id != trace_id
                or bounded.turn_id != turn_id
                or bounded.required_scope != spec.scope
                or bounded.sandbox_id != sandbox_id
                or bounded.target_version < 1
                or bounded.state != "PREPARED"
                or bounded.argument_commitment
                != action_argument_commitment(
                    "REFUND_REQUEST",
                    arguments.order_id,
                    arguments.amount_minor,
                    arguments.currency,
                )
            ):
                raise ToolBoundaryFailure(
                    status_code=409,
                    reason="ACTION_PREPARATION_DURABLE_TRUTH_INCONSISTENT",
                    detail="Action preparation conflict",
                )
            pending_action = PendingActionPayload.model_validate(
                bounded.model_dump(by_alias=True, mode="json")
            )
        else:
            pending_action = None
        model_view = (
            {
                "pendingActionId": pending_action.pending_action_id,
                "actionType": pending_action.action_type,
                "orderId": pending_action.order_id,
                "amountMinor": pending_action.amount_minor,
                "currency": pending_action.currency,
                "state": pending_action.state,
                "expiresAt": canonical_action_timestamp(pending_action.expires_at),
            }
            if pending_action is not None
            else bounded.model_dump(by_alias=True)
        )
        events.append(AgentEvent("TOOL_LIFECYCLE", {"tool": name, "state": "succeeded"}))
        return ToolResult(
            outcome="ok",
            model_view=model_view,
            pending_action=pending_action,
            operation_outcome=(
                OperationOutcome.REPLAY
                if isinstance(bounded, PreparedActionResponse) and bounded.replayed
                else OperationOutcome.SUCCESS
            )
            if spec is REFUND_PREPARE_SPEC
            else None,
        )

    def _prepare_with_bounded_replay(
        self,
        *,
        spec: ToolSpec,
        arguments: BaseModel,
        headers: dict[str, str],
        name: str,
        budget: AttemptBudget,
    ) -> BoundedHttpResponse:
        request_body = {
            "actionType": "REFUND_REQUEST",
            "arguments": arguments.model_dump(by_alias=True),
        }
        for attempt in range(2):
            budget.charge("tool_http", name)
            try:
                response = bounded_http_post(
                    f"{self._base_url}/internal/tools/actions/prepare",
                    headers=headers,
                    json=request_body,
                    timeout=spec.timeout_seconds,
                )
            except httpx.TimeoutException as exception:
                if attempt == 0:
                    continue
                raise ToolBoundaryFailure(
                    status_code=503,
                    reason="ACTION_PREPARATION_COMMERCE_TIMEOUT",
                    detail="Action preparation unavailable",
                ) from exception
            except (httpx.NetworkError, httpx.ProtocolError) as exception:
                if attempt == 0:
                    continue
                raise ToolBoundaryFailure(
                    status_code=503,
                    reason="ACTION_PREPARATION_COMMERCE_UNAVAILABLE",
                    detail="Action preparation unavailable",
                ) from exception
            if response.status_code in {408, 429, 502, 503, 504} and attempt == 0:
                continue
            return response
        raise RuntimeError("Bounded action preparation replay did not terminate")

    @staticmethod
    def _prepare_reason_outcome(reason: str) -> OperationOutcome:
        if reason in {
            "ACTION_PREPARATION_IDENTITY_UNAUTHENTICATED",
            "ACTION_PREPARATION_IDENTITY_FORBIDDEN",
            "ACTION_PREPARATION_COMMERCE_UNAUTHENTICATED",
            "ACTION_PREPARATION_COMMERCE_FORBIDDEN",
        }:
            return OperationOutcome.DENIED
        if reason == "ACTION_PREPARATION_COMMERCE_VALIDATION_REJECTED":
            return OperationOutcome.REJECTED
        if reason == "ACTION_PREPARATION_TARGET_NOT_FOUND":
            return OperationOutcome.NOT_FOUND
        if reason in {
            "ACTION_PREPARATION_INTENT_CONFLICT",
            "ACTION_PREPARATION_DURABLE_TRUTH_INCONSISTENT",
        }:
            return OperationOutcome.CONFLICT
        if reason == "ACTION_PREPARATION_COMMERCE_INDETERMINATE":
            return OperationOutcome.INDETERMINATE
        if reason == "ACTION_PREPARATION_RESPONSE_INVALID":
            return OperationOutcome.INVALID
        return OperationOutcome.UNAVAILABLE

    @classmethod
    def _boundary_operation_outcome(
        cls, operation: Operation, exception: ToolBoundaryFailure
    ) -> OperationOutcome:
        if operation is Operation.PENDING_ACTION_PREPARE:
            return cls._prepare_reason_outcome(exception.reason)
        return OperationOutcome.ERROR

    @staticmethod
    def _classify_prepare_rejection(response: BoundedHttpResponse | httpx.Response) -> str:
        content = response.content
        try:
            document = strict_json_object(content)
        except ActionJsonError as exception:
            raise ToolBoundaryFailure(
                status_code=502,
                reason="ACTION_PREPARATION_RESPONSE_INVALID",
                detail="Invalid action preparation response",
            ) from exception
        status = response.status_code
        if status in {401, 403}:
            expected = "Unauthorized" if status == 401 else "Forbidden"
            if document != {"error": expected}:
                raise ToolBoundaryFailure(
                    status_code=502,
                    reason="ACTION_PREPARATION_RESPONSE_INVALID",
                    detail="Invalid action preparation response",
                )
            return {
                401: "ACTION_PREPARATION_COMMERCE_UNAUTHENTICATED",
                403: "ACTION_PREPARATION_COMMERCE_FORBIDDEN",
            }[status]
        if status in {408, 502, 503, 504} and document == {"error": "Service unavailable"}:
            return (
                "ACTION_PREPARATION_COMMERCE_TIMEOUT"
                if status in {408, 504}
                else "ACTION_PREPARATION_COMMERCE_UNAVAILABLE"
            )
        if set(document) != {"category", "message"}:
            raise ToolBoundaryFailure(
                status_code=502,
                reason="ACTION_PREPARATION_RESPONSE_INVALID",
                detail="Invalid action preparation response",
            )
        category = document.get("category")
        message = document.get("message")
        if not isinstance(message, str) or not 1 <= len(message) <= 160:
            raise ToolBoundaryFailure(
                status_code=502,
                reason="ACTION_PREPARATION_RESPONSE_INVALID",
                detail="Invalid action preparation response",
            )
        expected_categories: dict[int, frozenset[str]] = {
            400: frozenset({"VALIDATION"}),
            404: frozenset({"NOT_FOUND"}),
            408: frozenset({"DEPENDENCY_UNAVAILABLE"}),
            409: frozenset({"CONFLICT", "INCONSISTENT_DURABLE_STATE"}),
            422: frozenset({"VALIDATION"}),
            429: frozenset({"INDETERMINATE"}),
            502: frozenset({"DEPENDENCY_UNAVAILABLE"}),
            503: frozenset({"DEPENDENCY_UNAVAILABLE"}),
            504: frozenset({"DEPENDENCY_UNAVAILABLE"}),
        }
        if type(category) is not str or category not in expected_categories.get(
            status, frozenset()
        ):
            raise ToolBoundaryFailure(
                status_code=502,
                reason="ACTION_PREPARATION_RESPONSE_INVALID",
                detail="Invalid action preparation response",
            )
        if status in {400, 422}:
            return "ACTION_PREPARATION_COMMERCE_VALIDATION_REJECTED"
        if status == 404:
            return "ACTION_PREPARATION_TARGET_NOT_FOUND"
        if status == 409:
            return (
                "ACTION_PREPARATION_INTENT_CONFLICT"
                if category == "CONFLICT"
                else "ACTION_PREPARATION_DURABLE_TRUTH_INCONSISTENT"
            )
        if status in {408, 504}:
            return "ACTION_PREPARATION_COMMERCE_TIMEOUT"
        if status == 429:
            return "ACTION_PREPARATION_COMMERCE_INDETERMINATE"
        return "ACTION_PREPARATION_COMMERCE_UNAVAILABLE"

    @staticmethod
    def _deny(
        name: str,
        reason: str,
        events: list[AgentEvent],
        *,
        server_reason: str | None = None,
        operation_outcome: OperationOutcome | None = None,
    ) -> ToolResult:
        payload: dict[str, object] = {
            "tool": name[:64],
            "reason": reason,
            "outcome": "deny_with_feedback",
        }
        if server_reason is not None:
            if server_reason not in TOOL_BOUNDARY_FAILURE_REASONS:
                raise RuntimeError("Unregistered tool denial producer")
            payload["producer"] = server_reason
        events.append(
            AgentEvent(
                "TOOL_DENIED",
                payload,
            )
        )
        return ToolResult(
            outcome="deny_with_feedback",
            model_view={"outcome": "deny_with_feedback", "reason": reason},
            server_reason=server_reason,
            operation_outcome=operation_outcome,
        )

    @staticmethod
    def _record_retrieval_decision(decision: RetrievalDecision, events: list[AgentEvent]) -> None:
        events.append(
            AgentEvent(
                "RETRIEVAL_DECISION",
                {
                    "indexVersion": decision.index_version,
                    "calibrationVersion": decision.calibration_version,
                    "outcome": decision.outcome,
                    "reason": decision.reason,
                    "candidateCount": decision.candidate_count,
                    "evidenceCount": len(decision.evidence),
                },
            )
        )

    @classmethod
    def _retrieval_denial(
        cls,
        name: str,
        decision: RetrievalDecision,
        events: list[AgentEvent],
        *,
        record: bool = True,
    ) -> ToolResult:
        if record:
            cls._record_retrieval_decision(decision, events)
        events.append(
            AgentEvent(
                "TOOL_DENIED",
                {
                    "tool": name,
                    "reason": decision.reason,
                    "outcome": "deny_with_feedback",
                },
            )
        )
        return ToolResult(
            outcome="deny_with_feedback",
            model_view={"outcome": "deny_with_feedback", "reason": decision.reason},
            retrieval_decision=decision,
            operation_outcome=OperationOutcome.INSUFFICIENT,
        )


class BoundedAgent:
    """The one production ReAct agent for this slice."""

    def __init__(
        self,
        rule_router: RuleRouter,
        model_router: ModelRouter,
        model: LiteLlmClient,
        tools: ToolAdapter,
    ) -> None:
        self._rule_router = rule_router
        self._model_router = model_router
        self._model = model
        self._tools = tools

    def run(
        self,
        *,
        message: str,
        direct_token: str,
        subject: str,
        session_id: str,
        trace_id: str,
        turn_id: str,
        sandbox_id: str | None = None,
    ) -> AgentRunResult:
        events: list[AgentEvent] = []
        request_reasons: list[str] = []
        signals = self._rule_router.signals(message)
        plan = self._model_router.plan(signals)
        events.append(
            AgentEvent(
                "ROUTING_DECISION",
                {
                    "signals": signals.evidence(),
                    "tier": plan.tier,
                    "attemptLimit": plan.attempt_limit,
                },
            )
        )
        budget = AttemptBudget(plan.attempt_limit, events)
        messages = [{"role": "user", "content": message}]
        retrieval_decision: RetrievalDecision | None = None
        try:
            while True:
                reply = self._model.complete(plan, messages, self._tools.schemas(), budget, events)
                if reply.content is not None:
                    events.append(AgentEvent("AGENT_OUTCOME", {"outcome": "completed"}))
                    return AgentRunResult(
                        reply.content,
                        "completed",
                        tuple(events),
                        retrieval_decision,
                        request_reasons=tuple(request_reasons),
                    )
                if reply.tool_name is None or reply.tool_arguments is None:
                    raise RuntimeError("Invalid model tool request")
                result = self._tools.execute(
                    name=reply.tool_name,
                    serialized_arguments=reply.tool_arguments,
                    direct_token=direct_token,
                    subject=subject,
                    session_id=session_id,
                    budget=budget,
                    events=events,
                    plan=plan,
                    knowledge_allowed=retrieval_decision is None,
                    sandbox_id=sandbox_id,
                    trace_id=trace_id,
                    turn_id=turn_id,
                    public_query=message,
                )
                if result.server_reason is not None:
                    request_reasons.append(result.server_reason)
                if result.retrieval_decision is not None:
                    retrieval_decision = result.retrieval_decision
                    if retrieval_decision.outcome != "SUFFICIENT":
                        events.append(AgentEvent("AGENT_OUTCOME", {"outcome": "retrieval_denied"}))
                        return AgentRunResult(
                            "I do not have sufficient grounded evidence to answer that request.",
                            "retrieval_denied",
                            tuple(events),
                            retrieval_decision,
                            request_reasons=tuple(request_reasons),
                        )
                if result.pending_action is not None:
                    events.append(
                        AgentEvent(
                            "ACTION_PREPARED",
                            {
                                "pendingActionId": result.pending_action.pending_action_id,
                                "actionType": result.pending_action.action_type,
                                "argumentCommitment": result.pending_action.argument_commitment,
                                "targetVersion": result.pending_action.target_version,
                                "expiresAt": canonical_action_timestamp(
                                    result.pending_action.expires_at
                                ),
                            },
                        )
                    )
                    events.append(AgentEvent("AGENT_OUTCOME", {"outcome": "action_pending"}))
                    return AgentRunResult(
                        "Please confirm or decline the prepared refund request.",
                        "action_pending",
                        tuple(events),
                        pending_action=result.pending_action,
                        request_reasons=tuple(request_reasons),
                    )
                if (
                    reply.tool_name == KNOWLEDGE_SEARCH_SPEC.name
                    and retrieval_decision is None
                    and result.outcome == "deny_with_feedback"
                ):
                    events.append(AgentEvent("AGENT_OUTCOME", {"outcome": "retrieval_denied"}))
                    return AgentRunResult(
                        "I do not have sufficient grounded evidence to answer that request.",
                        "retrieval_denied",
                        tuple(events),
                        request_reasons=tuple(request_reasons),
                    )
                messages.append({"role": "assistant", "content": "tool request"})
                messages.append(
                    {
                        "role": "tool",
                        "content": json.dumps(result.model_view, separators=(",", ":")),
                    }
                )
        except AttemptBudgetExhausted:
            events.append(AgentEvent("AGENT_OUTCOME", {"outcome": "budget_exhausted"}))
            return AgentRunResult(
                "I could not complete this request within the bounded attempt limit.",
                "budget_exhausted",
                tuple(events),
                retrieval_decision,
                request_reasons=tuple(request_reasons),
            )
        except ProviderFailure:
            events.append(AgentEvent("AGENT_OUTCOME", {"outcome": "provider_denied"}))
            return AgentRunResult(
                "I could not complete this request through the approved model route.",
                "provider_denied",
                tuple(events),
                retrieval_decision,
                request_reasons=tuple(request_reasons),
            )
