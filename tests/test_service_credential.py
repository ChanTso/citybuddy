from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/service_credential.py"
SECRET = "cbsvc_v1_" + "0123456789abcdef" * 4


def run_tool(
    operation: str, client_id: str | None = None, stdin: str = ""
) -> subprocess.CompletedProcess[str]:
    command = [sys.executable, str(SCRIPT), operation]
    if client_id is not None:
        command.append(client_id)
    return subprocess.run(command, input=stdin, capture_output=True, text=True, check=False)


def test_hash_is_client_bound_and_matches_the_java_vector() -> None:
    result = run_tool("hash", "agent-service", SECRET)

    assert result.returncode == 0
    assert result.stdout.strip() == (
        "sha256$v1$9b4ba6b7ddad9e69a3b5604cdee2d3929e8c0751b976e5b072d3f907f8c0bc9a"
    )
    assert run_tool("hash", "commerce-service", SECRET).stdout != result.stdout


def test_generate_produces_a_valid_distinct_256_bit_token() -> None:
    first = run_tool("generate")
    second = run_tool("generate")

    assert first.returncode == second.returncode == 0
    assert first.stdout != second.stdout
    assert run_tool("validate", stdin=first.stdout.strip()).returncode == 0
    assert run_tool("validate", stdin="cbsvc_v1_" + "0" * 63).returncode == 1
