"""CB-113 complete snapshot rebuild, validation, and atomic alias handoff."""

from __future__ import annotations

import hashlib
import json
import re
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from http.client import HTTPException
from typing import Any, Protocol, cast
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen
from uuid import UUID

from .incremental import (
    MAX_ANSWER_LENGTH,
    MAX_QUESTION_LENGTH,
    MAX_SOURCE_VERSION,
    ElasticsearchKnowledgeProjection,
    FaqKnowledgeEvent,
    KnowledgeEventError,
    KnowledgeSyncConflict,
    KnowledgeSyncError,
    ProjectionOutcome,
    deterministic_document_embedding,
)
from .knowledge import (
    EMBEDDING_DIMS,
    KNOWLEDGE_ALIAS,
    KNOWLEDGE_INDEX_MAPPING,
    KNOWLEDGE_SYNC_SCHEMA_VERSION,
    KnowledgeBootstrapError,
    validate_knowledge_mapping,
)

REBUILD_SCHEMA_VERSION = "cb113-v1"
MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
MAX_SNAPSHOT_RECORDS = 1_000
MAX_JOURNAL_EVENTS = 1_000
MAX_CATCH_UP_PASSES = 8
MAX_CANDIDATE_PROBES = 32
MAX_CANDIDATE_DOCUMENTS = (2 * MAX_SNAPSHOT_RECORDS) + 1
ROLLBACK_LEASE_SECONDS = 3_600
REBUILD_RECORD_ID = "__rebuild_switch__"

_INDEX = re.compile(r"^knowledge_docs_v([1-9][0-9]*)$")
_SOURCE_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
_CHUNK_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_INSTANT = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.(\d{3}|\d{6}|\d{9}))?Z$")
_SNAPSHOT_FIELDS = {
    "schemaVersion",
    "snapshotId",
    "capturedAt",
    "recordCount",
    "sourceCount",
    "contentCommitment",
    "watermark",
    "records",
}
_RECORD_FIELDS = {
    "eventId",
    "sourceId",
    "sourceVersion",
    "chunkId",
    "docType",
    "publicationState",
    "tombstone",
    "occurredTime",
    "title",
    "content",
    "publicMetadata",
}
_METADATA_FIELDS = {"category", "language", "productId"}
_PROJECTION_FIELDS = {
    "schema_version",
    "source_id",
    "source_version",
    "chunk_id",
    "doc_type",
    "published",
    "deleted",
    "title",
    "content",
    "embedding",
    "public_metadata",
    "sync_record_type",
    "sync_event_id",
    "sync_event_commitment",
    "sync_occurred_at",
}
_REBUILD_RECORD_FIELDS = {
    "schemaVersion",
    "initialSnapshotId",
    "initialSnapshotCommitment",
    "predecessor",
    "candidate",
    "state",
    "handoffWatermark",
    "rollbackLeaseExpiresAt",
    "completionSnapshotId",
    "completionSnapshotCommitment",
    "completionWatermark",
    "switchedAt",
}

_APPROVED_SETTINGS = {
    "index.number_of_shards": "1",
    "index.number_of_replicas": "0",
    "index.refresh_interval": "1s",
}
_CREATE_DEFINITION: dict[str, object] = {
    "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 0,
        "refresh_interval": "1s",
    },
    **KNOWLEDGE_INDEX_MAPPING,
}


