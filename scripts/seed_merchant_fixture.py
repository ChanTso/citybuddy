#!/usr/bin/env python3
"""Print repeatable merchant demo SQL; pipe it to the existing privileged MySQL connection.

The exclusive --as-of date fixes 42 complete UTC days. This is synthetic development data,
not measured results. The reserved shopmate-fixture namespace is reset in one transaction.
No database credentials are read or written by this generator.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from uuid import NAMESPACE_URL, uuid5

PREFIX = "shopmate-fixture-"
OPERATOR = PREFIX + "operator"


@dataclass(frozen=True)
class Product:
    sku: str
    name: str
    price_minor: int
    currency: str
    stock: int
    available: bool = True

    @property
    def product_id(self) -> str:
        return PREFIX + self.sku


PRODUCTS = (
    Product("coffee", "House coffee beans", 2400, "CNY", 420),
    Product("tea", "Jasmine tea", 1800, "CNY", 95),
    Product("mug", "Ceramic mug", 3900, "CNY", 18),
    Product("tote", "Canvas tote", 2900, "CNY", 80),
    Product("cocoa-usd", "Cocoa gift box", 1100, "USD", 32),
    Product("unavailable", "Seasonal blend", 3200, "CNY", 0, False),
    Product("seckill", "Limited coffee set", 5900, "CNY", 20),
)


@dataclass(frozen=True)
class Order:
    key: str
    product: Product
    quantity: int
    unit_price_minor: int
    product_version: int
    created_at: datetime
    payment_state: str | None = "SUCCEEDED"

    @property
    def amount_minor(self) -> int:
        return self.quantity * self.unit_price_minor


def fixture_orders(as_of: date) -> list[Order]:
    orders = []
    for day_index in range(42):
        day = as_of - timedelta(days=42 - day_index)
        for product_index in (0, 1, 2, 4):
            product = PRODUCTS[product_index]
            if product.sku == "mug" and day_index % 7 < 5:
                continue
            recent = day_index >= 28
            quantity = {
                "coffee": 5 if recent else 2,
                "tea": 1 if recent else 4,
                "mug": 3,
                "cocoa-usd": 2,
            }[product.sku]
            price = product.price_minor - (300 if day_index < 21 else 100)
            orders.append(
                Order(
                    f"day-{day_index:02d}-{product.sku}",
                    product,
                    quantity,
                    price,
                    1 if day_index < 21 else 2,
                    datetime.combine(day, time(10 + product_index, 15)),
                )
            )
    last_day = datetime.combine(as_of - timedelta(days=1), time(18))
    orders.extend(
        (
            Order("unpaid", PRODUCTS[0], 2, 2400, 3, last_day, None),
            Order("pending", PRODUCTS[1], 3, 1800, 3, last_day, "PENDING"),
            Order("failed", PRODUCTS[2], 1, 3900, 3, last_day, "FAILED"),
        )
    )
    return orders


def identity(label: str) -> str:
    return str(uuid5(NAMESPACE_URL, "citybuddy/merchant-fixture/" + label))


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def sql_value(value: str | int | bool | datetime | None) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, datetime):
        value = value.strftime("%Y-%m-%d %H:%M:%S.%f")
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def insert(table: str, values: dict[str, str | int | bool | datetime | None]) -> str:
    columns = ", ".join(values)
    cells = ", ".join(sql_value(value) for value in values.values())
    return f"INSERT INTO {table} ({columns}) VALUES ({cells});"


def order_sql(order: Order) -> list[str]:
    product = order.product
    order_id = identity("order/" + order.key)
    user = PREFIX + "buyer-" + order.key
    paid = order.payment_state == "SUCCEEDED"
    rows = [
        insert(
            "standard_order",
            {
                "order_id": order_id,
                "user_subject": user,
                "product_id": product.product_id,
                "product_name": product.name,
                "unit_price_minor": order.unit_price_minor,
                "currency": product.currency,
                "quantity": order.quantity,
                "total_price_minor": order.amount_minor,
                "product_version": order.product_version,
                "status": "PAID" if paid else "UNPAID",
                "state_version": 2 if paid else 1,
                "created_at": order.created_at,
            },
        ),
        insert(
            "order_idempotency",
            {
                "user_subject": user,
                "idempotency_key": PREFIX + "order-" + order.key,
                "intent_hash": digest(
                    f"{len(product.product_id)}:{product.product_id}:"
                    f"{order.quantity}:{order.product_version}"
                ),
                "order_id": order_id,
                "created_at": order.created_at,
            },
        ),
    ]
    event_id = identity("order-event/" + order.key)
    rows.append(
        insert(
            "commerce_outbox",
            {
                "event_id": event_id,
                "aggregate_type": "STANDARD_ORDER",
                "aggregate_id": order_id,
                "aggregate_version": 1,
                "event_type": "STANDARD_ORDER_CREATED",
                "payload": json.dumps(
                    {
                        "eventId": event_id,
                        "orderId": order_id,
                        "productId": product.product_id,
                        "quantity": order.quantity,
                        "unitPriceMinor": order.unit_price_minor,
                        "currency": product.currency,
                        "productVersion": order.product_version,
                    },
                    separators=(",", ":"),
                ),
                "created_at": order.created_at,
            },
        )
    )
    if order.payment_state is None:
        return rows
    attempt_id = identity("attempt/" + order.key)
    correlation_id = identity("correlation/" + order.key)
    request_key = PREFIX + "payment-" + order.key
    paid_at = order.created_at + timedelta(minutes=2)
    rows.append(
        insert(
            "mock_payment_attempt",
            {
                "attempt_id": attempt_id,
                "callback_correlation_id": correlation_id,
                "user_subject": user,
                "order_id": order_id,
                "order_kind": "STANDARD",
                "request_idempotency_key": request_key,
                "intent_hash": digest(
                    f"{order_id}\n{request_key}\n{order.amount_minor}\n{product.currency}\n"
                ),
                "amount_minor": order.amount_minor,
                "currency": product.currency,
                "state": order.payment_state,
                "state_version": 1 if order.payment_state == "PENDING" else 2,
                "succeeded_at": paid_at if paid else None,
                "created_at": order.created_at,
            },
        )
    )
    if not paid:
        return rows
    callback_id = identity("callback/" + order.key)
    callback_key = PREFIX + "callback-" + order.key
    rows.append(
        insert(
            "mock_payment_callback",
            {
                "callback_event_id": callback_id,
                "callback_idempotency_key": callback_key,
                "attempt_id": attempt_id,
                "callback_correlation_id": correlation_id,
                "intent_hash": digest(
                    "\n".join(
                        (
                            callback_id,
                            correlation_id,
                            order_id,
                            str(order.amount_minor),
                            product.currency,
                            "SUCCEEDED",
                            "",
                            "",
                            "",
                            "",
                            callback_key,
                        )
                    )
                ),
                "requested_outcome": "SUCCEEDED",
                "result_state": "APPLIED",
                "created_at": paid_at,
            },
        )
    )
    rows.append(
        insert(
            "inventory_ledger",
            {
                "movement_id": identity("payment-ledger/" + order.key),
                "business_event_key": "mock-payment:" + attempt_id,
                "movement_type": "STANDARD_PAYMENT",
                "order_id": order_id,
                "reservation_id": None,
                "activity_id": None,
                "product_id": product.product_id,
                "inventory_delta": 0,
                "activity_quota_delta": 0,
                "payment_amount_minor": order.amount_minor,
                "payment_currency": product.currency,
                "created_at": paid_at,
            },
        )
    )
    return rows


def fixture_sql(as_of: date) -> str:
    product_ids = ", ".join(sql_value(product.product_id) for product in PRODUCTS)
    rows = [
        f"-- Synthetic merchant fixture; exclusive UTC as_of={as_of.isoformat()}.",
        f"-- Reserved operator subject: {OPERATOR}; no credentials are provisioned here.",
        "SET NAMES utf8mb4;",
        "SET SESSION time_zone = '+00:00';",
        "START TRANSACTION;",
        f"DELETE FROM merchant_price_draft WHERE operator_subject = '{OPERATOR}';",
        "DELETE r FROM action_receipt r JOIN pending_action p USING (pending_action_id) "
        f"WHERE p.user_subject LIKE '{PREFIX}buyer-%';",
        f"DELETE FROM pending_action WHERE user_subject LIKE '{PREFIX}buyer-%';",
        f"DELETE FROM mock_refund WHERE user_subject LIKE '{PREFIX}buyer-%';",
        "DELETE c FROM mock_payment_callback c JOIN mock_payment_attempt a USING (attempt_id) "
        f"WHERE a.user_subject LIKE '{PREFIX}buyer-%';",
        "DELETE l FROM inventory_ledger l JOIN standard_order o USING (order_id) "
        f"WHERE o.user_subject LIKE '{PREFIX}buyer-%';",
        "DELETE e FROM commerce_outbox e JOIN standard_order o ON e.aggregate_id = o.order_id "
        f"WHERE e.aggregate_type = 'STANDARD_ORDER' AND o.user_subject LIKE '{PREFIX}buyer-%';",
        f"DELETE FROM mock_payment_attempt WHERE user_subject LIKE '{PREFIX}buyer-%';",
        f"DELETE FROM order_idempotency WHERE user_subject LIKE '{PREFIX}buyer-%';",
        f"DELETE FROM standard_order WHERE user_subject LIKE '{PREFIX}buyer-%';",
        f"DELETE FROM seckill_activity WHERE activity_id = '{PREFIX}closed-activity';",
        "DELETE FROM commerce_outbox WHERE aggregate_type = 'PRODUCT' "
        f"AND aggregate_id IN ({product_ids});",
        f"DELETE FROM product WHERE product_id IN ({product_ids});",
    ]
    for product in PRODUCTS:
        rows.append(
            insert(
                "product",
                {
                    "product_id": product.product_id,
                    "name": product.name,
                    "description": "Synthetic merchant development fixture",
                    "price_minor": product.price_minor,
                    "currency": product.currency,
                    "stock_quantity": product.stock,
                    "available": product.available,
                    "publication_state": "PUBLISHED",
                    "publication_version": 3,
                    "created_at": datetime.combine(as_of - timedelta(days=43), time()),
                    "updated_at": datetime.combine(as_of, time()),
                },
            )
        )
    rows.append(
        insert(
            "seckill_activity",
            {
                "activity_id": PREFIX + "closed-activity",
                "product_id": PRODUCTS[-1].product_id,
                "starts_at": datetime.combine(as_of - timedelta(days=3), time()),
                "ends_at": datetime.combine(as_of - timedelta(days=2), time()),
                "state": "CLOSED",
                "allocated_quota": 10,
                "projection_version": 1,
            },
        )
    )
    for order in fixture_orders(as_of):
        rows.extend(order_sql(order))
    rows.extend(
        (
            "INSERT INTO catalog_metadata (singleton_id, publication_generation) VALUES (1, 1) "
            "ON DUPLICATE KEY UPDATE publication_generation = publication_generation + 1;",
            "COMMIT;",
        )
    )
    return "\n".join(rows) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--as-of",
        required=True,
        type=date.fromisoformat,
        help="exclusive reporting end date (YYYY-MM-DD, UTC)",
    )
    args = parser.parse_args()
    print(fixture_sql(args.as_of), end="")


if __name__ == "__main__":
    main()
