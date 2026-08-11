from __future__ import annotations

import csv
import hashlib
import json
import math
import os
import re
import stat
import sys
from collections import Counter
from collections.abc import Iterable, Iterator, Mapping
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath
from typing import Any, NoReturn, cast

HASH = re.compile(r"[0-9a-f]{64}")
JWT = re.compile(
    rb"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\."
    rb"[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])"
)
UUID = re.compile(
    r"(?<![0-9A-Fa-f])[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}(?![0-9A-Fa-f])"
)
SAMPLE_FIELDS = tuple(
    "sampleIndex startTimestampMs elapsedMs latencyMs connectMs responseCode jmeterSuccess "
    "producerClassification state decisionCode activityProjectionVersion projectionVersion "
    "durableOrderCreated replay reservationLocatorHash orderLocatorHash responseBytes".split()
)
PUBLIC_FIELDS = set(
    "activityId quantity activityProjectionVersion state decisionCode projectionVersion replay "
    "durableOrderCreated reservationLocatorHash orderLocatorHash".split()
)
MANIFEST_FIELDS = set(
    "schemaVersion sliceId codeRevision environment machine containerResources "
    "fixtureOrDatasetVersion tool toolVersion warmup measuredDuration concurrencyOrWorkload "
    "sampleCount commands artifactInventory cleanupResult activityId productId "
    "activityProjectionVersion baselineActivityState baselineAllocatedQuota baselineProductStock "
    "settleCutoff observationAt dispatchSettleCutoff unpaidTimeoutSeconds settlementTimeoutSeconds "
    "jmeterConnectTimeoutMs jmeterResponseTimeoutMs runOrder".split()
)
RESULT_FIELDS = set(
    "schemaVersion sliceId profileId codeRevision valid sampleCount measuredDurationSeconds "
    "achievedQps latencyMs httpStatusDistribution stateDecisionDistribution errorDistribution "
    "q01Q09".split()
)
QUERY_FILES = {f"Q{number:02d}": f"q{number:02d}.csv" for number in range(1, 7)}
QUERY_FILES.update({"Q07a": "q07-details.csv", "Q07b": "q07-duplicates.csv", "Q09": "q09.csv"})
QUERY_FIELDS = {
    "Q01": "activity_id product_id state allocated_quota projection_version stock_quantity",
    "Q02": "total_reservations distinct_reservations distinct_user_activity duplicate_idempotency_groups",  # noqa: E501
    "Q03": "pending_count admitted_count rejected_count ordered_count cancelled_count unknown_state overdue_nonterminal",  # noqa: E501
    "Q04": "successful_reservations orders_for_activity missing_orders orphan_orders duplicate_orders binding_mismatches",  # noqa: E501
    "Q05": "bad_create_count bad_cancel_count unexpected_cancel_count bad_quantity_count unexpected_movement_types orphan_movements binding_mismatches",  # noqa: E501
    "Q06": "final_stock expected_final_stock net_consumed_quota active_quantity final_allocated_quota baseline_allocated_quota",  # noqa: E501
    "Q07a": "activity_id quantity activity_projection_version state decision_code projection_version order_count create_movement_count cancel_movement_count movement_linkage_mismatches reservation_locator_hash order_locator_hash",  # noqa: E501
    "Q07b": "duplicate_reservation_keys duplicate_order_keys duplicate_ledger_keys",
    "Q09": "overdue_reservation_resolution overdue_unpaid_orders overdue_timeout_dispatch failed_timeout_dispatch",  # noqa: E501
}
STATE_RULES = {"PENDING": (None, 1, False, False), "ADMITTED": ("ADMITTED", 2, False, False)}
STATE_RULES["ORDERED"] = ("ADMITTED", 3, True, True)
STATE_RULES["CANCELLED"] = ("ADMITTED", 4, True, True)
REJECTION_DECISIONS = {"EXHAUSTED", "NOT_STARTED", "ENDED", "INACTIVE", "STALE_VERSION"}
NESTED_FIELDS = {"environment": "scope operatingSystem architecture runtime"}
NESTED_FIELDS.update({"machine": "cpuCount memoryBytes dockerVersion"})
NESTED_FIELDS.update({"containerResources": "composeVersion declaredCpuLimit declaredMemoryLimit"})
NESTED_FIELDS.update({"measuredDuration": "startTimestampMs endTimestampMs seconds"})
NESTED_FIELDS.update({"cleanupResult": "status containers networks volumes children pathsAbsent"})
NESTED_FIELDS["warmup"] = (
    "activityId productId baselineQuota baselineStock sampleCount threads loopsPerThread "
    "rampSeconds quantity expectedActivityVersion"
)
NESTED_FIELDS["concurrencyOrWorkload"] = (
    "profileId samples threads loopsPerThread csvRows rampSeconds quantity expectedActivityVersion"
)
FIXED_MANIFEST: dict[str, Any] = {"schemaVersion": "cb155-manifest-v1", "sliceId": "CB-155"}
FIXED_MANIFEST.update({"tool": "Apache JMeter", "toolVersion": "5.6.3", "sampleCount": 500})
FIXED_MANIFEST.update({"activityProjectionVersion": 1, "baselineActivityState": "ACTIVE"})
FIXED_MANIFEST.update({"baselineAllocatedQuota": 252, "baselineProductStock": 252})
FIXED_MANIFEST.update({"unpaidTimeoutSeconds": 120, "settlementTimeoutSeconds": 300})
FIXED_MANIFEST.update({"jmeterConnectTimeoutMs": 2000, "jmeterResponseTimeoutMs": 10000})
FIXED_MANIFEST["commands"] = ["make measure-cb155"]
FIXED_MANIFEST["runOrder"] = (
    "build acquire-jmeter init-local up fixtures auth commerce q01 warmup controls measured "
    "settlement q02-q09 cleanup reconstruct publish"
).split()
WARMUP_KEYS = NESTED_FIELDS["warmup"].split()[2:]
WORKLOAD_KEYS = NESTED_FIELDS["concurrencyOrWorkload"].split()
RESIDUE_KINDS = {
    "children": "auth commerce",
    "paths": "docker_client_config run_env jmeter_checksum jmeter_archive jmeter_install "
    "rsa_private_key rsa_public_key auth_log commerce_log warmup_token_input "
    "warmup_temporary_jtl measured_token_input measured_temporary_jtl temporary_directory",
}


