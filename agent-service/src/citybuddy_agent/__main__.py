"""Command-line entry point for the support identity and evidence service."""

import os

import uvicorn
from fastapi import FastAPI

from .application import AgentSettings, create_app
from .http_client import HttpClientLayout


def _strict_bool(name: str, *, default: bool = False) -> bool:
    value = os.environ.get(name, "").strip().casefold()
    if not value:
        return default
    if value == "false":
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
        commerce_liveness_url=os.environ.get("AGENT_COMMERCE_LIVENESS_URL", ""),
        attempt_budget=int(os.environ.get("AGENT_ATTEMPT_BUDGET", "16")),
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
        workers=_positive_ascii_integer("AGENT_WORKERS", default=4),
    )


if __name__ == "__main__":
    main()
