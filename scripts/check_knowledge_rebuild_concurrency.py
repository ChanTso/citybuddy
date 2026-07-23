"""Real Broker-driven catch-up across every CB-113 rebuild handoff phase."""

from __future__ import annotations

import argparse
import hashlib
import json
import threading
import time
from typing import Any, cast
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from uuid import UUID, uuid4

from citybuddy_indexer.incremental import (
    EVENT_TAG,
    ElasticsearchKnowledgeProjection,
    FaqKnowledgeEvent,
)
from citybuddy_indexer.rebuild import (
    ElasticsearchRebuildClient,
    JournalCheckpoint,
    KnowledgeRebuildCoordinator,
    KnowledgeRebuildError,
    KnowledgeSnapshot,
    _VersionedRebuildRecord,
)
from citybuddy_indexer.rebuild_runtime import (
    HttpOwnerSnapshotSource,
    RocketMqAcceptedEventJournal,
)
from rocketmq.v5.client import ClientConfiguration, Credentials  # type: ignore[import-untyped]
from rocketmq.v5.model import Message  # type: ignore[import-untyped]
from rocketmq.v5.producer import Producer  # type: ignore[import-untyped]

PHASES = ("snapshot", "load", "validation", "switch")
TARGET_SOURCE = "faq-store-hours"


def canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()


def digest(value: object) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


def stable_snapshot_id(content_commitment: str, watermark: str) -> str:
    raw = bytearray(
        hashlib.md5(  # noqa: S324 - exact Java UUID.nameUUIDFromBytes compatibility.
            f"{content_commitment}:{watermark}".encode(), usedforsecurity=False
        ).digest()
    )
    raw[6] = (raw[6] & 0x0F) | 0x30
    raw[8] = (raw[8] & 0x3F) | 0x80
    return str(UUID(bytes=bytes(raw)))


def snapshot(records: list[dict[str, object]], captured_at: str) -> KnowledgeSnapshot:
    ordered = sorted(records, key=lambda item: (item["sourceId"], item["chunkId"]))
    states: list[dict[str, object]] = []
    grouped: dict[str, list[dict[str, object]]] = {}
    for record in ordered:
        grouped.setdefault(cast(str, record["sourceId"]), []).append(record)
    for source_id in sorted(grouped):
        source_records = grouped[source_id]
        first = source_records[0]
        states.append(
            {
                "sourceId": source_id,
                "sourceVersion": first["sourceVersion"],
                "docType": first["docType"],
                "tombstone": first["tombstone"],
                "eventId": first["eventId"],
                "occurredTime": first["occurredTime"],
                "recordCommitments": [digest(record) for record in source_records],
            }
        )
    content_commitment = digest(ordered)
    watermark = digest(states)
    payload = {
        "schemaVersion": "cb113-v1",
        "snapshotId": stable_snapshot_id(content_commitment, watermark),
        "capturedAt": captured_at,
        "recordCount": len(ordered),
        "sourceCount": len(states),
        "contentCommitment": content_commitment,
        "watermark": watermark,
        "records": ordered,
    }
    return KnowledgeSnapshot.from_bytes(canonical(payload))


def accepted_event_payload(event: FaqKnowledgeEvent) -> dict[str, object]:
    return {
        "eventId": event.event_id,
        "sourceId": event.source_id,
        "sourceType": "faq",
        "sourceVersion": event.source_version,
        "publicationState": "PUBLISHED",
        "tombstone": event.tombstone,
        "occurredTime": event.occurred_time,
        "content": {
            "question": event.content.question,
            "answer": event.content.answer,
        },
    }


def send_event(producer: Any, topic: str, event: FaqKnowledgeEvent) -> None:
    message = Message()
    message.topic = topic
    message.tag = EVENT_TAG
    message.keys = event.event_id
    message.body = canonical(accepted_event_payload(event))
    producer.send(message)