class BundleError(ValueError):
    pass


def reject(code: str, detail: str) -> NoReturn:
    raise BundleError(f"{code}: {detail}")


def require(condition: bool, code: str, detail: str = "mismatch") -> None:
    if not condition:
        reject(code, detail)


def unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, "JSON_DUPLICATE_KEY", key)
        result[key] = value
    return result


def named_fields(names: str, values: tuple[Any, ...]) -> dict[str, Any]:
    return dict(zip(names.split(), values, strict=True))


def bounded(path: Path, maximum: int = 2 * 1024 * 1024) -> bytes:
    with path.open("rb") as source:
        payload = source.read(maximum + 1)
    require(len(payload) <= maximum, "FILE_SIZE_LIMIT", path.name)
    return payload


def json_file(path: Path, maximum: int = 2 * 1024 * 1024) -> Any:
    try:
        return json.loads(bounded(path, maximum), object_pairs_hook=unique)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        reject("JSON_INVALID", f"{path.name}: {error}")


def closed(value: Any, fields: set[str], code: str) -> dict[str, Any]:
    require(isinstance(value, dict), code, "object required")
    actual = set(value)
    if actual != fields:
        reject(code, f"missing={sorted(fields - actual)} unknown={sorted(actual - fields)}")
    return cast(dict[str, Any], value)


def text(value: Any, code: str, maximum: int = 512) -> str:
    require(isinstance(value, str) and bool(value) and len(value) <= maximum, code, repr(value))
    return cast(str, value)


def hash_value(value: Any, code: str, nullable: bool = False) -> str | None:
    if nullable and value is None:
        return None
    require(isinstance(value, str) and HASH.fullmatch(value) is not None, code, repr(value))
    return cast(str, value)


def int_text(value: str, code: str, minimum: int = 0) -> int:
    valid = re.fullmatch(r"0|[1-9][0-9]*", value)
    require(valid is not None and int(value) >= minimum, code, repr(value))
    return int(value)


def bool_text(value: str, code: str) -> bool:
    require(value in {"true", "false"}, code, repr(value))
    return value == "true"


def instant(value: Any, code: str) -> datetime:
    raw = text(value, code, 27)
    try:
        parsed = datetime.strptime(raw, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=UTC)
        require(parsed.strftime("%Y-%m-%dT%H:%M:%S.%fZ") == raw, code, repr(raw))
        return parsed
    except ValueError:
        reject(code, repr(raw))


