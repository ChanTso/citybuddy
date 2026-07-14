from __future__ import annotations

import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/check_docs_route.py"
HEADINGS = """
## Referenced contracts

- [Contract](../CONTRACTS.md#contract-test)

## Goal
Goal.
## In scope
Scope.
## Acceptance criteria
Criteria.
## Rejection paths
Rejections.
## Out of scope
Excluded.
## Required evidence
Evidence.
## Completion record
Record.
"""


def write_fixture(
    root: Path,
    *,
    active_state: str = "READY",
    duplicate_active: bool = False,
    duplicate_route: bool = False,
    duplicate_outcome: bool = False,
    duplicate_anchor: bool = False,
    outside_outcome_row: bool = False,
    unlink_next: bool = False,
    missing_anchor: bool = False,
) -> None:
    slices = root / "docs/slices"
    slices.mkdir(parents=True)
    rows = [
        f"| [CB-010 — First](docs/slices/CB-010.md) | P0 | `{active_state}` | `CB-000` |",
        (
            "| [CB-011 — Second](docs/slices/CB-011.md) | P0 | `IN_PROGRESS` | `CB-010` |"
            if duplicate_active
            else "| [CB-011 — Second](docs/slices/CB-011.md) | P0 | `PLANNED` | `CB-010` |"
        ),
        (
            "| `CB-012 — Third` | P0 | `PLANNED` | `CB-011` |"
            if unlink_next
            else (
                "| [CB-011 — Third](docs/slices/CB-011.md) | P0 | `PLANNED` | `CB-011` |"
                if duplicate_route
                else "| [CB-012 — Third](docs/slices/CB-012.md) | P0 | `PLANNED` | `CB-011` |"
            )
        ),
    ]
    (root / "IMPLEMENTATION.md").write_text(
        "## Complete route\n\n| Slice | Priority | State | Depends on |\n"
        "|---|---:|---:|---|\n" + "\n".join(rows) + "\n\n## Change control\n",
        encoding="utf-8",
    )
    outcome_rows = "\n".join(
        f"| `{slice_id}` | Outcome |" for slice_id in ("CB-010", "CB-011", "CB-012")
    )
    if duplicate_outcome:
        outcome_rows += "\n| `CB-012` | Duplicate outcome |"
    anchor = "" if missing_anchor else '<a id="contract-test"></a>\n'
    if duplicate_anchor:
        anchor += '<a id="contract-test"></a>\n'
    outside_row = (
        '\n<a id="other-section"></a>\n\n## Other\n\n| `CB-010` | Not an outcome |\n'
        if outside_outcome_row
        else ""
    )
    (root / "docs/CONTRACTS.md").write_text(
        f'{anchor}## Contract\n\n<a id="contracts-route-outcomes"></a>\n\n'
        f"## Route outcomes\n\n| Slice | Target outcome |\n|---|---|\n{outcome_rows}\n"
        f"{outside_row}",
        encoding="utf-8",
    )
    for slice_id, title in (("CB-010", "First"), ("CB-011", "Second"), ("CB-012", "Third")):
        if unlink_next and slice_id == "CB-012":
            continue
        (slices / f"{slice_id}.md").write_text(
            f"# {slice_id} — {title}\n{HEADINGS}", encoding="utf-8"
        )


def run_check(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root)],
        check=False,
        capture_output=True,
        text=True,
    )


def test_accepts_valid_route_and_window(tmp_path: Path) -> None:
    write_fixture(tmp_path)

    result = run_check(tmp_path)

    assert result.returncode == 0, result.stderr


def test_rejects_multiple_active_slices(tmp_path: Path) -> None:
    write_fixture(tmp_path, duplicate_active=True)

    result = run_check(tmp_path)

    assert result.returncode == 1
    assert "at most one READY or IN_PROGRESS" in result.stderr


def test_rejects_unlinked_window_slice(tmp_path: Path) -> None:
    write_fixture(tmp_path, unlink_next=True)

    result = run_check(tmp_path)

    assert result.returncode == 1
    assert "rolling specification window slice CB-012 is not linked" in result.stderr


def test_rejects_missing_contract_anchor(tmp_path: Path) -> None:
    write_fixture(tmp_path, missing_anchor=True)

    result = run_check(tmp_path)

    assert result.returncode == 1
    assert "references missing contract anchor: contract-test" in result.stderr


def test_accepts_blocked_route_without_active_slice(tmp_path: Path) -> None:
    write_fixture(tmp_path, active_state="BLOCKED")

    result = run_check(tmp_path)

    assert result.returncode == 0, result.stderr


def test_rejects_route_without_active_or_blocked_slice(tmp_path: Path) -> None:
    write_fixture(tmp_path, active_state="PLANNED")

    result = run_check(tmp_path)

    assert result.returncode == 1
    assert "without an active slice must contain a BLOCKED slice" in result.stderr


def test_rejects_duplicate_route_and_outcome_ids(tmp_path: Path) -> None:
    write_fixture(tmp_path, duplicate_route=True, duplicate_outcome=True)

    result = run_check(tmp_path)

    assert result.returncode == 1
    assert "duplicate route slice id: CB-011" in result.stderr
    assert "duplicate route outcome id: CB-012" in result.stderr


def test_ignores_cb_rows_outside_route_outcome_section(tmp_path: Path) -> None:
    write_fixture(tmp_path, outside_outcome_row=True)

    result = run_check(tmp_path)

    assert result.returncode == 0, result.stderr


def test_rejects_duplicate_contract_anchor(tmp_path: Path) -> None:
    write_fixture(tmp_path, duplicate_anchor=True)

    result = run_check(tmp_path)

    assert result.returncode == 1
    assert "duplicate contract anchor: contract-test" in result.stderr
