"""Bounded operator entry point for the isolated CityBuddy verified-path demo."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import NoReturn

REPOSITORY = Path(__file__).resolve().parents[1]
STATE_ROOT = REPOSITORY / ".citybuddy-demo"
ACTIVE_STATE = STATE_ROOT / "active.json"
RUN_ID = re.compile(r"^[a-z0-9][a-z0-9-]{11,63}$")


class DemoError(RuntimeError):
    """A bounded operator error safe to include in machine-readable output."""


@dataclass(frozen=True)
class ActiveRun:
    run_id: str
    run_directory: Path
    project: str

    @classmethod
    def load(cls) -> ActiveRun:
        try:
            payload = json.loads(ACTIVE_STATE.read_text(encoding="utf-8"))
        except FileNotFoundError as error:
            raise DemoError("no active demo run") from error
        except (OSError, json.JSONDecodeError) as error:
            raise DemoError("active demo state is unreadable") from error
        if not isinstance(payload, dict) or set(payload) != {"project", "runDirectory", "runId"}:
            raise DemoError("active demo state has an invalid schema")
        run_id = payload.get("runId")
        project = payload.get("project")
        run_directory = payload.get("runDirectory")
        if (
            not isinstance(run_id, str)
            or RUN_ID.fullmatch(run_id) is None
            or project != f"citybuddy-demo-{run_id}"
            or run_directory != str(STATE_ROOT / "runs" / run_id)
        ):
            raise DemoError("active demo state failed its destructive-scope guard")
        return cls(run_id, Path(run_directory), project)

    def require_confirmation(self, supplied: str) -> None:
        if supplied != self.run_id:
            raise DemoError("CONFIRM_DEMO_RUN_ID must exactly match the active run id")
        expected = (STATE_ROOT / "runs" / self.run_id).resolve()
        if (
            self.run_directory.resolve() != expected
            or expected.parent != (STATE_ROOT / "runs").resolve()
        ):
            raise DemoError("demo run directory is outside the bounded cleanup root")


def emit(command: str, status: str, **details: object) -> None:
    print(
        json.dumps(
            {"command": command, "schemaVersion": "citybuddy-demo-v1", "status": status, **details},
            separators=(",", ":"),
            sort_keys=True,
        )
    )


def fail(command: str, message: str) -> NoReturn:
    emit(command, "rejected", error=message)
    raise SystemExit(2)


def status() -> None:
    try:
        active = ActiveRun.load()
    except DemoError:
        emit("status", "inactive")
        return
    emit(
        "status",
        "active",
        project=active.project,
        runDirectory=str(active.run_directory),
        runId=active.run_id,
    )


def guarded_command(command: str, confirmation: str) -> None:
    active = ActiveRun.load()
    active.require_confirmation(confirmation)
    emit(command, "guard-passed", project=active.project, runId=active.run_id)


def dispatch(arguments: argparse.Namespace) -> None:
    command = arguments.command
    if command == "status":
        status()
        return
    if command in {"cleanup", "reset"}:
        guarded_command(command, arguments.confirm_run_id)
        return
    raise DemoError(f"{command} implementation is not yet complete on this draft branch")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    for command in ("setup", "demo", "faults", "status", "check", "all"):
        commands.add_parser(command)
    for command in ("cleanup", "reset"):
        destructive = commands.add_parser(command)
        destructive.add_argument("--confirm-run-id", required=True)
    return result


def main() -> None:
    arguments = parser().parse_args()
    try:
        dispatch(arguments)
    except DemoError as error:
        fail(arguments.command, str(error))


if __name__ == "__main__":
    main()