def walk(root: Path) -> dict[str, os.stat_result]:
    info = root.lstat()
    require(not stat.S_ISLNK(info.st_mode) and stat.S_ISDIR(info.st_mode), "BUNDLE_ROOT")
    files: dict[str, os.stat_result] = {}
    pending = [root]
    total = 0
    entries = 0
    while pending:
        for entry in os.scandir(pending.pop()):
            entries += 1
            path = Path(entry.path)
            item = path.lstat()
            relative = path.relative_to(root).as_posix()
            require(not stat.S_ISLNK(item.st_mode), "SYMLINK_REJECTED", relative)
            if stat.S_ISDIR(item.st_mode):
                pending.append(path)
            elif stat.S_ISREG(item.st_mode):
                files[relative] = item
                total += item.st_size
            else:
                reject("NON_REGULAR_FILE", relative)
            require(entries <= 64 and total <= 54 * 1024 * 1024, "BUNDLE_STAT_LIMIT", relative)
    return files


def validate_manifest(root: Path, files: Mapping[str, os.stat_result]) -> dict[str, Any]:
    required = {"manifest.json", "result.json", "checksums.sha256"}
    require(required.issubset(files), "BUNDLE_LAYOUT", "manifest/result/checksums required")
    manifest = closed(
        json_file(root / "manifest.json", 256 * 1024), MANIFEST_FIELDS, "MANIFEST_SCHEMA"
    )
    revision = text(manifest["codeRevision"], "MANIFEST_IDENTITY", 40)
    require(re.fullmatch(r"[0-9a-f]{40}", revision) is not None, "MANIFEST_IDENTITY")
    for name, fields in NESTED_FIELDS.items():
        closed(manifest[name], set(fields.split()), f"MANIFEST_{name.upper()}")
    facts = [*manifest["environment"].values(), manifest["machine"]["dockerVersion"]]
    facts.extend(manifest["containerResources"].values())
    require(all(type(value) is str and bool(value) for value in facts), "MANIFEST_FACT")
    machine = manifest["machine"]
    machine_valid = all(
        type(machine[key]) is int and machine[key] > 0 for key in ("cpuCount", "memoryBytes")
    )
    require(machine_valid, "MANIFEST_FACT")
    fixed = all(manifest[key] == value for key, value in FIXED_MANIFEST.items())
    require(fixed, "MANIFEST_RUNTIME_PARAMETER", "fixed runtime mismatch")
    warmup_values = tuple(manifest["warmup"][key] for key in WARMUP_KEYS)
    require(warmup_values == (1, 1, 32, 8, 4, 2, 2, 1), "MANIFEST_WARMUP", "mismatch")
    measured = manifest["concurrencyOrWorkload"]
    workload_values = tuple(measured[key] for key in WORKLOAD_KEYS)
    expected_workload = ("cb155-formal-v1", 500, 64, 8, 500, 5, 1, 1)
    require(workload_values == expected_workload, "MANIFEST_WORKLOAD")
    expected_cleanup = named_fields(
        "status containers networks volumes children pathsAbsent", ("PASS", 0, 0, 0, 0, True)
    )
    require(manifest["cleanupResult"] == expected_cleanup, "MANIFEST_CLEANUP")
    timestamp_fields = "settleCutoff observationAt dispatchSettleCutoff".split()
    timestamps = [instant(manifest[field], "MANIFEST_TIMESTAMP") for field in timestamp_fields]
    require(timestamps[0] <= timestamps[1] == timestamps[2], "MANIFEST_TIMESTAMP")
    return manifest


def safe_path(value: Any) -> str:
    value = text(value, "INVENTORY_PATH", 300)
    path = PurePosixPath(value)
    valid = not path.is_absolute() and ".." not in path.parts and str(path) == value
    require(valid and value.startswith("raw/"), "INVENTORY_PATH", value)
    return cast(str, value)


def inventory(
    root: Path, files: Mapping[str, os.stat_result], manifest: Mapping[str, Any]
) -> list[dict[str, Any]]:
    actual = {path: info for path, info in files.items() if path.startswith("raw/")}
    raw_bytes = sum(info.st_size for info in actual.values())
    require(bool(actual) and raw_bytes <= 50 * 1024 * 1024, "RAW_SIZE_LIMIT")
    declared = manifest["artifactInventory"]
    require(isinstance(declared, list) and bool(declared), "INVENTORY_SCHEMA")
    result: list[dict[str, Any]] = []
    for item in declared:
        row = closed(item, {"path", "bytes", "records", "mediaType"}, "INVENTORY_SCHEMA")
        path = safe_path(row["path"])
        require(path in actual and row["bytes"] == actual[path].st_size, "INVENTORY_BYTES", path)
        require(type(row["records"]) is int and row["records"] >= 0, "INVENTORY_RECORDS")
        media_valid = row["mediaType"] in {"text/csv", "application/json", "application/x-ndjson"}
        require(media_valid, "INVENTORY_MEDIA_TYPE")
        result.append(row)
    paths = [row["path"] for row in result]
    require(paths == sorted(actual) and len(paths) == len(set(paths)), "INVENTORY_SET_MISMATCH")
    return result