class KnowledgeRebuildError(Exception):
    """A bounded fail-closed rebuild result without raw dependency payloads."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _canonical(value: object) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError, RecursionError) as error:
        raise KnowledgeRebuildError("invalid_snapshot") from error


def _digest(value: object) -> str:
    return hashlib.sha256(_canonical(value)).hexdigest()


def _stable_snapshot_id(content_commitment: str, watermark: str) -> str:
    raw = bytearray(
        hashlib.md5(  # noqa: S324 - Java UUID.nameUUIDFromBytes compatibility, not security.
            f"{content_commitment}:{watermark}".encode(), usedforsecurity=False
        ).digest()
    )
    raw[6] = (raw[6] & 0x0F) | 0x30
    raw[8] = (raw[8] & 0x3F) | 0x80
    return str(UUID(bytes=bytes(raw)))


def _uuid(value: object) -> str:
    if not isinstance(value, str):
        raise KnowledgeRebuildError("invalid_snapshot")
    try:
        if str(UUID(value)) != value:
            raise ValueError
    except ValueError as error:
        raise KnowledgeRebuildError("invalid_snapshot") from error
    return value


def _instant(value: object) -> str:
    if not isinstance(value, str):
        raise KnowledgeRebuildError("invalid_snapshot")
    match = _INSTANT.fullmatch(value)
    if match is None:
        raise KnowledgeRebuildError("invalid_snapshot")
    fraction = match.group(1)
    if fraction is not None and fraction.endswith("000"):
        raise KnowledgeRebuildError("invalid_snapshot")
    try:
        datetime.fromisoformat(f"{value[:-1]}+00:00")
    except ValueError as error:
        raise KnowledgeRebuildError("invalid_snapshot") from error
    return value


def _internal_instant(value: object) -> str:
    if not isinstance(value, str) or _INSTANT.fullmatch(value) is None:
        raise KnowledgeRebuildError("invalid_snapshot")
    try:
        datetime.fromisoformat(f"{value[:-1]}+00:00")
    except ValueError as error:
        raise KnowledgeRebuildError("invalid_snapshot") from error
    return value


def _bounded_text(value: object, maximum: int) -> str:
    if not isinstance(value, str) or not value.strip():
        raise KnowledgeRebuildError("invalid_snapshot")
    try:
        units = len(value.encode("utf-16-le")) // 2
    except UnicodeEncodeError as error:
        raise KnowledgeRebuildError("invalid_snapshot") from error
    if units > maximum:
        raise KnowledgeRebuildError("invalid_snapshot")
    return value


@dataclass(frozen=True)
class SnapshotRecord:
    event_id: str
    source_id: str
    source_version: int
    chunk_id: str
    doc_type: str
    tombstone: bool
    occurred_time: str
    title: str
    content: str
    public_metadata: dict[str, str]

    @classmethod
    def from_mapping(cls, value: object) -> SnapshotRecord:
        if not isinstance(value, dict) or set(value) != _RECORD_FIELDS:
            raise KnowledgeRebuildError("invalid_snapshot_record")
        source_id = value.get("sourceId")
        chunk_id = value.get("chunkId")
        source_version = value.get("sourceVersion")
        doc_type = value.get("docType")
        tombstone = value.get("tombstone")
        metadata = value.get("publicMetadata")
        if (
            not isinstance(source_id, str)
            or _SOURCE_ID.fullmatch(source_id) is None
            or not isinstance(chunk_id, str)
            or _CHUNK_ID.fullmatch(chunk_id) is None
            or type(source_version) is not int
            or source_version < 1
            or source_version > MAX_SOURCE_VERSION
            or doc_type not in {"faq", "product"}
            or value.get("publicationState") != "PUBLISHED"
            or type(tombstone) is not bool
            or not isinstance(metadata, dict)
            or not set(metadata).issubset(_METADATA_FIELDS)
            or set(metadata) < {"category", "language"}
            or any(not isinstance(item, str) or not item for item in metadata.values())
        ):
            raise KnowledgeRebuildError("invalid_snapshot_record")
        if doc_type == "faq":
            if chunk_id != "answer" or metadata != {"category": "faq", "language": "und"}:
                raise KnowledgeRebuildError("invalid_snapshot_record")
        elif metadata.get("productId") != source_id:
            raise KnowledgeRebuildError("invalid_snapshot_record")
        return cls(
            event_id=_uuid(value.get("eventId")),
            source_id=source_id,
            source_version=source_version,
            chunk_id=chunk_id,
            doc_type=cast(str, doc_type),
            tombstone=tombstone,
            occurred_time=_instant(value.get("occurredTime")),
            title=_bounded_text(value.get("title"), MAX_QUESTION_LENGTH),
            content=_bounded_text(value.get("content"), MAX_ANSWER_LENGTH),
            public_metadata=cast(dict[str, str], metadata),
        )

    @property
    def document_id(self) -> str:
        return f"{self.source_id}:{self.chunk_id}"

    def canonical_mapping(self) -> dict[str, object]:
        return {
            "eventId": self.event_id,
            "sourceId": self.source_id,
            "sourceVersion": self.source_version,
            "chunkId": self.chunk_id,
            "docType": self.doc_type,
            "publicationState": "PUBLISHED",
            "tombstone": self.tombstone,
            "occurredTime": self.occurred_time,
            "title": self.title,
            "content": self.content,
            "publicMetadata": self.public_metadata,
        }

    def as_faq_event(self) -> FaqKnowledgeEvent:
        if self.doc_type != "faq":
            raise KnowledgeRebuildError("unsupported_catch_up_source")
        payload = {
            "eventId": self.event_id,
            "sourceId": self.source_id,
            "sourceType": "faq",
            "sourceVersion": self.source_version,
            "publicationState": "PUBLISHED",
            "tombstone": self.tombstone,
            "occurredTime": self.occurred_time,
            "content": {"question": self.title, "answer": self.content},
        }
        try:
            return FaqKnowledgeEvent.from_bytes(_canonical(payload))
        except (KnowledgeEventError, KnowledgeRebuildError) as error:
            raise KnowledgeRebuildError("invalid_snapshot_record") from error

    def as_projection_source(self) -> dict[str, object]:
        if self.doc_type == "faq":
            return ElasticsearchKnowledgeProjection._projection_source(self.as_faq_event())
        commitment = _digest(self.canonical_mapping())
        return {
            "schema_version": KNOWLEDGE_SYNC_SCHEMA_VERSION,
            "source_id": self.source_id,
            "source_version": self.source_version,
            "chunk_id": self.chunk_id,
            "doc_type": self.doc_type,
            "published": not self.tombstone,
            "deleted": self.tombstone,
            "title": self.title,
            "content": self.content,
            "embedding": deterministic_document_embedding(self.title, self.content),
            "public_metadata": {
                "product_id": self.public_metadata["productId"],
                "category": self.public_metadata["category"],
                "language": self.public_metadata["language"],
            },
            "sync_record_type": "PUBLIC_DOCUMENT",
            "sync_event_id": self.event_id,
            "sync_event_commitment": commitment,
            "sync_occurred_at": self.occurred_time,
        }


@dataclass(frozen=True)
class _SourceState:
    source_id: str
    source_version: int
    doc_type: str
    tombstone: bool
    event_id: str
    occurred_time: str
    record_commitments: tuple[str, ...]

    def canonical_mapping(self) -> dict[str, object]:
        return {
            "sourceId": self.source_id,
            "sourceVersion": self.source_version,
            "docType": self.doc_type,
            "tombstone": self.tombstone,
            "eventId": self.event_id,
            "occurredTime": self.occurred_time,
            "recordCommitments": list(self.record_commitments),
        }


@dataclass(frozen=True)
class KnowledgeSnapshot:
    snapshot_id: str
    captured_at: str
    records: tuple[SnapshotRecord, ...]
    content_commitment: str
    watermark: str

    @classmethod
    def from_bytes(cls, payload: bytes) -> KnowledgeSnapshot:
        if not isinstance(payload, bytes) or not payload or len(payload) > MAX_SNAPSHOT_BYTES:
            raise KnowledgeRebuildError("invalid_snapshot")
        try:
            decoded = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_object)
        except (UnicodeDecodeError, ValueError, RecursionError) as error:
            raise KnowledgeRebuildError("invalid_snapshot") from error
        if not isinstance(decoded, dict) or set(decoded) != _SNAPSHOT_FIELDS:
            raise KnowledgeRebuildError("invalid_snapshot")
        raw_records = decoded.get("records")
        if (
            decoded.get("schemaVersion") != REBUILD_SCHEMA_VERSION
            or not isinstance(raw_records, list)
            or not raw_records
            or len(raw_records) > MAX_SNAPSHOT_RECORDS
        ):
            raise KnowledgeRebuildError("invalid_snapshot")
        records = tuple(SnapshotRecord.from_mapping(record) for record in raw_records)
        ordered = tuple(sorted(records, key=lambda record: (record.source_id, record.chunk_id)))
        if records != ordered or len({record.document_id for record in records}) != len(records):
            raise KnowledgeRebuildError("invalid_snapshot_order")
        states = _source_states(records)
        commitment = _digest([record.canonical_mapping() for record in records])
        watermark = _digest([state.canonical_mapping() for state in states.values()])
        claimed_commitment = decoded.get("contentCommitment")
        claimed_watermark = decoded.get("watermark")
        claimed_snapshot_id = _uuid(decoded.get("snapshotId"))
        if (
            decoded.get("recordCount") != len(records)
            or decoded.get("sourceCount") != len(states)
            or not isinstance(claimed_commitment, str)
            or _SHA256.fullmatch(claimed_commitment) is None
            or not isinstance(claimed_watermark, str)
            or _SHA256.fullmatch(claimed_watermark) is None
            or claimed_commitment != commitment
            or claimed_watermark != watermark
            or claimed_snapshot_id != _stable_snapshot_id(commitment, watermark)
        ):
            raise KnowledgeRebuildError("snapshot_commitment_mismatch")
        return cls(
            snapshot_id=claimed_snapshot_id,
            captured_at=_instant(decoded.get("capturedAt")),
            records=records,
            content_commitment=commitment,
            watermark=watermark,
        )

    @property
    def source_states(self) -> dict[str, _SourceState]:
        return _source_states(self.records)

    @property
    def documents(self) -> dict[str, dict[str, object]]:
        return {record.document_id: record.as_projection_source() for record in self.records}


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def _source_states(records: tuple[SnapshotRecord, ...]) -> dict[str, _SourceState]:
    grouped: dict[str, list[SnapshotRecord]] = {}
    for record in records:
        grouped.setdefault(record.source_id, []).append(record)
    states: dict[str, _SourceState] = {}
    for source_id in sorted(grouped):
        source_records = grouped[source_id]
        first = source_records[0]
        shared = {
            (
                record.source_version,
                record.doc_type,
                record.tombstone,
                record.event_id,
                record.occurred_time,
            )
            for record in source_records
        }
        if len(shared) != 1 or (first.doc_type == "faq" and len(source_records) != 1):
            raise KnowledgeRebuildError("conflicting_snapshot_source")
        states[source_id] = _SourceState(
            source_id=source_id,
            source_version=first.source_version,
            doc_type=first.doc_type,
            tombstone=first.tombstone,
            event_id=first.event_id,
            occurred_time=first.occurred_time,
            record_commitments=tuple(
                _digest(record.canonical_mapping())
                for record in sorted(source_records, key=lambda record: record.chunk_id)
            ),
        )
    return states


class SnapshotSource(Protocol):
    def capture(self) -> KnowledgeSnapshot: ...


class AcceptedEventJournal(Protocol):
    def events(self) -> tuple[FaqKnowledgeEvent, ...]: ...

    def commit(self, snapshot: KnowledgeSnapshot) -> None: ...


class RebuildIndex(Protocol):
    def resolve_alias(self) -> str: ...

    def existing_result(self, current: str, initial: KnowledgeSnapshot) -> RebuildResult | None: ...

    def pending_switch(self, current: str) -> RebuildResult | None: ...

    def candidate_for(self, predecessor: str, initial: KnowledgeSnapshot) -> str: ...

    def create_or_resume_candidate(
        self, predecessor: str, candidate: str, initial: KnowledgeSnapshot
    ) -> None: ...

    def load_snapshot(self, candidate: str, snapshot: KnowledgeSnapshot) -> None: ...

    def apply_event(self, candidate: str, event: FaqKnowledgeEvent) -> ProjectionOutcome: ...

    def validate_candidate(
        self,
        candidate: str,
        snapshot: KnowledgeSnapshot,
        accepted_events: tuple[FaqKnowledgeEvent, ...],
    ) -> None: ...

    def prepare_switch(
        self,
        candidate: str,
        initial: KnowledgeSnapshot,
        handoff: KnowledgeSnapshot,
    ) -> str: ...

    def atomic_switch(self, predecessor: str, candidate: str) -> None: ...

    def complete_switch(self, candidate: str, completion: KnowledgeSnapshot) -> None: ...


@dataclass(frozen=True)
class RebuildResult:
    predecessor: str
    candidate: str
    handoff_watermark: str
    rollback_lease_expires_at: str
    document_count: int
    replayed: bool


class ElasticsearchRebuildClient:
    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: float = 5.0,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        if not base_url.startswith(("http://", "https://")) or timeout_seconds <= 0:
            raise ValueError("Knowledge rebuild configuration is incomplete")
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._now = now or (lambda: datetime.now(UTC))

    def resolve_alias(self) -> str:
        status, payload = self._request(
            "GET", f"/_alias/{quote(KNOWLEDGE_ALIAS)}", expected=(200, 404)
        )
        if status == 404 or len(payload) != 1:
            raise KnowledgeRebuildError("alias_unavailable")
        index = next(iter(payload))
        index_payload = payload.get(index)
        aliases = index_payload.get("aliases") if isinstance(index_payload, dict) else None
        if (
            _INDEX.fullmatch(index) is None
            or not isinstance(aliases, dict)
            or set(aliases) != {KNOWLEDGE_ALIAS}
            or aliases.get(KNOWLEDGE_ALIAS) != {}
        ):
            raise KnowledgeRebuildError("alias_ambiguous")
        self._validate_mapping(index)
        return index

    def candidate_for(self, predecessor: str, initial: KnowledgeSnapshot) -> str:
        match = _INDEX.fullmatch(predecessor)
        if match is None:
            raise KnowledgeRebuildError("invalid_predecessor")
        first_version = int(match.group(1)) + 1
        for offset in range(MAX_CANDIDATE_PROBES):
            candidate = f"knowledge_docs_v{first_version + offset}"
            status, _ = self._request("GET", f"/{quote(candidate)}", expected=(200, 404))
            if status == 404:
                return candidate
            record = self._read_rebuild_record(candidate)
            if (
                record is not None
                and record.get("predecessor") == predecessor
                and record.get("initialSnapshotId") == initial.snapshot_id
                and record.get("initialSnapshotCommitment") == initial.content_commitment
                and record.get("state") in {"BUILDING", "PREPARED"}
            ):
                self._require_record_identity(record, candidate)
                return candidate
        raise KnowledgeRebuildError("candidate_space_exhausted")

    def existing_result(self, current: str, initial: KnowledgeSnapshot) -> RebuildResult | None:
        record = self._read_rebuild_record(current)
        if record is None or record.get("state") != "SWITCHED":
            return None
        self._require_record_identity(record, current)
        if (
            record.get("completionSnapshotId") != initial.snapshot_id
            or record.get("completionSnapshotCommitment") != initial.content_commitment
            or record.get("completionWatermark") != initial.watermark
        ):
            return None
        handoff = record.get("handoffWatermark")
        lease = record.get("rollbackLeaseExpiresAt")
        if not isinstance(handoff, str) or not isinstance(lease, str):
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        return RebuildResult(
            predecessor=cast(str, record["predecessor"]),
            candidate=current,
            handoff_watermark=handoff,
            rollback_lease_expires_at=lease,
            document_count=len(initial.records),
            replayed=True,
        )

    def pending_switch(self, current: str) -> RebuildResult | None:
        record = self._read_rebuild_record(current)
        if record is None or record.get("state") != "PREPARED":
            return None
        self._require_record_identity(record, current)
        handoff = record.get("handoffWatermark")
        lease = record.get("rollbackLeaseExpiresAt")
        if not isinstance(handoff, str) or not isinstance(lease, str):
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        return RebuildResult(
            predecessor=cast(str, record["predecessor"]),
            candidate=current,
            handoff_watermark=handoff,
            rollback_lease_expires_at=lease,
            document_count=self._public_document_count(current),
            replayed=True,
        )

    def create_or_resume_candidate(
        self, predecessor: str, candidate: str, initial: KnowledgeSnapshot
    ) -> None:
        status, _ = self._request("GET", f"/{quote(candidate)}", expected=(200, 404))
        if status == 404:
            self._request("PUT", f"/{quote(candidate)}", _CREATE_DEFINITION)
            self._validate_mapping(candidate)
            self._validate_settings(candidate)
            initial_record: dict[str, object] = {
                "schemaVersion": REBUILD_SCHEMA_VERSION,
                "initialSnapshotId": initial.snapshot_id,
                "initialSnapshotCommitment": initial.content_commitment,
                "predecessor": predecessor,
                "candidate": candidate,
                "state": "BUILDING",
                "handoffWatermark": None,
                "rollbackLeaseExpiresAt": None,
                "completionSnapshotId": None,
                "completionSnapshotCommitment": None,
                "completionWatermark": None,
                "switchedAt": None,
            }
            self._write_rebuild_record(candidate, initial_record, create=True)
            return
        self._validate_mapping(candidate)
        self._validate_settings(candidate)
        record = self._read_rebuild_record(candidate)
        if record is None:
            raise KnowledgeRebuildError("candidate_already_exists")
        self._require_record_identity(record, candidate)
        if (
            record.get("initialSnapshotId") != initial.snapshot_id
            or record.get("initialSnapshotCommitment") != initial.content_commitment
            or record.get("predecessor") != predecessor
            or record.get("state") not in {"BUILDING", "PREPARED"}
        ):
            raise KnowledgeRebuildError("candidate_owned_by_other_rebuild")

    def load_snapshot(self, candidate: str, snapshot: KnowledgeSnapshot) -> None:
        lines: list[bytes] = []
        for document_id, source in snapshot.documents.items():
            lines.append(_canonical({"index": {"_index": candidate, "_id": document_id}}))
            lines.append(_canonical(source))
        payload = b"\n".join(lines) + b"\n"
        response = self._raw_request(
            "POST", "/_bulk?refresh=false", payload, "application/x-ndjson"
        )
        items = response.get("items")
        if (
            response.get("errors") is not False
            or not isinstance(items, list)
            or len(items) != len(snapshot.records)
        ):
            raise KnowledgeRebuildError("partial_bulk_failure")
        for item in items:
            operation = item.get("index") if isinstance(item, dict) else None
            if (
                not isinstance(operation, dict)
                or operation.get("status") not in {200, 201}
                or "error" in operation
            ):
                raise KnowledgeRebuildError("partial_bulk_failure")
        self._refresh(candidate)

    def apply_event(self, candidate: str, event: FaqKnowledgeEvent) -> ProjectionOutcome:
        projection = ElasticsearchKnowledgeProjection(
            self._base_url, timeout_seconds=self._timeout_seconds
        )
        try:
            return projection.apply_to_index(event, candidate)
        except KnowledgeSyncConflict as error:
            raise KnowledgeRebuildError("conflicting_catch_up_event") from error
        except KnowledgeSyncError as error:
            raise KnowledgeRebuildError(error.code) from error

    def validate_candidate(
        self,
        candidate: str,
        snapshot: KnowledgeSnapshot,
        accepted_events: tuple[FaqKnowledgeEvent, ...],
    ) -> None:
        self._validate_mapping(candidate)
        self._validate_settings(candidate)
        self._refresh(candidate)
        _, health = self._request(
            "GET",
            f"/_cluster/health/{quote(candidate)}?wait_for_status=yellow&timeout=5s",
        )
        if (
            health.get("timed_out") is not False
            or health.get("status") not in {"yellow", "green"}
            or health.get("active_primary_shards") != 1
            or health.get("unassigned_shards") != 0
        ):
            raise KnowledgeRebuildError("candidate_health_incomplete")
        actual = self._candidate_public_documents(candidate, accepted_events)
        if actual != snapshot.documents:
            raise KnowledgeRebuildError("candidate_commitment_mismatch")
        if (
            _digest(
                [
                    record.canonical_mapping()
                    for record in sorted(snapshot.records, key=lambda item: item.document_id)
                ]
            )
            != snapshot.content_commitment
        ):
            raise KnowledgeRebuildError("candidate_commitment_mismatch")
        self._fixed_retrieval_probes(candidate)

    def prepare_switch(
        self,
        candidate: str,
        initial: KnowledgeSnapshot,
        handoff: KnowledgeSnapshot,
    ) -> str:
        record = self._read_rebuild_record(candidate)
        if record is None:
            raise KnowledgeRebuildError("missing_rebuild_record")
        self._require_record_identity(record, candidate)
        if record.get("initialSnapshotId") != initial.snapshot_id:
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        if record.get("state") == "PREPARED":
            if record.get("handoffWatermark") != handoff.watermark:
                if self.resolve_alias() == candidate:
                    raise KnowledgeRebuildError("handoff_changed_after_switch")
                predecessor = record.get("predecessor")
                if not isinstance(predecessor, str) or self.resolve_alias() != predecessor:
                    raise KnowledgeRebuildError("alias_changed_before_switch")
                expires_at = self._now().astimezone(UTC) + timedelta(seconds=ROLLBACK_LEASE_SECONDS)
                lease = expires_at.isoformat().replace("+00:00", "Z")
                self._write_rebuild_record(
                    candidate,
                    {
                        **record,
                        "handoffWatermark": handoff.watermark,
                        "rollbackLeaseExpiresAt": lease,
                    },
                )
                return lease
            existing_lease = record.get("rollbackLeaseExpiresAt")
            if not isinstance(existing_lease, str):
                raise KnowledgeRebuildError("inconsistent_rebuild_record")
            return existing_lease
        if record.get("state") != "BUILDING":
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        expires_at = self._now().astimezone(UTC) + timedelta(seconds=ROLLBACK_LEASE_SECONDS)
        lease = expires_at.isoformat().replace("+00:00", "Z")
        prepared = {
            **record,
            "state": "PREPARED",
            "handoffWatermark": handoff.watermark,
            "rollbackLeaseExpiresAt": lease,
        }
        self._write_rebuild_record(candidate, prepared)
        return lease

    def atomic_switch(self, predecessor: str, candidate: str) -> None:
        if self.resolve_alias() != predecessor:
            raise KnowledgeRebuildError("alias_changed_before_switch")
        _, response = self._request(
            "POST",
            "/_aliases",
            {
                "actions": [
                    {
                        "remove": {
                            "index": predecessor,
                            "alias": KNOWLEDGE_ALIAS,
                            "must_exist": True,
                        }
                    },
                    {"add": {"index": candidate, "alias": KNOWLEDGE_ALIAS}},
                ],
            },
        )
        if response.get("acknowledged") is not True or self.resolve_alias() != candidate:
            raise KnowledgeRebuildError("alias_switch_unverified")

    def complete_switch(self, candidate: str, completion: KnowledgeSnapshot) -> None:
        record = self._read_rebuild_record(candidate)
        if record is None:
            raise KnowledgeRebuildError("missing_rebuild_record")
        self._require_record_identity(record, candidate)
        if record.get("state") == "SWITCHED":
            if (
                record.get("completionSnapshotId") != completion.snapshot_id
                or record.get("completionSnapshotCommitment") != completion.content_commitment
                or record.get("completionWatermark") != completion.watermark
            ):
                raise KnowledgeRebuildError("inconsistent_rebuild_record")
            return
        if record.get("state") != "PREPARED":
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        self._write_rebuild_record(
            candidate,
            {
                **record,
                "state": "SWITCHED",
                "completionSnapshotId": completion.snapshot_id,
                "completionSnapshotCommitment": completion.content_commitment,
                "completionWatermark": completion.watermark,
                "switchedAt": _now(self._now),
            },
        )

    def _validate_mapping(self, index: str) -> None:
        _, payload = self._request("GET", f"/{quote(index)}/_mapping")
        try:
            validate_knowledge_mapping(payload, index)
        except KnowledgeBootstrapError as error:
            raise KnowledgeRebuildError("incompatible_mapping") from error

    def _validate_settings(self, index: str) -> None:
        _, payload = self._request(
            "GET", f"/{quote(index)}/_settings?flat_settings=true&include_defaults=false"
        )
        index_payload = payload.get(index)
        settings = index_payload.get("settings") if isinstance(index_payload, dict) else None
        if not isinstance(settings, dict) or any(
            settings.get(name) != value for name, value in _APPROVED_SETTINGS.items()
        ):
            raise KnowledgeRebuildError("incompatible_settings")

    def _refresh(self, index: str) -> None:
        _, response = self._request("POST", f"/{quote(index)}/_refresh")
        _require_complete_shards(response)

    def _public_document_count(self, index: str) -> int:
        return len(self._candidate_public_documents(index))

    def _candidate_public_documents(
        self,
        index: str,
        accepted_events: tuple[FaqKnowledgeEvent, ...] | None = None,
    ) -> dict[str, dict[str, object]]:
        _, response = self._request(
            "POST",
            f"/{quote(index)}/_search",
            {
                "size": MAX_CANDIDATE_DOCUMENTS + 1,
                "track_total_hits": True,
                "query": {"match_all": {}},
            },
        )
        _require_complete_search(response)
        hits_wrapper = response.get("hits")
        hits = hits_wrapper.get("hits") if isinstance(hits_wrapper, dict) else None
        total = hits_wrapper.get("total") if isinstance(hits_wrapper, dict) else None
        total_value = total.get("value") if isinstance(total, dict) else None
        total_relation = total.get("relation") if isinstance(total, dict) else None
        if (
            not isinstance(hits, list)
            or type(total_value) is not int
            or total_relation != "eq"
            or total_value != len(hits)
            or len(hits) > MAX_CANDIDATE_DOCUMENTS
        ):
            raise KnowledgeRebuildError("malformed_elasticsearch_response")
        documents: dict[str, dict[str, object]] = {}
        sync_markers: dict[str, dict[str, object]] = {}
        control_records = 0
        for hit in hits:
            document_id = hit.get("_id") if isinstance(hit, dict) else None
            source = hit.get("_source") if isinstance(hit, dict) else None
            if (
                not isinstance(document_id, str)
                or not isinstance(source, dict)
                or set(source) != _PROJECTION_FIELDS
            ):
                raise KnowledgeRebuildError("candidate_public_boundary_violation")
            record_type = source.get("sync_record_type")
            if record_type == "PUBLIC_DOCUMENT":
                if document_id in documents:
                    raise KnowledgeRebuildError("candidate_public_boundary_violation")
                documents[document_id] = cast(dict[str, object], source)
            elif record_type == "SYNC_EVENT":
                self._validate_sync_marker(document_id, cast(dict[str, object], source))
                sync_markers[document_id] = cast(dict[str, object], source)
            elif record_type == "REBUILD_SWITCH" and document_id == REBUILD_RECORD_ID:
                control_records += 1
            else:
                raise KnowledgeRebuildError("candidate_public_boundary_violation")
        if control_records != 1:
            raise KnowledgeRebuildError("candidate_public_boundary_violation")
        if self._read_rebuild_record(index) is None:
            raise KnowledgeRebuildError("candidate_public_boundary_violation")
        if accepted_events is not None:
            expected_markers = self._expected_sync_markers(accepted_events)
            if sync_markers != expected_markers:
                raise KnowledgeRebuildError("candidate_commitment_mismatch")
        return documents

    @staticmethod
    def _expected_sync_markers(
        accepted_events: tuple[FaqKnowledgeEvent, ...],
    ) -> dict[str, dict[str, object]]:
        expected: dict[str, dict[str, object]] = {}
        for event in accepted_events:
            marker_id = f"__sync_event__:{event.event_id}"
            marker = ElasticsearchKnowledgeProjection._marker_source(event)
            existing = expected.get(marker_id)
            if existing is not None and existing != marker:
                raise KnowledgeRebuildError("conflicting_catch_up_event")
            expected[marker_id] = marker
        return expected

    @staticmethod
    def _validate_sync_marker(document_id: str, source: dict[str, object]) -> None:
        event_id = source.get("sync_event_id")
        commitment = source.get("sync_event_commitment")
        source_id = source.get("source_id")
        source_version = source.get("source_version")
        if (
            not isinstance(event_id, str)
            or document_id != f"__sync_event__:{event_id}"
            or not isinstance(commitment, str)
            or _SHA256.fullmatch(commitment) is None
            or source.get("content") != commitment
            or not isinstance(source_id, str)
            or _SOURCE_ID.fullmatch(source_id) is None
            or type(source_version) is not int
            or source_version < 1
            or source.get("schema_version") != KNOWLEDGE_SYNC_SCHEMA_VERSION
            or source.get("chunk_id") != "__sync_event__"
            or source.get("doc_type") != "faq"
            or source.get("published") is not False
            or source.get("deleted") is not True
            or source.get("title") != "knowledge synchronization event"
            or source.get("embedding") != [1.0, *([0.0] * (EMBEDDING_DIMS - 1))]
            or source.get("public_metadata") != {"category": "sync", "language": "und"}
        ):
            raise KnowledgeRebuildError("candidate_public_boundary_violation")
        try:
            _uuid(event_id)
            _instant(source.get("sync_occurred_at"))
        except KnowledgeRebuildError as error:
            raise KnowledgeRebuildError("candidate_public_boundary_violation") from error

    def _fixed_retrieval_probes(self, index: str) -> None:
        bm25_refund = self._search_ids(index, _bm25("退款 refund policy"))
        bm25_delivery = self._search_ids(index, _bm25("delivery guide"))
        dense_tea = self._search_ids(index, _dense(1))
        dense_refund = self._search_ids(index, _dense(0))
        internal_boundary = self._search_ids(index, _bm25("knowledge rebuild switch record"))
        if (
            not bm25_refund
            or bm25_refund[0] != "faq-refund-policy:answer"
            or "faq-delivery:answer" not in bm25_delivery
            or not dense_tea
            or dense_tea[0] != "product-jasmine-tea:description"
            or "faq-refund-policy:answer" not in dense_refund
            or internal_boundary
        ):
            raise KnowledgeRebuildError("retrieval_probe_failed")
        fused = _rrf(bm25_delivery, dense_refund)
        if not {"faq-delivery:answer", "faq-refund-policy:answer"}.issubset(fused):
            raise KnowledgeRebuildError("retrieval_probe_failed")

    def _search_ids(self, index: str, body: dict[str, object]) -> list[str]:
        _, response = self._request("POST", f"/{quote(index)}/_search", body)
        _require_complete_search(response)
        hits_wrapper = response.get("hits")
        hits = hits_wrapper.get("hits") if isinstance(hits_wrapper, dict) else None
        total = hits_wrapper.get("total") if isinstance(hits_wrapper, dict) else None
        total_value = total.get("value") if isinstance(total, dict) else None
        total_relation = total.get("relation") if isinstance(total, dict) else None
        if (
            not isinstance(hits, list)
            or type(total_value) is not int
            or total_value < 0
            or total_relation != "eq"
            or total_value < len(hits)
            or len(hits) > 8
        ):
            raise KnowledgeRebuildError("malformed_elasticsearch_response")
        result: list[str] = []
        for hit in hits:
            document_id = hit.get("_id") if isinstance(hit, dict) else None
            if not isinstance(document_id, str):
                raise KnowledgeRebuildError("malformed_elasticsearch_response")
            result.append(document_id)
        return result

    def _read_rebuild_record(self, index: str) -> dict[str, object] | None:
        status, response = self._request(
            "GET",
            f"/{quote(index)}/_doc/{quote(REBUILD_RECORD_ID, safe='')}",
            expected=(200, 404),
        )
        if status == 404:
            return None
        source = response.get("_source")
        if not isinstance(source, dict) or set(source) != _PROJECTION_FIELDS:
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        content = source.get("content")
        commitment = source.get("sync_event_commitment")
        if not isinstance(content, str) or not isinstance(commitment, str):
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        try:
            record = json.loads(content, object_pairs_hook=_unique_object)
        except (ValueError, RecursionError) as error:
            raise KnowledgeRebuildError("inconsistent_rebuild_record") from error
        if (
            not isinstance(record, dict)
            or set(record) != _REBUILD_RECORD_FIELDS
            or _digest(record) != commitment
            or _canonical(record).decode("utf-8") != content
        ):
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        self._validate_rebuild_record(cast(dict[str, object], record), index)
        match = _INDEX.fullmatch(index)
        if (
            match is None
            or source.get("schema_version") != KNOWLEDGE_SYNC_SCHEMA_VERSION
            or source.get("source_id") != "rebuild-control"
            or source.get("source_version") != int(match.group(1))
            or source.get("chunk_id") != REBUILD_RECORD_ID
            or source.get("doc_type") != "faq"
            or source.get("published") is not False
            or source.get("deleted") is not True
            or source.get("title") != "knowledge rebuild switch record"
            or source.get("embedding") != [1.0, *([0.0] * (EMBEDDING_DIMS - 1))]
            or source.get("public_metadata") != {"category": "sync", "language": "und"}
            or source.get("sync_record_type") != "REBUILD_SWITCH"
            or source.get("sync_event_id") != record.get("initialSnapshotId")
        ):
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        try:
            _internal_instant(source.get("sync_occurred_at"))
        except KnowledgeRebuildError as error:
            raise KnowledgeRebuildError("inconsistent_rebuild_record") from error
        return cast(dict[str, object], record)

    @staticmethod
    def _validate_rebuild_record(record: dict[str, object], index: str) -> None:
        candidate_match = _INDEX.fullmatch(index)
        predecessor = record.get("predecessor")
        predecessor_match = _INDEX.fullmatch(predecessor) if isinstance(predecessor, str) else None
        initial_commitment = record.get("initialSnapshotCommitment")
        state = record.get("state")
        if (
            candidate_match is None
            or record.get("schemaVersion") != REBUILD_SCHEMA_VERSION
            or record.get("candidate") != index
            or predecessor_match is None
            or int(predecessor_match.group(1)) >= int(candidate_match.group(1))
            or not isinstance(initial_commitment, str)
            or _SHA256.fullmatch(initial_commitment) is None
            or state not in {"BUILDING", "PREPARED", "SWITCHED"}
        ):
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        try:
            _uuid(record.get("initialSnapshotId"))
        except KnowledgeRebuildError as error:
            raise KnowledgeRebuildError("inconsistent_rebuild_record") from error
        handoff = record.get("handoffWatermark")
        lease = record.get("rollbackLeaseExpiresAt")
        completion_id = record.get("completionSnapshotId")
        completion_commitment = record.get("completionSnapshotCommitment")
        completion_watermark = record.get("completionWatermark")
        switched_at = record.get("switchedAt")
        if state == "BUILDING":
            if any(
                value is not None
                for value in (
                    handoff,
                    lease,
                    completion_id,
                    completion_commitment,
                    completion_watermark,
                    switched_at,
                )
            ):
                raise KnowledgeRebuildError("inconsistent_rebuild_record")
            return
        if (
            not isinstance(handoff, str)
            or _SHA256.fullmatch(handoff) is None
            or not isinstance(lease, str)
        ):
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        try:
            _internal_instant(lease)
        except KnowledgeRebuildError as error:
            raise KnowledgeRebuildError("inconsistent_rebuild_record") from error
        if state == "PREPARED":
            if any(
                value is not None
                for value in (
                    completion_id,
                    completion_commitment,
                    completion_watermark,
                    switched_at,
                )
            ):
                raise KnowledgeRebuildError("inconsistent_rebuild_record")
            return
        if (
            not isinstance(completion_commitment, str)
            or _SHA256.fullmatch(completion_commitment) is None
            or not isinstance(completion_watermark, str)
            or _SHA256.fullmatch(completion_watermark) is None
            or not isinstance(switched_at, str)
        ):
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        try:
            _uuid(completion_id)
            _internal_instant(switched_at)
        except KnowledgeRebuildError as error:
            raise KnowledgeRebuildError("inconsistent_rebuild_record") from error

    def _write_rebuild_record(
        self, index: str, record: dict[str, object], *, create: bool = False
    ) -> None:
        if set(record) != _REBUILD_RECORD_FIELDS:
            raise KnowledgeRebuildError("inconsistent_rebuild_record")
        content = _canonical(record).decode("utf-8")
        source = {
            "schema_version": KNOWLEDGE_SYNC_SCHEMA_VERSION,
            "source_id": "rebuild-control",
            "source_version": int(_INDEX.fullmatch(index).group(1)),  # type: ignore[union-attr]
            "chunk_id": REBUILD_RECORD_ID,
            "doc_type": "faq",
            "published": False,
            "deleted": True,
            "title": "knowledge rebuild switch record",
            "content": content,
            "embedding": [1.0, *([0.0] * (EMBEDDING_DIMS - 1))],
            "public_metadata": {"category": "sync", "language": "und"},
            "sync_record_type": "REBUILD_SWITCH",
            "sync_event_id": record["initialSnapshotId"],
            "sync_event_commitment": _digest(record),
            "sync_occurred_at": _now(self._now),
        }
        suffix = "?op_type=create" if create else ""
        status, _ = self._request(
            "PUT",
            f"/{quote(index)}/_doc/{quote(REBUILD_RECORD_ID, safe='')}{suffix}",
            source,
            expected=(200, 201, 409) if create else (200, 201),
        )
        if status == 409:
            raise KnowledgeRebuildError("concurrent_rebuild")

    @staticmethod
    def _require_record_identity(record: dict[str, object], candidate: str) -> None:
        if (
            record.get("schemaVersion") != REBUILD_SCHEMA_VERSION
            or record.get("candidate") != candidate
            or not isinstance(record.get("predecessor"), str)
            or _INDEX.fullmatch(cast(str, record.get("predecessor"))) is None
        ):
            raise KnowledgeRebuildError("inconsistent_rebuild_record")

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        *,
        expected: tuple[int, ...] = (200, 201),
    ) -> tuple[int, dict[str, Any]]:
        data = None if payload is None else _canonical(payload)
        request = Request(
            f"{self._base_url}{path}",
            data=data,
            headers={"Content-Type": "application/json"},
            method=method,
        )
        return self._execute(request, expected)

    def _raw_request(
        self, method: str, path: str, payload: bytes, content_type: str
    ) -> dict[str, Any]:
        request = Request(
            f"{self._base_url}{path}",
            data=payload,
            headers={"Content-Type": content_type},
            method=method,
        )
        _, response = self._execute(request, (200,))
        return response

    def _execute(self, request: Request, expected: tuple[int, ...]) -> tuple[int, dict[str, Any]]:
        try:
            try:
                with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310
                    status = response.status
                    body = response.read(MAX_SNAPSHOT_BYTES + 1)
            except HTTPError as error:
                status = error.code
                body = error.read(MAX_SNAPSHOT_BYTES + 1)
        except (URLError, TimeoutError, OSError, HTTPException) as error:
            raise KnowledgeRebuildError("elasticsearch_unavailable") from error
        if len(body) > MAX_SNAPSHOT_BYTES:
            raise KnowledgeRebuildError("malformed_elasticsearch_response")
        if status not in expected:
            raise KnowledgeRebuildError(
                "elasticsearch_unavailable" if status >= 500 else "elasticsearch_rejected"
            )
        if not body:
            return status, {}
        try:
            decoded = json.loads(body, object_pairs_hook=_unique_object)
        except (ValueError, UnicodeDecodeError, RecursionError) as error:
            raise KnowledgeRebuildError("malformed_elasticsearch_response") from error
        if not isinstance(decoded, dict):
            raise KnowledgeRebuildError("malformed_elasticsearch_response")
        return status, cast(dict[str, Any], decoded)


class KnowledgeRebuildCoordinator:
    def __init__(
        self,
        elasticsearch: RebuildIndex,
        *,
        catch_up_wait_seconds: float = 10.0,
    ) -> None:
        if catch_up_wait_seconds <= 0 or catch_up_wait_seconds > 60:
            raise ValueError("Knowledge rebuild catch-up window is invalid")
        self._elasticsearch = elasticsearch
        self._catch_up_wait_seconds = catch_up_wait_seconds

    def rebuild(self, source: SnapshotSource, journal: AcceptedEventJournal) -> RebuildResult:
        initial = source.capture()
        predecessor = self._elasticsearch.resolve_alias()
        pending = self._elasticsearch.pending_switch(predecessor)
        if pending is not None:
            self._elasticsearch.load_snapshot(predecessor, initial)
            accepted = self._catch_up(predecessor, initial, initial, journal)
            self._elasticsearch.validate_candidate(predecessor, initial, accepted)
            converged = self._post_switch_converge(predecessor, initial, source, journal)
            journal.commit(converged)
            self._elasticsearch.complete_switch(predecessor, converged)
            return RebuildResult(
                predecessor=pending.predecessor,
                candidate=predecessor,
                handoff_watermark=pending.handoff_watermark,
                rollback_lease_expires_at=pending.rollback_lease_expires_at,
                document_count=len(converged.records),
                replayed=True,
            )
        existing = self._elasticsearch.existing_result(predecessor, initial)
        if existing is not None:
            accepted = self._catch_up(predecessor, initial, initial, journal)
            self._elasticsearch.validate_candidate(predecessor, initial, accepted)
            journal.commit(initial)
            return existing
        candidate = self._elasticsearch.candidate_for(predecessor, initial)
        self._elasticsearch.create_or_resume_candidate(predecessor, candidate, initial)
        self._elasticsearch.load_snapshot(candidate, initial)
        current = initial
        handoff: KnowledgeSnapshot | None = None
        for _ in range(MAX_CATCH_UP_PASSES):
            latest = source.capture()
            self._require_monotonic_capture(current, latest)
            accepted = self._catch_up(candidate, current, latest, journal)
            self._elasticsearch.validate_candidate(candidate, latest, accepted)
            confirm = source.capture()
            self._require_monotonic_capture(latest, confirm)
            if confirm.watermark != latest.watermark:
                current = latest
                continue
            accepted = self._catch_up(candidate, latest, confirm, journal)
            self._elasticsearch.validate_candidate(candidate, confirm, accepted)
            final = source.capture()
            self._require_monotonic_capture(confirm, final)
            if final.watermark != confirm.watermark:
                current = confirm
                continue
            accepted = self._catch_up(candidate, confirm, final, journal)
            self._elasticsearch.validate_candidate(candidate, final, accepted)
            switch_check = source.capture()
            self._require_monotonic_capture(final, switch_check)
            if switch_check.watermark != final.watermark:
                current = final
                continue
            self._catch_up(candidate, final, switch_check, journal)
            handoff = switch_check
            break
        if handoff is None:
            raise KnowledgeRebuildError("stale_catch_up")
        lease = self._elasticsearch.prepare_switch(candidate, initial, handoff)
        accepted = self._catch_up(candidate, handoff, handoff, journal)
        self._elasticsearch.validate_candidate(candidate, handoff, accepted)
        self._elasticsearch.atomic_switch(predecessor, candidate)
        converged = self._post_switch_converge(candidate, handoff, source, journal)
        journal.commit(converged)
        self._elasticsearch.complete_switch(candidate, converged)
        return RebuildResult(
            predecessor=predecessor,
            candidate=candidate,
            handoff_watermark=handoff.watermark,
            rollback_lease_expires_at=lease,
            document_count=len(converged.records),
            replayed=False,
        )

    def _post_switch_converge(
        self,
        candidate: str,
        handoff: KnowledgeSnapshot,
        source: SnapshotSource,
        journal: AcceptedEventJournal,
    ) -> KnowledgeSnapshot:
        current = handoff
        for _ in range(MAX_CATCH_UP_PASSES):
            latest = source.capture()
            self._require_monotonic_capture(current, latest)
            accepted = self._catch_up(candidate, current, latest, journal)
            self._elasticsearch.validate_candidate(candidate, latest, accepted)
            confirm = source.capture()
            self._require_monotonic_capture(latest, confirm)
            if confirm.watermark != latest.watermark:
                current = latest
                continue
            accepted = self._catch_up(candidate, latest, confirm, journal)
            self._elasticsearch.validate_candidate(candidate, confirm, accepted)
            return confirm
        raise KnowledgeRebuildError("stale_after_switch")

    @staticmethod
    def _require_monotonic_capture(previous: KnowledgeSnapshot, current: KnowledgeSnapshot) -> None:
        current_time = datetime.fromisoformat(current.captured_at.replace("Z", "+00:00"))
        previous_time = datetime.fromisoformat(previous.captured_at.replace("Z", "+00:00"))
        if current_time < previous_time:
            raise KnowledgeRebuildError("snapshot_time_regressed")
        previous_states = previous.source_states
        for source_id, state in current.source_states.items():
            old = previous_states.get(source_id)
            if old is not None and state.source_version < old.source_version:
                raise KnowledgeRebuildError("snapshot_version_regressed")
            if old is not None and state.source_version == old.source_version and state != old:
                raise KnowledgeRebuildError("conflicting_snapshot_source_version")
        if set(previous_states).difference(current.source_states):
            raise KnowledgeRebuildError("snapshot_source_disappeared")

    def _catch_up(
        self,
        candidate: str,
        previous: KnowledgeSnapshot,
        current: KnowledgeSnapshot,
        journal: AcceptedEventJournal,
    ) -> tuple[FaqKnowledgeEvent, ...]:
        if current.watermark == previous.watermark:
            committed = self._committed_events(current, journal.events())
            self._materialize_committed_events(candidate, committed)
            return committed
        previous_states = previous.source_states
        current_states = current.source_states
        changed = [
            source_id
            for source_id, state in current_states.items()
            if previous_states.get(source_id) != state
        ]
        deadline = time.monotonic() + self._catch_up_wait_seconds
        events: tuple[FaqKnowledgeEvent, ...] = ()
        while True:
            events = journal.events()
            if self._events_cover(previous_states, current_states, changed, events):
                break
            if time.monotonic() >= deadline:
                raise KnowledgeRebuildError("missing_catch_up_event")
            time.sleep(0.05)
        indexed = _index_events(events)
        committed = self._committed_events(current, events)
        self._materialize_committed_events(candidate, committed)
        for source_id in sorted(changed):
            old = previous_states.get(source_id)
            new = current_states[source_id]
            if new.doc_type != "faq":
                raise KnowledgeRebuildError("unsupported_concurrent_source_change")
            start = 1 if old is None else old.source_version + 1
            if any(
                (source_id, version) not in indexed
                for version in range(start, new.source_version + 1)
            ):
                raise KnowledgeRebuildError("missing_catch_up_event")
            record = next(record for record in current.records if record.source_id == source_id)
            event = indexed[(source_id, new.source_version)]
            if record.as_projection_source() != ElasticsearchKnowledgeProjection._projection_source(
                event
            ):
                raise KnowledgeRebuildError("catch_up_snapshot_mismatch")
        return committed

    def _materialize_committed_events(
        self,
        candidate: str,
        events: tuple[FaqKnowledgeEvent, ...],
    ) -> None:
        for event in events:
            self._elasticsearch.apply_event(candidate, event)

    @staticmethod
    def _events_cover(
        previous: dict[str, _SourceState],
        current: dict[str, _SourceState],
        changed: list[str],
        events: tuple[FaqKnowledgeEvent, ...],
    ) -> bool:
        try:
            indexed = _index_events(events)
        except KnowledgeRebuildError:
            return True
        for source_id in changed:
            old = previous.get(source_id)
            new = current[source_id]
            if new.doc_type != "faq":
                return True
            start = 1 if old is None else old.source_version + 1
            if any(
                (source_id, version) not in indexed
                for version in range(start, new.source_version + 1)
            ):
                return False
        return True

    @staticmethod
    def _committed_events(
        snapshot: KnowledgeSnapshot, events: tuple[FaqKnowledgeEvent, ...]
    ) -> tuple[FaqKnowledgeEvent, ...]:
        _index_events(events)
        captured = datetime.fromisoformat(snapshot.captured_at.replace("Z", "+00:00"))
        states = snapshot.source_states
        committed: list[FaqKnowledgeEvent] = []
        for event in events:
            occurred = datetime.fromisoformat(event.occurred_time.replace("Z", "+00:00"))
            current = states.get(event.source_id)
            if current is not None and event.source_version == current.source_version:
                record = next(
                    item for item in snapshot.records if item.source_id == event.source_id
                )
                if (
                    current.doc_type != "faq"
                    or record.as_projection_source()
                    != ElasticsearchKnowledgeProjection._projection_source(event)
                ):
                    raise KnowledgeRebuildError("catch_up_snapshot_mismatch")
            if occurred <= captured and (
                current is None or event.source_version > current.source_version
            ):
                raise KnowledgeRebuildError("snapshot_behind_accepted_event")
            if current is not None and event.source_version <= current.source_version:
                if current.doc_type != "faq":
                    raise KnowledgeRebuildError("unsupported_concurrent_source_change")
                committed.append(event)
        return tuple(committed)


def _index_events(
    events: Iterable[FaqKnowledgeEvent],
) -> dict[tuple[str, int], FaqKnowledgeEvent]:
    indexed: dict[tuple[str, int], FaqKnowledgeEvent] = {}
    for event in events:
        key = (event.source_id, event.source_version)
        current = indexed.get(key)
        if current is not None and current.commitment != event.commitment:
            raise KnowledgeRebuildError("conflicting_catch_up_event")
        indexed[key] = event
    return indexed


def _require_complete_shards(payload: dict[str, Any]) -> None:
    shards = payload.get("_shards")
    if not isinstance(shards, dict):
        raise KnowledgeRebuildError("partial_shard_failure")
    total = shards.get("total")
    successful = shards.get("successful")
    failed = shards.get("failed")
    if (
        type(total) is not int
        or type(successful) is not int
        or type(failed) is not int
        or total < 1
        or successful != total
        or failed != 0
    ):
        raise KnowledgeRebuildError("partial_shard_failure")


def _require_complete_search(payload: dict[str, Any]) -> None:
    if payload.get("timed_out") is not False:
        raise KnowledgeRebuildError("partial_search_failure")
    _require_complete_shards(payload)


def _bm25(query: str) -> dict[str, object]:
    return {
        "size": 8,
        "track_total_hits": True,
        "sort": [{"_score": "desc"}, {"source_id": "asc"}, {"chunk_id": "asc"}],
        "query": {
            "bool": {
                "filter": [
                    {"term": {"published": True}},
                    {"term": {"deleted": False}},
                ],
                "must": [
                    {
                        "multi_match": {
                            "query": query,
                            "fields": ["title^2", "content"],
                            "type": "best_fields",
                        }
                    }
                ],
            }
        },
    }


def _dense(dimension: int) -> dict[str, object]:
    vector = [0.0] * EMBEDDING_DIMS
    vector[dimension] = 1.0
    return {
        "size": 8,
        "track_total_hits": True,
        "sort": [{"_score": "desc"}, {"source_id": "asc"}, {"chunk_id": "asc"}],
        "knn": {
            "field": "embedding",
            "query_vector": vector,
            "k": 8,
            "num_candidates": 16,
            "filter": {
                "bool": {
                    "filter": [
                        {"term": {"published": True}},
                        {"term": {"deleted": False}},
                    ]
                }
            },
        },
    }


def _rrf(first: list[str], second: list[str]) -> set[str]:
    scores: dict[str, float] = {}
    for candidates in (first, second):
        for rank, document_id in enumerate(candidates, start=1):
            scores[document_id] = scores.get(document_id, 0.0) + 1 / (60 + rank)
    return set(sorted(scores, key=lambda document_id: (-scores[document_id], document_id))[:5])


def _now(clock: Callable[[], datetime]) -> str:
    value = clock().astimezone(UTC)
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")
