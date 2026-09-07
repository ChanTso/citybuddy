from __future__ import annotations

import inspect
from typing import Any

import pytest
from citybuddy_agent import __main__ as main_module
from citybuddy_agent.application import AgentSettings


@pytest.mark.parametrize("value", (None, "", "   "))
def test_workers_default_to_four_when_unset_or_blank(
    monkeypatch: pytest.MonkeyPatch, value: str | None
) -> None:
    if value is None:
        monkeypatch.delenv("AGENT_WORKERS", raising=False)
    else:
        monkeypatch.setenv("AGENT_WORKERS", value)

    assert main_module._positive_ascii_integer("AGENT_WORKERS", default=4) == 4


@pytest.mark.parametrize(("value", "expected"), (("1", 1), ("2", 2), (" 12 ", 12)))
def test_workers_accept_ascii_positive_integers(
    monkeypatch: pytest.MonkeyPatch, value: str, expected: int
) -> None:
    monkeypatch.setenv("AGENT_WORKERS", value)

    assert main_module._positive_ascii_integer("AGENT_WORKERS", default=4) == expected


@pytest.mark.parametrize("value", ("0", "-1", "+1", "1.0", "two", "２", "١"))
def test_workers_reject_zero_negative_non_integer_and_non_ascii_values(
    monkeypatch: pytest.MonkeyPatch, value: str
) -> None:
    monkeypatch.setenv("AGENT_WORKERS", value)

    with pytest.raises(ValueError, match="positive ASCII integer"):
        main_module._positive_ascii_integer("AGENT_WORKERS", default=4)


@pytest.mark.parametrize(
    ("value", "expected"),
    (
        (None, "shared"),
        ("", "shared"),
        ("  ", "shared"),
        ("shared", "shared"),
        ("per-authority", "per-authority"),
    ),
)
def test_http_client_layout_is_strict_with_shared_default(
    monkeypatch: pytest.MonkeyPatch, value: str | None, expected: str
) -> None:
    if value is None:
        monkeypatch.delenv("AGENT_HTTP_CLIENT_LAYOUT", raising=False)
    else:
        monkeypatch.setenv("AGENT_HTTP_CLIENT_LAYOUT", value)

    assert main_module._http_client_layout() == expected


@pytest.mark.parametrize("value", ("SHARED", "per_authority", "authority", "1"))
def test_http_client_layout_rejects_other_values(
    monkeypatch: pytest.MonkeyPatch, value: str
) -> None:
    monkeypatch.setenv("AGENT_HTTP_CLIENT_LAYOUT", value)

    with pytest.raises(ValueError, match="must be shared or per-authority"):
        main_module._http_client_layout()


def test_settings_records_requested_http_client_layout(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_HTTP_CLIENT_LAYOUT", "per-authority")

    assert main_module._settings().http_client_layout == "per-authority"


@pytest.mark.parametrize(
    ("value", "expected"),
    (
        (None, False),
        ("", False),
        ("   ", False),
        ("true", True),
        ("TrUe", True),
        ("false", False),
        ("FALSE", False),
    ),
)
def test_metrics_configuration_is_strict_with_disabled_default(
    monkeypatch: pytest.MonkeyPatch, value: str | None, expected: bool
) -> None:
    if value is None:
        monkeypatch.delenv("CITYBUDDY_METRICS_ENABLED", raising=False)
    else:
        monkeypatch.setenv("CITYBUDDY_METRICS_ENABLED", value)

    assert main_module._settings().metrics_enabled is expected
    assert AgentSettings().metrics_enabled is False


def test_metrics_configuration_rejects_other_nonempty_values(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("CITYBUDDY_METRICS_ENABLED", "yes")

    with pytest.raises(
        ValueError,
        match="CITYBUDDY_METRICS_ENABLED must be true or false",
    ):
        main_module._settings()


@pytest.mark.parametrize(("value", "expected_workers"), ((None, 4), ("2", 2)))
def test_main_uses_the_zero_argument_factory_and_resolved_workers(
    monkeypatch: pytest.MonkeyPatch, value: str | None, expected_workers: int
) -> None:
    call: dict[str, Any] = {}

    def run(app: str, **kwargs: Any) -> None:
        call["app"] = app
        call.update(kwargs)

    monkeypatch.setattr("citybuddy_agent.__main__.uvicorn.run", run)
    if value is None:
        monkeypatch.delenv("AGENT_WORKERS", raising=False)
    else:
        monkeypatch.setenv("AGENT_WORKERS", value)
    monkeypatch.setenv("WEB_CONCURRENCY", "99")

    main_module.main()

    assert call == {
        "app": "citybuddy_agent.__main__:create_runtime_app",
        "host": "127.0.0.1",
        "port": 8001,
        "factory": True,
        "workers": expected_workers,
    }
    assert tuple(inspect.signature(main_module.create_runtime_app).parameters) == ()


def test_runtime_settings_do_not_parse_retired_model_configuration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AGENT_MODEL_TEMPERATURE", "not-a-number")
    monkeypatch.setenv("AGENT_MODEL_TIMEOUT_SECONDS", "not-a-number")
    monkeypatch.setenv("AGENT_EVALUATION_SESSION_PROPAGATION_ENABLED", "not-a-boolean")
    resolved = main_module._settings()
    assert "model_proxy_url" not in resolved.model_dump()
    assert "model_temperature" not in resolved.model_dump()
    assert "evaluation_session_propagation_enabled" not in resolved.model_dump()
