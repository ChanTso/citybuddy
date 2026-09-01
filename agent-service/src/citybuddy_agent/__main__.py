"""Command-line entry point for the FastAPI service."""

import os

import uvicorn
from fastapi import FastAPI

from .application import AgentSettings, create_app
from .http_client import HttpClientLayout


def _strict_bool(name: str) -> bool:
    value = os.environ.get(name, "").strip().casefold()
    if value in {"", "false"}:
        return False
    if value == "true":
        return True
    raise ValueError(f"{name} must be true or false")


def _positive_ascii_integer(name: str, *, default: int) -> int:
    value = os.environ.get(name, "").strip()
    if not value:
        return default
    if not value.isascii() or not value.isdecimal():
        raise ValueError(f"{name} must be a positive ASCII integer")
    parsed = int(value)
    if parsed <= 0:
        raise ValueError(f"{name} must be a positive ASCII integer")
    return parsed


def _http_client_layout() -> HttpClientLayout:
    value = os.environ.get("AGENT_HTTP_CLIENT_LAYOUT", "").strip()
    if not value:
        return "shared"
    if value == "shared":
        return "shared"
    if value == "per-authority":
        return "per-authority"
    raise ValueError("AGENT_HTTP_CLIENT_LAYOUT must be shared or per-authority")


def _settings() -> AgentSettings:
    scopes = tuple(item for item in os.environ.get("AGENT_EXCHANGE_SCOPES", "").split() if item)
    temperature = os.environ.get("AGENT_MODEL_TEMPERATURE", "").strip()
    return AgentSettings(
        environment=os.environ.get("CITYBUDDY_ENVIRONMENT", "development"),
        identity_enabled=os.environ.get("AGENT_IDENTITY_ENABLED", "false").lower() == "true",
        evaluation_enabled=os.environ.get("AGENT_EVALUATION_ENABLED", "false").lower() == "true",
        evaluation_client_id=os.environ.get("AGENT_EVALUATION_CLIENT_ID", ""),
        evaluation_client_secret=os.environ.get("AGENT_EVALUATION_CLIENT_SECRET", ""),
        issuer=os.environ.get("IDENTITY_ISSUER", ""),
        user_audience=os.environ.get("IDENTITY_USER_AUDIENCE", ""),
        jwks_url=os.environ.get("IDENTITY_JWKS_URL", ""),
        mysql_host=os.environ.get("MYSQL_HOST", ""),
        mysql_port=int(os.environ.get("MYSQL_PORT", "3306")),
        mysql_password=os.environ.get("MYSQL_AGENT_APP_PASSWORD", ""),
        auth_exchange_url=os.environ.get("IDENTITY_EXCHANGE_URL", ""),
        service_client_id=os.environ.get("AGENT_SERVICE_CLIENT_ID", ""),
        service_client_secret=os.environ.get("AGENT_SERVICE_CLIENT_SECRET", ""),
        exchange_scopes=scopes,
        model_proxy_url=os.environ.get("AGENT_MODEL_PROXY_URL", ""),
        model_proxy_api_key=os.environ.get("AGENT_MODEL_PROXY_API_KEY", ""),
        model_temperature=float(temperature) if temperature else None,
        model_timeout_seconds=float(os.environ.get("AGENT_MODEL_TIMEOUT_SECONDS", "2")),
        commerce_tools_url=os.environ.get("AGENT_COMMERCE_TOOLS_URL", ""),
        commerce_liveness_url=os.environ.get("AGENT_COMMERCE_LIVENESS_URL", ""),
        elasticsearch_url=os.environ.get("AGENT_ELASTICSEARCH_URL", ""),
        knowledge_alias=os.environ.get("AGENT_KNOWLEDGE_ALIAS", "knowledge_docs_read"),
        support_redis_url=os.environ.get("AGENT_SUPPORT_REDIS_URL", ""),
        primary_role_alias=os.environ.get("AGENT_PRIMARY_ROLE_ALIAS", "support-standard-primary"),
        fallback_role_alias=os.environ.get(
            "AGENT_FALLBACK_ROLE_ALIAS", "support-standard-fallback"
        ),
        primary_provider_key=os.environ.get("AGENT_PRIMARY_PROVIDER_KEY", "primary"),
        fallback_provider_key=os.environ.get("AGENT_FALLBACK_PROVIDER_KEY", "fallback"),
        attempt_budget=int(os.environ.get("AGENT_ATTEMPT_BUDGET", "16")),
        circuit_minimum_requests=int(os.environ.get("AGENT_CIRCUIT_MINIMUM_REQUESTS", "2")),
        circuit_open_seconds=float(os.environ.get("AGENT_CIRCUIT_OPEN_SECONDS", "1")),
        circuit_half_open_probes=int(os.environ.get("AGENT_CIRCUIT_HALF_OPEN_PROBES", "1")),
        metrics_enabled=_strict_bool("CITYBUDDY_METRICS_ENABLED"),
        trace_export_url=os.environ.get("CITYBUDDY_TRACE_EXPORT_URL", ""),
        http_client_layout=_http_client_layout(),
    )


def create_runtime_app() -> FastAPI:
    """Build one worker's complete application before it accepts traffic."""
    return create_app(_settings())


def main() -> None:
    uvicorn.run(
        "citybuddy_agent.__main__:create_runtime_app",
        host="127.0.0.1",
        port=int(os.environ.get("AGENT_PORT", "8001")),
        factory=True,
        workers=_positive_ascii_integer("AGENT_WORKERS", default=1),
    )


if __name__ == "__main__":
    main()
