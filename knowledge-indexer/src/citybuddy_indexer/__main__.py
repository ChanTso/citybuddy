"""Command-line entry point for the index worker and initial knowledge bootstrap."""

import argparse
import json

from .knowledge import ElasticsearchBootstrapClient, KnowledgeBootstrapError
from .rebuild import ElasticsearchRebuildClient, KnowledgeRebuildCoordinator, KnowledgeRebuildError
from .rebuild_runtime import HttpOwnerSnapshotSource, RocketMqAcceptedEventJournal
from .worker import IndexerSettings, RocketMqKnowledgeConsumer, create_worker


def main() -> None:
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command")
    bootstrap = subcommands.add_parser("bootstrap")
    bootstrap.add_argument("--elasticsearch-url", required=True)
    bootstrap.add_argument("--index", required=True)
    bootstrap.add_argument("--alias", default="knowledge_docs_read")
    consume = subcommands.add_parser("consume")
    consume.add_argument("--rocketmq-endpoints", required=True)
    consume.add_argument("--topic", required=True)
    consume.add_argument("--consumer-group", required=True)
    consume.add_argument("--elasticsearch-url", required=True)
    consume.add_argument("--support-redis-url", required=True)
    consume.add_argument("--alias", default="knowledge_docs_read")
    consume.add_argument("--invisible-seconds", type=int, default=30)
    rebuild = subcommands.add_parser("rebuild")
    rebuild.add_argument("--elasticsearch-url", required=True)
    rebuild.add_argument("--owner-snapshot-url", required=True)
    rebuild.add_argument("--owner-client-id", required=True)
    rebuild.add_argument("--owner-client-secret", required=True)
    rebuild.add_argument("--rocketmq-endpoints", required=True)
    rebuild.add_argument("--topic", required=True)
    rebuild.add_argument("--consumer-group", required=True)
    rebuild.add_argument("--invisible-seconds", type=int, default=30)
    args = parser.parse_args()
    if args.command == "bootstrap":
        try:
            bootstrap_result = ElasticsearchBootstrapClient(args.elasticsearch_url).bootstrap(
                index=args.index,
                alias=args.alias,
            )
        except KnowledgeBootstrapError as error:
            raise SystemExit(f"knowledge bootstrap failed: {error.code}") from error
        print(
            json.dumps(
                {
                    "alias": bootstrap_result.alias,
                    "documentCount": bootstrap_result.document_count,
                    "indexVersion": bootstrap_result.index,
                },
                separators=(",", ":"),
                sort_keys=True,
            )
        )
        return
    if args.command == "consume":
        settings = IndexerSettings(
            rocketmq_endpoints=args.rocketmq_endpoints,
            rocketmq_topic=args.topic,
            rocketmq_consumer_group=args.consumer_group,
            elasticsearch_url=args.elasticsearch_url,
            support_redis_url=args.support_redis_url,
            knowledge_alias=args.alias,
            invisible_seconds=args.invisible_seconds,
        )
        RocketMqKnowledgeConsumer(create_worker(settings)).run_forever()
        return
    if args.command == "rebuild":
        source = HttpOwnerSnapshotSource(
            args.owner_snapshot_url,
            args.owner_client_id,
            args.owner_client_secret,
        )
        try:
            with RocketMqAcceptedEventJournal(
                args.rocketmq_endpoints,
                args.topic,
                args.consumer_group,
                invisible_seconds=args.invisible_seconds,
            ) as journal:
                rebuild_result = KnowledgeRebuildCoordinator(
                    ElasticsearchRebuildClient(args.elasticsearch_url)
                ).rebuild(source, journal)
        except KnowledgeRebuildError as error:
            raise SystemExit(f"knowledge rebuild failed: {error.code}") from error
        print(
            json.dumps(
                {
                    "candidate": rebuild_result.candidate,
                    "documentCount": rebuild_result.document_count,
                    "handoffWatermark": rebuild_result.handoff_watermark,
                    "predecessor": rebuild_result.predecessor,
                    "replayed": rebuild_result.replayed,
                    "rollbackLeaseExpiresAt": rebuild_result.rollback_lease_expires_at,
                },
                separators=(",", ":"),
                sort_keys=True,
            )
        )
        return
    worker = create_worker()
    print(f"{worker.settings.service_name} skeleton constructed")


if __name__ == "__main__":
    main()
