import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "commerce-service/src/main/resources/async-entry-inventory.json"

FAQ_REPOSITORY = "commerce-service/src/main/java/io/citybuddy/commerce/faq/FaqRepository.java"
EVALUATION_VIEW_REPOSITORY = (
    "commerce-service/src/main/java/io/citybuddy/commerce/evaluation/EvaluationViewRepository.java"
)
LEGACY_COMMITMENT_STORE = (
    "commerce-service/src/main/java/io/citybuddy/commerce/evaluation/"
    "EvaluationLegacyAuditCommitmentStore.java"
)

# This registry is a cheap review heuristic for direct SQL predicates, not a completeness proof.
# Behavioral totality is proved by the information_schema-driven real integration matrix.
INTEGRITY_ENUMERATOR_PREDICATE_HEURISTICS = {
    (FAQ_REPOSITORY, "publicationTruths"): {
        "c.event_id": "stable command/outbox correlation key",
        "o.event_id": "stable command/outbox correlation key and missing-face signal",
        "c.faq_id": "stable command/source correlation key",
        "s.faq_id": "stable command/source correlation key and missing-face signal",
    },
    (FAQ_REPOSITORY, "allOutboxEvents"): {},
    (FAQ_REPOSITORY, "allSources"): {},
    (FAQ_REPOSITORY, "allDraftCommands"): {},
    (EVALUATION_VIEW_REPOSITORY, "allAuditReferences"): {
        "sandbox_id": "stable sandbox scope",
    },
    (EVALUATION_VIEW_REPOSITORY, "productObservationTruths"): {
        "sandbox_id": "stable sandbox scope",
    },
    (EVALUATION_VIEW_REPOSITORY, "paidOrderTruths"): {
        "sandbox_id": "stable sandbox scope",
        "status": "terminal truth-face classifier",
    },
    (EVALUATION_VIEW_REPOSITORY, "paymentLedgerTruths"): {
        "order_id": "stable ledger/order correlation key",
        "sandbox_id": "stable sandbox key source for the order face",
    },
    (EVALUATION_VIEW_REPOSITORY, "succeededCallbackTruths"): {
        "a.attempt_id": "stable callback/attempt correlation key",
        "c.attempt_id": "stable callback/attempt correlation key",
        "c.callback_correlation_id": "stable callback/attempt correlation key",
        "c.callback_event_id": "stable callback/audit correlation key",
        "sandbox_id": "stable sandbox key source for attempt and audit faces",
    },
    (EVALUATION_VIEW_REPOSITORY, "paymentFaceCardinalitiesConsistent"): {
        "sandbox_id": "stable sandbox key source; outer grouping remains unscoped",
        "callback_correlation_id": "stable callback/attempt correlation key",
        "order_id": "stable order/ledger correlation key",
        "entity_id": "stable audit/callback entity correlation key",
    },
    (LEGACY_COMMITMENT_STORE, "load"): {
        "created_at_anchor": "fixed committed legacy-set classifier",
    },
}


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


def java_method_body(java_source: str, method_name: str) -> str:
    signature = re.search(
        rf"\b{re.escape(method_name)}\s*\([^)]*\)\s*"
        rf"(?:throws\s+[\w., ]+\s*)?\{{",
        java_source,
        re.DOTALL,
    )
    assert signature is not None, f"Java method not found: {method_name}"
    opening = java_source.index("{", signature.start())
    depth = 0
    for index in range(opening, len(java_source)):
        if java_source[index] == "{":
            depth += 1
        elif java_source[index] == "}":
            depth -= 1
            if depth == 0:
                return java_source[opening + 1 : index]
    raise AssertionError(f"Unclosed Java method: {method_name}")


def sql_predicate_columns(method_body: str) -> set[str]:
    columns: set[str] = set()
    clause_pattern = re.compile(
        r"\b(?:ON|WHERE)\b(.*?)(?="
        r"\b(?:LEFT|RIGHT|INNER|FULL|CROSS)?\s*JOIN\b|\bWHERE\b|"
        r"\bGROUP\s+BY\b|\bORDER\s+BY\b|\bLIMIT\b|"
        r"\bFOR\s+(?:UPDATE|SHARE)\b|$)",
        re.IGNORECASE | re.DOTALL,
    )
    for sql in re.findall(r'"""(.*?)"""', method_body, re.DOTALL):
        without_literals = re.sub(r"'(?:''|[^'])*'", "''", sql)
        for clause in clause_pattern.findall(without_literals):
            columns.update(
                f"{table.casefold()}.{column.casefold()}"
                for table, column in re.findall(
                    r"\b([a-z_][a-z0-9_]*)\.([a-z_][a-z0-9_]*)\b",
                    clause,
                    re.IGNORECASE,
                )
            )
            columns.update(
                column.casefold()
                for column in re.findall(
                    r"(?<![.\w])([a-z_][a-z0-9_]*)\b\s*"
                    r"(?:=|<>|!=|<=|>=|<|>|\bIS\b|\bIN\b|\bLIKE\b)",
                    clause,
                    re.IGNORECASE,
                )
            )
    return columns


def java_methods(java_source: str) -> dict[str, str]:
    method_names = set(
        re.findall(
            r"\b(?:public|private|protected)\s+(?:static\s+)?"
            r"[\w<>,.?\[\] ]+\s+(\w+)\s*\(",
            java_source,
        )
    )
    return {name: java_method_body(java_source, name) for name in method_names}


