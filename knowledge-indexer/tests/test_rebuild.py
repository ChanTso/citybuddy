import hashlib
import json
import threading
from collections.abc import Callable
from copy import deepcopy
from datetime import UTC, datetime
from http.client import HTTPException
from uuid import UUID

import citybuddy_indexer.incremental as incremental_module
import citybuddy_indexer.rebuild as rebuild_module
import citybuddy_indexer.rebuild_runtime as rebuild_runtime_module
import pytest
from citybuddy_indexer.incremental import (
    RESERVED_SANDBOX_PROPERTY,
    ElasticsearchKnowledgeProjection,
    FaqKnowledgeEvent,
    ProjectionOutcome,
)
from citybuddy_indexer.rebuild import (
    ElasticsearchRebuildClient,
    KnowledgeRebuildCoordinator,
    KnowledgeRebuildError,
    KnowledgeSnapshot,
    RebuildResult,
    _internal_instant,
    _now,
)
from citybuddy_indexer.rebuild_runtime import HttpOwnerSnapshotSource, RocketMqAcceptedEventJournal


def canonical(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()


def digest(value: object) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


def stable_snapshot_id(content_commitment: str, watermark: str) -> str:
    raw = bytearray(
        hashlib.md5(  # noqa: S324 - exact Java UUID.nameUUIDFromBytes test fixture.
            f"{content_commitment}:{watermark}".encode(), usedforsecurity=False
        ).digest()
    )
    raw[6] = (raw[6] & 0x0F) | 0x30
    raw[8] = (raw[8] & 0x3F) | 0x80
    return str(UUID(bytes=bytes(raw)))


def faq_record(*, version: int = 1, tombstone: bool = False) -> dict[str, object]:
    return {
        "eventId": f"11111111-1111-4111-8111-{version:012d}",
        "sourceId": "faq-refund-policy",
        "sourceVersion": version,
        "chunkId": "answer",
        "docType": "faq",
        "publicationState": "PUBLISHED",
        "tombstone": tombstone,
        "occurredTime": f"2026-07-22T00:00:0{version}.123456789Z",
        "title": "退款政策 Refund policy",
        "content": "Public refund guidance.",
        "publicMetadata": {"category": "faq", "language": "und"},
    }


def product_record() -> dict[str, object]:
    return {
        "eventId": "22222222-2222-4222-8222-222222222222",
        "sourceId": "product-jasmine-tea",
        "sourceVersion": 3,
        "chunkId": "description",
        "docType": "product",
        "publicationState": "PUBLISHED",
        "tombstone": False,
        "occurredTime": "2026-07-22T00:00:03.123456789Z",
        "title": "茉莉绿茶 Jasmine green tea",
        "content": "A public jasmine tea description.",
        "publicMetadata": {
            "category": "product",
            "language": "und",
            "productId": "product-jasmine-tea",
        },
    }


def snapshot_payload(
    records: list[dict[str, object]] | None = None,
    *,
    snapshot_id: str | None = None,
    captured_at: str = "2026-07-22T00:00:04.123456789Z",
) -> dict[str, object]:
    selected = records or [faq_record(), product_record()]
    states: dict[str, dict[str, object]] = {}
    for record in selected:
        source_id = str(record["sourceId"])
        commitment = digest(record)
        current = states.get(source_id)
        if current is None:
            states[source_id] = {
                "sourceId": source_id,
                "sourceVersion": record["sourceVersion"],
                "docType": record["docType"],
                "tombstone": record["tombstone"],
                "eventId": record["eventId"],
                "occurredTime": record["occurredTime"],
                "recordCommitments": [commitment],
            }
        else:
            current_commitments = current["recordCommitments"]
            assert isinstance(current_commitments, list)
            current_commitments.append(commitment)
    ordered_states = [states[source_id] for source_id in sorted(states)]
    content_commitment = digest(selected)
    watermark = digest(ordered_states)
    return {
        "schemaVersion": "cb113-v1",
        "snapshotId": snapshot_id or stable_snapshot_id(content_commitment, watermark),
        "capturedAt": captured_at,
        "recordCount": len(selected),
        "sourceCount": len(states),
        "contentCommitment": content_commitment,
        "watermark": watermark,
        "records": selected,
    }


def snapshot(
    records: list[dict[str, object]] | None = None,
    *,
    snapshot_id: str | None = None,
    captured_at: str = "2026-07-22T00:00:04.123456789Z",
) -> KnowledgeSnapshot:
    return KnowledgeSnapshot.from_bytes(
        canonical(snapshot_payload(records, snapshot_id=snapshot_id, captured_at=captured_at))
    )


def markers(*events: FaqKnowledgeEvent) -> dict[str, dict[str, object]]:
    return {
        f"__sync_event__:{event.event_id}": ElasticsearchKnowledgeProjection._marker_source(event)
        for event in events
    }


def test_snapshot_accepts_exact_closed_committed_owner_payload() -> None:
    parsed = snapshot()

    assert parsed.snapshot_id == stable_snapshot_id(parsed.content_commitment, parsed.watermark)
    assert len(parsed.records) == 2
    assert set(parsed.documents) == {
        "faq-refund-policy:answer",
        "product-jasmine-tea:description",
    }
    assert parsed.documents["faq-refund-policy:answer"]["sync_record_type"] == "PUBLIC_DOCUMENT"


@pytest.mark.parametrize(
    "mutation",
    [
        lambda value: value.update({"private": "not-public"}),
        lambda value: value.update({"recordCount": 1}),
        lambda value: value.update({"sourceCount": 1}),
        lambda value: value.update({"contentCommitment": "0" * 64}),
        lambda value: value.update({"watermark": "0" * 64}),
        lambda value: value.update({"snapshotId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}),
        lambda value: value["records"][0].update({"private": "not-public"}),
        lambda value: value["records"][0].update({"publicationState": "DRAFT"}),
        lambda value: value["records"][0].update({"sourceVersion": 0}),
        lambda value: value["records"][0].update({"chunkId": "overview"}),
        lambda value: value["records"][0].update({"occurredTime": "2026-07-22T00:00:01Zjunk"}),
        lambda value: value["records"][0].update(
            {"publicMetadata": {"category": "faq", "language": "und", "owner": "private"}}
        ),
        lambda value: value["records"].reverse(),
        lambda value: value["records"].append(deepcopy(value["records"][0])),
    ],
)
def test_snapshot_rejects_incomplete_malformed_or_private_payload(mutation: object) -> None:
    payload = snapshot_payload()
    mutation(payload)  # type: ignore[operator]

    with pytest.raises(KnowledgeRebuildError):
        KnowledgeSnapshot.from_bytes(canonical(payload))


def test_snapshot_preserves_nine_digit_owner_timestamps() -> None:
    parsed = snapshot()

    assert parsed.records[0].occurred_time.endswith(".123456789Z")


def test_snapshot_order_uses_source_and_chunk_tuple_for_prefix_identities() -> None:
    first = faq_record()
    first["sourceId"] = "a"
    second = faq_record(version=2)
    second["sourceId"] = "a-b"

    parsed = snapshot([first, second])

    assert [record.source_id for record in parsed.records] == ["a", "a-b"]


def test_internal_control_timestamp_keeps_fixed_microsecond_precision() -> None:
    rendered = _now(lambda: datetime(2026, 7, 22, 1, 2, 3, 123450, tzinfo=UTC))

    assert rendered == "2026-07-22T01:02:03.123450Z"
    assert _internal_instant(rendered) == rendered


def test_owner_snapshot_http_framing_failure_is_bounded(monkeypatch: pytest.MonkeyPatch) -> None:
    def fail(*_args: object, **_kwargs: object) -> object:
        raise HTTPException("malformed framing")

    monkeypatch.setattr(rebuild_runtime_module, "urlopen", fail)
    source = HttpOwnerSnapshotSource(
        "http://commerce/internal/knowledge/snapshot", "client", "secret"
    )

    with pytest.raises(KnowledgeRebuildError, match="snapshot_source_unavailable"):
        source.capture()


def test_elasticsearch_http_framing_failure_is_bounded(monkeypatch: pytest.MonkeyPatch) -> None:
    def fail(*_args: object, **_kwargs: object) -> object:
        raise HTTPException("malformed framing")

    monkeypatch.setattr(rebuild_module, "urlopen", fail)

    with pytest.raises(KnowledgeRebuildError, match="elasticsearch_unavailable"):
        ElasticsearchRebuildClient("http://elasticsearch:9200")._request(  # noqa: SLF001
            "GET", "/knowledge_docs_v1"
        )


class Source:
    def __init__(self, snapshots: list[KnowledgeSnapshot]) -> None:
        self._snapshots = snapshots
        self.calls = 0

    def capture(self) -> KnowledgeSnapshot:
        selected = self._snapshots[min(self.calls, len(self._snapshots) - 1)]
        self.calls += 1
        return selected


class Journal:
    def __init__(self, events: tuple[FaqKnowledgeEvent, ...] = ()) -> None:
        self._events = events
        self.committed: list[str] = []

    def events(self) -> tuple[FaqKnowledgeEvent, ...]:
        return self._events

    def replace(self, events: tuple[FaqKnowledgeEvent, ...]) -> None:
        self._events = events

    def commit(
        self,
        selected: KnowledgeSnapshot,
        validated_markers: dict[str, dict[str, object]],
    ) -> None:
        del validated_markers
        self.committed.append(selected.watermark)


class FailingCommitJournal(Journal):
    def commit(
        self,
        selected: KnowledgeSnapshot,
        validated_markers: dict[str, dict[str, object]],
    ) -> None:
        del selected, validated_markers
        raise KnowledgeRebuildError("catch_up_snapshot_mismatch")


class Elasticsearch:
    def __init__(
        self,
        *,
        pending: RebuildResult | None = None,
        on_switch: Callable[[], None] | None = None,
    ) -> None:
        self.calls: list[tuple[str, object]] = []
        self._pending = pending
        self._on_switch = on_switch

    def resolve_alias(self) -> str:
        self.calls.append(("resolve", "knowledge_docs_v1"))
        return "knowledge_docs_v1"

    def existing_result(self, current: str, initial: KnowledgeSnapshot) -> RebuildResult | None:
        self.calls.append(("existing", current))
        return None

    def pending_switch(self, current: str) -> RebuildResult | None:
        self.calls.append(("pending", current))
        return self._pending

    def candidate_for(self, predecessor: str, initial: KnowledgeSnapshot) -> str:
        self.calls.append(("next", predecessor))
        return "knowledge_docs_v2"

    def create_or_resume_candidate(
        self, predecessor: str, candidate: str, initial: KnowledgeSnapshot
    ) -> None:
        self.calls.append(("create", candidate))

    def load_snapshot(self, candidate: str, selected: KnowledgeSnapshot) -> None:
        self.calls.append(("load", selected.watermark))

    def apply_event(self, candidate: str, event: FaqKnowledgeEvent) -> ProjectionOutcome:
        self.calls.append(("event", (event.source_id, event.source_version)))
        return ProjectionOutcome.APPLIED

    def validate_candidate(
        self,
        candidate: str,
        selected: KnowledgeSnapshot,
        accepted_events: tuple[FaqKnowledgeEvent, ...],
    ) -> None:
        del candidate, accepted_events
        self.calls.append(("validate", selected.watermark))

    def seal_journal_events(
        self,
        candidate: str,
        accepted_events: tuple[FaqKnowledgeEvent, ...],
    ) -> dict[str, dict[str, object]]:
        del candidate
        return {
            f"__sync_event__:{event.event_id}": ElasticsearchKnowledgeProjection._marker_source(
                event
            )
            for event in accepted_events
        }

    def prepare_switch(
        self,
        candidate: str,
        initial: KnowledgeSnapshot,
        handoff: KnowledgeSnapshot,
    ) -> str:
        self.calls.append(("prepare", handoff.watermark))
        return "2026-07-22T01:00:00Z"

    def atomic_switch(self, predecessor: str, candidate: str) -> None:
        self.calls.append(("switch", (predecessor, candidate)))
        if self._on_switch is not None:
            self._on_switch()

    def complete_switch(self, candidate: str, completion: KnowledgeSnapshot) -> None:
        self.calls.append(("complete", candidate))


def test_coordinator_confirms_handoff_before_durable_prepare() -> None:
    initial = snapshot()
    source = Source([initial])
    elasticsearch = Elasticsearch()
    journal = Journal()

    result = KnowledgeRebuildCoordinator(elasticsearch, catch_up_wait_seconds=0.1).rebuild(
        source, journal
    )

    assert result.candidate == "knowledge_docs_v2"
    assert source.calls == 7
    names = [name for name, _ in elasticsearch.calls]
    assert names.index("prepare") > names.index("validate")
    assert names.index("switch") < names.index("complete")
    assert names[-1] == "complete"
    assert journal.committed == [initial.watermark]


def test_broker_commit_failure_does_not_claim_switch_completion() -> None:
    initial = snapshot()
    elasticsearch = Elasticsearch()

    with pytest.raises(KnowledgeRebuildError, match="catch_up_snapshot_mismatch"):
        KnowledgeRebuildCoordinator(elasticsearch, catch_up_wait_seconds=0.1).rebuild(
            Source([initial]), FailingCommitJournal()
        )

    assert all(name != "complete" for name, _ in elasticsearch.calls)


def test_coordinator_replays_every_contiguous_version_before_switch() -> None:
    first = snapshot([faq_record(version=1)])
    second = snapshot(
        [faq_record(version=3, tombstone=True)],
        captured_at="2026-07-22T00:00:05.123456789Z",
    )
    event_two = faq_record(version=2)
    event_three = faq_record(version=3, tombstone=True)
    events = tuple(
        KnowledgeSnapshot.from_bytes(canonical(snapshot_payload([record])))
        .records[0]
        .as_faq_event()
        for record in (event_two, event_three)
    )
    source = Source([first, second, second, second, second])
    elasticsearch = Elasticsearch()

    KnowledgeRebuildCoordinator(elasticsearch, catch_up_wait_seconds=0.1).rebuild(
        source, Journal(events)
    )

    replayed = [value for name, value in elasticsearch.calls if name == "event"]
    assert list(dict.fromkeys(replayed)) == [
        ("faq-refund-policy", 2),
        ("faq-refund-policy", 3),
    ]
    assert replayed.index(("faq-refund-policy", 2)) < replayed.index(("faq-refund-policy", 3))


def test_event_accepted_in_alias_window_is_applied_before_success() -> None:
    first = snapshot([faq_record(version=1)])
    updated_record = faq_record(version=2)
    updated_record["occurredTime"] = "2026-07-22T00:00:05.123456789Z"
    updated = snapshot(
        [updated_record],
        captured_at="2026-07-22T00:00:06.123456789Z",
    )
    event = updated.records[0].as_faq_event()
    journal = Journal()
    elasticsearch = Elasticsearch(on_switch=lambda: journal.replace((event,)))
    source = Source([first, first, first, first, first, updated, updated])

    result = KnowledgeRebuildCoordinator(elasticsearch, catch_up_wait_seconds=0.1).rebuild(
        source, journal
    )

    switch_position = next(
        index for index, call in enumerate(elasticsearch.calls) if call[0] == "switch"
    )
    event_position = next(
        index
        for index, call in enumerate(elasticsearch.calls)
        if call == ("event", ("faq-refund-policy", 2))
    )
    assert switch_position < event_position
    assert elasticsearch.calls[-1] == ("complete", "knowledge_docs_v2")
    assert result.document_count == 1


def test_prepared_alias_restart_reloads_owner_truth_before_completion() -> None:
    current = snapshot()
    pending = RebuildResult(
        "knowledge_docs_v0",
        "knowledge_docs_v1",
        "a" * 64,
        "2026-07-22T01:00:00Z",
        1,
        True,
    )
    elasticsearch = Elasticsearch(pending=pending)
    journal = Journal()

    result = KnowledgeRebuildCoordinator(elasticsearch, catch_up_wait_seconds=0.1).rebuild(
        Source([current]), journal
    )

    names = [name for name, _ in elasticsearch.calls]
    assert names == [
        "resolve",
        "pending",
        "load",
        "validate",
        "validate",
        "validate",
        "complete",
    ]
    assert result.replayed is True
    assert result.document_count == 2
    assert journal.committed == [current.watermark]


def test_snapshot_source_version_cannot_regress_or_change_at_equal_version() -> None:
    first = snapshot([faq_record(version=2)])
    regressed = snapshot(
        [faq_record(version=1)],
        captured_at="2026-07-22T00:00:05.123456789Z",
    )
    changed_record = faq_record(version=2)
    changed_record["content"] = "Conflicting same-version content"
    conflicting = snapshot(
        [changed_record],
        captured_at="2026-07-22T00:00:06.123456789Z",
    )

    with pytest.raises(KnowledgeRebuildError, match="snapshot_version_regressed"):
        KnowledgeRebuildCoordinator._require_monotonic_capture(first, regressed)
    with pytest.raises(KnowledgeRebuildError, match="conflicting_snapshot_source_version"):
        KnowledgeRebuildCoordinator._require_monotonic_capture(first, conflicting)


def test_equal_version_broker_event_must_match_owner_snapshot() -> None:
    owner = snapshot([faq_record(version=1)])
    contradictory = faq_record(version=1)
    contradictory["content"] = "Contradictory accepted content."
    event = snapshot([contradictory]).records[0].as_faq_event()

    with pytest.raises(KnowledgeRebuildError, match="catch_up_snapshot_mismatch"):
        KnowledgeRebuildCoordinator._committed_events(owner, (event,))


def event_bytes(record: dict[str, object]) -> bytes:
    return canonical(
        {
            "eventId": record["eventId"],
            "sourceId": record["sourceId"],
            "sourceType": "faq",
            "sourceVersion": record["sourceVersion"],
            "publicationState": "PUBLISHED",
            "tombstone": record["tombstone"],
            "occurredTime": record["occurredTime"],
            "content": {"question": record["title"], "answer": record["content"]},
        }
    )


class FakeBrokerConsumer:
    def __init__(self) -> None:
        self.acked: list[object] = []

    def ack(self, message: object) -> None:
        self.acked.append(message)


class FailSecondAckConsumer(FakeBrokerConsumer):
    def ack(self, message: object) -> None:
        if self.acked:
            raise RuntimeError("controlled second ACK failure")
        super().ack(message)


class FakeBrokerMessage:
    def __init__(
        self,
        message_id: str,
        body: bytes,
        properties: dict[str, str] | None = None,
    ) -> None:
        self.message_id = message_id
        self.body = body
        self.properties = properties or {}


def test_broker_journal_rejects_reserved_evaluation_carrier() -> None:
    message = FakeBrokerMessage(
        "broker-message-evaluation",
        event_bytes(faq_record(version=2)),
        {RESERVED_SANDBOX_PROPERTY: "synthetic"},
    )
    journal = RocketMqAcceptedEventJournal.__new__(RocketMqAcceptedEventJournal)

    assert journal._accept(message) is False


def test_broker_journal_defers_ack_until_owner_snapshot_covers_event() -> None:
    first = faq_record(version=1)
    second = faq_record(version=2)
    message = FakeBrokerMessage("broker-message-v2", event_bytes(second))
    consumer = FakeBrokerConsumer()
    journal = RocketMqAcceptedEventJournal.__new__(RocketMqAcceptedEventJournal)
    journal._consumer = consumer
    journal._events = {}
    journal._pending_messages = {}
    journal._condition = threading.Condition()
    journal._failure = None

    assert journal._accept(message) is True
    assert consumer.acked == []

    journal.commit(snapshot([first]), {})
    assert consumer.acked == []

    journal.commit(snapshot([second]), markers(snapshot([second]).records[0].as_faq_event()))
    assert consumer.acked == [message]


def test_broker_commit_rechecks_equal_version_intent_before_ack() -> None:
    owner = faq_record(version=2)
    contradictory = faq_record(version=2)
    contradictory["content"] = "Contradictory same-version content."
    message = FakeBrokerMessage("broker-message-conflict", event_bytes(contradictory))
    consumer = FakeBrokerConsumer()
    journal = RocketMqAcceptedEventJournal.__new__(RocketMqAcceptedEventJournal)
    journal._consumer = consumer
    journal._events = {}
    journal._pending_messages = {}
    journal._condition = threading.Condition()
    journal._failure = None

    assert journal._accept(message) is True

    with pytest.raises(KnowledgeRebuildError, match="catch_up_snapshot_mismatch"):
        journal.commit(
            snapshot([owner]),
            markers(snapshot([contradictory]).records[0].as_faq_event()),
        )
    assert consumer.acked == []


def test_broker_commit_rejects_covered_event_not_in_validated_checkpoint() -> None:
    owner = faq_record(version=2)
    older = faq_record(version=1)
    message = FakeBrokerMessage("broker-message-late-v1", event_bytes(older))
    consumer = FakeBrokerConsumer()
    journal = RocketMqAcceptedEventJournal.__new__(RocketMqAcceptedEventJournal)
    journal._consumer = consumer
    journal._events = {}
    journal._pending_messages = {}
    journal._condition = threading.Condition()
    journal._failure = None

    assert journal._accept(message) is True

    with pytest.raises(KnowledgeRebuildError, match="journal_changed_after_validation"):
        journal.commit(snapshot([owner]), markers())
    assert consumer.acked == []


def test_partial_ack_keeps_validated_prefix_in_durable_checkpoint() -> None:
    first = faq_record(version=1)
    second = faq_record(version=1)
    second["eventId"] = "22222222-2222-4222-8222-222222222222"
    second["sourceId"] = "faq-delivery"
    first_event = snapshot([first]).records[0].as_faq_event()
    second_event = snapshot([second]).records[0].as_faq_event()
    messages = [
        FakeBrokerMessage("broker-message-first", event_bytes(first)),
        FakeBrokerMessage("broker-message-second", event_bytes(second)),
    ]
    consumer = FailSecondAckConsumer()
    journal = RocketMqAcceptedEventJournal.__new__(RocketMqAcceptedEventJournal)
    journal._consumer = consumer
    journal._events = {}
    journal._pending_messages = {}
    journal._condition = threading.Condition()
    journal._failure = None
    for message in messages:
        assert journal._accept(message) is True

    checkpoint = markers(first_event, second_event)
    with pytest.raises(KnowledgeRebuildError, match="broker_unavailable"):
        journal.commit(snapshot([second, first]), checkpoint)

    assert consumer.acked == [messages[0]]
    assert set(journal._pending_messages) == {"broker-message-second"}


class RecordingClient(ElasticsearchRebuildClient):
    def __init__(self) -> None:
        super().__init__("http://elasticsearch:9200")
        self.requests: list[tuple[str, str, dict[str, object] | None]] = []
        self.aliases = ["knowledge_docs_v1", "knowledge_docs_v2"]
        self.records: dict[str, dict[str, object] | None] = {}
        self.responses: dict[str, tuple[int, dict[str, object]]] = {}

    def resolve_alias(self) -> str:
        return self.aliases.pop(0)

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        *,
        expected: tuple[int, ...] = (200, 201),
    ) -> tuple[int, dict[str, object]]:
        del expected
        self.requests.append((method, path, payload))
        return self.responses.get(path, (200, {"acknowledged": True}))

    def _read_rebuild_record(self, index: str) -> dict[str, object] | None:
        return self.records.get(index)

    def _write_rebuild_record(
        self,
        index: str,
        record: dict[str, object],
        *,
        create: bool = False,
    ) -> None:
        del create
        self.records[index] = deepcopy(record)


def building_rebuild_record() -> dict[str, object]:
    return {
        "schemaVersion": "cb113-v2",
        "initialSnapshotId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        "initialSnapshotCommitment": "b" * 64,
        "predecessor": "knowledge_docs_v1",
        "candidate": "knowledge_docs_v2",
        "state": "BUILDING",
        "handoffWatermark": None,
        "rollbackLeaseExpiresAt": None,
        "completionSnapshotId": None,
        "completionSnapshotCommitment": None,
        "completionWatermark": None,
        "switchedAt": None,
        "journalEvents": [],
    }


def test_atomic_switch_uses_one_exact_remove_add_action() -> None:
    client = RecordingClient()

    client.atomic_switch("knowledge_docs_v1", "knowledge_docs_v2")

    assert client.requests == [
        (
            "POST",
            "/_aliases",
            {
                "actions": [
                    {
                        "remove": {
                            "index": "knowledge_docs_v1",
                            "alias": "knowledge_docs_read",
                            "must_exist": True,
                        }
                    },
                    {
                        "add": {
                            "index": "knowledge_docs_v2",
                            "alias": "knowledge_docs_read",
                        }
                    },
                ]
            },
        )
    ]


def test_candidate_selection_skips_other_snapshot_without_wildcard_enumeration() -> None:
    client = RecordingClient()
    current = snapshot()
    client.responses = {
        "/knowledge_docs_v2": (200, {}),
        "/knowledge_docs_v3": (404, {}),
    }
    client.records["knowledge_docs_v2"] = building_rebuild_record()

    assert client.candidate_for("knowledge_docs_v1", current) == "knowledge_docs_v3"
    assert [path for _, path, _ in client.requests] == [
        "/knowledge_docs_v2",
        "/knowledge_docs_v3",
    ]


def test_sealed_journal_checkpoint_preserves_partially_acked_prefix() -> None:
    client = RecordingClient()
    client.records["knowledge_docs_v2"] = building_rebuild_record()
    first = snapshot([faq_record(version=1)]).records[0].as_faq_event()
    second_record = faq_record(version=1)
    second_record["eventId"] = "22222222-2222-4222-8222-222222222222"
    second_record["sourceId"] = "faq-delivery"
    second = snapshot([second_record]).records[0].as_faq_event()

    initial = client.seal_journal_events("knowledge_docs_v2", (first, second))
    resumed = client.seal_journal_events("knowledge_docs_v2", (second,))

    assert initial == markers(first, second)
    assert resumed == initial


def test_candidate_validation_enumerates_all_documents_before_classification() -> None:
    client = RecordingClient()
    public = next(iter(snapshot().documents.values())).copy()
    public["sync_record_type"] = "UNREGISTERED_INTERNAL_RECORD"
    client.responses["/knowledge_docs_v2/_search"] = (
        200,
        {
            "timed_out": False,
            "_shards": {"total": 1, "successful": 1, "failed": 0},
            "hits": {
                "total": {"value": 1, "relation": "eq"},
                "hits": [{"_id": "hidden-record", "_source": public}],
            },
        },
    )

    with pytest.raises(KnowledgeRebuildError, match="candidate_public_boundary_violation"):
        client._candidate_public_documents("knowledge_docs_v2")  # noqa: SLF001

    assert client.requests[0][2] == {
        "size": 2002,
        "track_total_hits": True,
        "query": {"match_all": {}},
    }


@pytest.mark.parametrize(
    ("response", "code"),
    [
        (
            {
                "timed_out": True,
                "_shards": {"total": 1, "successful": 1, "failed": 0},
            },
            "partial_search_failure",
        ),
        (
            {
                "timed_out": False,
                "_shards": {"total": 2, "successful": 1, "failed": 1},
            },
            "partial_shard_failure",
        ),
    ],
)
def test_candidate_search_rejects_timeout_or_partial_shards(
    response: dict[str, object], code: str
) -> None:
    client = RecordingClient()
    client.responses["/knowledge_docs_v2/_search"] = (200, response)

    with pytest.raises(KnowledgeRebuildError, match=code):
        client._candidate_public_documents("knowledge_docs_v2")  # noqa: SLF001


def test_candidate_sync_markers_equal_committed_broker_journal_face() -> None:
    client = RecordingClient()
    event = snapshot([faq_record()]).records[0].as_faq_event()
    marker = ElasticsearchKnowledgeProjection._marker_source(event)  # noqa: SLF001
    control = marker.copy()
    control["sync_record_type"] = "REBUILD_SWITCH"
    client.records["knowledge_docs_v2"] = {"state": "BUILDING", "journalEvents": []}

    def response(hits: list[dict[str, object]]) -> tuple[int, dict[str, object]]:
        return (
            200,
            {
                "timed_out": False,
                "_shards": {"total": 1, "successful": 1, "failed": 0},
                "hits": {
                    "total": {"value": len(hits), "relation": "eq"},
                    "hits": hits,
                },
            },
        )

    exact_hits: list[dict[str, object]] = [
        {"_id": "__rebuild_switch__", "_source": control},
        {"_id": f"__sync_event__:{event.event_id}", "_source": marker},
    ]
    client.responses["/knowledge_docs_v2/_search"] = response(exact_hits)
    assert (
        client._candidate_public_documents(  # noqa: SLF001
            "knowledge_docs_v2", (event,)
        )
        == {}
    )

    with pytest.raises(KnowledgeRebuildError, match="candidate_commitment_mismatch"):
        client._candidate_public_documents("knowledge_docs_v2", ())  # noqa: SLF001

    client.responses["/knowledge_docs_v2/_search"] = response(exact_hits[:1])
    with pytest.raises(KnowledgeRebuildError, match="candidate_commitment_mismatch"):
        client._candidate_public_documents("knowledge_docs_v2", (event,))  # noqa: SLF001


def test_search_requires_exact_total_relation() -> None:
    client = RecordingClient()
    client.responses["/knowledge_docs_v2/_search"] = (
        200,
        {
            "timed_out": False,
            "_shards": {"total": 1, "successful": 1, "failed": 0},
            "hits": {"total": {"value": 0, "relation": "gte"}, "hits": []},
        },
    )

    with pytest.raises(KnowledgeRebuildError, match="malformed_elasticsearch_response"):
        client._search_ids("knowledge_docs_v2", {"size": 8})  # noqa: SLF001


def test_elasticsearch_json_response_rejects_duplicate_keys(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Response:
        status = 200

        def __enter__(self) -> object:
            return self

        def __exit__(self, *_: object) -> None:
            return None

        def read(self, _maximum: int) -> bytes:
            return b'{"acknowledged":true,"acknowledged":false}'

    monkeypatch.setattr(rebuild_module, "urlopen", lambda *_args, **_kwargs: Response())

    with pytest.raises(KnowledgeRebuildError, match="malformed_elasticsearch_response"):
        ElasticsearchRebuildClient("http://elasticsearch:9200")._request(  # noqa: SLF001
            "GET", "/knowledge_docs_v1"
        )


def test_incremental_catch_up_response_rejects_duplicate_keys(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Response:
        status = 200

        def __enter__(self) -> object:
            return self

        def __exit__(self, *_: object) -> None:
            return None

        def read(self) -> bytes:
            return b'{"found":true,"found":false}'

    monkeypatch.setattr(incremental_module, "urlopen", lambda *_args, **_kwargs: Response())

    with pytest.raises(KnowledgeRebuildError, match="malformed_elasticsearch_response"):
        ElasticsearchRebuildClient("http://elasticsearch:9200").apply_event(
            "knowledge_docs_v2",
            snapshot([faq_record()]).records[0].as_faq_event(),
        )
