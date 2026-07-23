"""Runtime-only owner snapshot and Broker journal boundaries for CB-113."""

from __future__ import annotations

import base64
import threading
import time
from collections.abc import Mapping
from http.client import HTTPException
from typing import Any, cast
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from .incremental import (
    EVENT_TAG,
    RESERVED_SANDBOX_PROPERTY,
    ElasticsearchKnowledgeProjection,
    FaqKnowledgeEvent,
    KnowledgeEventError,
)
from .rebuild import (
    MAX_JOURNAL_EVENTS,
    MAX_SNAPSHOT_BYTES,
    KnowledgeRebuildError,
    KnowledgeSnapshot,
)


class HttpOwnerSnapshotSource:
    def __init__(
        self,
        endpoint: str,
        client_id: str,
        client_secret: str,
        *,
        timeout_seconds: float = 5.0,
    ) -> None:
        parsed = urlsplit(endpoint)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.netloc
            or parsed.path != "/internal/knowledge/snapshot"
            or parsed.query
            or parsed.fragment
            or not client_id
            or not client_secret
            or timeout_seconds <= 0
        ):
            raise ValueError("Knowledge snapshot source configuration is incomplete")
        self._endpoint = endpoint
        credential = base64.b64encode(f"{client_id}:{client_secret}".encode()).decode("ascii")
        self._authorization = f"Basic {credential}"
        self._timeout_seconds = timeout_seconds

    def capture(self) -> KnowledgeSnapshot:
        request = Request(
            self._endpoint,
            headers={"Authorization": self._authorization, "Accept": "application/json"},
            method="GET",
        )
        try:
            try:
                with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310
                    status = response.status
                    payload = response.read(MAX_SNAPSHOT_BYTES + 1)
            except HTTPError as error:
                status = error.code
                payload = error.read(MAX_SNAPSHOT_BYTES + 1)
        except (URLError, TimeoutError, OSError, HTTPException) as error:
            raise KnowledgeRebuildError("snapshot_source_unavailable") from error
        if status != 200:
            raise KnowledgeRebuildError(
                "snapshot_source_unavailable" if status >= 500 else "snapshot_source_rejected"
            )
        if len(payload) > MAX_SNAPSHOT_BYTES:
            raise KnowledgeRebuildError("invalid_snapshot")
        return KnowledgeSnapshot.from_bytes(payload)


