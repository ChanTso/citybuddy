"""Pure deterministic event model for the disposable CB-085 experiment."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class SpikeEvent:
    source_id: str
    source_version: int
    document_type: str
    content: str | None
    tombstone: bool = False

    @classmethod
    def from_bytes(cls, body: bytes) -> SpikeEvent:
        payload = json.loads(body)
        if not isinstance(payload, dict):
            raise ValueError("spike event must be a JSON object")
        source_id = payload.get("source_id")
        source_version = payload.get("source_version")
        document_type = payload.get("document_type")
        content = payload.get("content")
        tombstone = payload.get("tombstone", False)
        if not isinstance(source_id, str) or not source_id:
            raise ValueError("source_id must be a non-empty string")
        if not isinstance(source_version, int) or source_version < 1:
            raise ValueError("source_version must be a positive integer")
        if not isinstance(document_type, str) or not document_type:
            raise ValueError("document_type must be a non-empty string")
        if content is not None and not isinstance(content, str):
            raise ValueError("content must be a string or null")
        if not isinstance(tombstone, bool):
            raise ValueError("tombstone must be a boolean")
        if not tombstone and not content:
            raise ValueError("non-tombstone events require content")
        return cls(source_id, source_version, document_type, content, tombstone)

    def to_bytes(self) -> bytes:
        return json.dumps(
            {
                "source_id": self.source_id,
                "source_version": self.source_version,
                "document_type": self.document_type,
                "content": self.content,
                "tombstone": self.tombstone,
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode()

    def projection(self) -> dict[str, Any]:
        return {
            "source_id": self.source_id,
            "source_version": self.source_version,
            "document_type": self.document_type,
            "deleted": self.tombstone,
            "content": None if self.tombstone else self.content,
        }
