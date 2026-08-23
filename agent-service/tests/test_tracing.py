from __future__ import annotations

import json
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

import httpx
import pytest
from citybuddy_agent import http_client
from citybuddy_agent.metrics import (
    NoopCityBuddyMetrics,
    Operation,
    OperationOutcome,
    TraceExportOutcome,
)
from citybuddy_agent.tracing import (
    TRACE_QUEUE_SIZE,
    TRACE_SHUTDOWN_SECONDS,
    BoundedHttpTraceSink,
    NoopTraceSink,
    OperationObservation,
    TraceEnvelope,
    create_trace_sink,
    validate_trace_url,
)


class RecordingMetrics(NoopCityBuddyMetrics):
    def __init__(self) -> None:
        self.trace_exports: list[TraceExportOutcome] = []
        self.operations: list[tuple[Operation, OperationOutcome, float]] = []
        self._lock = threading.Lock()

    def observe_operation(
        self, operation: Operation, outcome: OperationOutcome, duration_seconds: float
    ) -> None:
        with self._lock:
            self.operations.append((operation, outcome, duration_seconds))

    def record_trace_export(self, outcome: TraceExportOutcome) -> None:
        with self._lock:
            self.trace_exports.append(outcome)


class ExplodingMetrics(RecordingMetrics):
    def record_trace_export(self, outcome: TraceExportOutcome) -> None:
        raise RuntimeError("private recorder failure")


def wait_for(predicate: Any, timeout: float = 1.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.005)
    raise AssertionError("bounded asynchronous observation did not arrive")


def envelope() -> TraceEnvelope:
    return TraceEnvelope.create(Operation.CHAT_TURN, OperationOutcome.SUCCESS, 0.125)


def test_noop_has_no_thread_queue_network_or_export_metric() -> None:
    before = {thread.ident for thread in threading.enumerate()}
    metrics = RecordingMetrics()
    sink = create_trace_sink("", metrics)

    assert isinstance(sink, NoopTraceSink)
    sink.emit(envelope())
    sink.close()

    assert {thread.ident for thread in threading.enumerate()} == before
    assert not hasattr(sink, "_queue")
    assert metrics.trace_exports == []


@pytest.mark.parametrize(
    "url",
    [
        "ftp://trace.test/export",
        "http:///missing-host",
        "http://user@trace.test/export",
        "http://trace.test/export?secret=yes",
        "http://trace.test/export#fragment",
        "http://trace.test:99999/export",
        "http://trace.test:invalid/export",
        "http://trace.test/export with-space",
        "http://" + "a" * 2048,
    ],
)
def test_trace_url_validation_rejects_unbounded_or_ambiguous_destinations(url: str) -> None:
    with pytest.raises(ValueError, match="Trace export URL is invalid"):
        validate_trace_url(url)