def checksums(root: Path, files: Mapping[str, os.stat_result]) -> None:
    lines = bounded(root / "checksums.sha256", 256 * 1024).decode("ascii").splitlines()
    expected = sorted(set(files) - {"checksums.sha256"})
    parsed: list[tuple[str, str]] = []
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
        require(match is not None, "CHECKSUM_FORMAT", line[:80])
        assert match is not None
        parsed.append((match.group(2), match.group(1)))
    require([path for path, _ in parsed] == expected, "CHECKSUM_INVENTORY")
    for relative, declared in parsed:
        digest = hashlib.sha256()
        with (root / relative).open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
        require(digest.hexdigest() == declared, "CHECKSUM_MISMATCH", relative)


def lines(path: Path) -> Iterator[bytes]:
    with path.open("rb") as source:
        for number, line in enumerate(source, 1):
            line_valid = len(line) <= 64 * 1024 and line.endswith(b"\n")
            require(line_valid, "RECORD_LINE_BOUND", f"{path.name}:{number}")
            require(not line[:-1].endswith((b" ", b"\t", b"\r")), "TEXT_CANONICAL")
            yield line[:-1]


def record_counts(root: Path, declared: Iterable[Mapping[str, Any]]) -> None:
    for row in declared:
        path = root / str(row["path"])
        media = row["mediaType"]
        if media == "application/json":
            json_file(path)
            count = 1
        else:
            count = 0
            for line in lines(path):
                if media == "application/x-ndjson":
                    json.loads(line, object_pairs_hook=unique)
                count += 1
            count -= media == "text/csv"
        require(count == row["records"], "INVENTORY_RECORD_COUNT")


def csv_rows(path: Path, fields: tuple[str, ...] | None = None) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None or len(reader.fieldnames) != len(set(reader.fieldnames)):
            reject("CSV_HEADER", path.name)
        require(fields is None or tuple(reader.fieldnames) == fields, "CSV_HEADER")
        result = list(reader)
    require(not any(None in row for row in result), "CSV_ROW_WIDTH", path.name)
    return result


def jsonl(path: Path) -> list[dict[str, Any]]:
    result = []
    for line in lines(path):
        row = json.loads(line, object_pairs_hook=unique)
        require(isinstance(row, dict), "JSONL_SCHEMA", path.name)
        result.append(row)
    return result


def public(body: Mapping[str, Any], activity: str, code: str, quantity: int = 1) -> dict[str, Any]:
    value = closed(dict(body), PUBLIC_FIELDS, code)
    integer_fields = "quantity activityProjectionVersion projectionVersion".split()
    intent = (value["activityId"], value["quantity"], value["activityProjectionVersion"])
    require(all(type(value[key]) is int for key in integer_fields), code, "integer fields")
    require(intent == (activity, quantity, 1), code, "intent mismatch")
    hash_value(value["reservationLocatorHash"], code)
    order = hash_value(value["orderLocatorHash"], code, nullable=True)
    booleans = (value["replay"], value["durableOrderCreated"])
    require(all(type(item) is bool for item in booleans), code, "boolean fields")
    state = value["state"]
    actual = (
        value["decisionCode"],
        value["projectionVersion"],
        value["durableOrderCreated"],
        order is not None,
    )
    expected = (
        (value["decisionCode"], 2, False, False)
        if state == "REJECTED" and value["decisionCode"] in REJECTION_DECISIONS
        else STATE_RULES.get(state)
    )
    require(actual == expected, code, "contradictory body")
    return value


def classification(status: int, body: Mapping[str, Any], activity: str, quantity: int = 1) -> str:
    value = public(body, activity, "PUBLIC_SAMPLE_CONTRACT", quantity)
    state = value["state"]
    valid = (
        (status == 201 and state == "ADMITTED" and not value["replay"])
        or (status == 202 and state == "PENDING" and not value["replay"])
        or (status == 409 and state == "REJECTED")
        or (status == 200 and (value["replay"] or state in {"ORDERED", "CANCELLED"}))
    )
    require(valid, "PUBLIC_SAMPLE_CONTRACT", "status/body mismatch")
    return "business_rejected" if status == 409 else "accepted"


