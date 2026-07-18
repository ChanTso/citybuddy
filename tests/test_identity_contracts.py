import json
from pathlib import Path
from typing import Any, cast

ROOT = Path(__file__).resolve().parents[1]


def load(relative: str) -> dict[str, Any]:
    payload: Any = json.loads((ROOT / relative).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("OpenAPI contract must be an object")
    return cast(dict[str, Any], payload)


def test_auth_contract_has_only_owned_identity_routes_and_closed_request_shapes() -> None:
    contract = load("auth-service/src/main/resources/openapi.json")

    assert set(contract["paths"]) == {
        "/auth/eval/test-token",
        "/auth/login",
        "/auth/jwks",
        "/auth/token/exchange",
        "/internal/eval/test-principals/provision",
        "/internal/eval/test-principals/{handle}/revoke",
    }
    assert contract["components"]["schemas"]["LoginRequest"]["additionalProperties"] is False
    exchange = contract["components"]["schemas"]["ExchangeRequest"]
    assert exchange["additionalProperties"] is False
    assert exchange["properties"]["scope"]["pattern"] == r"^[^*\s]+$"
    assert set(exchange["required"]) == {"sessionId", "userSubject", "scope"}
    assert "actor" not in exchange["properties"]
    schemas = contract["components"]["schemas"]
    assert set(schemas["TokenResponse"]["required"]) == {
        "accessToken",
        "tokenType",
        "expiresIn",
    }
    assert schemas["PublicRsaJwk"]["additionalProperties"] is False
    assert "d" not in schemas["PublicRsaJwk"]["properties"]
    assert (
        contract["paths"]["/auth/jwks"]["get"]["responses"]["200"]["content"]["application/json"][
            "schema"
        ]["$ref"]
        == "#/components/schemas/JwksResponse"
    )
    assert "401" in contract["paths"]["/auth/jwks"]["get"]["responses"]


def test_agent_contract_preserves_server_owned_session_bootstrap() -> None:
    contract = load("agent-service/openapi.json")

    assert "/api/sessions" in contract["paths"]
    operation = contract["paths"]["/api/sessions"]["post"]
    request = operation["requestBody"]["content"]["application/json"]["schema"]
    response = operation["responses"]["201"]["content"]["application/json"]["schema"]
    assert request == {"type": "object", "additionalProperties": False, "maxProperties": 0}
    assert response["required"] == ["sessionId"]