def request(base_url: str, method: str, path: str) -> tuple[int, dict[str, Any]]:
    http_request = Request(f"{base_url.rstrip('/')}{path}", method=method)
    try:
        with urlopen(http_request, timeout=10) as response:  # noqa: S310
            status = response.status
            body = response.read()
    except HTTPError as error:
        status = error.code
        body = error.read()
    decoded = {} if not body else json.loads(body)
    if not isinstance(decoded, dict):
        raise AssertionError("Elasticsearch response was not an object")
    return status, cast(dict[str, Any], decoded)


class ControlledSource:
    def __init__(
        self,
        current: KnowledgeSnapshot,
        updated: KnowledgeSnapshot,
        producer: Any,
        topic: str,
        event: FaqKnowledgeEvent,
        *,
        advance_on_capture: int | None,
    ) -> None:
        self.current = current
        self.updated = updated
        self._producer = producer
        self._topic = topic
        self._event = event
        self._advance_on_capture = advance_on_capture
        self._captures = 0
        self.advanced = False

    def capture(self) -> KnowledgeSnapshot:
        self._captures += 1
        if self._advance_on_capture == self._captures:
            self.advance()
        return self.current

    def advance(self) -> None:
        if self.advanced:
            return
        self.current = self.updated
        send_event(self._producer, self._topic, self._event)
        self.advanced = True


class PhaseHookClient(ElasticsearchRebuildClient):
    def __init__(self, base_url: str, phase: str, source: ControlledSource) -> None:
        super().__init__(base_url)
        self._phase = phase
        self._source = source
        self._validation_calls = 0

    def load_snapshot(self, candidate: str, selected: KnowledgeSnapshot) -> None:
        super().load_snapshot(candidate, selected)
        if self._phase == "load":
            self._source.advance()

    def validate_candidate(
        self,
        candidate: str,
        selected: KnowledgeSnapshot,
        accepted_events: tuple[FaqKnowledgeEvent, ...],
    ) -> None:
        super().validate_candidate(candidate, selected, accepted_events)
        self._validation_calls += 1
        if self._phase == "validation" and self._validation_calls == 1:
            self._source.advance()

    def atomic_switch(self, predecessor: str, candidate: str) -> None:
        super().atomic_switch(predecessor, candidate)
        if self._phase == "switch":
            self._source.advance()


class ControlledCrash(RuntimeError):
    pass


class CrashSwitchClient(ElasticsearchRebuildClient):
    def __init__(self, base_url: str, *, after_action: bool) -> None:
        super().__init__(base_url)
        self._after_action = after_action
        self.candidate: str | None = None

    def atomic_switch(self, predecessor: str, candidate: str) -> None:
        self.candidate = candidate
        if self._after_action:
            super().atomic_switch(predecessor, candidate)
        raise ControlledCrash("controlled indexer crash at alias boundary")


class RacingSwitchClient(ElasticsearchRebuildClient):
    def __init__(self, base_url: str, ready: threading.Event, barrier: threading.Barrier) -> None:
        super().__init__(base_url)
        self._ready = ready
        self._barrier = barrier

    def atomic_switch(self, predecessor: str, candidate: str) -> None:
        self._ready.set()
        self._barrier.wait(timeout=20)
        super().atomic_switch(predecessor, candidate)


class StaleCompleteSwitchClient(ElasticsearchRebuildClient):
    def __init__(
        self,
        base_url: str,
        stale_read_complete: threading.Event,
        resume_stale_write: threading.Event,
    ) -> None:
        super().__init__(base_url)
        self._stale_read_complete = stale_read_complete
        self._resume_stale_write = resume_stale_write
        self._paused = False
        self.conflicts = 0

    def _write_rebuild_record(
        self,
        index: str,
        record: dict[str, object],
        *,
        create: bool = False,
        expected: _VersionedRebuildRecord | None = None,
    ) -> bool:
        if record.get("state") == "SWITCHED" and not self._paused:
            self._paused = True
            self._stale_read_complete.set()
            if not self._resume_stale_write.wait(timeout=20):
                raise AssertionError("stale complete_switch write was not released")
        written = super()._write_rebuild_record(
            index,
            record,
            create=create,
            expected=expected,
        )
        if not written:
            self.conflicts += 1
        return written