def samples(root: Path, manifest: Mapping[str, Any], warmup: bool) -> list[dict[str, Any]]:
    name = "warmup.csv" if warmup else "measured.csv"
    rows = csv_rows(root / "raw/performance" / name, SAMPLE_FIELDS)
    expected = 32 if warmup else 500
    require(len(rows) == expected, "SAMPLE_COUNT", f"{name}={len(rows)}")
    result: list[dict[str, Any]] = []
    indices: set[int] = set()
    activity = str(manifest["warmup"]["activityId"] if warmup else manifest["activityId"])
    for row in rows:
        index = int_text(row["sampleIndex"], "SAMPLE_INDEX", 1)
        indices.add(index)
        body = {
            "activityId": activity,
            "quantity": 2 if warmup else 1,
            "activityProjectionVersion": int_text(row["activityProjectionVersion"], "SAMPLE_BODY"),
            "state": row["state"],
            "decisionCode": row["decisionCode"] or None,
            "projectionVersion": int_text(row["projectionVersion"], "SAMPLE_BODY"),
            "replay": bool_text(row["replay"], "SAMPLE_BODY"),
            "durableOrderCreated": bool_text(row["durableOrderCreated"], "SAMPLE_BODY"),
            "reservationLocatorHash": row["reservationLocatorHash"],
            "orderLocatorHash": row["orderLocatorHash"] or None,
        }
        status = int_text(row["responseCode"], "SAMPLE_STATUS", 100)
        independent = classification(status, body, activity, 2 if warmup else 1)
        classified = independent == row["producerClassification"]
        require(classified, "INDEPENDENT_CLASSIFICATION", str(index))
        timing = [int_text(row[field], "SAMPLE_TIMING") for field in SAMPLE_FIELDS[1:5]]
        int_text(row["responseBytes"], "SAMPLE_BODY", 1)
        require(timing[0] > 0, "SAMPLE_TIMING")
        item = named_fields("index start elapsed latency connect", (index, *timing))
        item.update(
            {"status": status, "success": bool_text(row["jmeterSuccess"], "SAMPLE_SUCCESS")}
        )
        item.update({"classification": independent, "body": body})
        valid_timing = item["latency"] <= item["elapsed"] and item["connect"] <= item["elapsed"]
        require(valid_timing and item["success"] == (status < 400), "SAMPLE_TIMING_OR_SUCCESS")
        result.append(item)
    require(indices == set(range(1, expected + 1)), "SAMPLE_INDEX", "missing or duplicate")
    warmup_closed = all(
        item["status"] == 409
        and item["body"]["state"] == "REJECTED"
        and item["body"]["decisionCode"] == "EXHAUSTED"
        for item in result
    )
    require(not warmup or warmup_closed, "WARMUP_CLOSURE", "not exact EXHAUSTED")
    return result


def one(root: Path, query: str) -> dict[str, str]:
    fields = tuple(QUERY_FIELDS[query].split())
    rows = csv_rows(root / "raw/reconciliation" / QUERY_FILES[query], fields)
    require(len(rows) == 1, f"{query}_ROWSET", str(len(rows)))
    return rows[0]


def zeros(row: Mapping[str, str], code: str) -> None:
    require(all(int_text(value, code) == 0 for value in row.values()), code)


def numbers(row: Mapping[str, str], fields: str, code: str) -> list[int]:
    return [int_text(row.get(field, ""), code) for field in fields.split()]


