#!/usr/bin/env python3
"""Strict, independent reconstruction of the CB-152 result bundle."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from collections import Counter
from collections.abc import Iterable
from pathlib import Path
from typing import Any

MAX_RAW_BYTES = 50 * 1024 * 1024
MAX_FILES = 32
MAX_RECORDS = 10_000
MANIFEST_KEYS = {
    "schemaVersion",
    "sliceId",
    "codeRevision",
    "environment",
    "machine",
    "containerResources",
    "fixtureOrDatasetVersion",
    "tool",
    "toolVersion",
    "toolArchiveUrl",
    "toolArchiveSha512",
    "warmup",
    "measuredDuration",
    "concurrencyOrWorkload",
    "sampleCount",
    "commands",
    "artifactInventory",
    "cleanupResult",
    "activityId",
    "productId",
    "activityProjectionVersion",
    "baselineActivityState",
    "baselineAllocatedQuota",
    "baselineProductStock",
    "settleCutoff",
    "observationAt",
    "dispatchSettleCutoff",
    "runOrder",
    "percentileAlgorithm",
    "locatorHashAlgorithm",
}
RESULT_KEYS = {
    "schemaVersion",
    "sliceId",
    "seckillQps",
    "latencyMs",
    "sampleCount",
    "durationSeconds",
    "httpStatusDistribution",
    "outcomeDistribution",
    "errorDistribution",
    "concurrencyCorrectnessValid",
    "queries",
}
SAMPLE_KEYS = {
    "sampleIndex",
    "startTimestampMs",
    "elapsedMs",
    "latencyMs",
    "connectTimeMs",
    "responseCode",
    "jmeterSuccess",
    "classification",
    "state",
    "decisionCode",
    "projectionVersion",
    "durableOrderCreated",
    "replay",
    "reservationLocatorHash",
    "orderLocatorHash",
    "responseBytes",
}
STATES = {"PENDING", "ADMITTED", "REJECTED", "ORDERED", "CANCELLED"}
DECISIONS = {
    "ADMITTED",
    "ACTIVITY_INACTIVE",
    "NOT_OPEN",
    "EXPIRED",
    "STALE_VERSION",
    "EXHAUSTED",
    "DUPLICATE_USER",
    "TRANSACTION_TIMEOUT",
}


class BundleError(ValueError):
    pass


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise BundleError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def strict_json_text(text: str, *, source: str) -> Any:
    try:
        return json.loads(text, object_pairs_hook=_reject_duplicates)
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise BundleError(f"malformed JSON in {source}: {exc}") from exc


def strict_json(path: Path) -> Any:
    return strict_json_text(path.read_text(encoding="utf-8"), source=str(path))


def exact_keys(value: dict[str, Any], expected: set[str], source: str) -> None:
    if not isinstance(value, dict):
        raise BundleError(f"{source} must be an object")
    unknown = set(value) - expected
    missing = expected - set(value)
    if unknown or missing:
        raise BundleError(
            f"{source} schema mismatch: unknown={sorted(unknown)} missing={sorted(missing)}"
        )


def require_int(value: Any, source: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise BundleError(f"{source} must be an integer >= {minimum}")
    return int(value)


def canonical_text(path: Path) -> None:
    data = path.read_bytes()
    if not data:
        raise BundleError(f"empty committed text artifact: {path}")
    if b"\r" in data or not data.endswith(b"\n") or data.endswith(b"\n\n"):
        raise BundleError(f"non-canonical line endings/final newline: {path}")
    for line in data.splitlines():
        if line.endswith((b" ", b"\t")):
            raise BundleError(f"trailing whitespace: {path}")


def verify_checksums(bundle: Path) -> None:
    checksum_path = bundle / "checksums.sha256"
    canonical_text(checksum_path)
    lines = checksum_path.read_text(encoding="utf-8").splitlines()
    paths: list[str] = []
    for line in lines:
        if len(line) < 67 or line[64:66] != "  ":
            raise BundleError("malformed checksums.sha256 line")
        digest, relative = line[:64], line[66:]
        if any(char not in "0123456789abcdef" for char in digest):
            raise BundleError("malformed checksum digest")
        candidate = Path(relative)
        if candidate.is_absolute() or ".." in candidate.parts or relative == "checksums.sha256":
            raise BundleError(f"unsafe checksum path: {relative}")
        path = bundle / candidate
        if not path.is_file() or path.is_symlink():
            raise BundleError(f"checksum payload missing or not a regular file: {relative}")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != digest:
            raise BundleError(f"checksum mismatch: {relative}")
        paths.append(relative)
    if paths != sorted(paths) or len(paths) != len(set(paths)):
        raise BundleError("checksum paths must be unique and sorted")
    actual_payload = sorted(
        str(path.relative_to(bundle))
        for path in bundle.rglob("*")
        if path.is_file() and path.name != "checksums.sha256"
    )
    if paths != actual_payload:
        raise BundleError("checksums.sha256 does not cover the exact bundle payload")


def iter_jsonl(path: Path, *, maximum: int) -> Iterable[dict[str, Any]]:
    with path.open(encoding="utf-8", newline="") as handle:
        for index, line in enumerate(handle, start=1):
            if index > maximum:
                raise BundleError(f"record bound exceeded: {path}")
            if not line.endswith("\n"):
                raise BundleError(f"JSONL record lacks newline: {path}:{index}")
            value = strict_json_text(line, source=f"{path}:{index}")
            if not isinstance(value, dict):
                raise BundleError(f"JSONL record must be object: {path}:{index}")
            yield value


def read_csv(path: Path, *, maximum: int) -> tuple[list[str], list[dict[str, str]]]:
    rows: list[dict[str, str]] = []
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None or len(reader.fieldnames) != len(set(reader.fieldnames)):
            raise BundleError(f"missing or duplicate CSV header: {path}")
        for index, row in enumerate(reader, start=1):
            if index > maximum:
                raise BundleError(f"record bound exceeded: {path}")
            if None in row:
                raise BundleError(f"malformed CSV row: {path}:{index}")
            rows.append(dict(row))
    return list(reader.fieldnames), rows


def single_csv(path: Path, expected_header: list[str], maximum: int) -> dict[str, str]:
    header, rows = read_csv(path, maximum=maximum)
    if header != expected_header or len(rows) != 1:
        raise BundleError(f"{path} must contain one row with the literal header")
    return rows[0]


def as_int(row: dict[str, str], key: str) -> int:
    try:
        value = int(row[key])
    except (KeyError, ValueError) as exc:
        raise BundleError(f"invalid integer {key}") from exc
    return value


def nearest_rank(values: list[int], percentile: int) -> int:
    if not values:
        raise BundleError("percentile input is empty")
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile / 100 * len(ordered)))
    return ordered[rank - 1]


def validate_sample(record: dict[str, Any], position: int) -> None:
    exact_keys(record, SAMPLE_KEYS, f"measured sample {position}")
    require_int(record["sampleIndex"], "sampleIndex", minimum=1)
    require_int(record["startTimestampMs"], "startTimestampMs", minimum=1)
    require_int(record["elapsedMs"], "elapsedMs")
    require_int(record["latencyMs"], "latencyMs")
    require_int(record["connectTimeMs"], "connectTimeMs")
    require_int(record["responseCode"], "responseCode")
    require_int(record["responseBytes"], "responseBytes")
    if not isinstance(record["jmeterSuccess"], bool):
        raise BundleError("jmeterSuccess must be boolean")
    if record["classification"] not in {
        "BUSINESS",
        "TRANSPORT_ERROR",
        "PARSE_ERROR",
        "UNEXPECTED_ERROR",
        "UNKNOWN",
    }:
        raise BundleError("unknown classification")
    state = record["state"]
    decision = record["decisionCode"]
    if state is not None and state not in STATES:
        raise BundleError("unknown state")
    if decision is not None and decision not in DECISIONS:
        raise BundleError("unknown decisionCode")
    projection = record["projectionVersion"]
    if projection is not None and require_int(projection, "projectionVersion", minimum=1) > 4:
        raise BundleError("projectionVersion exceeds 4")
    for key in ("durableOrderCreated", "replay"):
        if record[key] is not None and not isinstance(record[key], bool):
            raise BundleError(f"{key} must be boolean or null")
    for key in ("reservationLocatorHash", "orderLocatorHash"):
        value = record[key]
        if value is not None and (not isinstance(value, str) or len(value) != 64):
            raise BundleError(f"{key} must be a SHA-256 hex digest or null")


def validate_manifest(manifest: dict[str, Any]) -> int:
    exact_keys(manifest, MANIFEST_KEYS, "manifest")
    if manifest["schemaVersion"] != 1 or manifest["sliceId"] != "CB-152":
        raise BundleError("unsupported manifest identity")
    if not isinstance(manifest["codeRevision"], str) or len(manifest["codeRevision"]) != 40:
        raise BundleError("codeRevision must be a full commit SHA")
    sample_count = require_int(manifest["sampleCount"], "sampleCount", minimum=1)
    if sample_count > MAX_RECORDS:
        raise BundleError("sampleCount exceeds checker bound")
    if manifest["percentileAlgorithm"] != "nearest-rank":
        raise BundleError("unsupported percentile algorithm")
    if manifest["locatorHashAlgorithm"] != "per-run-domain-separated-sha256":
        raise BundleError("unsupported locator hashing")
    return sample_count


def validate_inventory(bundle: Path, manifest: dict[str, Any]) -> int:
    inventory = manifest["artifactInventory"]
    exact_keys(
        inventory, {"maxRawBytes", "maxFiles", "maxRecordsPerFile", "files"}, "artifactInventory"
    )
    max_raw = require_int(inventory["maxRawBytes"], "maxRawBytes", minimum=1)
    max_files = require_int(inventory["maxFiles"], "maxFiles", minimum=1)
    max_records = require_int(inventory["maxRecordsPerFile"], "maxRecordsPerFile", minimum=1)
    if max_raw > MAX_RAW_BYTES or max_files > MAX_FILES or max_records > MAX_RECORDS:
        raise BundleError("manifest inventory bound exceeds checker hard bound")
    files = inventory["files"]
    if not isinstance(files, list) or len(files) > max_files:
        raise BundleError("artifact file inventory exceeds bound")
    declared: dict[str, tuple[int, int]] = {}
    for entry in files:
        exact_keys(entry, {"path", "bytes", "records"}, "artifact inventory entry")
        relative = entry["path"]
        if not isinstance(relative, str) or not relative.startswith("raw/"):
            raise BundleError("inventory may contain only relative raw paths")
        path = Path(relative)
        if path.is_absolute() or ".." in path.parts or relative in declared:
            raise BundleError("unsafe or duplicate inventory path")
        declared[relative] = (
            require_int(entry["bytes"], "artifact bytes", minimum=1),
            require_int(entry["records"], "artifact records", minimum=1),
        )
    actual = sorted(
        str(path.relative_to(bundle)) for path in (bundle / "raw").rglob("*") if path.is_file()
    )
    if sorted(declared) != actual:
        raise BundleError("artifact inventory does not equal raw file set")
    total = 0
    for relative, (size, records) in declared.items():
        path = bundle / relative
        actual_size = path.stat().st_size
        if size != actual_size or records > max_records:
            raise BundleError(f"inventory size/count mismatch: {relative}")
        total += actual_size
        canonical_text(path)
    if total > max_raw:
        raise BundleError("raw artifact budget exceeded")
    return max_records


def evaluate_queries(
    bundle: Path, manifest: dict[str, Any], max_records: int, samples: list[dict[str, Any]]
) -> dict[str, Any]:
    raw = bundle / "raw"
    result: dict[str, Any] = {}

    q01 = single_csv(
        raw / "reconciliation/q01.csv",
        [
            "activity_id",
            "product_id",
            "state",
            "allocated_quota",
            "projection_version",
            "stock_quantity",
        ],
        max_records,
    )
    result["Q01"] = {
        "valid": q01
        == {
            "activity_id": manifest["activityId"],
            "product_id": manifest["productId"],
            "state": manifest["baselineActivityState"],
            "allocated_quota": str(manifest["baselineAllocatedQuota"]),
            "projection_version": str(manifest["activityProjectionVersion"]),
            "stock_quantity": str(manifest["baselineProductStock"]),
        }
    }

    q02 = single_csv(
        raw / "reconciliation/q02.csv",
        [
            "total_reservations",
            "distinct_reservations",
            "distinct_user_activity",
            "duplicate_idempotency_groups",
        ],
        max_records,
    )
    result["Q02"] = {
        "valid": as_int(q02, "total_reservations") == as_int(q02, "distinct_reservations")
        and as_int(q02, "duplicate_idempotency_groups") == 0,
        "totalReservations": as_int(q02, "total_reservations"),
        "distinctUserActivity": as_int(q02, "distinct_user_activity"),
    }

    q03 = single_csv(
        raw / "reconciliation/q03.csv",
        [
            "pending_count",
            "admitted_count",
            "rejected_count",
            "ordered_count",
            "cancelled_count",
            "unknown_state",
            "overdue_nonterminal",
        ],
        max_records,
    )
    q03_counts = {key: as_int(q03, key) for key in q03}
    result["Q03"] = {
        "valid": all(
            q03_counts[key] == 0
            for key in ("pending_count", "admitted_count", "unknown_state", "overdue_nonterminal")
        ),
        "states": {
            key.removesuffix("_count").upper(): value
            for key, value in q03_counts.items()
            if key.endswith("_count")
        },
    }

    q04 = single_csv(
        raw / "reconciliation/q04.csv",
        [
            "successful_reservations",
            "orders_for_activity",
            "missing_orders",
            "orphan_orders",
            "duplicate_orders",
            "binding_mismatches",
            "public_binding_mismatches",
        ],
        max_records,
    )
    q04_values = {key: as_int(q04, key) for key in q04}
    controls04 = list(iter_jsonl(raw / "controls/q04.jsonl", maximum=max_records))
    for entry in controls04:
        exact_keys(
            entry,
            {"reservationLocatorHash", "state", "durableOrderCreated", "orderLocatorHash"},
            "q04 control",
        )
        if entry["state"] not in STATES or not isinstance(entry["durableOrderCreated"], bool):
            raise BundleError("malformed q04 control")
        if entry["durableOrderCreated"] != (entry["state"] in {"ORDERED", "CANCELLED"}):
            raise BundleError("q04 public durable-order claim mismatch")
        if entry["durableOrderCreated"] != (entry["orderLocatorHash"] is not None):
            raise BundleError("q04 public order locator mismatch")
    result["Q04"] = {
        "valid": q04_values["successful_reservations"] == q04_values["orders_for_activity"]
        and all(
            q04_values[key] == 0
            for key in (
                "missing_orders",
                "orphan_orders",
                "duplicate_orders",
                "binding_mismatches",
                "public_binding_mismatches",
            )
        )
    }

    q05 = single_csv(
        raw / "reconciliation/q05.csv",
        [
            "bad_create_count",
            "bad_cancel_count",
            "unexpected_cancel_count",
            "bad_quantity_count",
            "unexpected_movement_types",
            "orphan_movements",
            "binding_mismatches",
        ],
        max_records,
    )
    result["Q05"] = {"valid": all(as_int(q05, key) == 0 for key in q05)}

    q06 = single_csv(
        raw / "reconciliation/q06.csv",
        [
            "final_stock",
            "expected_final_stock",
            "net_consumed_quota",
            "active_quantity",
            "final_allocated_quota",
            "baseline_allocated_quota",
        ],
        max_records,
    )
    q06_values = {key: as_int(q06, key) for key in q06}
    result["Q06"] = {
        "valid": q06_values["final_stock"] >= 0
        and q06_values["final_stock"] == q06_values["expected_final_stock"]
        and q06_values["final_allocated_quota"]
        == q06_values["baseline_allocated_quota"]
        == manifest["baselineAllocatedQuota"]
        and q06_values["net_consumed_quota"] == q06_values["active_quantity"]
        and 0 <= q06_values["net_consumed_quota"] <= manifest["baselineAllocatedQuota"]
    }

    q07_controls = list(iter_jsonl(raw / "controls/q07.jsonl", maximum=max_records))
    q07_keys = {
        "caseId",
        "phase",
        "reservationLocatorHash",
        "activityId",
        "quantity",
        "activityProjectionVersion",
        "state",
        "decisionCode",
        "projectionVersion",
        "durableOrderCreated",
        "orderLocatorHash",
        "replay",
    }
    grouped: dict[str, list[dict[str, Any]]] = {}
    for entry in q07_controls:
        exact_keys(entry, q07_keys, "q07 control")
        grouped.setdefault(entry["caseId"], []).append(entry)
    q07_public_valid = bool(grouped)
    for entries in grouped.values():
        phases = {entry["phase"]: entry for entry in entries}
        if set(phases) != {"initial", "replay1", "replay2"}:
            q07_public_valid = False
            continue
        initial, replay1, replay2 = phases["initial"], phases["replay1"], phases["replay2"]
        stable_keys = (
            "reservationLocatorHash",
            "activityId",
            "quantity",
            "activityProjectionVersion",
        )
        q07_public_valid &= all(initial[key] == replay1[key] == replay2[key] for key in stable_keys)
        terminal_keys = (
            "state",
            "decisionCode",
            "projectionVersion",
            "durableOrderCreated",
            "orderLocatorHash",
        )
        q07_public_valid &= all(replay1[key] == replay2[key] for key in terminal_keys)
        q07_public_valid &= replay1["replay"] is True and replay2["replay"] is True
    q07_detail_header = [
        "reservation_locator_hash",
        "activity_id",
        "quantity",
        "activity_projection_version",
        "state",
        "decision_code",
        "projection_version",
        "order_locator_hash",
        "order_count",
        "canonical_order_locator_hash",
        "create_movement_count",
        "cancel_movement_count",
        "movement_linkage_mismatches",
    ]
    _, q07_details = read_csv(raw / "reconciliation/q07-details.csv", maximum=max_records)
    if list(q07_details[0]) != q07_detail_header if q07_details else True:
        raise BundleError("q07 details header mismatch")
    q07_durable_valid = len(q07_details) == len(grouped)
    for row in q07_details:
        order_claimed = bool(row["order_locator_hash"])
        q07_durable_valid &= as_int(row, "order_count") == (1 if order_claimed else 0)
        q07_durable_valid &= row["canonical_order_locator_hash"] == row["order_locator_hash"]
        q07_durable_valid &= as_int(row, "movement_linkage_mismatches") == 0
        q07_durable_valid &= as_int(row, "create_movement_count") == (1 if order_claimed else 0)
        q07_durable_valid &= as_int(row, "cancel_movement_count") == (
            1 if row["state"] == "CANCELLED" else 0
        )
    q07_dup = single_csv(
        raw / "reconciliation/q07-duplicates.csv",
        ["duplicate_reservation_keys", "duplicate_order_keys", "duplicate_ledger_keys"],
        max_records,
    )
    result["Q07"] = {
        "valid": q07_public_valid
        and q07_durable_valid
        and all(as_int(q07_dup, key) == 0 for key in q07_dup)
    }

    q08_controls = list(iter_jsonl(raw / "controls/q08.jsonl", maximum=max_records))
    q08_by_kind: dict[str, dict[str, Any]] = {}
    for entry in q08_controls:
        exact_keys(
            entry,
            {"kind", "status", "category", "message", "reservationLocatorHash"},
            "q08 control",
        )
        q08_by_kind[entry["kind"]] = entry
    q08_digest = strict_json(raw / "reconciliation/q08.json")
    exact_keys(q08_digest, {"beforeDigest", "afterDigest"}, "q08 digest")
    q08_valid = set(q08_by_kind) == {"owner", "unknown", "other-owner"}
    if q08_valid:
        owner = q08_by_kind["owner"]
        unknown = q08_by_kind["unknown"]
        other = q08_by_kind["other-owner"]
        q08_valid = owner["status"] == 200 and unknown["status"] == other["status"] == 404
        q08_valid &= (unknown["category"], unknown["message"]) == (
            other["category"],
            other["message"],
        )
        q08_valid &= q08_digest["beforeDigest"] == q08_digest["afterDigest"]
    result["Q08"] = {"valid": q08_valid}

    q09 = single_csv(
        raw / "reconciliation/q09.csv",
        [
            "overdue_reservation_resolution",
            "overdue_unpaid_orders",
            "overdue_timeout_dispatch",
            "failed_timeout_dispatch",
        ],
        max_records,
    )
    q09_control = strict_json(raw / "controls/q09.json")
    exact_keys(
        q09_control,
        {"unexpectedError", "unknownClassification", "measuredSampleCount", "expectedSampleCount"},
        "q09 control",
    )
    residue = strict_json(raw / "residue.json")
    exact_keys(
        residue,
        {"project", "containers", "networks", "volumes", "childPids", "absentPaths"},
        "residue",
    )
    residue_valid = residue["containers"] == residue["networks"] == residue["volumes"] == 0
    residue_valid &= all(
        entry.get("absent") is True and set(entry) == {"kind", "absent"}
        for entry in residue["childPids"]
    )
    residue_valid &= all(
        entry.get("absent") is True and set(entry) == {"kind", "absent"}
        for entry in residue["absentPaths"]
    )
    q09_valid = all(as_int(q09, key) == 0 for key in q09)
    q09_valid &= q09_control == {
        "unexpectedError": 0,
        "unknownClassification": 0,
        "measuredSampleCount": len(samples),
        "expectedSampleCount": manifest["sampleCount"],
    }
    q09_valid &= residue_valid and manifest["cleanupResult"] == "PASS"
    result["Q09"] = {"valid": q09_valid}
    return result


def reconstruct(
    bundle: Path, *, verify_result: bool = True, verify_integrity: bool = True
) -> dict[str, Any]:
    bundle = bundle.resolve()
    if verify_integrity:
        verify_checksums(bundle)
    for path in bundle.rglob("*"):
        if path.is_symlink():
            raise BundleError(f"symlink forbidden in bundle: {path}")
        if path.is_file() and path.name != "checksums.sha256":
            canonical_text(path)
    manifest = strict_json(bundle / "manifest.json")
    if not isinstance(manifest, dict):
        raise BundleError("manifest must be an object")
    sample_count = validate_manifest(manifest)
    max_records = validate_inventory(bundle, manifest)
    samples = list(iter_jsonl(bundle / "raw/measured.jsonl", maximum=max_records))
    if len(samples) != sample_count:
        raise BundleError("measured sample count does not equal manifest")
    for position, record in enumerate(samples, start=1):
        validate_sample(record, position)
    if sorted(record["sampleIndex"] for record in samples) != list(range(1, sample_count + 1)):
        raise BundleError("sample indexes are not exact and contiguous")

    starts = [record["startTimestampMs"] for record in samples]
    end_ms = max(record["startTimestampMs"] + record["elapsedMs"] for record in samples)
    span_ms = end_ms - min(starts)
    if span_ms <= 0:
        raise BundleError("measured wall-clock span must be positive")
    elapsed = [record["elapsedMs"] for record in samples]
    http = Counter(str(record["responseCode"]) for record in samples)
    outcomes = Counter(
        f"{record['state']}:{record['decisionCode'] or 'NONE'}"
        if record["classification"] == "BUSINESS"
        else record["classification"]
        for record in samples
    )
    errors = {
        "transportError": sum(record["classification"] == "TRANSPORT_ERROR" for record in samples),
        "parseError": sum(record["classification"] == "PARSE_ERROR" for record in samples),
        "unexpectedError": sum(
            record["classification"] == "UNEXPECTED_ERROR" for record in samples
        ),
        "unknownClassification": sum(record["classification"] == "UNKNOWN" for record in samples),
    }
    queries = evaluate_queries(bundle, manifest, max_records, samples)
    valid = all(query["valid"] for query in queries.values()) and all(
        value == 0 for value in errors.values()
    )
    reconstructed: dict[str, Any] = {
        "schemaVersion": 1,
        "sliceId": "CB-152",
        "sampleCount": sample_count,
        "durationSeconds": round(span_ms / 1000, 6),
        "httpStatusDistribution": dict(sorted(http.items())),
        "outcomeDistribution": dict(sorted(outcomes.items())),
        "errorDistribution": errors,
        "concurrencyCorrectnessValid": valid,
        "queries": queries,
    }
    if valid:
        reconstructed["seckillQps"] = round(sample_count / (span_ms / 1000), 6)
        reconstructed["latencyMs"] = {
            "p50": nearest_rank(elapsed, 50),
            "p95": nearest_rank(elapsed, 95),
            "p99": nearest_rank(elapsed, 99),
        }
    if verify_result:
        committed = strict_json(bundle / "result.json")
        allowed = RESULT_KEYS if valid else RESULT_KEYS - {"seckillQps", "latencyMs"}
        exact_keys(committed, allowed, "result")
        if committed != reconstructed:
            raise BundleError("committed result does not equal independent reconstruction")
    return reconstructed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", nargs="?", default="evidence/measurements/CB-152")
    parser.add_argument("--print-result", action="store_true")
    args = parser.parse_args()
    result = reconstruct(Path(args.bundle))
    if args.print_result:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print("CB-152 checksum and reconstruction: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
