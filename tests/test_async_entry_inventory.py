import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "commerce-service/src/main/resources/async-entry-inventory.json"


def source(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def relative_sources_with(
    root: str, suffix: str, needle: str, *, casefold: bool = False
) -> set[str]:
    base = ROOT / root
    expected = needle.casefold() if casefold else needle
    return {
        str(path.relative_to(ROOT))
        for path in base.rglob(f"*{suffix}")
        if expected
        in (
            path.read_text(encoding="utf-8").casefold()
            if casefold
            else path.read_text(encoding="utf-8")
        )
    }


def test_inventory_is_complete_and_has_no_evaluation_reachable_path() -> None:
    inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
    assert inventory["evaluationReachablePathCount"] == 0
    assert inventory["reservedSandboxEnvelopeProperty"] == "citybuddy-eval-sandbox-id"
    paths = {path["id"]: path for path in inventory["paths"]}
    assert set(paths) == {
        "seckill-order-transaction",
        "seckill-unpaid-timeout",
        "product-publication",
        "faq-publication",
        "standard-order-and-refund-outbox",
        "knowledge-indexer-spike",
        "agent-service",
    }
    assert all(path["classification"] != "evaluation-reachable" for path in paths.values())
    assert all(path["evidence"] for path in paths.values())


def test_commerce_message_schemas_cannot_encode_sandbox_context() -> None:
    transaction = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/seckill/"
        "SeckillTransactionMessage.java"
    )
    timeout = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/seckill/SeckillTimeoutMessage.java"
    )
    catalog = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/catalog/ProductRepository.java"
    )
    faq = source("commerce-service/src/main/java/io/citybuddy/commerce/faq/FaqKnowledgeEvent.java")
    assert "sandbox" not in transaction.lower()
    assert "sandbox" not in timeout.lower()
    catalog_event = catalog[catalog.index("public record CatalogEvent") :]
    assert "sandbox" not in catalog_event.split(") {}", 1)[0].lower()
    assert "sandbox" not in faq.lower()


def test_production_consumers_reject_reserved_sandbox_envelope_property() -> None:
    catalog = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/catalog/RocketMqCatalogMessaging.java"
    )
    transaction = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/seckill/"
        "RocketMqSeckillTransactions.java"
    )
    timeout = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/seckill/RocketMqSeckillTimeouts.java"
    )
    assert 'RESERVED_SANDBOX_PROPERTY = "citybuddy-eval-sandbox-id"' in catalog
    assert 'RESERVED_SANDBOX_PROPERTY = "citybuddy-eval-sandbox-id"' in transaction
    assert "rejectEvaluationContext(message)" in catalog
    assert "rejectEvaluationContext(message)" in transaction
    assert "rejectEvaluationContext(message)" in timeout
    assert ".addProperty(" not in catalog
    assert ".addProperty(" not in transaction
    assert ".addProperty(" not in timeout


def test_outbox_and_non_commerce_paths_are_not_hidden_async_carriers() -> None:
    products = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/catalog/ProductRepository.java"
    )
    faq = source("commerce-service/src/main/java/io/citybuddy/commerce/faq/FaqRepository.java")
    indexer_worker = source("knowledge-indexer/src/citybuddy_indexer/worker.py")
    spike_event = source("knowledge-indexer/src/citybuddy_indexer/spike_event.py")
    agent_files = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((ROOT / "agent-service/src").rglob("*.py"))
    )
    assert "event_type = 'PRODUCT_PUBLICATION_CHANGED'" in products
    assert 'static final String EVENT_TYPE = "FAQ_KNOWLEDGE_SYNCHRONIZATION"' in faq
    assert "sandbox" not in spike_event.lower()
    assert "without messaging" in indexer_worker
    assert "rocketmq" not in agent_files.lower()


