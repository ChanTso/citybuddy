"""Bounded single-agent control, model routing, and ToolSpec mediation."""

from __future__ import annotations

import json
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal, Protocol

import httpx
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from .knowledge import (
    KnowledgeSearch,
    KnowledgeSearchFailure,
    KnowledgeSearchInput,
    KnowledgeSearchOutput,
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
    ) -> AgentRunResult: ...


class OboExchange(Protocol):
    def exchange(self, direct_token: str, subject: str, session_id: str, scope: str) -> str: ...


class AttemptBudgetExhausted(Exception):
    """The one turn-scoped attempt budget has no remaining capacity."""


class ProviderFailure(Exception):
    def __init__(self, *, transient: bool) -> None:
        super().__init__("Provider request failed")
        self.transient = transient


class CircuitOpen(Exception):
    """The selected provider circuit is not currently admitting work."""


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

    def __init__(self, url: str, circuits: ProviderCircuits) -> None:
        self._url = url.rstrip("/")
        self._circuits = circuits

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
        for route in plan.routes:
            attempts_for_route = 0
            while True:
                transient_failure: ProviderFailure | None = None
                admitted = False
                budget.charge("model_http", route.provider_key)
                attempts_for_route += 1
                try:
                    self._circuits.admit(route.provider_key, events)
                    admitted = True
                    response = httpx.post(
                        f"{self._url}/v1/chat/completions",
                        json={"model": route.role_alias, "messages": messages, "tools": tools},
                        timeout=2.0,
                    )
                    if response.status_code in {408, 429, 502, 503, 504}:
                        raise ProviderFailure(transient=True)
                    if response.status_code != 200:
                        raise ProviderFailure(transient=False)
                    try:
                        payload = response.json()
                    except ValueError as exception:
                        raise ProviderFailure(transient=False) from exception
                    reply = self._parse(payload)
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
                except (httpx.TimeoutException, httpx.NetworkError):
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
            budget.charge("reranker_http", route.provider_key)
            try:
                self._circuits.admit(route.provider_key, events)
                admitted = True
                response = httpx.post(
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
                    raise ProviderFailure(transient=True)
                if response.status_code != 200:
                    raise ProviderFailure(transient=False)
                try:
                    payload = response.json()
                except ValueError as exception:
                    raise ProviderFailure(transient=False) from exception
                output = self._parse_rerank(payload)
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
            except (httpx.TimeoutException, httpx.NetworkError):
                failure = ProviderFailure(transient=True)
            except CircuitOpen:
                failure = ProviderFailure(transient=True)
            except ProviderFailure as exception:
                failure = exception
            finally:
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
    risk: Literal["read"]
    timeout_seconds: float
    idempotency: Literal["read-only"]
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


@dataclass(frozen=True)
class ToolResult:
    outcome: Literal["ok", "deny_with_feedback"]
    model_view: dict[str, object]
    retrieval_decision: RetrievalDecision | None = None


class ToolAdapter:
    def __init__(
        self,
        base_url: str,
        obo: OboExchange,
        knowledge: KnowledgeSearch | None = None,
        reranker: Reranker | None = None,
        calibration: SufficiencyCalibration | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._obo = obo
        self._knowledge = knowledge
        self._reranker = reranker
        self._calibration = calibration
        self._specs = {
            CATALOG_PRODUCT_SPEC.name: CATALOG_PRODUCT_SPEC,
            KNOWLEDGE_SEARCH_SPEC.name: KNOWLEDGE_SEARCH_SPEC,
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
    ) -> ToolResult:
        spec = self._specs.get(name)
        if spec is None:
            return self._deny(name, "unknown_tool", events)
        try:
            decoded = json.loads(serialized_arguments)
            arguments = spec.input_schema.model_validate(decoded)
        except (json.JSONDecodeError, ValidationError, TypeError):
            return self._deny(name, "invalid_arguments", events)
        events.append(AgentEvent("TOOL_LIFECYCLE", {"tool": name, "state": "requested"}))
        if spec.authority == "elasticsearch":
            if not knowledge_allowed:
                return self._deny(name, "retrieval_already_decided", events)
            if (
                self._knowledge is None
                or self._reranker is None
                or self._calibration is None
                or plan is None
                or not isinstance(arguments, KnowledgeSearchInput)
            ):
                return self._deny(name, "knowledge_unavailable", events)
            try:
                bounded_knowledge = self._knowledge.search(arguments, budget.charge)
            except KnowledgeSearchFailure as error:
                return self._deny(name, error.code, events)
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
            events.append(AgentEvent("TOOL_LIFECYCLE", {"tool": name, "state": "succeeded"}))
            return ToolResult(
                outcome="ok",
                model_view=decision.model_dump(by_alias=True, mode="json"),
                retrieval_decision=decision,
            )
        if spec.scope is None:
            raise RuntimeError("Commerce ToolSpec omitted its exact scope")
        try:
            budget.charge("identity_http", spec.scope)
            obo = self._obo.exchange(direct_token, subject, session_id, spec.scope)
        except HTTPException:
            return self._deny(name, "identity_denied", events)
        except (httpx.TimeoutException, httpx.NetworkError):
            return self._deny(name, "identity_unavailable", events)
        budget.charge("tool_http", name)
        try:
            response = httpx.post(
                f"{self._base_url}/internal/tools/{name}",
                headers={
                    "Authorization": f"Bearer {obo}",
                    "X-Support-Session-Id": session_id,
                },
                json=arguments.model_dump(by_alias=True),
                timeout=spec.timeout_seconds,
            )
        except httpx.TimeoutException:
            return self._deny(name, "timeout", events)
        except httpx.NetworkError:
            return self._deny(name, "tool_unavailable", events)
        if response.status_code in {400, 401, 403, 404, 408, 422, 504}:
            return self._deny(name, "policy_denied", events)
        if response.status_code != 200:
            raise RuntimeError("Unexpected commerce tool failure")
        try:
            bounded = spec.output_schema.model_validate(response.json())
        except (ValidationError, ValueError, TypeError) as exception:
            raise RuntimeError("Invalid commerce tool response") from exception
        model_view = bounded.model_dump(by_alias=True)
        events.append(AgentEvent("TOOL_LIFECYCLE", {"tool": name, "state": "succeeded"}))
        return ToolResult(outcome="ok", model_view=model_view)

    @staticmethod
    def _deny(name: str, reason: str, events: list[AgentEvent]) -> ToolResult:
        events.append(
            AgentEvent(
                "TOOL_DENIED",
                {"tool": name[:64], "reason": reason, "outcome": "deny_with_feedback"},
            )
        )
        return ToolResult(
            outcome="deny_with_feedback",
            model_view={"outcome": "deny_with_feedback", "reason": reason},
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
    ) -> AgentRunResult:
        del trace_id, turn_id
        events: list[AgentEvent] = []
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
                )
                if result.retrieval_decision is not None:
                    retrieval_decision = result.retrieval_decision
                    if retrieval_decision.outcome != "SUFFICIENT":
                        events.append(AgentEvent("AGENT_OUTCOME", {"outcome": "retrieval_denied"}))
                        return AgentRunResult(
                            "I do not have sufficient grounded evidence to answer that request.",
                            "retrieval_denied",
                            tuple(events),
                            retrieval_decision,
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
            )
        except ProviderFailure:
            events.append(AgentEvent("AGENT_OUTCOME", {"outcome": "provider_denied"}))
            return AgentRunResult(
                "I could not complete this request through the approved model route.",
                "provider_denied",
                tuple(events),
                retrieval_decision,
            )
