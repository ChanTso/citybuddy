"""Application factory without business routes or provider access."""

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict


class AgentSettings(BaseModel):
    """Deterministic settings accepted by the baseline factory."""

    model_config = ConfigDict(frozen=True)

    service_name: str = "agent-service"
    environment: str = "development"


def create_app(settings: AgentSettings | None = None) -> FastAPI:
    """Construct the framework application without adding business behavior."""
    resolved = settings or AgentSettings()
    app = FastAPI(title=resolved.service_name, docs_url=None, redoc_url=None)
    app.state.settings = resolved
    return app
