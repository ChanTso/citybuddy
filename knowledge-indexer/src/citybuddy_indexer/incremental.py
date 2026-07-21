"""Fail-closed CB-111 FAQ event validation and Elasticsearch convergence."""

from __future__ import annotations

import hashlib
import json
import math
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Any, cast
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen
from uuid import UUID

from .knowledge import (
    EMBEDDING_DIMS,
    KNOWLEDGE_ALIAS,
    KNOWLEDGE_SYNC_SCHEMA_VERSION,
    KnowledgeBootstrapError,
    validate_knowledge_mapping,
)

MAX_EVENT_BYTES = 8192
MAX_QUESTION_LENGTH = 500
MAX_ANSWER_LENGTH = 4000
MAX_SOURCE_VERSION = 9_223_372_036_854_775_807
MAX_CAS_ATTEMPTS = 8
EVENT_TAG = "knowledge-sync"
RESERVED_SANDBOX_PROPERTY = "citybuddy-eval-sandbox-id"

_SOURCE_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
_INSTANT = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.(\d{3}|\d{6}|\d{9}))?Z$")
_EVENT_FIELDS = {
    "eventId",
    "sourceId",
    "sourceType",
    "sourceVersion",
    "publicationState",
    "tombstone",
    "occurredTime",
    "content",
}
_CONTENT_FIELDS = {"question", "answer"}
_SEMANTIC_GROUPS: tuple[tuple[str, ...], ...] = (
    ("refund", "return", "退款", "退货"),
    ("tea", "drink", "product", "茉莉", "茶", "饮品", "商品"),
    ("delivery", "shipping", "配送", "快递"),
    ("hours", "opening", "open", "营业", "时间"),
    ("ingredient", "allergy", "成分", "过敏"),
    ("cancel", "order", "取消", "订单"),
)


