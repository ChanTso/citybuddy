#!/usr/bin/env python3
"""Strict, independent reconstruction of the CB-152 result bundle."""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import math
import os
import re
import stat
from collections import Counter
from collections.abc import Iterable
from pathlib import Path
from typing import Any

MAX_RAW_BYTES = 50 * 1024 * 1024
MAX_FILES = 32
MAX_RECORDS = 10_000
MAX_BUNDLE_FILES = MAX_FILES + 4
MANIFEST_MAX_BYTES = 64 * 1024
RESULT_MAX_BYTES = 256 * 1024
CHECKSUMS_MAX_BYTES = 64 * 1024
RECONSTRUCT_MAX_BYTES = 512 * 1024
RAW_JSON_MAX_BYTES = 1024 * 1024
TEXT_CHUNK_BYTES = 64 * 1024
LOWER_HEX_40 = re.compile(r"[0-9a-f]{40}")
LOWER_HEX_64 = re.compile(r"[0-9a-f]{64}")
RUN_ORDER = [
    "Q01 baseline",
    "isolated warm-up",
    "warm-up closure",
    "Q07 initial control",
    "Q08 initial control",
    "control durable truth",
    "measured JMeter burst",
    "immediate no-cancellation invariant",
    "bounded real cancellation settlement",
    "Q07 replays",
    "Q08 owner/unknown/other-owner polls",
    "Q02-Q09 reconciliation",
]
CHILD_KINDS = {"auth", "commerce"}
ABSENT_PATH_KINDS = {
    "generatedEnvFile",
    "dockerClientConfig",
    "jmeterArchive",
    "jmeterChecksum",
    "jmeterInstall",
    "rsaPrivateKey",
    "rsaPublicKey",
    "warmupTokenFile",
    "warmupJtl",
    "measuredTokenFile",
    "measuredJtl",
    "temporaryDirectory",
}
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
    "unpaidTimeoutSeconds",
    "settlementTimeoutSeconds",
    "jmeterConnectTimeoutMs",
    "jmeterResponseTimeoutMs",
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
    "activityId",
    "quantity",
    "activityProjectionVersion",
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


def read_bounded(path: Path, maximum: int, *, label: str) -> bytes:
    size = path.stat().st_size
    if size > maximum:
        raise BundleError(f"{label} exceeds hard byte cap")
    with path.open("rb") as handle:
        data = handle.read(maximum + 1)
    if len(data) > maximum:
        raise BundleError(f"{label} exceeds hard byte cap")
    return data


def strict_json(path: Path, *, maximum: int = RAW_JSON_MAX_BYTES) -> Any:
    data = read_bounded(path, maximum, label=str(path))
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BundleError(f"malformed JSON in {path}: {exc}") from exc
    return strict_json_text(text, source=str(path))


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


def require_text(value: Any, source: str, *, maximum: int = 1024) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise BundleError(f"{source} must be a bounded nonblank string")
    return value


def require_utc(value: Any, source: str) -> dt.datetime:
    require_text(value, source, maximum=40)
    if not value.endswith("Z"):
        raise BundleError(f"{source} must be a UTC timestamp")
    try:
        parsed = dt.datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise BundleError(f"{source} must be a UTC timestamp") from exc
    if parsed.tzinfo != dt.UTC:
        raise BundleError(f"{source} must be a UTC timestamp")
    return parsed


def require_digest(value: Any, source: str, *, length: int = 64) -> str:
    pattern = LOWER_HEX_64 if length == 64 else LOWER_HEX_40
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise BundleError(f"{source} must be {length} lowercase hexadecimal characters")
    return value


def canonical_text(path: Path, *, maximum: int = MAX_RAW_BYTES) -> None:
    size = path.stat().st_size
    if size == 0:
        raise BundleError(f"empty committed text artifact: {path}")
    if size > maximum:
        raise BundleError(f"{path} exceeds hard byte cap")
    previous = b""
    with path.open("rb") as handle:
        while chunk := handle.read(TEXT_CHUNK_BYTES):
            if b"\r" in chunk:
                raise BundleError(f"non-canonical line endings/final newline: {path}")
            data = previous + chunk
            lines = data.split(b"\n")
            previous = lines.pop()
            for line in lines:
                if line.endswith((b" ", b"\t")):
                    raise BundleError(f"trailing whitespace: {path}")
    if previous or size < 1:
        raise BundleError(f"non-canonical line endings/final newline: {path}")
    with path.open("rb") as handle:
        handle.seek(max(0, size - 2))
        tail = handle.read()
    if not tail.endswith(b"\n") or tail.endswith(b"\n\n"):
        raise BundleError(f"non-canonical line endings/final newline: {path}")


