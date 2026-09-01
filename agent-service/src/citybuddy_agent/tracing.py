"""Bounded identifier-free Agent trace mirror."""

from __future__ import annotations

import json
import queue
import threading
import time
from contextlib import nullcontext
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Protocol
from urllib.parse import urlsplit

import httpx

from . import http_client
from .metrics import (
    CityBuddyMetrics,
    Operation,
    OperationOutcome,
    SafeCityBuddyMetrics,
    TraceExportOutcome,
)

TRACE_QUEUE_SIZE = 64
TRACE_URL_MAX_LENGTH = 2048
TRACE_PAYLOAD_MAX_BYTES = 2048
TRACE_HTTP_TIMEOUT_SECONDS = 0.05
TRACE_SHUTDOWN_SECONDS = 0.3
MAX_TRACE_DURATION_MS = 2_147_483_647


class SpanName(StrEnum):
    CHAT_REQUEST = "chat.request"
    KNOWLEDGE_SEARCH = "knowledge.search"
    ACTION_PREPARE = "action.prepare"
    ACTION_CLARIFICATION = "action.clarification"
    ACTION_DECLINE = "action.decline"
    ACTION_EXPIRY = "action.expiry"


SPAN_BY_OPERATION = {
    Operation.CHAT_TURN: SpanName.CHAT_REQUEST,
    Operation.KNOWLEDGE_SEARCH: SpanName.KNOWLEDGE_SEARCH,
    Operation.PENDING_ACTION_PREPARE: SpanName.ACTION_PREPARE,
    Operation.PENDING_ACTION_CLARIFICATION: SpanName.ACTION_CLARIFICATION,
    Operation.PENDING_ACTION_DECLINE: SpanName.ACTION_DECLINE,
    Operation.PENDING_ACTION_EXPIRY: SpanName.ACTION_EXPIRY,
}


@dataclass(frozen=True)
class TraceEnvelope:
    schemaVersion: str
    service: str
    spanName: str
    outcome: str
    durationMs: int
    occurredAt: str

    @classmethod
    def create(
        cls, operation: Operation, outcome: OperationOutcome, duration_seconds: float
    ) -> TraceEnvelope:
        duration_ms = min(
            MAX_TRACE_DURATION_MS,
            max(0, int(round(duration_seconds * 1000))),
        )
        occurred_at = datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        return cls(
            schemaVersion="citybuddy-trace-v1",
            service="agent",
            spanName=SPAN_BY_OPERATION[operation].value,
            outcome=outcome.value,
            durationMs=duration_ms,
            occurredAt=occurred_at,
        )

    def encode(self) -> bytes:
        return json.dumps(
            asdict(self), ensure_ascii=False, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")


class TraceSink(Protocol):
    def emit(self, envelope: TraceEnvelope) -> None: ...

    def close(self) -> None: ...


class NoopTraceSink:
    def emit(self, envelope: TraceEnvelope) -> None:
        return None

    def close(self) -> None:
        return None


class BoundedHttpTraceSink:
    def __init__(
        self,
        url: str,
        metrics: CityBuddyMetrics,
        *,
        http_clients: http_client.HttpClients | None = None,
    ) -> None:
        self._url = validate_trace_url(url)
        self._metrics = SafeCityBuddyMetrics(metrics)
        self._http_clients = http_clients
        self._queue: queue.Queue[bytes] = queue.Queue(maxsize=TRACE_QUEUE_SIZE)
        self._closed = False
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._worker = threading.Thread(
            target=self._run,
            name="citybuddy-agent-trace",
            daemon=True,
        )
        self._worker.start()

    def emit(self, envelope: TraceEnvelope) -> None:
        try:
            payload = envelope.encode()
        except Exception:
            self._metrics.record_trace_export(TraceExportOutcome.DROPPED)
            return
        if len(payload) > TRACE_PAYLOAD_MAX_BYTES:
            self._metrics.record_trace_export(TraceExportOutcome.DROPPED)
            return
        with self._lock:
            if self._closed:
                self._metrics.record_trace_export(TraceExportOutcome.DROPPED)
                return
            try:
                self._queue.put_nowait(payload)
            except queue.Full:
                self._metrics.record_trace_export(TraceExportOutcome.DROPPED)

    def close(self) -> None:
        started = time.monotonic()
        with self._lock:
            if self._closed:
                return
            self._closed = True
        deadline = started + TRACE_SHUTDOWN_SECONDS
        while not self._queue.empty() and time.monotonic() < deadline:
            time.sleep(0.002)
        self._stop.set()
        self._worker.join(timeout=max(0.0, deadline - time.monotonic()))
        while True:
            try:
                self._queue.get_nowait()
            except queue.Empty:
                break
            else:
                self._metrics.record_trace_export(TraceExportOutcome.DROPPED)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                payload = self._queue.get(timeout=0.02)
            except queue.Empty:
                continue
            self._export(payload)

    def _export(self, payload: bytes) -> None:
        outcome = TraceExportOutcome.FAILED
        try:
            timeout = httpx.Timeout(TRACE_HTTP_TIMEOUT_SECONDS)
            binding = (
                http_client.use(self._http_clients)
                if self._http_clients is not None
                else nullcontext()
            )
            with binding:
                with http_client.stream(
                    "POST",
                    self._url,
                    content=payload,
                    headers={"Content-Type": "application/json"},
                    timeout=timeout,
                ) as response:
                    has_body = next(response.iter_raw(chunk_size=1), b"") != b""
                    if response.status_code == 204 and not has_body:
                        outcome = TraceExportOutcome.SENT
        except Exception:
            outcome = TraceExportOutcome.FAILED
        self._metrics.record_trace_export(outcome)


class OperationObservation:
    """Close one count/duration observation and enqueue its trace after timing."""

    def __init__(
        self,
        operation: Operation,
        metrics: CityBuddyMetrics,
        trace_sink: TraceSink,
    ) -> None:
        self.operation = operation
        self.outcome = OperationOutcome.ERROR
        self._metrics = metrics
        self._trace_sink = trace_sink
        self._started_ns = 0

    def __enter__(self) -> OperationObservation:
        self._started_ns = time.monotonic_ns()
        return self

    def __exit__(self, exception_type: object, exception: object, traceback: object) -> None:
        duration_seconds = max(0, time.monotonic_ns() - self._started_ns) / 1_000_000_000
        try:
            self._metrics.observe_operation(self.operation, self.outcome, duration_seconds)
        except Exception:
            pass
        try:
            self._trace_sink.emit(
                TraceEnvelope.create(self.operation, self.outcome, duration_seconds)
            )
        except Exception:
            pass


def create_trace_sink(
    url: str,
    metrics: CityBuddyMetrics,
    *,
    http_clients: http_client.HttpClients | None = None,
) -> TraceSink:
    if not url:
        return NoopTraceSink()
    return BoundedHttpTraceSink(url, metrics, http_clients=http_clients)


def validate_trace_url(url: str) -> str:
    if not url or len(url) > TRACE_URL_MAX_LENGTH or any(character.isspace() for character in url):
        raise ValueError("Trace export URL is invalid")
    try:
        parsed = urlsplit(url)
        port = parsed.port
    except ValueError as exception:
        raise ValueError("Trace export URL is invalid") from exception
    if (
        parsed.scheme not in {"http", "https"}
        or parsed.hostname is None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or (port is not None and not 1 <= port <= 65535)
    ):
        raise ValueError("Trace export URL is invalid")
    return url
