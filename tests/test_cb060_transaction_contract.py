from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_transaction_checker_is_a_pure_durable_marker_mapping() -> None:
    source = (
        ROOT / "commerce-service/src/main/java/io/citybuddy/commerce/seckill/"
        "RocketMqSeckillTransactions.java"
    ).read_text()
    start = source.index("  TransactionResolution check(MessageView message)")
    end = source.index("\n  private static String singleKey", start)
    checker = source[start:end]

    assert "admissionStore.transactionResolution(singleKey(message))" in checker
    assert "reservationService" not in checker
    assert "properties" not in checker
    assert "getBody" not in checker
    assert "Jdbc" not in checker
    assert "redis" not in checker


def test_deadline_resolver_has_persisted_indexed_bounded_truth() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/commerce/V006__seckill_transaction_order.sql"
    ).read_text()
    repository = (
        ROOT / "commerce-service/src/main/java/io/citybuddy/commerce/seckill/"
        "SeckillReservationRepository.java"
    ).read_text()
    worker = (
        ROOT / "commerce-service/src/main/java/io/citybuddy/commerce/seckill/"
        "SeckillTransactionResolutionWorker.java"
    ).read_text()

    assert "transaction_resolution_due_at TIMESTAMP(6) NOT NULL" in migration
    assert "(state, transaction_resolution_due_at, reservation_id)" in migration
    assert "transaction_resolution_due_at <= CURRENT_TIMESTAMP(6)" in repository
    assert "ORDER BY transaction_resolution_due_at, reservation_id LIMIT ?" in repository
    assert "static final int BATCH_SIZE = 32" in worker
