import runpy
from datetime import date, timedelta
from pathlib import Path

fixture = runpy.run_path(
    str(Path(__file__).resolve().parents[1] / "scripts" / "seed_merchant_fixture.py")
)
PRODUCTS = fixture["PRODUCTS"]
fixture_orders = fixture["fixture_orders"]
fixture_sql = fixture["fixture_sql"]
identity = fixture["identity"]
sql_value = fixture["sql_value"]


def test_fixture_replays_same_identities_when_reporting_date_moves() -> None:
    first = fixture_orders(date(2026, 9, 5))
    second = fixture_orders(date(2026, 9, 6))
    assert len(first) == len(second)
    # Keys use relative days so changing the demo date does not leave old payment identities behind.
    assert {identity(order.key) for order in first} == {identity(order.key) for order in second}
    assert min(order.created_at.date() for order in first) == date(2026, 7, 25)
    assert max(order.created_at.date() for order in first) == date(2026, 9, 4)
    assert min(order.created_at.date() for order in second) == date(2026, 7, 26)


def test_fixture_supports_paid_historical_price_and_period_comparisons() -> None:
    as_of = date(2026, 9, 5)
    orders = fixture_orders(as_of)
    paid = [order for order in orders if order.payment_state == "SUCCEEDED"]
    assert {order.product.currency for order in paid} == {"CNY", "USD"}
    assert all(order.unit_price_minor != order.product.price_minor for order in paid)
    assert {order.payment_state for order in orders} == {None, "PENDING", "FAILED", "SUCCEEDED"}
    assert PRODUCTS[3].product_id not in {order.product.product_id for order in orders}
    boundary = as_of - timedelta(days=14)
    previous = boundary - timedelta(days=14)
    coffee = [order for order in paid if order.product.sku == "coffee"]
    recent_units = sum(order.quantity for order in coffee if order.created_at.date() >= boundary)
    previous_units = sum(
        order.quantity for order in coffee if previous <= order.created_at.date() < boundary
    )
    assert recent_units == 70
    assert previous_units == 28


def test_fixture_sql_is_deterministic_and_quotes_values() -> None:
    as_of = date(2026, 9, 5)
    assert fixture_sql(as_of) == fixture_sql(as_of)
    assert sql_value("shop's \\ name") == "'shop''s \\\\ name'"
    assert sql_value(None) == "NULL"
    assert sql_value(False) == "0"
