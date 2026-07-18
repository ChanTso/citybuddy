"""Validate bounded CB-102 integration responses without printing private payloads."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def load(path: Path) -> dict[str, Any]:
    payload: Any = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("Evaluation response must be an object")
    return payload


def exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise ValueError(f"Unexpected {label} fields")


def check_state(args: argparse.Namespace) -> None:
    payload = load(args.path)
    exact_keys(payload, {"sandbox", "products", "effects"}, "state")
    sandbox = payload["sandbox"]
    products = payload["products"]
    effects = payload["effects"]
    if (
        not isinstance(sandbox, dict)
        or not isinstance(products, list)
        or not isinstance(effects, list)
    ):
        raise ValueError("Invalid state response shape")
    exact_keys(
        sandbox,
        {
            "sandboxId",
            "lifecycleState",
            "authInvalidationState",
            "deathReason",
            "fixtureCount",
            "expiresAt",
            "activatedAt",
            "deadAt",
            "closedAt",
            "version",
        },
        "sandbox state",
    )
    if sandbox["sandboxId"] != args.sandbox or sandbox["lifecycleState"] != args.lifecycle:
        raise ValueError("State response selected the wrong sandbox truth")
    if len(products) != args.product_count or len(products) > 16 or len(effects) > 8:
        raise ValueError("State response exceeded or missed its bounded families")
    for product in products:
        if not isinstance(product, dict):
            raise ValueError("Invalid product state")
        exact_keys(
            product,
            {
                "productId",
                "name",
                "description",
                "priceMinor",
                "currency",
                "stockQuantity",
                "available",
                "publicationVersion",
            },
            "product state",
        )
    for effect in effects:
        if not isinstance(effect, dict):
            raise ValueError("Invalid effect state")
        exact_keys(effect, {"effectType", "outcome", "createdAt"}, "effect state")
    serialized = json.dumps(payload, sort_keys=True)
    for forbidden in (
        "caseCorrelation",
        "resetIdempotencyKey",
        "testUserLabel",
        "opaqueHandle",
        "credential",
        "password",
        "token",
    ):
        if forbidden in serialized:
            raise ValueError("State response leaked private metadata")


def check_audit(args: argparse.Namespace) -> None:
    payload = load(args.path)
    exact_keys(payload, {"entries", "nextCursor"}, "audit page")
    entries = payload["entries"]
    if not isinstance(entries, list) or len(entries) != args.count or len(entries) > 50:
        raise ValueError("Unexpected audit page size")
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("Invalid audit reference")
        exact_keys(
            entry,
            {
                "sequence",
                "auditReferenceId",
                "sandboxId",
                "supportSessionId",
                "traceId",
                "operationId",
                "entityType",
                "entityId",
                "entityVersion",
                "outcome",
                "createdAt",
            },
            "audit reference",
        )
        if (
            entry["sandboxId"] != args.sandbox
            or entry["supportSessionId"] != args.session
            or entry["entityType"] != "PRODUCT_FIXTURE"
            or entry["entityVersion"] != 1
            or entry["outcome"] != "OBSERVED"
        ):
            raise ValueError("Audit reference does not match authoritative association")
    if args.trace is not None and args.trace not in {entry["traceId"] for entry in entries}:
        raise ValueError("Audit page omitted the server-owned trace")
    if args.next_cursor and not isinstance(payload["nextCursor"], int):
        raise ValueError("Audit page omitted its stable cursor")
    if not args.next_cursor and payload["nextCursor"] is not None:
        raise ValueError("Final audit page retained a cursor")


def check_version(args: argparse.Namespace) -> None:
    payload = load(args.path)
    exact_keys(payload, {"buildId", "schemaCompatibility", "capabilities"}, "version")
    if payload != {
        "buildId": args.build,
        "schemaCompatibility": args.schema,
        "capabilities": ["commerce-audit-v1", "commerce-state-v1", "commerce-version-v1"],
    }:
        raise ValueError("Version response is not the fixed configured allowlist")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="kind", required=True)

    state = subparsers.add_parser("state")
    state.add_argument("path", type=Path)
    state.add_argument("--sandbox", required=True)
    state.add_argument("--lifecycle", choices=("ACTIVE", "DEAD"), required=True)
    state.add_argument("--product-count", type=int, required=True)
    state.set_defaults(handler=check_state)

    audit = subparsers.add_parser("audit")
    audit.add_argument("path", type=Path)
    audit.add_argument("--sandbox", required=True)
    audit.add_argument("--session", required=True)
    audit.add_argument("--count", type=int, required=True)
    audit.add_argument("--trace")
    audit.add_argument("--next-cursor", action="store_true")
    audit.set_defaults(handler=check_audit)

    version = subparsers.add_parser("version")
    version.add_argument("path", type=Path)
    version.add_argument("--build", required=True)
    version.add_argument("--schema", required=True)
    version.set_defaults(handler=check_version)

    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
