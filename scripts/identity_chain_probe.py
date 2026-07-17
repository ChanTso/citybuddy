"""Exercise the agent-owned session recheck and JIT exchange against real services."""

import argparse
import os
from pathlib import Path

from citybuddy_agent.application import (
    AgentSettings,
    DirectJwtValidator,
    HttpJwksSource,
    MysqlSessionStore,
    OboClient,
)


def required(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise ValueError(f"Missing runtime setting: {name}")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    settings = AgentSettings(
        environment="integration",
        identity_enabled=True,
        issuer=required("IDENTITY_ISSUER"),
        user_audience=required("IDENTITY_USER_AUDIENCE"),
        jwks_url=required("IDENTITY_JWKS_URL"),
        mysql_host="127.0.0.1",
        mysql_port=int(required("MYSQL_PORT")),
        mysql_password=required("MYSQL_AGENT_APP_PASSWORD"),
        auth_exchange_url=required("IDENTITY_EXCHANGE_URL"),
        service_client_id="agent-service",
        service_client_secret=required("AGENT_SERVICE_CLIENT_SECRET"),
        exchange_scopes=("catalog:read",),
    )
    token = required("DIRECT_TOKEN")
    session_id = required("SUPPORT_SESSION_ID")
    validator = DirectJwtValidator(settings, HttpJwksSource(settings.jwks_url))
    principal = validator.validate(token)
    obo = OboClient(settings, MysqlSessionStore(settings)).exchange(
        token, principal.subject, session_id, "catalog:read"
    )
    args.output.write_text(obo, encoding="utf-8")


if __name__ == "__main__":
    main()
