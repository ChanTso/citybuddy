"""Run the verified knowledge rebuild inside an isolated demo network."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from citybuddy_indexer.rebuild import ElasticsearchRebuildClient, KnowledgeRebuildCoordinator
from citybuddy_indexer.rebuild_runtime import HttpOwnerSnapshotSource, RocketMqAcceptedEventJournal


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--private-file", required=True, type=Path)
    parser.add_argument("--owner-snapshot-url", required=True)
    parser.add_argument("--elasticsearch-url", required=True)
    parser.add_argument("--rocketmq-endpoints", required=True)
    parser.add_argument("--topic", required=True)
    parser.add_argument("--consumer-group", required=True)
    arguments = parser.parse_args()
    private = json.loads(arguments.private_file.read_text(encoding="utf-8"))
    secret = private["KNOWLEDGE_SNAPSHOT_SECRET"]
    source = HttpOwnerSnapshotSource(
        arguments.owner_snapshot_url,
        "knowledge-indexer",
        secret,
    )
    with RocketMqAcceptedEventJournal(
        arguments.rocketmq_endpoints,
        arguments.topic,
        arguments.consumer_group,
        invisible_seconds=30,
    ) as journal:
        result = KnowledgeRebuildCoordinator(
            ElasticsearchRebuildClient(arguments.elasticsearch_url)
        ).rebuild(source, journal)
    print(
        json.dumps(
            {
                "candidate": result.candidate,
                "documentCount": result.document_count,
                "handoffWatermark": result.handoff_watermark,
                "predecessor": result.predecessor,
                "replayed": result.replayed,
                "rollbackLeaseExpiresAt": result.rollback_lease_expires_at,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
