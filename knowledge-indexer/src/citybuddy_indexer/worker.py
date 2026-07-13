"""Worker factory without messaging, retrieval, or indexing behavior."""

from dataclasses import dataclass


@dataclass(frozen=True)
class IndexerSettings:
    service_name: str = "knowledge-indexer"
    environment: str = "development"


@dataclass(frozen=True)
class IndexerWorker:
    settings: IndexerSettings


def create_worker(settings: IndexerSettings | None = None) -> IndexerWorker:
    """Construct a worker from explicit or deterministic default settings."""
    return IndexerWorker(settings=settings or IndexerSettings())