def test_inventory_is_complete_and_has_no_evaluation_reachable_path() -> None:
    inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
    assert inventory["evaluationReachablePathCount"] == 0
    assert inventory["version"] == "cb112-v1"
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
    indexer = source("knowledge-indexer/src/citybuddy_indexer/worker.py")
    assert "RESERVED_SANDBOX_PROPERTY" in indexer
    assert "_has_reserved_sandbox(properties)" in indexer
    assert indexer.index("_has_reserved_sandbox(properties)") < indexer.index(
        "FaqKnowledgeEvent.from_bytes(body)"
    )
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
    assert "RocketMqKnowledgeConsumer" in indexer_worker
    assert "ElasticsearchKnowledgeProjection" in indexer_worker
    assert "RedisFaqCacheProjection" in indexer_worker
    assert "RESERVED_SANDBOX_PROPERTY" in indexer_worker
    assert "SimpleConsumer" in indexer_worker
    inventory = source("commerce-service/src/main/resources/async-entry-inventory.json")
    assert (
        "RocketMqKnowledgeConsumer -> ElasticsearchKnowledgeProjection + "
        "RedisFaqCacheProjection" in inventory
    )
    assert "rocketmq" not in agent_files.lower()


def test_faq_cache_writers_remain_on_their_exact_runtime_boundaries() -> None:
    indexer_projection_references = relative_sources_with(
        "knowledge-indexer/src", ".py", "RedisFaqCacheProjection"
    )
    assert indexer_projection_references == {
        "knowledge-indexer/src/citybuddy_indexer/__init__.py",
        "knowledge-indexer/src/citybuddy_indexer/faq_cache.py",
        "knowledge-indexer/src/citybuddy_indexer/worker.py",
    }
    agent_cache_references = relative_sources_with("agent-service/src", ".py", "RedisFaqCache")
    assert agent_cache_references == {
        "agent-service/src/citybuddy_agent/application.py",
        "agent-service/src/citybuddy_agent/faq_cache.py",
    }
    commerce_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((ROOT / "commerce-service/src").rglob("*"))
        if path.is_file()
    )
    assert "cb:faq:v1:" not in commerce_sources
    assert "redis-support" not in commerce_sources.casefold()


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

    python_consumers = relative_sources_with("knowledge-indexer/src", ".py", "SimpleConsumer(")
    assert python_consumers == {
        "knowledge-indexer/src/citybuddy_indexer/rocketmq_spike.py",
        "knowledge-indexer/src/citybuddy_indexer/worker.py",
    }
    assert source("knowledge-indexer/src/citybuddy_indexer/worker.py").count("SimpleConsumer(") == 1

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
    assert faq_query.casefold().count("from commerce_outbox") == 3
    assert 'static final String AGGREGATE_TYPE = "FAQ"' in faq_query
    assert 'static final String EVENT_TYPE = "FAQ_KNOWLEDGE_SYNCHRONIZATION"' in faq_query
    assert faq_query.count("AGGREGATE_TYPE") == 5
    assert faq_query.count("EVENT_TYPE") == 5
    assert "aggregate_type = ?" in faq_query
    assert "event_type = ?" in faq_query
    assert (
        "FROM faq_publication_command c\n"
        "        LEFT JOIN commerce_outbox o ON o.event_id = c.event_id" in faq_query
    )
    assert "LEFT JOIN faq_source s ON s.faq_id = c.faq_id" in faq_query
    assert "public List<PublicationTruth> publicationTruths()" in faq_query
    assert "public List<OutboxEvent> allOutboxEvents()" in faq_query
    assert "public List<FaqSource> allSources()" in faq_query
    assert "public List<DraftCommand> allDraftCommands()" in faq_query
    for outbox_query in (product_query, faq_query):
        assert "STANDARD_ORDER" not in outbox_query[outbox_query.index("FROM commerce_outbox") :]
        assert "REFUND_" not in outbox_query[outbox_query.index("FROM commerce_outbox") :]


def test_direct_integrity_predicates_match_the_review_heuristic() -> None:
    for (path, method_name), allowlist in INTEGRITY_ENUMERATOR_PREDICATE_HEURISTICS.items():
        body = java_method_body(source(path), method_name)
        actual = sql_predicate_columns(body)
        assert actual == set(allowlist), (
            f"{path}::{method_name} predicate columns changed; every WHERE/ON column "
            "must be reviewed as a stable key/scope or terminal face; this textual "
            "heuristic is not totality evidence"
        )
        assert all(reason.strip() for reason in allowlist.values())

    faq_publisher = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/faq/FaqOutboxPublisher.java"
    )
    before_first_send = java_method_body(faq_publisher, "publishPending").split(
        "sender.send", maxsplit=1
    )[0]
    faq_enumerators = set(re.findall(r"repository\.(\w+)\s*\(", before_first_send))
    registered_faq = {
        method
        for path, method in INTEGRITY_ENUMERATOR_PREDICATE_HEURISTICS
        if path == FAQ_REPOSITORY
    }
    assert faq_enumerators == registered_faq

    evaluation_source = source(EVALUATION_VIEW_REPOSITORY)
    evaluation_methods = java_methods(evaluation_source)
    reachable = {"auditReferencesConsistent"}
    pending = ["auditReferencesConsistent"]
    while pending:
        method = pending.pop()
        body = evaluation_methods[method]
        for candidate in evaluation_methods:
            if candidate not in reachable and re.search(rf"\b{re.escape(candidate)}\s*\(", body):
                reachable.add(candidate)
                pending.append(candidate)
    reachable_sql_enumerators = {
        method for method in reachable if sql_predicate_columns(evaluation_methods[method])
    }
    registered_evaluation = {
        method
        for path, method in INTEGRITY_ENUMERATOR_PREDICATE_HEURISTICS
        if path == EVALUATION_VIEW_REPOSITORY
    }
    assert reachable_sql_enumerators == registered_evaluation
    assert (
        "EvaluationLegacyAuditCommitmentStore.load(jdbc)"
        in evaluation_methods["auditReferencesConsistent"]
    )


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