def q01_q09(
    root: Path, manifest: Mapping[str, Any], measured: list[dict[str, Any]]
) -> dict[str, str]:
    activity = str(manifest["activityId"])
    q01 = one(root, "Q01")
    expected_q01 = named_fields(
        QUERY_FIELDS["Q01"],
        (activity, str(manifest["productId"]), "ACTIVE", "252", "1", "252"),
    )
    require(q01 == expected_q01, "Q01_BASELINE", repr(q01))
    q02 = one(root, "Q02")
    q02_counts = numbers(q02, QUERY_FIELDS["Q02"], "Q02_CARDINALITY")
    require(q02_counts == [502, 502, 502, 0], "Q02_CARDINALITY", repr(q02))
    q03 = one(root, "Q03")
    state_names = "PENDING ADMITTED REJECTED ORDERED CANCELLED".split()
    state_fields = "pending_count admitted_count rejected_count ordered_count cancelled_count"
    state_counts = numbers(q03, state_fields, "Q03_CLOSURE")
    states = Counter(dict(zip(state_names, state_counts, strict=True)))
    closure = [states["PENDING"], states["ADMITTED"]]
    closure.extend(numbers(q03, "unknown_state overdue_nonterminal", "Q03_CLOSURE"))
    require(not any(closure) and states.total() == 502, "Q03_CLOSURE", repr(q03))
    q04 = one(root, "Q04")
    successful, orders = numbers(
        q04, "successful_reservations orders_for_activity", "Q04_DURABLE_BINDING"
    )
    require(successful == orders == states["ORDERED"] + states["CANCELLED"], "Q04_DURABLE_BINDING")
    for key in ("missing_orders", "orphan_orders", "duplicate_orders", "binding_mismatches"):
        require(not int_text(q04.get(key, ""), "Q04_DURABLE_BINDING"), "Q04_DURABLE_BINDING")
    controls = jsonl(root / "raw/controls/q04.jsonl")
    require(len(controls) == 502, "Q04_CONTROL_COVERAGE", str(len(controls)))
    terminal_by_hash: dict[str, dict[str, Any]] = {}
    expected_hashes = {str(item["body"]["reservationLocatorHash"]) for item in measured}
    public_states: Counter[str] = Counter()
    for control in controls:
        control = closed(control, {"public", "durable"}, "Q04_CONTROL_SCHEMA")
        body = public(control["public"], activity, "Q04_PUBLIC_BINDING")
        durable = public(control["durable"], activity, "Q04_DURABLE_BINDING")
        require(body == durable, "Q04_PUBLIC_DURABLE")
        locator = str(body["reservationLocatorHash"])
        require(locator not in terminal_by_hash, "Q04_CONTROL_DUPLICATE", locator)
        terminal_by_hash[locator] = body
        public_states[str(body["state"])] += 1
    require(public_states == states, "Q04_CONTROL_COVERAGE", "public/durable state mismatch")
    zeros(one(root, "Q05"), "Q05_LEDGER")
    q06 = {key: int_text(value, "Q06_STOCK_QUOTA") for key, value in one(root, "Q06").items()}
    q06_valid = q06["final_stock"] == q06["expected_final_stock"]
    q06_valid &= q06["final_allocated_quota"] == q06["baseline_allocated_quota"] == 252
    q06_valid &= q06["net_consumed_quota"] == q06["active_quantity"]
    q06_valid &= 0 <= q06["net_consumed_quota"] <= 252
    require(q06_valid, "Q06_STOCK_QUOTA", repr(q06))
    replay_rows = jsonl(root / "raw/controls/q07.jsonl")
    require(len(replay_rows) >= 3, "Q07_REPLAY_COUNT", str(len(replay_rows)))
    initial = closed(replay_rows[0], {"case", "observedAt", "status", "body"}, "Q07_SCHEMA")
    require(initial["case"] == "initial", "Q07_SCHEMA", "initial first")
    initial_body = public(initial["body"], activity, "Q07_PUBLIC_BINDING")
    require(type(initial["status"]) is int, "Q07_STATUS")
    initial_class = classification(initial["status"], initial_body, activity)
    require(initial_class == "accepted", "Q07_INITIAL", "control was rejected")
    terminal: list[dict[str, Any]] = []
    settle_cutoff = instant(manifest["settleCutoff"], "Q07_REPLAY_TIMING")
    observation_at = instant(manifest["observationAt"], "Q07_REPLAY_TIMING")
    initial_at = instant(initial["observedAt"], "Q07_REPLAY_TIMING")
    require(initial_at < settle_cutoff, "Q07_REPLAY_TIMING", "initial after settlement")
    for row in replay_rows[1:]:
        row = closed(row, {"case", "observedAt", "status", "body"}, "Q07_SCHEMA")
        observed_at = instant(row["observedAt"], "Q07_REPLAY_TIMING")
        replay_timing = row["case"] == "replay" and settle_cutoff <= observed_at <= observation_at
        require(replay_timing, "Q07_REPLAY_TIMING")
        body = public(row["body"], activity, "Q07_PUBLIC_BINDING")
        require(type(row["status"]) is int, "Q07_STATUS")
        classification(row["status"], body, activity)
        require(bool(body["replay"]), "Q07_REPLAY_FLAG", "false")
        terminal.append(body)
    stable = "reservationLocatorHash activityId quantity activityProjectionVersion".split()
    frozen = "state decisionCode projectionVersion durableOrderCreated replay".split()
    frozen.append("orderLocatorHash")
    identity_stable = all(body[key] == initial_body[key] for body in terminal for key in stable)
    terminal_stable = all(body[key] == terminal[0][key] for body in terminal[1:] for key in frozen)
    require(identity_stable, "Q07_IDENTITY_BINDING", "initial/replay mismatch")
    require(terminal_stable, "Q07_TERMINAL_REPLAY", "terminal fields changed")
    detail = one(root, "Q07a")
    expected_detail = {
        "reservation_locator_hash": (terminal[0]["reservationLocatorHash"], "Q07_IDENTITY_BINDING"),
        "activity_id": (activity, "Q07_ACTIVITY_BINDING"),
        "quantity": ("1", "Q07_QUANTITY_BINDING"),
        "activity_projection_version": ("1", "Q07_VERSION_BINDING"),
        "state": (str(terminal[0]["state"]), "Q07_DURABLE_BINDING"),
        "decision_code": (str(terminal[0]["decisionCode"] or ""), "Q07_DURABLE_BINDING"),
        "projection_version": (str(terminal[0]["projectionVersion"]), "Q07_DURABLE_BINDING"),
        "order_locator_hash": (str(terminal[0]["orderLocatorHash"] or ""), "Q07_DURABLE_BINDING"),
    }
    for field, (expected, code) in expected_detail.items():
        require(detail[field] == expected, code, field)
    effect_fields = "order_count create_movement_count cancel_movement_count"
    effect_fields += " movement_linkage_mismatches"
    effects = numbers(detail, effect_fields, "Q07_DURABLE_BINDING")
    effects_valid = terminal[0]["state"] == "CANCELLED" and effects == [1, 1, 1, 0]
    require(effects_valid, "Q07_DURABLE_BINDING")
    zeros(one(root, "Q07b"), "Q07_DUPLICATE_EFFECT")
    ownership = jsonl(root / "raw/controls/q08.jsonl")
    require(len(ownership) == 3, "Q08_CONTROL_COUNT", str(len(ownership)))
    ownership_fields = set("case status reservationLocatorHash body".split())
    owner = closed(ownership[0], ownership_fields, "Q08_SCHEMA")
    require(owner["case"] == "owner" and owner["status"] == 200, "Q08_OWNER", repr(owner))
    hash_value(owner["reservationLocatorHash"], "Q08_LOCATOR_HASH")
    owner_body = public(owner["body"], activity, "Q08_OWNER")
    require(owner_body["reservationLocatorHash"] == owner["reservationLocatorHash"], "Q08_OWNER")
    classification(200, owner_body, activity)
    owner_bound = owner_body == terminal_by_hash.get(owner["reservationLocatorHash"])
    require(owner_bound, "Q08_OWNER_BINDING")
    errors = []
    locators = []
    for case, control in zip(("unknown", "other-owner"), ownership[1:], strict=True):
        row = closed(control, ownership_fields, "Q08_SCHEMA")
        require(row["case"] == case and row["status"] == 404, "Q08_404_STATUS", repr(row))
        locators.append(hash_value(row["reservationLocatorHash"], "Q08_LOCATOR_HASH"))
        body = closed(row["body"], {"category", "message"}, "Q08_ERROR_KEYSET")
        text(body["category"], "Q08_ERROR_STRING")
        text(body["message"], "Q08_ERROR_STRING")
        errors.append(body)
    require(errors[0] == errors[1], "Q08_DISCLOSURE_EQUALITY", repr(errors))
    require(locators[0] not in terminal_by_hash, "Q08_LOCATOR_BINDING", "unknown")
    require(locators[1] == owner["reservationLocatorHash"], "Q08_LOCATOR_BINDING", "other-owner")
    expected_hashes.update(
        (initial_body["reservationLocatorHash"], owner_body["reservationLocatorHash"])
    )
    complete_controls = set(terminal_by_hash) == expected_hashes
    require(complete_controls, "Q04_CONTROL_COVERAGE", "locator set mismatch")
    q08 = closed(
        json_file(root / "raw/reconciliation/q08.json"),
        {"beforeDigest", "afterDigest"},
        "Q08_DIGEST_SCHEMA",
    )
    before = hash_value(q08["beforeDigest"], "Q08_DIGEST")
    after = hash_value(q08["afterDigest"], "Q08_DIGEST")
    require(before == after, "Q08_MUTATION", "canonical row changed")
    zeros(one(root, "Q09"), "Q09_DURABLE_WORK")
    residue = closed(
        json_file(root / "raw/residue.json"),
        set("projectDigest cleanupStatus containers networks volumes children paths".split()),
        "Q09_RESIDUE_SCHEMA",
    )
    hash_value(residue["projectDigest"], "Q09_PROJECT_HASH")
    resources_zero = all(residue[key] == 0 for key in ("containers", "networks", "volumes"))
    require(residue["cleanupStatus"] == "PASS" and resources_zero, "Q09_RESIDUE")
    for name, expected in RESIDUE_KINDS.items():
        entries = residue[name]
        require(isinstance(entries, list) and bool(entries), "Q09_EMPTY_INVENTORY", name)
        kinds: set[str] = set()
        for item in entries:
            item = closed(item, {"kind", "absent"}, "Q09_INVENTORY_SCHEMA")
            kind = text(item["kind"], "Q09_INVENTORY_SCHEMA", 80)
            require(kind not in kinds and item["absent"] is True, "Q09_INVENTORY_SCHEMA", kind)
            kinds.add(kind)
        require(kinds == set(expected.split()), "Q09_INVENTORY_SET", name)
    q09 = closed(
        json_file(root / "raw/controls/q09.json"),
        set(
            "expectedSamples actualSamples unexpectedError unknownClassification lostSample".split()
        ),
        "Q09_CONTROL_SCHEMA",
    )
    expected_errors = {"expectedSamples": 500, "actualSamples": len(measured)}
    expected_errors.update(
        dict.fromkeys("unexpectedError unknownClassification lostSample".split(), 0)
    )
    require(q09 == expected_errors, "Q09_ERROR_CLOSURE", repr(q09))
    return {f"Q{number:02d}": "PASS" for number in range(1, 10)}


