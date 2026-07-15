import json
from pathlib import Path
from typing import Any, cast

ROOT = Path(__file__).resolve().parents[1]


def load_contract() -> dict[str, Any]:
    payload: Any = json.loads(
        (ROOT / "commerce-service/src/main/resources/openapi.json").read_text(encoding="utf-8")
    )
    if not isinstance(payload, dict):
        raise ValueError("Catalog OpenAPI contract must be an object")
    return cast(dict[str, Any], payload)


def test_catalog_contract_exposes_only_authenticated_published_reads() -> None:
    contract = load_contract()

    assert contract["openapi"] == "3.1.0"
    assert set(contract["paths"]) == {
        "/api/products",
        "/api/products/{productId}",
        "/api/orders",
    }
    for path in [
        contract["paths"]["/api/products"],
        contract["paths"]["/api/products/{productId}"],
    ]:
        assert set(path) == {"get"}
        operation = path["get"]
        assert operation["security"] == [{"directUserBearer": []}]
        assert "401" in operation["responses"]

    detail = contract["paths"]["/api/products/{productId}"]["get"]
    assert "404" in detail["responses"]
    assert {item["name"] for item in detail["parameters"]} == {
        "productId",
        "X-Eval-Sandbox-Id",
    }


def test_catalog_product_shape_keeps_live_commerce_fields_explicit() -> None:
    product = load_contract()["components"]["schemas"]["Product"]

    assert product["additionalProperties"] is False
    assert set(product["required"]) == {
        "productId",
        "name",
        "description",
        "priceMinor",
        "currency",
        "stockQuantity",
        "available",
        "publicationVersion",
    }
    assert product["properties"]["priceMinor"]["minimum"] == 0
    assert product["properties"]["stockQuantity"]["minimum"] == 0
    assert product["properties"]["publicationVersion"]["minimum"] == 1
    assert product["properties"]["currency"]["pattern"] == "^[A-Z]{3}$"


def test_order_contract_is_direct_user_idempotent_and_rejects_client_authority() -> None:
    contract = load_contract()
    operation = contract["paths"]["/api/orders"]["post"]

    assert operation["security"] == [{"directUserBearer": []}]
    assert {item["name"] for item in operation["parameters"]} == {
        "Idempotency-Key",
        "X-Correlation-Id",
        "X-Eval-Sandbox-Id",
    }
    assert set(operation["responses"]) == {
        "200",
        "201",
        "400",
        "401",
        "403",
        "409",
        "422",
        "503",
    }
    request = contract["components"]["schemas"]["CreateOrderRequest"]
    assert request["additionalProperties"] is False
    assert set(request["properties"]) == {
        "productId",
        "quantity",
        "expectedProductVersion",
    }
    assert request["properties"]["quantity"] == {
        "type": "integer",
        "minimum": 1,
        "maximum": 100,
    }


def test_order_result_and_rejection_expose_safe_deterministic_evidence() -> None:
    schemas = load_contract()["components"]["schemas"]
    order = schemas["StandardOrder"]
    error = schemas["OrderError"]

    assert order["additionalProperties"] is False
    assert "userSubject" not in order["properties"]
    assert {"unitPriceMinor", "totalPriceMinor", "productVersion", "replayed"} <= set(
        order["required"]
    )
    assert set(error["properties"]["category"]["enum"]) == {
        "AUTHENTICATION",
        "AUTHORIZATION",
        "VALIDATION",
        "OWNERSHIP",
        "IDEMPOTENCY_CONFLICT",
        "STALE_VERSION",
        "INSUFFICIENT_STOCK",
        "CONCURRENCY_EXHAUSTED",
    }