class CommitWindowJournal:
    def __init__(
        self,
        delegate: RocketMqAcceptedEventJournal,
        producer: Any,
        topic: str,
        late_covered_event: FaqKnowledgeEvent,
    ) -> None:
        self._delegate = delegate
        self._producer = producer
        self._topic = topic
        self._late_covered_event = late_covered_event

    def events(self) -> tuple[FaqKnowledgeEvent, ...]:
        return self._delegate.events()

    def commit(
        self,
        snapshot: KnowledgeSnapshot,
        checkpoint: JournalCheckpoint,
    ) -> None:
        send_event(self._producer, self._topic, self._late_covered_event)
        wait_for_event(self._delegate, self._late_covered_event.event_id)
        self._delegate.commit(snapshot, checkpoint)


class PartialBulkClient(ElasticsearchRebuildClient):
    def load_snapshot(self, candidate: str, selected: KnowledgeSnapshot) -> None:
        document_id, source = next(iter(selected.documents.items()))
        self._request(
            "PUT",
            f"/{quote(candidate)}/_doc/{quote(document_id, safe='')}",
            source,
        )
        raise KnowledgeRebuildError("partial_bulk_failure")


class RetrievalProbeFailureClient(ElasticsearchRebuildClient):
    def _fixed_retrieval_probes(self, index: str) -> None:
        super()._fixed_retrieval_probes(index)
        raise KnowledgeRebuildError("retrieval_probe_failed")


class SilentControlledSource(ControlledSource):
    def advance(self) -> None:
        if self.advanced:
            return
        self.current = self.updated
        self.advanced = True


class StaticJournal:
    def events(self) -> tuple[FaqKnowledgeEvent, ...]:
        return ()

    def commit(
        self,
        snapshot: KnowledgeSnapshot,
        checkpoint: JournalCheckpoint,
    ) -> None:
        del snapshot, checkpoint