def reconstruct(
    manifest: Mapping[str, Any], measured: list[dict[str, Any]], q: Mapping[str, str]
) -> dict[str, Any]:
    start = min(item["start"] for item in measured)
    end = max(item["start"] + item["elapsed"] for item in measured)
    seconds = (end - start) / 1000
    require(seconds > 0, "MEASURED_DURATION", repr(seconds))
    elapsed = sorted(item["elapsed"] for item in measured)

    def nearest(value: float) -> int:
        return int(elapsed[math.ceil(value * len(elapsed)) - 1])

    errors: dict[str, int] = dict.fromkeys(
        "transport parse contract unexpectedError unknownClassification lostSample".split(), 0
    )
    result: dict[str, Any] = {"schemaVersion": "cb155-result-v1", "sliceId": "CB-155"}
    result["profileId"] = "cb155-formal-v1"
    result.update({"codeRevision": manifest["codeRevision"], "valid": True, "sampleCount": 500})
    result.update(
        {"measuredDurationSeconds": round(seconds, 6), "achievedQps": round(500 / seconds, 6)}
    )
    result["latencyMs"] = {"p50": nearest(0.50), "p95": nearest(0.95), "p99": nearest(0.99)}
    result["httpStatusDistribution"] = dict(
        sorted(Counter(str(item["status"]) for item in measured).items())
    )
    decisions = Counter(
        f"{item['body']['state']}/{item['body']['decisionCode']}" for item in measured
    )
    result.update(
        {"stateDecisionDistribution": dict(sorted(decisions.items())), "errorDistribution": errors}
    )
    result["q01Q09"] = dict(q)
    return result


