from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[2]
INTEGRATION_SCRIPT = REPOSITORY / "scripts" / "test_evaluation_sandbox_integration.sh"
LEADING_DASH_SESSION = "-leading-dash-session"
TRACE_ID = "00000000-0000-0000-0000-000000000150"
TURN_ID = "00000000-0000-0000-0000-000000000151"
TIMESTAMP = "2026-08-04T00:00:00Z"


def run_helper(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, *arguments],
        cwd=REPOSITORY,
        check=False,
        capture_output=True,
        text=True,
    )


def test_evaluation_helpers_accept_bound_leading_dash_session(tmp_path: Path) -> None:
    evidence_path = tmp_path / "evidence.json"
    evidence_path.write_text(
        json.dumps(
            {
                "schemaVersion": "agent-evidence-v1",
                "traceId": TRACE_ID,
                "sessionId": LEADING_DASH_SESSION,
                "turnId": TURN_ID,
                "terminalOutcome": "completed",
                "events": [
                    {"sequence": 1, "eventKind": "USER_INPUT", "occurredAt": TIMESTAMP},
                    {
                        "sequence": 2,
                        "eventKind": "AGENT_OUTCOME",
                        "outcome": "completed",
                        "occurredAt": TIMESTAMP,
                    },
                    {
                        "sequence": 3,
                        "eventKind": "ASSISTANT_RESPONSE",
                        "outcome": "completed",
                        "occurredAt": TIMESTAMP,
                    },
                    {
                        "sequence": 4,
                        "eventKind": "TURN_COMPLETED",
                        "outcome": "completed",
                        "occurredAt": TIMESTAMP,
                    },
                ],
                "feedback": [],
            }
        ),
        encoding="utf-8",
    )
    evidence = run_helper(
        "scripts/check_agent_evaluation_evidence.py",
        str(evidence_path),
        "--trace",
        TRACE_ID,
        f"--session={LEADING_DASH_SESSION}",
        "--outcome",
        "completed",
    )
    assert evidence.returncode == 0, evidence.stderr

    audit_path = tmp_path / "audit.json"
    audit_path.write_text(
        json.dumps(
            {
                "entries": [
                    {
                        "sequence": 1,
                        "auditReferenceId": "audit-reference",
                        "sandboxId": "sandbox-main",
                        "supportSessionId": LEADING_DASH_SESSION,
                        "traceId": TRACE_ID,
                        "operationId": "operation",
                        "entityType": "PRODUCT_FIXTURE",
                        "entityId": "product",
                        "entityVersion": 1,
                        "outcome": "OBSERVED",
                        "createdAt": TIMESTAMP,
                    }
                ],
                "nextCursor": None,
            }
        ),
        encoding="utf-8",
    )
    audit = run_helper(
        "scripts/check_evaluation_views.py",
        "audit",
        str(audit_path),
        "--sandbox",
        "sandbox-main",
        f"--session={LEADING_DASH_SESSION}",
        "--count",
        "1",
        "--trace",
        TRACE_ID,
    )
    assert audit.returncode == 0, audit.stderr


def test_evaluation_script_binds_every_opaque_session_option() -> None:
    script = INTEGRATION_SCRIPT.read_text(encoding="utf-8")
    logical_commands = script.replace("\\\n", " ").splitlines()
    evidence_calls = [
        command
        for command in logical_commands
        if "python scripts/check_agent_evaluation_evidence.py" in command
    ]
    audit_calls = [
        command
        for command in logical_commands
        if "python scripts/check_evaluation_views.py audit" in command
    ]

    assert len(evidence_calls) == 6
    assert len(audit_calls) == 3
    calls = evidence_calls + audit_calls
    assert all(re.search(r'--session="\$(?:cb122_session|session_id)"', call) for call in calls)
    assert all(not re.search(r'--session\s+"\$', call) for call in calls)
