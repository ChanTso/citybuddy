from citybuddy_agent import AgentSettings, create_app
from fastapi import FastAPI


def test_create_app_uses_explicit_deterministic_settings() -> None:
    settings = AgentSettings(environment="test")

    app = create_app(settings)

    assert isinstance(app, FastAPI)
    assert app.title == "agent-service"
    assert app.state.settings is settings
    assert app.state.settings.environment == "test"
