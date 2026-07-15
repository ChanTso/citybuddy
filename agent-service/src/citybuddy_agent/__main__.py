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
    )


def main() -> None:
    uvicorn.run(
        create_app(_settings()),
        host="127.0.0.1",
        port=int(os.environ.get("AGENT_PORT", "8001")),
    )


if __name__ == "__main__":
    main()