def test_faq_publication_has_no_early_api_projection_or_fixture_promotion() -> None:
    faq_root = ROOT / "commerce-service/src/main/java/io/citybuddy/commerce/faq"
    faq_sources = "\n".join(
        path.read_text(encoding="utf-8") for path in sorted(faq_root.glob("*.java"))
    )
    migration = source("infra/mysql/migrations/commerce/V014__faq_publication_outbox.sql")
    assert "Controller" not in faq_sources
    assert "Elasticsearch" not in faq_sources
    assert "Redis" not in faq_sources
    assert "INSERT INTO faq_source" not in migration
    assert "bootstrap" not in migration.casefold()


def test_inventory_closes_all_runtime_rocketmq_builders_and_outbox_readers() -> None:
    runtime_messaging = {
        "commerce-service/src/main/java/io/citybuddy/commerce/catalog/"
        "RocketMqCatalogMessaging.java",
        "commerce-service/src/main/java/io/citybuddy/commerce/seckill/RocketMqSeckillTimeouts.java",
        "commerce-service/src/main/java/io/citybuddy/commerce/seckill/"
        "RocketMqSeckillTransactions.java",
    }
    producers = relative_sources_with(
        "commerce-service/src/main/java", ".java", ".newProducerBuilder()"
    )
    consumers = relative_sources_with(
        "commerce-service/src/main/java", ".java", ".newSimpleConsumerBuilder()"
    )
    assert producers == runtime_messaging
    assert consumers == runtime_messaging
    assert sum(source(path).count(".newProducerBuilder()") for path in producers) == 3
    assert sum(source(path).count(".newSimpleConsumerBuilder()") for path in consumers) == 3

    outbox_readers = relative_sources_with(
        "commerce-service/src/main/java",
        ".java",
        "FROM commerce_outbox",
        casefold=True,
    )
    assert outbox_readers == {
        "commerce-service/src/main/java/io/citybuddy/commerce/catalog/ProductRepository.java",
        "commerce-service/src/main/java/io/citybuddy/commerce/faq/FaqRepository.java",
    }
    product_query = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/catalog/ProductRepository.java"
    )
    faq_query = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/faq/FaqRepository.java"
    )
    assert product_query.casefold().count("from commerce_outbox") == 1
    assert "event_type = 'PRODUCT_PUBLICATION_CHANGED'" in product_query
    assert faq_query.casefold().count("from commerce_outbox") == 2
    assert 'static final String AGGREGATE_TYPE = "FAQ"' in faq_query
    assert 'static final String EVENT_TYPE = "FAQ_KNOWLEDGE_SYNCHRONIZATION"' in faq_query
    assert faq_query.count("AGGREGATE_TYPE") == 5
    assert faq_query.count("EVENT_TYPE") == 5
    assert "aggregate_type = ?" in faq_query
    assert "event_type = ?" in faq_query
    for outbox_query in (product_query, faq_query):
        assert "STANDARD_ORDER" not in outbox_query[outbox_query.index("FROM commerce_outbox") :]
        assert "REFUND_" not in outbox_query[outbox_query.index("FROM commerce_outbox") :]


def test_real_entries_are_production_only_and_guard_draft_is_absent() -> None:
    controllers = [
        source(
            "commerce-service/src/main/java/io/citybuddy/commerce/seckill/"
            "SeckillReservationController.java"
        ),
        source("commerce-service/src/main/java/io/citybuddy/commerce/order/OrderController.java"),
        source("commerce-service/src/main/java/io/citybuddy/commerce/refund/RefundController.java"),
    ]
    assert all(".authorize(" in controller for controller in controllers)
    assert all("authorizeEvaluation" not in controller for controller in controllers)
    assert not (
        ROOT / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation/"
        "AsyncSandboxGuard.java"
    ).exists()
    assert not (
        ROOT / "infra/mysql/migrations/commerce/V012__asynchronous_sandbox_liveness.sql"
    ).exists()


def test_frozen_contract_transfers_guard_to_the_first_introducing_slice() -> None:
    contracts = source("docs/CONTRACTS.md")
    specification = source("docs/slices/CB-104.md")
    assert "evaluationReachablePathCount = 0" in specification
    assert "must implement the sandbox liveness guard in that same slice" in contracts
    assert "real producer and Broker" in contracts
    assert "CB-104 does not claim or test sandbox liveness guard behavior" in specification
