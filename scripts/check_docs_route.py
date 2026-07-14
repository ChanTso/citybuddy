from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

SLICE_ID = r"CB-\d{3}"
LINKED_SLICE = re.compile(
    rf"^\[(?P<id>{SLICE_ID}) — .+\]\((?P<path>docs/slices/(?P<file_id>{SLICE_ID})\.md)\)$"
)
PLAIN_SLICE = re.compile(rf"^`(?P<id>{SLICE_ID}) — .+`$")
SPEC_TITLE = re.compile(rf"^# (?P<id>{SLICE_ID}) — .+$", re.MULTILINE)
CONTRACT_REFERENCE = re.compile(r"\(\.\./CONTRACTS\.md#(?P<anchor>[a-z0-9-]+)\)")
CONTRACT_ANCHOR = re.compile(r'<a id="(?P<anchor>[a-z0-9-]+)"></a>')
OUTCOME_ROW = re.compile(rf"^\| `(?P<id>{SLICE_ID})` \|", re.MULTILINE)
ALLOWED_STATES = {"PLANNED", "READY", "IN_PROGRESS", "VERIFIED", "BLOCKED", "DEFERRED"}
REQUIRED_SPEC_HEADINGS = (
    "## Referenced contracts",
    "## Goal",
    "## In scope",
    "## Acceptance criteria",
    "## Rejection paths",
    "## Out of scope",
    "## Required evidence",
    "## Completion record",
)


@dataclass(frozen=True)
class RouteRow:
    slice_id: str
    state: str
    path: str | None


def parse_route(implementation: str) -> tuple[list[RouteRow], list[str]]:
    errors: list[str] = []
    try:
        route = implementation.split("## Complete route", 1)[1].split("\n## ", 1)[0]
    except IndexError:
        return [], ["IMPLEMENTATION.md must contain a Complete route section"]

    rows: list[RouteRow] = []
    for line in route.splitlines():
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) != 4 or not re.fullmatch(r"P[012]", cells[1]):
            continue

        linked = LINKED_SLICE.fullmatch(cells[0])
        plain = PLAIN_SLICE.fullmatch(cells[0])
        if linked:
            slice_id = linked.group("id")
            path = linked.group("path")
            if linked.group("file_id") != slice_id:
                errors.append(f"{slice_id} links to a mismatched specification filename")
        elif plain:
            slice_id = plain.group("id")
            path = None
        else:
            errors.append(f"unrecognized route slice cell: {cells[0]}")
            continue

        state_match = re.fullmatch(r"`([A-Z_]+)`", cells[2])
        if state_match is None or state_match.group(1) not in ALLOWED_STATES:
            errors.append(f"{slice_id} has an invalid state: {cells[2]}")
            continue
        rows.append(RouteRow(slice_id, state_match.group(1), path))

    if not rows:
        errors.append("Complete route must contain slice rows")
    return rows, errors


def duplicates(values: list[str]) -> set[str]:
    return {value for value in values if values.count(value) > 1}


def validate_repository(root: Path) -> list[str]:
    errors: list[str] = []
    implementation_path = root / "IMPLEMENTATION.md"
    contracts_path = root / "docs/CONTRACTS.md"
    if not implementation_path.is_file() or not contracts_path.is_file():
        return ["IMPLEMENTATION.md and docs/CONTRACTS.md must exist"]

    rows, route_errors = parse_route(implementation_path.read_text(encoding="utf-8"))
    errors.extend(route_errors)
    if not rows:
        return errors

    route_ids = [row.slice_id for row in rows]
    for duplicate in sorted(duplicates(route_ids)):
        errors.append(f"duplicate route slice id: {duplicate}")

    active_indices = [
        index for index, row in enumerate(rows) if row.state in {"READY", "IN_PROGRESS"}
    ]
    if len(active_indices) > 1:
        errors.append("route must contain at most one READY or IN_PROGRESS slice")
    elif len(active_indices) == 1:
        window = [row for row in rows[active_indices[0] :] if row.state != "DEFERRED"][:3]
        if len(window) < 3:
            errors.append("rolling specification window must contain three non-DEFERRED slices")
        for row in window:
            if row.path is None:
                errors.append(f"rolling specification window slice {row.slice_id} is not linked")
    elif not any(row.state == "BLOCKED" for row in rows):
        errors.append("route without an active slice must contain a BLOCKED slice")

    contracts = contracts_path.read_text(encoding="utf-8")
    anchor_values = CONTRACT_ANCHOR.findall(contracts)
    anchors = set(anchor_values)
    for duplicate in sorted(duplicates(anchor_values)):
        errors.append(f"duplicate contract anchor: {duplicate}")
    try:
        outcome_section = contracts.split('<a id="contracts-route-outcomes"></a>', 1)[1].split(
            "\n<a id=", 1
        )[0]
    except IndexError:
        outcome_section = ""
        errors.append("docs/CONTRACTS.md must contain the route outcome anchor")
    outcome_ids = OUTCOME_ROW.findall(outcome_section)
    for duplicate in sorted(duplicates(outcome_ids)):
        errors.append(f"duplicate route outcome id: {duplicate}")
    if set(outcome_ids) != set(route_ids):
        errors.append("route outcome ids must exactly match Complete route ids")

    linked_ids: set[str] = set()
    for row in rows:
        if row.path is None:
            continue
        linked_ids.add(row.slice_id)
        spec_path = root / row.path
        if not spec_path.is_file():
            errors.append(f"missing specification for {row.slice_id}: {row.path}")
            continue
        spec = spec_path.read_text(encoding="utf-8")
        title = SPEC_TITLE.search(spec)
        if title is None or title.group("id") != row.slice_id:
            errors.append(f"{row.path} title does not match {row.slice_id}")
        for heading in REQUIRED_SPEC_HEADINGS:
            if heading not in spec:
                errors.append(f"{row.path} is missing {heading}")
        if "../CONTRACTS.md" in spec and not CONTRACT_REFERENCE.search(spec):
            errors.append(f"{row.path} must use anchored CONTRACTS.md references")
        for anchor in CONTRACT_REFERENCE.findall(spec):
            if anchor not in anchors:
                errors.append(f"{row.path} references missing contract anchor: {anchor}")

    specs = sorted((root / "docs/slices").glob("CB-*.md"))
    spec_ids = [spec.stem for spec in specs]
    for duplicate in sorted(duplicates(spec_ids)):
        errors.append(f"duplicate slice specification id: {duplicate}")
    for spec_id in spec_ids:
        if spec_id not in linked_ids:
            errors.append(f"unlinked slice specification: docs/slices/{spec_id}.md")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate CityBuddy slice routing documents")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (defaults to the script's parent repository)",
    )
    args = parser.parse_args()
    errors = validate_repository(args.root.resolve())
    if errors:
        for error in errors:
            print(f"docs-route: {error}", file=sys.stderr)
        return 1
    print("docs-route: route, rolling window, specifications, and anchors are valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
