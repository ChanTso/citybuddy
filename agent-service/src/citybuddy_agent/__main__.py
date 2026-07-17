"""Command-line entry point for the FastAPI service."""

import os

import uvicorn

from .application import AgentSettings, create_app


def _settings() -> AgentSettings:
    scopes = tuple(item for item in os.environ.get("AGENT_EXCHANGE_SCOPES", "").split() if item)
    return AgentSettings(
        environment=os.environ.get("CITYBUDDY_ENVIRONMENT", "development"),
        identity_enabled=os.environ.get("AGENT_IDENTITY_ENABLED", "false").lower() == "true",
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
        commerce_tools_url=os.environ.get("AGENT_COMMERCE_TOOLS_URL", ""),
        primary_role_alias=os.environ.get("AGENT_PRIMARY_ROLE_ALIAS", "support-standard-primary"),
        fallback_role_alias=os.environ.get(
            "AGENT_FALLBACK_ROLE_ALIAS", "support-standard-fallback"
        ),
        primary_provider_key=os.environ.get("AGENT_PRIMARY_PROVIDER_KEY", "primary"),
        fallback_provider_key=os.environ.get("AGENT_FALLBACK_PROVIDER_KEY", "fallback"),
        attempt_budget=int(os.environ.get("AGENT_ATTEMPT_BUDGET", "8")),
        circuit_minimum_requests=int(os.environ.get("AGENT_CIRCUIT_MINIMUM_REQUESTS", "2")),
        circuit_open_seconds=float(os.environ.get("AGENT_CIRCUIT_OPEN_SECONDS", "1")),
        circuit_half_open_probes=int(os.environ.get("AGENT_CIRCUIT_HALF_OPEN_PROBES", "1")),
    )


def main() -> None:
    uvicorn.run(
        create_app(_settings()),
        host="127.0.0.1",
        port=int(os.environ.get("AGENT_PORT", "8001")),
    )


if __name__ == "__main__":
    main()