class RocketMqAcceptedEventJournal:
    def __init__(
        self,
        endpoints: str,
        topic: str,
        consumer_group: str,
        *,
        invisible_seconds: int = 300,
    ) -> None:
        if (
            not endpoints.strip()
            or not topic.strip()
            or not consumer_group.strip()
            or invisible_seconds < 10
            or invisible_seconds > 300
        ):
            raise ValueError("Knowledge rebuild Broker configuration is incomplete")
        from rocketmq.v5.client import (  # type: ignore[import-untyped]
            ClientConfiguration,
            Credentials,
        )
        from rocketmq.v5.consumer.simple import (  # type: ignore[import-untyped]
            SimpleConsumer,
        )
        from rocketmq.v5.model import FilterExpression  # type: ignore[import-untyped]

        configuration = ClientConfiguration(
            endpoints,
            Credentials(),
            request_timeout=5,
        )
        self._consumer: Any = SimpleConsumer(
            configuration,
            consumer_group,
            {topic: FilterExpression(EVENT_TAG)},
            await_duration=1,
        )
        self._invisible_seconds = invisible_seconds
        self._events: dict[tuple[str, int, str], FaqKnowledgeEvent] = {}
        self._pending_messages: dict[str, tuple[tuple[str, int, str], Any]] = {}
        self._condition = threading.Condition()
        self._stop = threading.Event()
        self._failure: KnowledgeRebuildError | None = None
        self._thread: threading.Thread | None = None

    def __enter__(self) -> RocketMqAcceptedEventJournal:
        self.startup()
        return self

    def __exit__(self, *_: object) -> None:
        self.shutdown()

    def startup(self) -> None:
        if self._thread is not None:
            raise ValueError("Knowledge rebuild Broker journal is already started")
        try:
            self._consumer.startup()
        except Exception as error:
            raise KnowledgeRebuildError("broker_unavailable") from error
        self._thread = threading.Thread(
            target=self._poll,
            name="knowledge-rebuild-broker-journal",
            daemon=True,
        )
        self._thread.start()

    def shutdown(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread is not None:
            thread.join(timeout=5)
        try:
            self._consumer.shutdown()
        except Exception as error:
            if self._failure is None:
                self._failure = KnowledgeRebuildError("broker_unavailable")
                self._failure.__cause__ = error
        self._thread = None

    def events(self) -> tuple[FaqKnowledgeEvent, ...]:
        with self._condition:
            if self._failure is not None:
                raise self._failure
            return tuple(
                sorted(
                    self._events.values(),
                    key=lambda event: (event.source_id, event.source_version, event.event_id),
                )
            )

    def commit(
        self,
        snapshot: KnowledgeSnapshot,
        validated_markers: dict[str, dict[str, object]],
    ) -> None:
        states = snapshot.source_states
        owner_records = {record.source_id: record for record in snapshot.records}
        with self._condition:
            if self._failure is not None:
                raise self._failure
            covered: list[tuple[str, Any]] = []
            for message_id, (event_key, message) in self._pending_messages.items():
                state = states.get(event_key[0])
                if state is None or state.source_version < event_key[1]:
                    continue
                event = self._events.get(event_key)
                if event is None:
                    failure = KnowledgeRebuildError("inconsistent_broker_journal")
                    self._failure = failure
                    self._condition.notify_all()
                    raise failure
                if state.source_version == event.source_version:
                    owner = owner_records.get(event.source_id)
                    if (
                        state.doc_type != "faq"
                        or owner is None
                        or owner.as_faq_event().commitment != event.commitment
                    ):
                        failure = KnowledgeRebuildError("catch_up_snapshot_mismatch")
                        self._failure = failure
                        self._condition.notify_all()
                        raise failure
                marker_id = f"__sync_event__:{event.event_id}"
                if validated_markers.get(
                    marker_id
                ) != ElasticsearchKnowledgeProjection._marker_source(event):
                    failure = KnowledgeRebuildError("journal_changed_after_validation")
                    self._failure = failure
                    self._condition.notify_all()
                    raise failure
                covered.append((message_id, message))
        for message_id, message in covered:
            try:
                self._consumer.ack(message)
            except Exception as error:
                failure = KnowledgeRebuildError("broker_unavailable")
                failure.__cause__ = error
                with self._condition:
                    self._failure = failure
                    self._condition.notify_all()
                raise failure from error
            with self._condition:
                self._pending_messages.pop(message_id, None)

    def _poll(self) -> None:
        while not self._stop.is_set():
            try:
                messages = self._consumer.receive(16, self._invisible_seconds)
                if not messages:
                    continue
                for message in messages:
                    if not self._accept(cast(object, message)):
                        self._consumer.ack(message)
            except Exception as error:
                with self._condition:
                    self._failure = KnowledgeRebuildError("broker_unavailable")
                    self._failure.__cause__ = error
                    self._condition.notify_all()
                return
            time.sleep(0.01)

    def _accept(self, message: object) -> bool:
        properties = getattr(message, "properties", None)
        if isinstance(properties, Mapping) and RESERVED_SANDBOX_PROPERTY in properties:
            return False
        body = getattr(message, "body", None)
        if not isinstance(body, bytes):
            return False
        try:
            event = FaqKnowledgeEvent.from_bytes(body)
        except KnowledgeEventError:
            return False
        message_id = str(getattr(message, "message_id", ""))
        if not message_id:
            with self._condition:
                self._failure = KnowledgeRebuildError("malformed_broker_message")
                self._condition.notify_all()
            return True
        key = (event.source_id, event.source_version, event.event_id)
        with self._condition:
            current = self._events.get(key)
            if current is not None and current.commitment != event.commitment:
                self._failure = KnowledgeRebuildError("conflicting_catch_up_event")
                self._condition.notify_all()
                return True
            if current is None and len(self._events) >= MAX_JOURNAL_EVENTS:
                self._failure = KnowledgeRebuildError("catch_up_event_limit_exceeded")
                self._condition.notify_all()
                return True
            self._events[key] = event
            self._pending_messages[message_id] = (key, message)
            self._condition.notify_all()
        return True