def wait_for_event(
    journal: RocketMqAcceptedEventJournal, event_id: str, timeout_seconds: float = 20
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if any(event.event_id == event_id for event in journal.events()):
            return
        time.sleep(0.05)
    raise AssertionError(f"Broker did not deliver expected event {event_id}")


def replace_target(
    current: KnowledgeSnapshot, version: int, *, tombstone: bool
) -> KnowledgeSnapshot:
    records = [record.canonical_mapping() for record in current.records]
    target = next(record for record in records if record["sourceId"] == TARGET_SOURCE)
    target["eventId"] = str(uuid4())
    target["sourceVersion"] = version
    target["tombstone"] = tombstone
    target["occurredTime"] = f"2026-07-22T00:02:{version:02d}.123456Z"
    target["title"] = "营业时间 Store hours"
    target["content"] = f"Public store-hours guidance version {version}."
    return snapshot(records, f"2026-07-22T00:03:{version:02d}.123456Z")


def assert_projection(
    base_url: str, expected_index: str, expected_version: int, tombstone: bool
) -> None:
    status, alias = request(base_url, "GET", "/_alias/knowledge_docs_read")
    assert status == 200 and set(alias) == {expected_index}
    status, document = request(base_url, "GET", f"/{expected_index}/_doc/{TARGET_SOURCE}%3Aanswer")
    assert status == 200 and document["found"] is True
    source = document["_source"]
    assert isinstance(source, dict)
    assert source["source_version"] == expected_version
    assert source["deleted"] is tombstone
    assert source["published"] is (not tombstone)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--elasticsearch-url", required=True)
    parser.add_argument("--owner-url", required=True)
    parser.add_argument("--owner-client-id", required=True)
    parser.add_argument("--owner-client-secret", required=True)
    parser.add_argument("--endpoints", required=True)
    parser.add_argument("--topic", required=True)
    parser.add_argument("--group", required=True)
    parser.add_argument("--restart-group", required=True)
    parser.add_argument("--race-group", required=True)
    parser.add_argument("--commit-group", required=True)
    args = parser.parse_args()

    owner = HttpOwnerSnapshotSource(
        f"{args.owner_url.rstrip('/')}/internal/knowledge/snapshot",
        args.owner_client_id,
        args.owner_client_secret,
    )
    current = owner.capture()
    configuration = ClientConfiguration(args.endpoints, Credentials(), request_timeout=5)
    producer: Any = Producer(configuration, [args.topic])
    producer.startup()
    cells = 0
    restart_windows = 0
    alias_race_proven = False
    fail_closed_scenarios = 0
    try:
        with RocketMqAcceptedEventJournal(
            args.endpoints, args.topic, args.group, invisible_seconds=30
        ) as journal:
            version = next(
                record.source_version
                for record in current.records
                if record.source_id == TARGET_SOURCE
            )
            for phase in PHASES:
                for tombstone in (False, True):
                    version += 1
                    current_target = next(
                        record for record in current.records if record.source_id == TARGET_SOURCE
                    )
                    initial = replace_target(current, version, tombstone=current_target.tombstone)
                    initial_event = next(
                        record.as_faq_event()
                        for record in initial.records
                        if record.source_id == TARGET_SOURCE
                    )
                    send_event(producer, args.topic, initial_event)
                    version += 1
                    updated = replace_target(initial, version, tombstone=tombstone)
                    event = next(
                        record.as_faq_event()
                        for record in updated.records
                        if record.source_id == TARGET_SOURCE
                    )
                    source = ControlledSource(
                        initial,
                        updated,
                        producer,
                        args.topic,
                        event,
                        advance_on_capture=2 if phase == "snapshot" else None,
                    )
                    client = PhaseHookClient(args.elasticsearch_url, phase, source)
                    result = KnowledgeRebuildCoordinator(client, catch_up_wait_seconds=10).rebuild(
                        source, journal
                    )
                    assert source.advanced is True
                    assert result.document_count == len(updated.records)
                    assert_projection(args.elasticsearch_url, result.candidate, version, tombstone)
                    status, predecessor = request(
                        args.elasticsearch_url, "GET", f"/{result.predecessor}"
                    )
                    assert status == 200 and result.predecessor in predecessor
                    current = updated
                    cells += 1

            committed_events = journal.events()
            assert committed_events
            current_alias = result.candidate
            missing_event = committed_events[0]
            missing_marker_id = f"__sync_event__:{missing_event.event_id}"
            client._request(  # noqa: SLF001
                "DELETE",
                f"/{quote(current_alias)}/_doc/{quote(missing_marker_id, safe='')}?refresh=true",
            )
            try:
                client.validate_candidate(current_alias, current, committed_events)
            except KnowledgeRebuildError as error:
                assert error.code == "candidate_commitment_mismatch"
            else:
                raise AssertionError("Missing Broker-journal marker was not rejected")
            client.apply_event(current_alias, missing_event)

            orphan_payload = accepted_event_payload(committed_events[-1])
            orphan_payload["eventId"] = str(uuid4())
            orphan = FaqKnowledgeEvent.from_bytes(canonical(orphan_payload))
            orphan_marker_id = f"__sync_event__:{orphan.event_id}"
            client._request(  # noqa: SLF001
                "PUT",
                f"/{quote(current_alias)}/_doc/{quote(orphan_marker_id, safe='')}?refresh=true",
                ElasticsearchKnowledgeProjection._marker_source(orphan),  # noqa: SLF001
            )
            try:
                client.validate_candidate(current_alias, current, committed_events)
            except KnowledgeRebuildError as error:
                assert error.code == "candidate_commitment_mismatch"
            else:
                raise AssertionError("Orphan Broker-journal marker was not rejected")
            client._request(  # noqa: SLF001
                "DELETE",
                f"/{quote(current_alias)}/_doc/{quote(orphan_marker_id, safe='')}?refresh=true",
            )
            client.validate_candidate(current_alias, current, committed_events)
            fail_closed_scenarios += 2

        for after_action in (False, True):
            current_alias = next(
                iter(request(args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read")[1])
            )
            current_target = next(
                record for record in current.records if record.source_id == TARGET_SOURCE
            )
            version += 1
            restart_snapshot = replace_target(current, version, tombstone=current_target.tombstone)
            restart_event = next(
                record.as_faq_event()
                for record in restart_snapshot.records
                if record.source_id == TARGET_SOURCE
            )
            restart_source = ControlledSource(
                restart_snapshot,
                restart_snapshot,
                producer,
                args.topic,
                restart_event,
                advance_on_capture=None,
            )
            crashing = CrashSwitchClient(args.elasticsearch_url, after_action=after_action)
            with RocketMqAcceptedEventJournal(
                args.endpoints,
                args.topic,
                args.restart_group,
                invisible_seconds=10,
            ) as journal:
                send_event(producer, args.topic, restart_event)
                wait_for_event(journal, restart_event.event_id)
                try:
                    KnowledgeRebuildCoordinator(crashing, catch_up_wait_seconds=10).rebuild(
                        restart_source, journal
                    )
                except ControlledCrash:
                    pass
                else:
                    raise AssertionError("Controlled alias-boundary crash did not fire")
            assert crashing.candidate is not None
            status, alias_after_crash = request(
                args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read"
            )
            assert status == 200
            expected_after_crash = crashing.candidate if after_action else current_alias
            assert set(alias_after_crash) == {expected_after_crash}

            time.sleep(11)
            with RocketMqAcceptedEventJournal(
                args.endpoints,
                args.topic,
                args.restart_group,
                invisible_seconds=10,
            ) as journal:
                wait_for_event(journal, restart_event.event_id)
                recovered = KnowledgeRebuildCoordinator(
                    ElasticsearchRebuildClient(args.elasticsearch_url),
                    catch_up_wait_seconds=10,
                ).rebuild(restart_source, journal)
            assert recovered.candidate == crashing.candidate
            assert recovered.replayed is after_action
            assert_projection(
                args.elasticsearch_url,
                recovered.candidate,
                version,
                current_target.tombstone,
            )
            current = restart_snapshot
            restart_windows += 1

        for failure_kind in ("partial-bulk", "missing-event", "retrieval-probe"):
            status, alias_before = request(
                args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read"
            )
            assert status == 200 and len(alias_before) == 1
            current_target = next(
                record for record in current.records if record.source_id == TARGET_SOURCE
            )
            version += 1
            failure_initial = replace_target(current, version, tombstone=current_target.tombstone)
            failure_source: ControlledSource
            expected_code: str
            if failure_kind == "partial-bulk":
                event = next(
                    record.as_faq_event()
                    for record in failure_initial.records
                    if record.source_id == TARGET_SOURCE
                )
                failure_source = ControlledSource(
                    failure_initial,
                    failure_initial,
                    producer,
                    args.topic,
                    event,
                    advance_on_capture=None,
                )
                failure_client: ElasticsearchRebuildClient = PartialBulkClient(
                    args.elasticsearch_url
                )
                expected_code = "partial_bulk_failure"
            elif failure_kind == "missing-event":
                version += 1
                missing_update = replace_target(
                    failure_initial, version, tombstone=not current_target.tombstone
                )
                event = next(
                    record.as_faq_event()
                    for record in missing_update.records
                    if record.source_id == TARGET_SOURCE
                )
                failure_source = SilentControlledSource(
                    failure_initial,
                    missing_update,
                    producer,
                    args.topic,
                    event,
                    advance_on_capture=2,
                )
                failure_client = ElasticsearchRebuildClient(args.elasticsearch_url)
                expected_code = "missing_catch_up_event"
            else:
                event = next(
                    record.as_faq_event()
                    for record in failure_initial.records
                    if record.source_id == TARGET_SOURCE
                )
                failure_source = ControlledSource(
                    failure_initial,
                    failure_initial,
                    producer,
                    args.topic,
                    event,
                    advance_on_capture=None,
                )
                failure_client = RetrievalProbeFailureClient(args.elasticsearch_url)
                expected_code = "retrieval_probe_failed"
            try:
                KnowledgeRebuildCoordinator(failure_client, catch_up_wait_seconds=0.2).rebuild(
                    failure_source, StaticJournal()
                )
            except KnowledgeRebuildError as error:
                assert error.code == expected_code
            else:
                raise AssertionError(f"{failure_kind} did not fail closed")
            status, alias_after = request(
                args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read"
            )
            assert status == 200 and alias_after == alias_before
            fail_closed_scenarios += 1

        current_target = next(
            record for record in current.records if record.source_id == TARGET_SOURCE
        )
        version += 1
        race_snapshot = replace_target(current, version, tombstone=current_target.tombstone)
        race_event = next(
            record.as_faq_event()
            for record in race_snapshot.records
            if record.source_id == TARGET_SOURCE
        )
        race_source = ControlledSource(
            race_snapshot,
            race_snapshot,
            producer,
            args.topic,
            race_event,
            advance_on_capture=None,
        )
        ready = threading.Event()
        barrier = threading.Barrier(2)
        outcomes: list[object] = []

        def race(client: RacingSwitchClient, journal: RocketMqAcceptedEventJournal) -> None:
            try:
                outcomes.append(
                    KnowledgeRebuildCoordinator(client, catch_up_wait_seconds=10).rebuild(
                        race_source, journal
                    )
                )
            except Exception as error:  # noqa: BLE001 - captured as explicit race evidence.
                outcomes.append(error)

        with RocketMqAcceptedEventJournal(
            args.endpoints, args.topic, args.race_group, invisible_seconds=30
        ) as journal:
            send_event(producer, args.topic, race_event)
            wait_for_event(journal, race_event.event_id)
            first = threading.Thread(
                target=race,
                args=(RacingSwitchClient(args.elasticsearch_url, ready, barrier), journal),
                daemon=True,
            )
            first.start()
            assert ready.wait(timeout=20)
            second_ready = threading.Event()
            second = threading.Thread(
                target=race,
                args=(
                    RacingSwitchClient(args.elasticsearch_url, second_ready, barrier),
                    journal,
                ),
                daemon=True,
            )
            second.start()
            first.join(timeout=30)
            second.join(timeout=30)
            assert not first.is_alive() and not second.is_alive()
        successes = [outcome for outcome in outcomes if hasattr(outcome, "candidate")]
        failures = [outcome for outcome in outcomes if isinstance(outcome, Exception)]
        assert len(successes) == 1 and len(failures) == 1
        winning_candidate = cast(Any, successes[0]).candidate
        assert_projection(
            args.elasticsearch_url,
            winning_candidate,
            version,
            current_target.tombstone,
        )
        alias_race_proven = True

        version += 1
        commit_snapshot = replace_target(current, version, tombstone=False)
        commit_event = next(
            record.as_faq_event()
            for record in commit_snapshot.records
            if record.source_id == TARGET_SOURCE
        )
        late_covered_record = next(
            record.canonical_mapping()
            for record in current.records
            if record.source_id == TARGET_SOURCE
        )
        late_covered_record["eventId"] = str(uuid4())
        late_covered_event = (
            snapshot(
                [late_covered_record],
                commit_snapshot.captured_at,
            )
            .records[0]
            .as_faq_event()
        )
        commit_source = ControlledSource(
            commit_snapshot,
            commit_snapshot,
            producer,
            args.topic,
            commit_event,
            advance_on_capture=None,
        )
        with RocketMqAcceptedEventJournal(
            args.endpoints, args.topic, args.commit_group, invisible_seconds=10
        ) as journal:
            send_event(producer, args.topic, commit_event)
            wait_for_event(journal, commit_event.event_id)
            commit_journal = CommitWindowJournal(
                journal,
                producer,
                args.topic,
                late_covered_event,
            )
            try:
                KnowledgeRebuildCoordinator(
                    ElasticsearchRebuildClient(args.elasticsearch_url),
                    catch_up_wait_seconds=10,
                ).rebuild(commit_source, commit_journal)
            except KnowledgeRebuildError as error:
                assert error.code == "journal_changed_after_validation"
            else:
                raise AssertionError("Late covered Broker event was terminally acknowledged")
            current_alias = next(
                iter(request(args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read")[1])
            )
            _, control = request(
                args.elasticsearch_url,
                "GET",
                f"/{current_alias}/_doc/__rebuild_switch__",
            )
            control_source = cast(dict[str, object], control["_source"])
            assert json.loads(cast(str, control_source["content"]))["state"] == "PREPARED"
        time.sleep(11)
        with RocketMqAcceptedEventJournal(
            args.endpoints, args.topic, args.commit_group, invisible_seconds=10
        ) as journal:
            wait_for_event(journal, late_covered_event.event_id)
            coordinator_b = ElasticsearchRebuildClient(args.elasticsearch_url)
            checkpoint_a = coordinator_b.seal_journal_events(current_alias, ())
            stale_read_complete = threading.Event()
            resume_stale_write = threading.Event()
            coordinator_a = StaleCompleteSwitchClient(
                args.elasticsearch_url,
                stale_read_complete,
                resume_stale_write,
            )
            completion_outcome: list[Exception | None] = []

            def complete_from_stale_record() -> None:
                try:
                    coordinator_a.complete_switch(
                        current_alias,
                        commit_snapshot,
                        checkpoint_a,
                    )
                    completion_outcome.append(None)
                except Exception as error:  # noqa: BLE001 - retained as race evidence.
                    completion_outcome.append(error)

            stale_complete = threading.Thread(
                target=complete_from_stale_record,
                daemon=True,
            )
            stale_complete.start()
            assert stale_read_complete.wait(timeout=20)
            accepted = journal.events()
            assert {event.event_id for event in accepted}.issuperset(
                {commit_event.event_id, late_covered_event.event_id}
            )
            for event in accepted:
                coordinator_b.apply_event(current_alias, event)
            checkpoint_b = coordinator_b.seal_journal_events(current_alias, accepted)
            resume_stale_write.set()
            stale_complete.join(timeout=20)
            assert not stale_complete.is_alive()
            assert completion_outcome == [None]
            assert coordinator_a.conflicts == 1

            journal.commit(commit_snapshot, checkpoint_b)
            coordinator_b.complete_switch(current_alias, commit_snapshot, checkpoint_b)
            restarted = ElasticsearchRebuildClient(args.elasticsearch_url)
            reconstructed = restarted.seal_journal_events(current_alias, ())
            assert reconstructed == checkpoint_b
            for marker_id, expected_marker in reconstructed.markers.items():
                _, marker = request(
                    args.elasticsearch_url,
                    "GET",
                    f"/{current_alias}/_doc/{quote(marker_id, safe='')}",
                )
                assert marker["_source"] == expected_marker
            _, control = request(
                args.elasticsearch_url,
                "GET",
                f"/{current_alias}/_doc/__rebuild_switch__",
            )
            control_source = cast(dict[str, object], control["_source"])
            persisted_record = json.loads(cast(str, control_source["content"]))
            assert persisted_record["state"] == "SWITCHED"
            assert persisted_record["journalEvents"] == list(checkpoint_b.canonical_mappings())
    finally:
        producer.shutdown()

    print(
        json.dumps(
            {
                "brokerDrivenCells": cells,
                "commitWindowLateCoveredEventRemainedUnacked": True,
                "concurrentAliasRaceSingleWinner": alias_race_proven,
                "failClosedRuntimeScenarios": fail_closed_scenarios,
                "handoffPhases": len(PHASES),
                "publicationAndTombstonePerPhase": True,
                "realBrokerRestartCrashWindows": restart_windows,
                "singleTargetAliasAfterEveryCell": True,
                "staleCompleteCasConflictPreservedAckCheckpoint": True,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
