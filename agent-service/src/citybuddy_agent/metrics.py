"""Closed, recording-only Agent metrics backed by an isolated registry."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol

from prometheus_client import CollectorRegistry, Counter, Histogram, disable_created_metrics
from prometheus_client.exposition import CONTENT_TYPE_LATEST, generate_latest


class Operation(StrEnum):
    KNOWLEDGE_SEARCH = "knowledge_search"
    CHAT_TURN = "chat_turn"
    PENDING_ACTION_PREPARE = "pending_action_prepare"
    PENDING_ACTION_CLARIFICATION = "pending_action_clarification"
    PENDING_ACTION_CONFIRM = "pending_action_confirm"
    PENDING_ACTION_DECLINE = "pending_action_decline"
    PENDING_ACTION_EXPIRY = "pending_action_expiry"


class OperationOutcome(StrEnum):
    SUCCESS = "success"
    REPLAY = "replay"
    PENDING = "pending"
    CLARIFICATION = "clarification"
    CONFIRMED = "confirmed"
    DECLINED = "declined"
    EXPIRED = "expired"
    SUFFICIENT = "sufficient"
    INSUFFICIENT = "insufficient"
    REJECTED = "rejected"
    DENIED = "denied"
    INVALID = "invalid"
    CONFLICT = "conflict"
    NOT_FOUND = "not_found"
    UNAVAILABLE = "unavailable"
    INDETERMINATE = "indeterminate"
    BUDGET_EXHAUSTED = "budget_exhausted"
    PROVIDER_DENIED = "provider_denied"
    RETRIEVAL_DENIED = "retrieval_denied"
    ERROR = "error"


class FaqLevel(StrEnum):
    MAPPING = "mapping"
    ANSWER = "answer"


class FaqResult(StrEnum):
    HIT = "hit"
    MISS = "miss"
    BYPASS = "bypass"
    UNAVAILABLE = "unavailable"
    INVALID = "invalid"


class BackendDecision(StrEnum):
    CACHE_SERVED = "cache_served"
    ELASTICSEARCH_ISSUED = "elasticsearch_issued"


class ProviderRole(StrEnum):
    PRIMARY = "primary"
    FALLBACK = "fallback"
    RERANKER = "reranker"


class ProviderOutcome(StrEnum):
    SUCCESS = "success"
    TRANSIENT = "transient"
    DENIED = "denied"
    INVALID = "invalid"
    ERROR = "error"


class TraceExportOutcome(StrEnum):
    SENT = "sent"
    FAILED = "failed"
    DROPPED = "dropped"


OPERATION_LABELS = frozenset(
    {
        (Operation.KNOWLEDGE_SEARCH, outcome)
        for outcome in (
            OperationOutcome.SUFFICIENT,
            OperationOutcome.INSUFFICIENT,
            OperationOutcome.DENIED,
            OperationOutcome.INVALID,
            OperationOutcome.UNAVAILABLE,
            OperationOutcome.ERROR,
        )
    }
    | {
        (Operation.CHAT_TURN, outcome)
        for outcome in (
            OperationOutcome.REPLAY,
            OperationOutcome.SUCCESS,
            OperationOutcome.PENDING,
            OperationOutcome.CLARIFICATION,
            OperationOutcome.CONFIRMED,
            OperationOutcome.DECLINED,
            OperationOutcome.EXPIRED,
            OperationOutcome.RETRIEVAL_DENIED,
            OperationOutcome.BUDGET_EXHAUSTED,
            OperationOutcome.PROVIDER_DENIED,
            OperationOutcome.DENIED,
            OperationOutcome.CONFLICT,
            OperationOutcome.UNAVAILABLE,
            OperationOutcome.ERROR,
        )
    }
    | {
        (Operation.PENDING_ACTION_PREPARE, outcome)
        for outcome in (
            OperationOutcome.SUCCESS,
            OperationOutcome.REPLAY,
            OperationOutcome.REJECTED,
            OperationOutcome.DENIED,
            OperationOutcome.INVALID,
            OperationOutcome.CONFLICT,
            OperationOutcome.NOT_FOUND,
            OperationOutcome.UNAVAILABLE,
            OperationOutcome.INDETERMINATE,
            OperationOutcome.ERROR,
        )
    }
    | {
        (operation, outcome)
        for operation, success in (
            (Operation.PENDING_ACTION_CLARIFICATION, OperationOutcome.CLARIFICATION),
            (Operation.PENDING_ACTION_CONFIRM, OperationOutcome.CONFIRMED),
            (Operation.PENDING_ACTION_DECLINE, OperationOutcome.DECLINED),
            (Operation.PENDING_ACTION_EXPIRY, OperationOutcome.EXPIRED),
        )
        for outcome in (
            success,
            OperationOutcome.UNAVAILABLE,
            OperationOutcome.CONFLICT,
            OperationOutcome.ERROR,
        )
    }
)
FAQ_LABELS = frozenset(
    {
        (FaqLevel.MAPPING, result)
        for result in (
            FaqResult.HIT,
            FaqResult.MISS,
            FaqResult.BYPASS,
            FaqResult.UNAVAILABLE,
            FaqResult.INVALID,
        )
    }
    | {(FaqLevel.ANSWER, result) for result in (FaqResult.HIT, FaqResult.MISS, FaqResult.INVALID)}
)
PROVIDER_LABELS = frozenset((role, outcome) for role in ProviderRole for outcome in ProviderOutcome)
OPERATION_BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
PROMETHEUS_CONTENT_TYPE = CONTENT_TYPE_LATEST


class CityBuddyMetrics(Protocol):
    def observe_operation(
        self, operation: Operation, outcome: OperationOutcome, duration_seconds: float
    ) -> None: ...

    def record_faq_lookup(self, level: FaqLevel, result: FaqResult) -> None: ...

    def record_backend_decision(self, decision: BackendDecision) -> None: ...

    def record_provider_attempt(self, role: ProviderRole, outcome: ProviderOutcome) -> None: ...

    def record_trace_export(self, outcome: TraceExportOutcome) -> None: ...


class NoopCityBuddyMetrics:
    def observe_operation(
        self, operation: Operation, outcome: OperationOutcome, duration_seconds: float
    ) -> None:
        return None

    def record_faq_lookup(self, level: FaqLevel, result: FaqResult) -> None:
        return None

    def record_backend_decision(self, decision: BackendDecision) -> None:
        return None

    def record_provider_attempt(self, role: ProviderRole, outcome: ProviderOutcome) -> None:
        return None

    def record_trace_export(self, outcome: TraceExportOutcome) -> None:
        return None


class SafeCityBuddyMetrics:
    """Prevent any recorder failure from crossing into business execution."""

    def __init__(self, delegate: CityBuddyMetrics) -> None:
        self._delegate = delegate

    def observe_operation(
        self, operation: Operation, outcome: OperationOutcome, duration_seconds: float
    ) -> None:
        self._call(self._delegate.observe_operation, operation, outcome, duration_seconds)

    def record_faq_lookup(self, level: FaqLevel, result: FaqResult) -> None:
        self._call(self._delegate.record_faq_lookup, level, result)

    def record_backend_decision(self, decision: BackendDecision) -> None:
        self._call(self._delegate.record_backend_decision, decision)

    def record_provider_attempt(self, role: ProviderRole, outcome: ProviderOutcome) -> None:
        self._call(self._delegate.record_provider_attempt, role, outcome)

    def record_trace_export(self, outcome: TraceExportOutcome) -> None:
        self._call(self._delegate.record_trace_export, outcome)

    @staticmethod
    def _call(function: Callable[..., None], *arguments: object) -> None:
        try:
            function(*arguments)
        except Exception:
            return None


class PrometheusCityBuddyMetrics:
    def __init__(self) -> None:
        disable_created_metrics()  # type: ignore[no-untyped-call]
        self._registry = CollectorRegistry(auto_describe=True)
        self._operation_requests = Counter(
            "citybuddy_agent_operation_requests_total",
            "Completed eligible Agent operation observations.",
            ("operation", "outcome"),
            registry=self._registry,
        )
        self._operation_duration = Histogram(
            "citybuddy_agent_operation_duration_seconds",
            "Monotonic duration of eligible Agent operations.",
            ("operation", "outcome"),
            buckets=OPERATION_BUCKETS,
            registry=self._registry,
        )
        self._faq_lookups = Counter(
            "citybuddy_agent_faq_cache_lookups_total",
            "Terminal FAQ cache observations at each reached layer.",
            ("level", "result"),
            registry=self._registry,
        )
        self._backend_decisions = Counter(
            "citybuddy_knowledge_backend_decisions_total",
            "Completed FAQ-cache versus issued Elasticsearch backend choices.",
            ("decision",),
            registry=self._registry,
        )
        self._provider_attempts = Counter(
            "citybuddy_agent_model_request_attempts_total",
            "Actual outbound model HTTP attempts by positional role and bounded outcome.",
            ("role", "outcome"),
            registry=self._registry,
        )
        self._trace_exports = Counter(
            "citybuddy_agent_trace_exports_total",
            "Enabled custom trace mirror export results.",
            ("outcome",),
            registry=self._registry,
        )

    def observe_operation(
        self, operation: Operation, outcome: OperationOutcome, duration_seconds: float
    ) -> None:
        bounded = self._operation_labels(operation, outcome)
        if bounded is None:
            return
        labels = tuple(value.value for value in bounded)
        self._operation_requests.labels(*labels).inc()
        self._operation_duration.labels(*labels).observe(max(0.0, duration_seconds))

    def record_faq_lookup(self, level: FaqLevel, result: FaqResult) -> None:
        try:
            bounded = (FaqLevel(level), FaqResult(result))
        except (TypeError, ValueError):
            return
        if bounded in FAQ_LABELS:
            self._faq_lookups.labels(*(value.value for value in bounded)).inc()

    def record_backend_decision(self, decision: BackendDecision) -> None:
        try:
            bounded = BackendDecision(decision)
        except (TypeError, ValueError):
            return
        self._backend_decisions.labels(bounded.value).inc()

    def record_provider_attempt(self, role: ProviderRole, outcome: ProviderOutcome) -> None:
        try:
            bounded_role = ProviderRole(role)
            bounded_outcome = ProviderOutcome(outcome)
        except (TypeError, ValueError):
            try:
                bounded_role = ProviderRole(role)
            except (TypeError, ValueError):
                return
            bounded_outcome = ProviderOutcome.ERROR
        if (bounded_role, bounded_outcome) in PROVIDER_LABELS:
            self._provider_attempts.labels(bounded_role.value, bounded_outcome.value).inc()

    def record_trace_export(self, outcome: TraceExportOutcome) -> None:
        try:
            bounded = TraceExportOutcome(outcome)
        except (TypeError, ValueError):
            return
        self._trace_exports.labels(bounded.value).inc()

    def render(self) -> bytes:
        return generate_latest(self._registry)

    @staticmethod
    def _operation_labels(
        operation: Operation, outcome: OperationOutcome
    ) -> tuple[Operation, OperationOutcome] | None:
        try:
            bounded_operation = Operation(operation)
            bounded_outcome = OperationOutcome(outcome)
        except (TypeError, ValueError):
            try:
                bounded_operation = Operation(operation)
            except (TypeError, ValueError):
                return None
            bounded_outcome = OperationOutcome.ERROR
        bounded = (bounded_operation, bounded_outcome)
        if bounded in OPERATION_LABELS:
            return bounded
        error = (bounded_operation, OperationOutcome.ERROR)
        return error if error in OPERATION_LABELS else None


@dataclass(frozen=True)
class MetricsRuntime:
    recorder: CityBuddyMetrics
    _renderer: Callable[[], bytes] | None = None

    def render(self) -> bytes:
        if self._renderer is None:
            raise RuntimeError("Metrics are disabled")
        return self._renderer()


def create_metrics_runtime(enabled: bool) -> MetricsRuntime:
    if not enabled:
        return MetricsRuntime(NoopCityBuddyMetrics())
    prometheus = PrometheusCityBuddyMetrics()
    return MetricsRuntime(SafeCityBuddyMetrics(prometheus), prometheus.render)
