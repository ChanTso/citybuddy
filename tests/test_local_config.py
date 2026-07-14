from __future__ import annotations

import os
import re
import stat
import subprocess
from pathlib import Path

ROOT = Path(__file__).parents[1]
CREDENTIAL_NAMES = (
    "MYSQL_BOOTSTRAP_PASSWORD",
    "MYSQL_AUTH_MIGRATION_PASSWORD",
    "MYSQL_COMMERCE_MIGRATION_PASSWORD",
    "MYSQL_AGENT_MIGRATION_PASSWORD",
    "MYSQL_AUTH_APP_PASSWORD",
    "MYSQL_COMMERCE_APP_PASSWORD",
    "MYSQL_AGENT_APP_PASSWORD",
)


def run_script(script: str, env_file: Path) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["ENV_FILE"] = str(env_file)
    return subprocess.run(
        [str(ROOT / "scripts" / script)],
        cwd=ROOT,
        env=env,
        check=False,
        capture_output=True,
        text=True,
    )


def parse_env(path: Path) -> dict[str, str]:
    return dict(
        line.split("=", maxsplit=1)
        for line in path.read_text().splitlines()
        if line and not line.startswith("#")
    )


def test_init_local_creates_private_distinct_credentials_and_preserves_them(
    tmp_path: Path,
) -> None:
    env_file = tmp_path / ".env"

    first = run_script("init_local.sh", env_file)

    assert first.returncode == 0, first.stderr
    original = env_file.read_bytes()
    values = parse_env(env_file)
    credentials = [values[name] for name in CREDENTIAL_NAMES]
    assert all(re.fullmatch(r"[0-9a-f]{48}", value) for value in credentials)
    assert len(set(credentials)) == len(credentials)
    assert stat.S_IMODE(env_file.stat().st_mode) == 0o600

    second = run_script("init_local.sh", env_file)

    assert second.returncode == 0, second.stderr
    assert "preserving it unchanged" in second.stdout
    assert env_file.read_bytes() == original


def test_require_local_env_rejects_missing_or_malformed_credentials(tmp_path: Path) -> None:
    env_file = tmp_path / ".env"
    missing = run_script("require_local_env.sh", env_file)
    assert missing.returncode != 0
    assert "Missing local configuration" in missing.stderr

    env_file.write_text("MYSQL_BOOTSTRAP_PASSWORD=not-a-credential\n")
    malformed = run_script("require_local_env.sh", env_file)
    assert malformed.returncode != 0
    assert "Invalid or missing MYSQL_BOOTSTRAP_PASSWORD" in malformed.stderr


def test_example_and_compose_contain_no_credential_defaults() -> None:
    example = parse_env(ROOT / ".env.example")
    assert all(example[name] == "" for name in CREDENTIAL_NAMES)

    compose = (ROOT / "compose.yaml").read_text()
    assert "mysql:8.4.10@sha256:" in compose
    for name in CREDENTIAL_NAMES:
        assert f"${{{name}:?" in compose