class KnowledgeEventError(Exception):
    """A bounded permanent event rejection."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class KnowledgeSyncError(Exception):
    """A bounded retryable projection failure."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class KnowledgeSyncConflict(Exception):
    """A bounded permanent durable-intent conflict."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class ProjectionOutcome(str, Enum):
    APPLIED = "applied"
    REPLAYED = "replayed"
    STALE = "stale"


@dataclass(frozen=True)
class FaqPublicContent:
    question: str
    answer: str


@dataclass(frozen=True)
class FaqKnowledgeEvent:
    event_id: str
    source_id: str
    source_version: int
    tombstone: bool
    occurred_time: str
    content: FaqPublicContent

    @classmethod
    def from_bytes(cls, payload: bytes) -> FaqKnowledgeEvent:
        if not isinstance(payload, bytes) or not payload or len(payload) > MAX_EVENT_BYTES:
            raise KnowledgeEventError("invalid_payload")
        try:
            decoded = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_object)
        except (
            UnicodeDecodeError,
            ValueError,
            RecursionError,
        ) as error:
            raise KnowledgeEventError("invalid_payload") from error
        if not isinstance(decoded, dict) or set(decoded) != _EVENT_FIELDS:
            raise KnowledgeEventError("invalid_envelope")
        content = decoded.get("content")
        if not isinstance(content, dict) or set(content) != _CONTENT_FIELDS:
            raise KnowledgeEventError("invalid_content")
        event_id = decoded.get("eventId")
        source_id = decoded.get("sourceId")
        source_version = decoded.get("sourceVersion")
        tombstone = decoded.get("tombstone")
        occurred_time = decoded.get("occurredTime")
        question = content.get("question")
        answer = content.get("answer")
        if (
            not isinstance(event_id, str)
            or not _canonical_uuid(event_id)
            or not isinstance(source_id, str)
            or _SOURCE_ID.fullmatch(source_id) is None
            or decoded.get("sourceType") != "faq"
            or type(source_version) is not int
            or source_version < 1
            or source_version > MAX_SOURCE_VERSION
            or decoded.get("publicationState") != "PUBLISHED"
            or type(tombstone) is not bool
            or not isinstance(occurred_time, str)
            or not _valid_instant(occurred_time)
            or not _valid_text(question, MAX_QUESTION_LENGTH)
            or not _valid_text(answer, MAX_ANSWER_LENGTH)
        ):
            raise KnowledgeEventError("invalid_values")
        return cls(
            event_id=event_id,
            source_id=source_id,
            source_version=source_version,
            tombstone=tombstone,
            occurred_time=occurred_time,
            content=FaqPublicContent(question=cast(str, question), answer=cast(str, answer)),
        )

    @property
    def commitment(self) -> str:
        canonical = json.dumps(
            {
                "content": {
                    "answer": self.content.answer,
                    "question": self.content.question,
                },
                "eventId": self.event_id,
                "occurredTime": self.occurred_time,
                "publicationState": "PUBLISHED",
                "sourceId": self.source_id,
                "sourceType": "faq",
                "sourceVersion": self.source_version,
                "tombstone": self.tombstone,
            },
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        return hashlib.sha256(canonical).hexdigest()

    @property
    def document_id(self) -> str:
        return f"{self.source_id}:answer"


class _DuplicateField(ValueError):
    pass


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateField(key)
        result[key] = value
    return result


def _canonical_uuid(value: str) -> bool:
    try:
        return str(UUID(value)) == value
    except ValueError:
        return False


def _valid_text(value: object, maximum: int) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        code_units = len(value.encode("utf-16-le")) // 2
    except UnicodeEncodeError:
        return False
    return code_units <= maximum


def _valid_instant(value: str) -> bool:
    match = _INSTANT.fullmatch(value)
    if match is None:
        return False
    fraction = match.group(1)
    if fraction is not None and fraction.endswith("000"):
        return False
    try:
        datetime.fromisoformat(f"{value[:-1]}+00:00")
    except ValueError:
        return False
    return True


def deterministic_document_embedding(question: str, answer: str) -> list[float]:
    """Use the fixed CB-090 local query fixture space without claiming model quality."""
    normalized = unicodedata.normalize("NFKC", f"{question} {answer}").casefold()
    vector = [0.0] * EMBEDDING_DIMS
    for dimension, terms in enumerate(_SEMANTIC_GROUPS):
        if any(term in normalized for term in terms):
            vector[dimension] = 1.0
    if not any(vector):
        digest = hashlib.sha256(normalized.encode("utf-8")).digest()
        for dimension in range(EMBEDDING_DIMS):
            vector[dimension] = (digest[dimension] + 1) / 256
    magnitude = math.sqrt(sum(value * value for value in vector))
    return [value / magnitude for value in vector]


@dataclass(frozen=True)
class _StoredDocument:
    source: dict[str, Any]
    sequence: int
    primary_term: int


class ElasticsearchKnowledgeProjection:
    def __init__(
        self,
        base_url: str,
        *,
        alias: str = KNOWLEDGE_ALIAS,
        timeout_seconds: float = 5.0,
    ) -> None:
        if (
            not base_url.startswith(("http://", "https://"))
            or alias != KNOWLEDGE_ALIAS
            or timeout_seconds <= 0
        ):
            raise ValueError("Knowledge projection configuration is incomplete")
        self._base_url = base_url.rstrip("/")
        self._alias = alias
        self._timeout_seconds = timeout_seconds

    def apply(self, event: FaqKnowledgeEvent) -> ProjectionOutcome:
        outcome, _ = self.apply_with_index(event)
        return outcome

    def apply_with_index(self, event: FaqKnowledgeEvent) -> tuple[ProjectionOutcome, str]:
        index = self._resolve_current_index()
        self._bind_event_identity(index, event)
        for _ in range(MAX_CAS_ATTEMPTS):
            current = self._get(index, event.document_id)
            if current is None:
                status, _ = self._request(
                    "PUT",
                    self._document_path(index, event.document_id, {"op_type": "create"}),
                    self._projection_source(event),
                    expected=(200, 201, 409),
                )
                if status == 409:
                    continue
                return ProjectionOutcome.APPLIED, index
            current_version = current.source.get("source_version")
            if type(current_version) is not int or current_version < 1:
                raise KnowledgeSyncError("inconsistent_projection")
            if current_version > event.source_version:
                return ProjectionOutcome.STALE, index
            if current_version == event.source_version:
                if not self._matches_projection(current.source, event):
                    raise KnowledgeSyncConflict("conflicting_source_version")
                return ProjectionOutcome.REPLAYED, index
            status, _ = self._request(
                "PUT",
                self._document_path(
                    index,
                    event.document_id,
                    {"if_seq_no": current.sequence, "if_primary_term": current.primary_term},
                ),
                self._projection_source(event),
                expected=(200, 201, 409),
            )
            if status == 409:
                continue
            return ProjectionOutcome.APPLIED, index
        raise KnowledgeSyncError("concurrent_projection_contention")

    def _resolve_current_index(self) -> str:
        status, alias_payload = self._request(
            "GET", f"/_alias/{quote(self._alias)}", expected=(200, 404)
        )
        if status == 404 or len(alias_payload) != 1:
            raise KnowledgeSyncError("alias_unavailable")
        index = next(iter(alias_payload))
        index_payload = alias_payload.get(index)
        aliases = index_payload.get("aliases") if isinstance(index_payload, dict) else None
        if not isinstance(aliases, dict) or set(aliases) != {self._alias}:
            raise KnowledgeSyncError("alias_ambiguous")
        _, mapping = self._request("GET", f"/{quote(index)}/_mapping")
        try:
            validate_knowledge_mapping(mapping, index)
        except KnowledgeBootstrapError as error:
            raise KnowledgeSyncError("incompatible_mapping") from error
        return index

    def _bind_event_identity(self, index: str, event: FaqKnowledgeEvent) -> None:
        marker_id = f"__sync_event__:{event.event_id}"
        status, _ = self._request(
            "PUT",
            self._document_path(index, marker_id, {"op_type": "create"}),
            self._marker_source(event),
            expected=(200, 201, 409),
        )
        if status != 409:
            return
        marker = self._get(index, marker_id)
        if marker is None or not self._matches_marker(marker.source, event):
            raise KnowledgeSyncConflict("conflicting_event_identity")

    def _get(self, index: str, document_id: str) -> _StoredDocument | None:
        status, response = self._request(
            "GET", f"/{quote(index)}/_doc/{quote(document_id, safe='')}", expected=(200, 404)
        )
        if status == 404:
            return None
        source = response.get("_source")
        sequence = response.get("_seq_no")
        primary_term = response.get("_primary_term")
        if (
            not isinstance(source, dict)
            or type(sequence) is not int
            or sequence < 0
            or type(primary_term) is not int
            or primary_term < 1
        ):
            raise KnowledgeSyncError("malformed_elasticsearch_response")
        return _StoredDocument(cast(dict[str, Any], source), sequence, primary_term)

    @staticmethod
    def _marker_source(event: FaqKnowledgeEvent) -> dict[str, object]:
        return {
            "schema_version": KNOWLEDGE_SYNC_SCHEMA_VERSION,
            "source_id": event.source_id,
            "source_version": event.source_version,
            "chunk_id": "__sync_event__",
            "doc_type": "faq",
            "published": False,
            "deleted": True,
            "title": "knowledge synchronization event",
            "content": event.commitment,
            # Cosine-indexed dense vectors reject a zero magnitude vector. This record is
            # excluded from every public query, so a fixed non-zero sentinel is sufficient.
            "embedding": [1.0, *([0.0] * (EMBEDDING_DIMS - 1))],
            "public_metadata": {"category": "sync", "language": "und"},
            "sync_record_type": "SYNC_EVENT",
            "sync_event_id": event.event_id,
            "sync_event_commitment": event.commitment,
            "sync_occurred_at": event.occurred_time,
        }

    @staticmethod
    def _projection_source(event: FaqKnowledgeEvent) -> dict[str, object]:
        return {
            "schema_version": KNOWLEDGE_SYNC_SCHEMA_VERSION,
            "source_id": event.source_id,
            "source_version": event.source_version,
            "chunk_id": "answer",
            "doc_type": "faq",
            "published": not event.tombstone,
            "deleted": event.tombstone,
            "title": event.content.question,
            "content": event.content.answer,
            "embedding": deterministic_document_embedding(
                event.content.question, event.content.answer
            ),
            "public_metadata": {"category": "faq", "language": "und"},
            "sync_record_type": "PUBLIC_DOCUMENT",
            "sync_event_id": event.event_id,
            "sync_event_commitment": event.commitment,
            "sync_occurred_at": event.occurred_time,
        }

    @staticmethod
    def _matches_marker(source: dict[str, Any], event: FaqKnowledgeEvent) -> bool:
        return source == ElasticsearchKnowledgeProjection._marker_source(event)

    @staticmethod
    def _matches_projection(source: dict[str, Any], event: FaqKnowledgeEvent) -> bool:
        return source == ElasticsearchKnowledgeProjection._projection_source(event)

    @staticmethod
    def _document_path(index: str, document_id: str, parameters: dict[str, object]) -> str:
        query = urlencode(parameters)
        return f"/{quote(index)}/_doc/{quote(document_id, safe='')}?{query}"

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        *,
        expected: tuple[int, ...] = (200, 201),
    ) -> tuple[int, dict[str, Any]]:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(
            f"{self._base_url}{path}",
            data=data,
            headers={"Content-Type": "application/json"},
            method=method,
        )
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310
                status = response.status
                body = response.read()
        except HTTPError as error:
            status = error.code
            body = error.read()
        except (URLError, TimeoutError, OSError) as error:
            raise KnowledgeSyncError("elasticsearch_unavailable") from error
        if status not in expected:
            raise KnowledgeSyncError(
                "elasticsearch_unavailable" if status >= 500 else "elasticsearch_rejected"
            )
        if not body:
            return status, {}
        try:
            decoded = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            raise KnowledgeSyncError("malformed_elasticsearch_response") from error
        if not isinstance(decoded, dict):
            raise KnowledgeSyncError("malformed_elasticsearch_response")
        return status, cast(dict[str, Any], decoded)