def test_enabled_exporter_receives_exact_identifier_free_envelope(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    received: list[tuple[tuple[object, ...], dict[str, object]]] = []

    def stream(*args: object, **kwargs: object) -> FakeStreamResponse:
        received.append((args, kwargs))
        return FakeStreamResponse(204, b"")

    monkeypatch.setattr(http_client, "stream", stream)
    metrics = RecordingMetrics()
    sink = BoundedHttpTraceSink("http://trace.test/export", metrics)
    try:
        sink.emit(envelope())
        wait_for(lambda: metrics.trace_exports == [TraceExportOutcome.SENT])
    finally:
        sink.close()

    assert len(received) == 1
    args, kwargs = received[0]
    assert args == ("POST", "http://trace.test/export")
    assert kwargs["headers"] == {"Content-Type": "application/json"}
    assert isinstance(kwargs["timeout"], httpx.Timeout)
    raw_payload = kwargs["content"]
    assert isinstance(raw_payload, bytes)
    document = json.loads(raw_payload)
    assert set(document) == {
        "schemaVersion",
        "service",
        "spanName",
        "outcome",
        "durationMs",
        "occurredAt",
    }
    assert document == {
        "schemaVersion": "citybuddy-trace-v1",
        "service": "agent",
        "spanName": "chat.request",
        "outcome": "success",
        "durationMs": 125,
        "occurredAt": document["occurredAt"],
    }
    assert document["occurredAt"].endswith("Z")
    serialized = raw_payload.decode("utf-8")
    for forbidden in (
        "traceId",
        "sessionId",
        "turnId",
        "userSubject",
        "sandboxId",
        "orderId",
        "pendingActionId",
        "private prompt",
        "private reply",
        "secret-token",
        "https://exporter.private",
    ):
        assert forbidden not in serialized


class FakeStreamResponse:
    def __init__(self, status_code: int, body: bytes) -> None:
        self.status_code = status_code
        self.body = body

    def __enter__(self) -> FakeStreamResponse:
        return self

    def __exit__(self, *args: object) -> None:
        return None

    def iter_raw(self, chunk_size: int) -> Iterator[bytes]:
        assert chunk_size == 1
        if self.body:
            yield self.body[:1]


@pytest.mark.parametrize(
    ("status", "body"),
    [(204, b"unexpected"), (200, b""), (500, b"private response body")],
)
def test_only_empty_204_is_sent_and_response_body_is_bounded(
    monkeypatch: pytest.MonkeyPatch, status: int, body: bytes
) -> None:
    calls = 0

    def stream(*args: object, **kwargs: object) -> FakeStreamResponse:
        nonlocal calls
        del args, kwargs
        calls += 1
        return FakeStreamResponse(status, body)

    monkeypatch.setattr(http_client, "stream", stream)
    metrics = RecordingMetrics()
    sink = BoundedHttpTraceSink("http://trace.test/export", metrics)
    sink.emit(envelope())
    wait_for(lambda: len(metrics.trace_exports) == 1)
    sink.close()

    assert calls == 1
    assert metrics.trace_exports == [TraceExportOutcome.FAILED]


@pytest.mark.parametrize(
    "failure",
    [httpx.ConnectError("refused"), httpx.ReadTimeout("timeout"), httpx.ProtocolError("bad")],
)
def test_export_failure_is_failed_with_zero_retry(
    monkeypatch: pytest.MonkeyPatch, failure: Exception
) -> None:
    calls = 0

    def stream(*args: object, **kwargs: object) -> FakeStreamResponse:
        nonlocal calls
        del args, kwargs
        calls += 1
        raise failure

    monkeypatch.setattr(http_client, "stream", stream)
    metrics = RecordingMetrics()
    sink = BoundedHttpTraceSink("https://trace.test/export", metrics)
    sink.emit(envelope())
    wait_for(lambda: len(metrics.trace_exports) == 1)
    sink.close()

    assert calls == 1
    assert metrics.trace_exports == [TraceExportOutcome.FAILED]


def test_queue_full_emit_and_shutdown_are_bounded_and_after_close_drops(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    started = threading.Event()
    release = threading.Event()

    def blocked_export(self: BoundedHttpTraceSink, payload: bytes) -> None:
        del self, payload
        started.set()
        release.wait(timeout=1)

    monkeypatch.setattr(BoundedHttpTraceSink, "_export", blocked_export)
    metrics = RecordingMetrics()
    sink = BoundedHttpTraceSink("http://trace.test/export", metrics)
    sink.emit(envelope())
    assert started.wait(timeout=1)

    began = time.monotonic()
    for _ in range(TRACE_QUEUE_SIZE + 1):
        sink.emit(envelope())
    emit_duration = time.monotonic() - began
    assert emit_duration < 0.05
    assert TraceExportOutcome.DROPPED in metrics.trace_exports

    began = time.monotonic()
    sink.close()
    close_duration = time.monotonic() - began
    assert close_duration <= TRACE_SHUTDOWN_SECONDS + 0.03
    dropped_before = metrics.trace_exports.count(TraceExportOutcome.DROPPED)
    sink.emit(envelope())
    assert metrics.trace_exports.count(TraceExportOutcome.DROPPED) == dropped_before + 1
    release.set()


def test_operation_observation_closes_metrics_and_trace_after_monotonic_duration() -> None:
    metrics = RecordingMetrics()
    exported: list[TraceEnvelope] = []

    class Sink(NoopTraceSink):
        def emit(self, item: TraceEnvelope) -> None:
            exported.append(item)

    with OperationObservation(Operation.KNOWLEDGE_SEARCH, metrics, Sink()) as observation:
        observation.outcome = OperationOutcome.INSUFFICIENT

    assert len(metrics.operations) == 1
    operation, outcome, duration = metrics.operations[0]
    assert (operation, outcome) == (
        Operation.KNOWLEDGE_SEARCH,
        OperationOutcome.INSUFFICIENT,
    )
    assert duration >= 0
    assert len(exported) == 1
    assert exported[0].spanName == "knowledge.search"
    assert exported[0].outcome == "insufficient"


def test_trace_metrics_failure_never_escapes_worker_or_close(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    @contextmanager
    def stream(*args: object, **kwargs: object) -> Iterator[FakeStreamResponse]:
        del args, kwargs
        yield FakeStreamResponse(204, b"")

    monkeypatch.setattr(http_client, "stream", stream)
    sink = BoundedHttpTraceSink("http://trace.test/export", ExplodingMetrics())
    sink.emit(envelope())
    time.sleep(0.03)
    sink.close()
