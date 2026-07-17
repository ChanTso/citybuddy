"""Command-line entry point for the index worker and initial knowledge bootstrap."""

import argparse
import json

from .knowledge import ElasticsearchBootstrapClient, KnowledgeBootstrapError
from .worker import create_worker


def main() -> None:
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command")
    bootstrap = subcommands.add_parser("bootstrap")
    bootstrap.add_argument("--elasticsearch-url", required=True)
    bootstrap.add_argument("--index", required=True)
    bootstrap.add_argument("--alias", default="knowledge_docs_read")
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
    worker = create_worker()
    print(f"{worker.settings.service_name} skeleton constructed")


if __name__ == "__main__":
    main()
