import pytest
from citybuddy_indexer.spike_event import SpikeEvent


def test_spike_event_round_trip_preserves_tombstone_version() -> None:
    event = SpikeEvent("faq-1", 7, "faq", None, tombstone=True)

    restored = SpikeEvent.from_bytes(event.to_bytes())

    assert restored == event
    assert restored.projection() == {
        "source_id": "faq-1",
        "source_version": 7,
        "document_type": "faq",
        "deleted": True,
        "content": None,
    }


@pytest.mark.parametrize(
    "payload",
    [
        b"[]",
        b'{"source_id":"","source_version":1,"document_type":"faq","content":"x"}',
        b'{"source_id":"x","source_version":0,"document_type":"faq","content":"x"}',
        b'{"source_id":"x","source_version":1,"document_type":"faq","content":null}',
    ],
)
def test_spike_event_rejects_invalid_boundary_payloads(payload: bytes) -> None:
    with pytest.raises(ValueError):
        SpikeEvent.from_bytes(payload)
