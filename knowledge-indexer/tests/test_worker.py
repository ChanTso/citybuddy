from citybuddy_indexer import IndexerSettings, IndexerWorker, create_worker


def test_create_worker_preserves_explicit_deterministic_settings() -> None:
    settings = IndexerSettings(environment="test")

    worker = create_worker(settings)

    assert isinstance(worker, IndexerWorker)
    assert worker.settings is settings
    assert worker.settings.service_name == "knowledge-indexer"
    assert worker.settings.environment == "test"
