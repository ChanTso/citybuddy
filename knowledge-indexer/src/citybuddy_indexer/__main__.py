"""Command-line entry point for the index worker and initial knowledge bootstrap."""

import argparse
import json

from .knowledge import ElasticsearchBootstrapClient, KnowledgeBootstrapError
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
    args = parser.parse_args()
    if args.command == "bootstrap":
        try:
            result = ElasticsearchBootstrapClient(args.elasticsearch_url).bootstrap(
                index=args.index,
                alias=args.alias,
            )
        except KnowledgeBootstrapError as error:
            raise SystemExit(f"knowledge bootstrap failed: {error.code}") from error
        print(
            json.dumps(
                {
                    "alias": result.alias,
                    "documentCount": result.document_count,
                    "indexVersion": result.index,
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
    worker = create_worker()
    print(f"{worker.settings.service_name} skeleton constructed")


if __name__ == "__main__":
    main()
