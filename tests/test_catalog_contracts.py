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
    assert set(contract["paths"]) == {"/api/products", "/api/products/{productId}"}
    for path in contract["paths"].values():
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
