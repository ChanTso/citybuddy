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


def test_grant_job_uses_only_fixed_manifest_and_isolated_bootstrap_config() -> None:
    script = (ROOT / "scripts" / "apply_mysql_grants.sh").read_text()
    manifest = (ROOT / "infra" / "mysql" / "grants" / "V001__migration_access.sql").read_text()
    compose = (ROOT / "compose.yaml").read_text()

    statements = [line for line in manifest.splitlines() if line]
    assert statements
    assert all(line.startswith(("GRANT ", "REVOKE ")) for line in statements)
    assert "V001__migration_access.sql" in script
    assert "Grant job rejects caller-supplied SQL" in script
    assert "--activate-all-roles-on-login=OFF" in compose

    migration_anchor = re.search(
        r"(?ms)^x-migration-service:\s*&migration-service\n(.*?)(?=^\S|\Z)",
        compose,
    )
    assert migration_anchor is not None
    migration_config_blocks = [migration_anchor.group(1)]

    for service in ("auth-migrate", "commerce-migrate", "agent-migrate"):
        match = re.search(
            rf"(?ms)^  {re.escape(service)}:\n(.*?)(?=^  [a-z][^:\n]*:\n|\Z)",
            compose,
        )
        assert match is not None
        migration_config_blocks.append(match.group(1))

    for config_block in migration_config_blocks:
        assert "MYSQL_BOOTSTRAP_PASSWORD" not in config_block
        assert "bootstrap_grant_role" not in config_block