def sanitization(root: Path, files: Mapping[str, os.stat_result]) -> None:
    markers = rb"authorization|bearer |private key|accesstoken|password|://|\u|\/".split(b"|")
    for relative in files:
        if relative == "checksums.sha256":
            continue
        payload = bounded(root / relative, 50 * 1024 * 1024)
        lowered = payload.lower()
        clean = not any(marker in lowered for marker in markers) and JWT.search(payload) is None
        require(clean, "SECRET_SCAN", relative)
        if relative.startswith("raw/"):
            content = payload.decode("utf-8")
            paths = ("/Users/", "/private/", "/tmp/", "/var/folders/", "/var/tmp/")
            identities = ("cb155-subject-", "cb155-login-")
            safe = UUID.search(content) is None
            safe &= not any(value in content for value in (*paths, *identities))
            require(safe, "RAW_LOCATOR_OR_PATH", relative)


def verify_bundle(bundle: Path) -> dict[str, Any]:
    files = walk(bundle)
    manifest = validate_manifest(bundle, files)
    declared = inventory(bundle, files, manifest)
    checksums(bundle, files)
    record_counts(bundle, declared)
    sanitization(bundle, files)
    samples(bundle, manifest, True)
    measured = samples(bundle, manifest, False)
    q = q01_q09(bundle, manifest, measured)
    rebuilt = reconstruct(manifest, measured, q)
    result = closed(json_file(bundle / "result.json"), RESULT_FIELDS, "RESULT_SCHEMA")
    require(result == rebuilt, "RESULT_RECONSTRUCTION")
    start = min(item["start"] for item in measured)
    end = max(item["start"] + item["elapsed"] for item in measured)
    duration = named_fields(
        "startTimestampMs endTimestampMs seconds",
        (start, end, rebuilt["measuredDurationSeconds"]),
    )
    require(manifest["measuredDuration"] == duration, "MANIFEST_DURATION")
    return rebuilt


if __name__ == "__main__":
    require(len(sys.argv) == 2, "CLI_USAGE")
    print(json.dumps(verify_bundle(Path(sys.argv[1])), sort_keys=True, separators=(",", ":")))