def bounded_walk(bundle: Path) -> dict[str, Path]:
    if not bundle.is_dir() or bundle.is_symlink():
        raise BundleError("bundle root must be a real directory")
    files: dict[str, Path] = {}
    stack = [bundle]
    while stack:
        directory = stack.pop()
        with os.scandir(directory) as entries:
            for entry in entries:
                path = Path(entry.path)
                mode = entry.stat(follow_symlinks=False).st_mode
                relative = str(path.relative_to(bundle))
                if stat.S_ISLNK(mode):
                    raise BundleError(f"symlink forbidden in bundle: {relative}")
                if stat.S_ISDIR(mode):
                    stack.append(path)
                elif stat.S_ISREG(mode):
                    files[relative] = path
                    if len(files) > MAX_BUNDLE_FILES:
                        raise BundleError("bundle file count exceeds hard cap")
                else:
                    raise BundleError(f"non-regular bundle entry: {relative}")
    return files


def hash_stream(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(TEXT_CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def verify_checksums(bundle: Path, files: dict[str, Path]) -> None:
    checksum_path = bundle / "checksums.sha256"
    canonical_text(checksum_path, maximum=CHECKSUMS_MAX_BYTES)
    try:
        lines = (
            read_bounded(checksum_path, CHECKSUMS_MAX_BYTES, label="checksums.sha256")
            .decode("utf-8")
            .splitlines()
        )
    except UnicodeDecodeError as exc:
        raise BundleError("checksums.sha256 must be UTF-8") from exc
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
        actual = hash_stream(path)
        if actual != digest:
            raise BundleError(f"checksum mismatch: {relative}")
        paths.append(relative)
    if paths != sorted(paths) or len(paths) != len(set(paths)):
        raise BundleError("checksum paths must be unique and sorted")
    actual_payload = sorted(relative for relative in files if relative != "checksums.sha256")
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


def validate_business_fields(
    record: dict[str, Any],
    *,
    manifest: dict[str, Any],
    operation: str,
    source: str,
) -> None:
    if record["activityId"] != manifest["activityId"]:
        raise BundleError(f"{source} activityId mismatch")
    if record["quantity"] != manifest["concurrencyOrWorkload"][
        "quantityPerSubmission"
    ] or isinstance(record["quantity"], bool):
        raise BundleError(f"{source} quantity mismatch")
    if record["activityProjectionVersion"] != manifest["activityProjectionVersion"] or isinstance(
        record["activityProjectionVersion"], bool
    ):
        raise BundleError(f"{source} activityProjectionVersion mismatch")
    state = record["state"]
    decision = record["decisionCode"]
    projection = record["projectionVersion"]
    durable = record["durableOrderCreated"]
    replay = record["replay"]
    order_hash = record["orderLocatorHash"]
    if state not in STATES or decision not in DECISIONS | {None}:
        raise BundleError(f"{source} unknown state or decisionCode")
    if not isinstance(projection, int) or isinstance(projection, bool):
        raise BundleError(f"{source} projectionVersion must be an integer")
    expected_projection = {
        "PENDING": 1,
        "ADMITTED": 2,
        "REJECTED": 2,
        "ORDERED": 3,
        "CANCELLED": 4,
    }[state]
    if projection != expected_projection:
        raise BundleError("public state/projectionVersion mismatch")
    if state == "PENDING" and decision is not None:
        raise BundleError(f"{source} state/decisionCode mismatch")
    if state == "REJECTED" and (decision is None or decision == "ADMITTED"):
        raise BundleError(f"{source} state/decisionCode mismatch")
    if state in {"ADMITTED", "ORDERED", "CANCELLED"} and decision != "ADMITTED":
        raise BundleError(f"{source} state/decisionCode mismatch")
    if not isinstance(durable, bool) or not isinstance(replay, bool):
        raise BundleError(f"{source} durableOrderCreated/replay must be boolean")
    if durable != (state in {"ORDERED", "CANCELLED"}) or durable != (order_hash is not None):
        raise BundleError(f"{source} state/durable-order mismatch")
    status = require_int(record["responseCode"], f"{source} responseCode")
    allowed = {
        "submit": {
            "PENDING": {202} if replay is False else set(),
            "ADMITTED": {200} if replay else {201},
            "REJECTED": {409},
            "ORDERED": {200} if replay else set(),
            "CANCELLED": {200} if replay else set(),
        },
        "poll": {state_name: {200} for state_name in STATES},
    }
    if operation not in allowed or status not in allowed[operation][state]:
        raise BundleError(f"{source} status/state/replay mismatch")


def validate_sample(record: dict[str, Any], position: int, manifest: dict[str, Any]) -> None:
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
    classification = record["classification"]
    if classification not in {
        "BUSINESS",
        "TRANSPORT_ERROR",
        "PARSE_ERROR",
        "UNEXPECTED_ERROR",
        "UNKNOWN",
    }:
        raise BundleError("unknown classification")
    for key in ("reservationLocatorHash", "orderLocatorHash"):
        value = record[key]
        if value is not None and (
            not isinstance(value, str) or LOWER_HEX_64.fullmatch(value) is None
        ):
            raise BundleError(f"{key} must be 64 lowercase hexadecimal characters or null")
    business_keys = (
        "activityId",
        "quantity",
        "activityProjectionVersion",
        "state",
        "decisionCode",
        "projectionVersion",
        "durableOrderCreated",
        "replay",
        "reservationLocatorHash",
        "orderLocatorHash",
    )
    if classification == "BUSINESS":
        if record["reservationLocatorHash"] is None:
            raise BundleError("measured sample business locator is missing")
        validate_business_fields(
            record, manifest=manifest, operation="submit", source="measured sample"
        )
        return
    if any(record[key] is not None for key in business_keys):
        raise BundleError("non-business classification carries business fields")
    response_code = record["responseCode"]
    expected = (
        "TRANSPORT_ERROR"
        if response_code <= 0
        else "PARSE_ERROR"
        if response_code in {200, 201, 202, 409}
        else "UNEXPECTED_ERROR"
    )
    if classification != expected:
        raise BundleError("measured sample public classification mismatch")


def validate_manifest(manifest: dict[str, Any]) -> int:
    exact_keys(manifest, MANIFEST_KEYS, "manifest")
    if manifest["schemaVersion"] != 1 or manifest["sliceId"] != "CB-152":
        raise BundleError("unsupported manifest identity")
    require_digest(manifest["codeRevision"], "codeRevision", length=40)
    require_text(manifest["environment"], "environment", maximum=512)
    exact_keys(
        manifest["machine"],
        {"os", "architecture", "logicalCpuCount", "memoryBytes", "dockerServerVersion"},
        "machine",
    )
    for key in ("os", "architecture", "dockerServerVersion"):
        require_text(manifest["machine"][key], f"machine.{key}", maximum=128)
    require_int(manifest["machine"]["logicalCpuCount"], "machine.logicalCpuCount", minimum=1)
    require_int(manifest["machine"]["memoryBytes"], "machine.memoryBytes", minimum=1)
    exact_keys(
        manifest["containerResources"],
        {"limits", "applicationProcesses"},
        "containerResources",
    )
    require_text(manifest["containerResources"]["limits"], "containerResources.limits")
    require_text(
        manifest["containerResources"]["applicationProcesses"],
        "containerResources.applicationProcesses",
    )
    exact_keys(manifest["warmup"], {"samples", "threads", "rampSeconds", "isolation"}, "warmup")
    for key in ("samples", "threads", "rampSeconds"):
        require_int(manifest["warmup"][key], f"warmup.{key}", minimum=1)
    if manifest["warmup"]["isolation"] != "separate activity and product":
        raise BundleError("warmup isolation is unsupported")
    exact_keys(manifest["measuredDuration"], {"model", "wallClockSeconds"}, "measuredDuration")
    if manifest["measuredDuration"]["model"] != "fixed sample count":
        raise BundleError("measuredDuration model is unsupported")
    duration = manifest["measuredDuration"]["wallClockSeconds"]
    if isinstance(duration, bool) or not isinstance(duration, int | float) or duration <= 0:
        raise BundleError("measuredDuration.wallClockSeconds must be positive")
    exact_keys(
        manifest["concurrencyOrWorkload"],
        {"threads", "loopsPerThread", "rampSeconds", "quantityPerSubmission"},
        "concurrencyOrWorkload",
    )
    for key in ("threads", "loopsPerThread", "rampSeconds", "quantityPerSubmission"):
        require_int(
            manifest["concurrencyOrWorkload"][key],
            f"concurrencyOrWorkload.{key}",
            minimum=1,
        )
    if manifest["concurrencyOrWorkload"]["quantityPerSubmission"] != 1:
        raise BundleError("unsupported workload quantity")
    for key in (
        "unpaidTimeoutSeconds",
        "settlementTimeoutSeconds",
        "jmeterConnectTimeoutMs",
        "jmeterResponseTimeoutMs",
    ):
        require_int(manifest[key], key, minimum=1)
    commands = manifest["commands"]
    if not isinstance(commands, list) or not 1 <= len(commands) <= 16:
        raise BundleError("commands must be a bounded nonempty list")
    for index, command in enumerate(commands):
        require_text(command, f"commands[{index}]", maximum=512)
    required_command_fragments = (
        f"-JconnectTimeoutMs={manifest['jmeterConnectTimeoutMs']}",
        f"-JresponseTimeoutMs={manifest['jmeterResponseTimeoutMs']}",
        f"unpaid timeout {manifest['unpaidTimeoutSeconds']}s",
        f"settlement timeout {manifest['settlementTimeoutSeconds']}s",
    )
    joined_commands = "\n".join(commands)
    if any(fragment not in joined_commands for fragment in required_command_fragments):
        raise BundleError("manifest timeout profile does not match recorded commands")
    if manifest["runOrder"] != RUN_ORDER:
        raise BundleError("runOrder must equal the frozen ordered lifecycle")
    for key in ("activityId", "productId", "fixtureOrDatasetVersion", "tool", "toolVersion"):
        require_text(manifest[key], key, maximum=256)
    require_text(manifest["toolArchiveUrl"], "toolArchiveUrl", maximum=512)
    if manifest["tool"] != "Apache JMeter" or manifest["toolVersion"] != "5.6.3":
        raise BundleError("unsupported load tool identity")
    if not manifest["toolArchiveUrl"].startswith("https://downloads.apache.org/jmeter/"):
        raise BundleError("toolArchiveUrl must be the official Apache download")
    if (
        not isinstance(manifest["toolArchiveSha512"], str)
        or re.fullmatch(r"[0-9a-f]{128}", manifest["toolArchiveSha512"]) is None
    ):
        raise BundleError("toolArchiveSha512 must be 128 lowercase hexadecimal characters")
    settle = require_utc(manifest["settleCutoff"], "settleCutoff")
    observation = require_utc(manifest["observationAt"], "observationAt")
    dispatch = require_utc(manifest["dispatchSettleCutoff"], "dispatchSettleCutoff")
    if observation < settle or dispatch != settle:
        raise BundleError("manifest settlement timestamps are inconsistent")
    if manifest["cleanupResult"] != "PASS":
        raise BundleError("cleanupResult must be PASS")
    require_int(manifest["activityProjectionVersion"], "activityProjectionVersion", minimum=1)
    require_int(manifest["baselineAllocatedQuota"], "baselineAllocatedQuota", minimum=1)
    require_int(manifest["baselineProductStock"], "baselineProductStock", minimum=1)
    if manifest["baselineActivityState"] != "ACTIVE":
        raise BundleError("baselineActivityState must be ACTIVE")
    sample_count = require_int(manifest["sampleCount"], "sampleCount", minimum=1)
    if sample_count > MAX_RECORDS:
        raise BundleError("sampleCount exceeds checker bound")
    if manifest["percentileAlgorithm"] != "nearest-rank":
        raise BundleError("unsupported percentile algorithm")
    if manifest["locatorHashAlgorithm"] != "per-run-domain-separated-sha256":
        raise BundleError("unsupported locator hashing")
    return sample_count


def validate_inventory(
    bundle: Path, manifest: dict[str, Any], files: dict[str, Path]
) -> tuple[int, dict[str, tuple[int, int]]]:
    inventory = manifest["artifactInventory"]
    exact_keys(
        inventory, {"maxRawBytes", "maxFiles", "maxRecordsPerFile", "files"}, "artifactInventory"
    )
    max_raw = require_int(inventory["maxRawBytes"], "maxRawBytes", minimum=1)
    max_files = require_int(inventory["maxFiles"], "maxFiles", minimum=1)
    max_records = require_int(inventory["maxRecordsPerFile"], "maxRecordsPerFile", minimum=1)
    if max_raw > MAX_RAW_BYTES or max_files > MAX_FILES or max_records > MAX_RECORDS:
        raise BundleError("manifest inventory bound exceeds checker hard bound")
    entries = inventory["files"]
    if not isinstance(entries, list) or len(entries) > max_files:
        raise BundleError("artifact file inventory exceeds bound")
    declared: dict[str, tuple[int, int]] = {}
    for entry in entries:
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
    actual = sorted(relative for relative in files if relative.startswith("raw/"))
    if sorted(declared) != actual:
        raise BundleError("artifact inventory does not equal raw file set")
    total = 0
    for relative, (size, records) in declared.items():
        path = bundle / relative
        actual_size = path.stat().st_size
        if size != actual_size:
            raise BundleError(f"inventory byte size mismatch: {relative}")
        if actual_size > MAX_RAW_BYTES:
            raise BundleError(f"{relative} exceeds hard byte cap")
        if records > max_records:
            raise BundleError(f"inventory record bound exceeded: {relative}")
        total += actual_size
    if total > max_raw:
        raise BundleError("raw artifact budget exceeded")
    return max_records, declared


def count_records(path: Path, relative: str, maximum: int) -> int:
    if relative.endswith(".jsonl"):
        return sum(1 for _ in iter_jsonl(path, maximum=maximum))
    if relative.endswith(".csv"):
        _, rows = read_csv(path, maximum=maximum)
        return len(rows)
    if relative.endswith(".json"):
        strict_json(path)
        return 1
    raise BundleError(f"unsupported inventory record format: {relative}")


def verify_record_counts(bundle: Path, declared: dict[str, tuple[int, int]], maximum: int) -> None:
    for relative, (_, expected) in declared.items():
        actual = count_records(bundle / relative, relative, maximum)
        if actual != expected:
            raise BundleError(f"inventory record count mismatch: {relative}")


def validate_payload_sizes(
    files: dict[str, Path], declared: dict[str, tuple[int, int]], *, verify_integrity: bool
) -> None:
    required = {"manifest.json", "reconstruct.py", *declared}
    optional: set[str] = set()
    if verify_integrity:
        required |= {"result.json", "checksums.sha256"}
    else:
        optional = {"result.json", "checksums.sha256"}
    if not required.issubset(files) or set(files) - required - optional:
        raise BundleError("bundle payload does not equal manifest and fixed interface")
    hard_caps = {
        "manifest.json": MANIFEST_MAX_BYTES,
        "result.json": RESULT_MAX_BYTES,
        "checksums.sha256": CHECKSUMS_MAX_BYTES,
        "reconstruct.py": RECONSTRUCT_MAX_BYTES,
    }
    for relative, path in files.items():
        cap = hard_caps.get(relative, MAX_RAW_BYTES)
        if path.stat().st_size > cap:
            raise BundleError(f"{relative} exceeds hard byte cap")


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
    q04_hashes: list[str] = []
    q04_states: Counter[str] = Counter()
    for entry in controls04:
        exact_keys(
            entry,
            {"reservationLocatorHash", "state", "durableOrderCreated", "orderLocatorHash"},
            "q04 control",
        )
        if entry["state"] not in STATES or not isinstance(entry["durableOrderCreated"], bool):
            raise BundleError("malformed q04 control")
        require_digest(entry["reservationLocatorHash"], "q04 reservationLocatorHash")
        if entry["orderLocatorHash"] is not None:
            require_digest(entry["orderLocatorHash"], "q04 orderLocatorHash")
        if entry["durableOrderCreated"] != (entry["state"] in {"ORDERED", "CANCELLED"}):
            raise BundleError("q04 public durable-order claim mismatch")
        if entry["durableOrderCreated"] != (entry["orderLocatorHash"] is not None):
            raise BundleError("q04 public order locator mismatch")
        q04_hashes.append(entry["reservationLocatorHash"])
        q04_states[entry["state"]] += 1
    if len(controls04) != result["Q02"]["totalReservations"]:
        raise BundleError("q04 public control coverage mismatch")
    if len(set(q04_hashes)) != result["Q02"]["totalReservations"]:
        raise BundleError("q04 duplicate reservation locator hash")
    if {state: q04_states[state] for state in STATES} != {
        state: result["Q03"]["states"].get(state, 0) for state in STATES
    }:
        raise BundleError("q04 public state distribution mismatch")
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
        "observedAt",
        "responseCode",
        "classification",
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
        require_text(entry["caseId"], "q07 caseId", maximum=128)
        grouped.setdefault(entry["caseId"], []).append(entry)
    q07_public_valid = bool(grouped)
    settle_cutoff = require_utc(manifest["settleCutoff"], "settleCutoff")
    public_by_hash: dict[str, dict[str, Any]] = {}
    for entries in grouped.values():
        if len({entry["phase"] for entry in entries}) != len(entries):
            raise BundleError("q07 duplicate phase")
        phases = {entry["phase"]: entry for entry in entries}
        if set(phases) != {"initial", "replay1", "replay2"}:
            raise BundleError("q07 phase coverage mismatch")
        initial, replay1, replay2 = phases["initial"], phases["replay1"], phases["replay2"]
        for phase, entry in phases.items():
            require_utc(entry["observedAt"], f"q07 {phase} observedAt")
            validate_business_fields(
                entry, manifest=manifest, operation="submit", source=f"q07 {phase}"
            )
            if entry["classification"] != "BUSINESS":
                raise BundleError("q07 control classification mismatch")
            require_digest(entry["reservationLocatorHash"], "q07 reservationLocatorHash")
            if entry["orderLocatorHash"] is not None:
                require_digest(entry["orderLocatorHash"], "q07 orderLocatorHash")
        if (
            require_utc(replay1["observedAt"], "q07 replay1 observedAt") < settle_cutoff
            or require_utc(replay2["observedAt"], "q07 replay2 observedAt") < settle_cutoff
        ):
            raise BundleError("q07 replay observedAt precedes settleCutoff")
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
        locator = initial["reservationLocatorHash"]
        if locator in public_by_hash:
            raise BundleError("q07 duplicate public reservation identity")
        public_by_hash[locator] = replay2
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
    durable_by_hash: dict[str, dict[str, str]] = {}
    for row in q07_details:
        locator = require_digest(row["reservation_locator_hash"], "q07 detail reservation hash")
        if locator in durable_by_hash:
            raise BundleError("q07 duplicate durable reservation identity")
        durable_by_hash[locator] = row
        if row["order_locator_hash"]:
            require_digest(row["order_locator_hash"], "q07 detail order hash")
        if row["canonical_order_locator_hash"]:
            require_digest(row["canonical_order_locator_hash"], "q07 detail canonical order hash")
    if set(durable_by_hash) != set(public_by_hash):
        raise BundleError("q07 public/durable reservation identity mismatch")
    q07_durable_valid = True
    for locator, row in durable_by_hash.items():
        public = public_by_hash[locator]
        order_claimed = bool(row["order_locator_hash"])
        if row["activity_id"] != public["activityId"]:
            raise BundleError("q07 durable activityId mismatch")
        if as_int(row, "quantity") != public["quantity"]:
            raise BundleError("q07 durable quantity mismatch")
        if as_int(row, "activity_projection_version") != public["activityProjectionVersion"]:
            raise BundleError("q07 durable activityProjectionVersion mismatch")
        if row["state"] != public["state"] or row["decision_code"] != public["decisionCode"]:
            raise BundleError("q07 durable terminal state/decision mismatch")
        if as_int(row, "projection_version") != public["projectionVersion"]:
            raise BundleError("q07 durable projectionVersion mismatch")
        durable_order_hash = row["order_locator_hash"] or None
        if (
            durable_order_hash != public["orderLocatorHash"]
            or order_claimed != public["durableOrderCreated"]
        ):
            raise BundleError("q07 durable order binding mismatch")
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
    require_digest(residue["project"], "residue project digest")
    residue_counts = {
        key: require_int(residue[key], f"residue.{key}")
        for key in ("containers", "networks", "volumes")
    }
    residue_valid = all(value == 0 for value in residue_counts.values())
    if not isinstance(residue["childPids"], list) or not residue["childPids"]:
        raise BundleError("q09 childPids inventory must be nonempty")
    child_kinds: set[str] = set()
    for entry in residue["childPids"]:
        exact_keys(entry, {"kind", "absent"}, "q09 child pid")
        require_text(entry["kind"], "q09 child kind", maximum=64)
        if entry["kind"] in child_kinds or entry["absent"] is not True:
            raise BundleError("q09 childPids inventory is not unique and absent")
        child_kinds.add(entry["kind"])
    if child_kinds != CHILD_KINDS:
        raise BundleError("q09 childPids inventory does not cover owned application children")
    if not isinstance(residue["absentPaths"], list) or not residue["absentPaths"]:
        raise BundleError("q09 absentPaths inventory must be nonempty")
    path_kinds: set[str] = set()
    for entry in residue["absentPaths"]:
        exact_keys(entry, {"kind", "absent"}, "q09 absent path")
        require_text(entry["kind"], "q09 absent path kind", maximum=64)
        if entry["kind"] in path_kinds or entry["absent"] is not True:
            raise BundleError("q09 absentPaths inventory is not unique and absent")
        path_kinds.add(entry["kind"])
    if path_kinds != ABSENT_PATH_KINDS:
        raise BundleError("q09 absentPaths inventory does not cover declared temporary resources")
    q09_valid = all(as_int(q09, key) == 0 for key in q09)
    for key in (
        "unexpectedError",
        "unknownClassification",
        "measuredSampleCount",
        "expectedSampleCount",
    ):
        require_int(q09_control[key], f"q09 control {key}")
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
    bundle = bundle.absolute()
    files = bounded_walk(bundle)
    manifest_path = files.get("manifest.json")
    if manifest_path is None:
        raise BundleError("manifest.json is missing")
    if manifest_path.stat().st_size > MANIFEST_MAX_BYTES:
        raise BundleError("manifest.json exceeds hard byte cap")
    manifest = strict_json(manifest_path, maximum=MANIFEST_MAX_BYTES)
    if not isinstance(manifest, dict):
        raise BundleError("manifest must be an object")
    sample_count = validate_manifest(manifest)
    max_records, declared = validate_inventory(bundle, manifest, files)
    validate_payload_sizes(files, declared, verify_integrity=verify_integrity)
    for relative, path in files.items():
        maximum = (
            MANIFEST_MAX_BYTES
            if relative == "manifest.json"
            else RESULT_MAX_BYTES
            if relative == "result.json"
            else CHECKSUMS_MAX_BYTES
            if relative == "checksums.sha256"
            else RECONSTRUCT_MAX_BYTES
            if relative == "reconstruct.py"
            else MAX_RAW_BYTES
        )
        canonical_text(path, maximum=maximum)
    if verify_integrity:
        verify_checksums(bundle, files)
    verify_record_counts(bundle, declared, max_records)
    samples = list(iter_jsonl(bundle / "raw/measured.jsonl", maximum=max_records))
    if len(samples) != sample_count:
        raise BundleError("measured sample count does not equal manifest")
    for position, record in enumerate(samples, start=1):
        validate_sample(record, position, manifest)
    if sorted(record["sampleIndex"] for record in samples) != list(range(1, sample_count + 1)):
        raise BundleError("sample indexes are not exact and contiguous")

    starts = [record["startTimestampMs"] for record in samples]
    end_ms = max(record["startTimestampMs"] + record["elapsedMs"] for record in samples)
    span_ms = end_ms - min(starts)
    if span_ms <= 0:
        raise BundleError("measured wall-clock span must be positive")
    elapsed = [record["elapsedMs"] for record in samples]
    if manifest["measuredDuration"]["wallClockSeconds"] != round(span_ms / 1000, 6):
        raise BundleError("manifest measuredDuration does not equal measured sample span")
    workload = manifest["concurrencyOrWorkload"]
    if workload["threads"] * workload["loopsPerThread"] != sample_count:
        raise BundleError("manifest workload does not equal sampleCount")
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
        committed = strict_json(bundle / "result.json", maximum=RESULT_MAX_BYTES)
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
