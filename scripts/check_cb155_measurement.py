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
from pathlib import Path, PurePosixPath
from typing import Any, NoReturn, cast

HASH = re.compile(r"[0-9a-f]{64}")
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
WARMUP_KEYS = NESTED_FIELDS["warmup"].split()[2:]
WORKLOAD_KEYS = NESTED_FIELDS["concurrencyOrWorkload"].split()


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
        if key in result:
            reject("JSON_DUPLICATE_KEY", key)
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


def walk(root: Path) -> dict[str, os.stat_result]:
    info = root.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        reject("BUNDLE_ROOT", "real directory required")
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
            if stat.S_ISLNK(item.st_mode):
                reject("SYMLINK_REJECTED", relative)
            if stat.S_ISDIR(item.st_mode):
                pending.append(path)
            elif stat.S_ISREG(item.st_mode):
                files[relative] = item
                total += item.st_size
            else:
                reject("NON_REGULAR_FILE", relative)
            if entries > 64 or total > 54 * 1024 * 1024:
                reject("BUNDLE_STAT_LIMIT", relative)
    return files


def validate_manifest(root: Path, files: Mapping[str, os.stat_result]) -> dict[str, Any]:
    if not {"manifest.json", "result.json", "checksums.sha256"}.issubset(files):
        reject("BUNDLE_LAYOUT", "manifest/result/checksums required")
    manifest = closed(
        json_file(root / "manifest.json", 256 * 1024), MANIFEST_FIELDS, "MANIFEST_SCHEMA"
    )
    revision = text(manifest["codeRevision"], "MANIFEST_IDENTITY", 40)
    require(re.fullmatch(r"[0-9a-f]{40}", revision) is not None, "MANIFEST_IDENTITY")
    for name, fields in NESTED_FIELDS.items():
        closed(manifest[name], set(fields.split()), f"MANIFEST_{name.upper()}")
    if any(manifest[key] != value for key, value in FIXED_MANIFEST.items()):
        reject("MANIFEST_RUNTIME_PARAMETER", "fixed runtime mismatch")
    warmup_values = tuple(manifest["warmup"][key] for key in WARMUP_KEYS)
    require(warmup_values == (1, 1, 32, 8, 4, 2, 2, 1), "MANIFEST_WARMUP", "mismatch")
    measured = manifest["concurrencyOrWorkload"]
    workload_values = tuple(measured[key] for key in WORKLOAD_KEYS)
    if workload_values != ("cb155-formal-v1", 500, 64, 8, 500, 5, 1, 1):
        reject("MANIFEST_WORKLOAD", "frozen workload mismatch")
    expected_cleanup = named_fields(
        "status containers networks volumes children pathsAbsent", ("PASS", 0, 0, 0, 0, True)
    )
    if manifest["cleanupResult"] != expected_cleanup:
        reject("MANIFEST_CLEANUP", repr(manifest["cleanupResult"]))
    if not isinstance(manifest["commands"], list) or not isinstance(manifest["runOrder"], list):
        reject("MANIFEST_RUNTIME_PARAMETER", "commands/runOrder")
    return manifest


def safe_path(value: Any) -> str:
    value = text(value, "INVENTORY_PATH", 300)
    path = PurePosixPath(value)
    if (
        path.is_absolute()
        or ".." in path.parts
        or str(path) != value
        or not value.startswith("raw/")
    ):
        reject("INVENTORY_PATH", value)
    return cast(str, value)


def inventory(
    root: Path, files: Mapping[str, os.stat_result], manifest: Mapping[str, Any]
) -> list[dict[str, Any]]:
    actual = {path: info for path, info in files.items() if path.startswith("raw/")}
    if not actual or sum(info.st_size for info in actual.values()) > 50 * 1024 * 1024:
        reject("RAW_SIZE_LIMIT", "50 MiB")
    declared = manifest["artifactInventory"]
    if not isinstance(declared, list) or not declared:
        reject("INVENTORY_SCHEMA", "nonempty array required")
    result: list[dict[str, Any]] = []
    for item in declared:
        row = closed(item, {"path", "bytes", "records", "mediaType"}, "INVENTORY_SCHEMA")
        path = safe_path(row["path"])
        if path not in actual or row["bytes"] != actual[path].st_size:
            reject("INVENTORY_BYTES", path)
        require(type(row["records"]) is int and row["records"] >= 0, "INVENTORY_RECORDS")
        require(
            row["mediaType"] in {"text/csv", "application/json", "application/x-ndjson"},
            "INVENTORY_MEDIA_TYPE",
        )
        result.append(row)
    paths = [row["path"] for row in result]
    if paths != sorted(actual) or len(paths) != len(set(paths)):
        reject("INVENTORY_SET_MISMATCH", repr(paths))
    return result


def checksums(root: Path, files: Mapping[str, os.stat_result]) -> None:
    lines = bounded(root / "checksums.sha256", 256 * 1024).decode("ascii").splitlines()
    expected = sorted(set(files) - {"checksums.sha256"})
    parsed: list[tuple[str, str]] = []
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
        if match is None:
            reject("CHECKSUM_FORMAT", line[:80])
        parsed.append((match.group(2), match.group(1)))
    if [path for path, _ in parsed] != expected:
        reject("CHECKSUM_INVENTORY", "payload set/order mismatch")
    for relative, declared in parsed:
        digest = hashlib.sha256()
        with (root / relative).open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
        require(digest.hexdigest() == declared, "CHECKSUM_MISMATCH", relative)


def lines(path: Path) -> Iterator[bytes]:
    with path.open("rb") as source:
        for number, line in enumerate(source, 1):
            if len(line) > 64 * 1024 or not line.endswith(b"\n"):
                reject("RECORD_LINE_BOUND", f"{path.name}:{number}")
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
    if any(type(value[key]) is not int for key in integer_fields) or (
        value["activityId"] != activity
        or value["quantity"] != quantity
        or value["activityProjectionVersion"] != 1
    ):
        reject(code, "intent mismatch")
    hash_value(value["reservationLocatorHash"], code)
    order = hash_value(value["orderLocatorHash"], code, nullable=True)
    if type(value["replay"]) is not bool or type(value["durableOrderCreated"]) is not bool:
        reject(code, "boolean fields")
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
    if not valid:
        reject("PUBLIC_SAMPLE_CONTRACT", "status/body mismatch")
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
        require(
            independent == row["producerClassification"],
            "INDEPENDENT_CLASSIFICATION",
            str(index),
        )
        timing = [int_text(row[field], "SAMPLE_TIMING") for field in SAMPLE_FIELDS[1:5]]
        require(timing[0] > 0, "SAMPLE_TIMING")
        item = named_fields("index start elapsed latency connect", (index, *timing))
        item.update(
            {"status": status, "success": bool_text(row["jmeterSuccess"], "SAMPLE_SUCCESS")}
        )
        item.update({"classification": independent, "body": body})
        if (
            item["latency"] > item["elapsed"]
            or item["connect"] > item["elapsed"]
            or item["success"] != (status < 400)
        ):
            reject("SAMPLE_TIMING_OR_SUCCESS", str(index))
        result.append(item)
    require(indices == set(range(1, expected + 1)), "SAMPLE_INDEX", "missing or duplicate")
    if warmup and any(
        item["status"] != 409
        or item["body"]["state"] != "REJECTED"
        or item["body"]["decisionCode"] != "EXHAUSTED"
        for item in result
    ):
        reject("WARMUP_CLOSURE", "not exact EXHAUSTED")
    return result


def one(root: Path, query: str) -> dict[str, str]:
    rows = csv_rows(root / "raw/reconciliation" / QUERY_FILES[query])
    require(len(rows) == 1, f"{query}_ROWSET", str(len(rows)))
    return rows[0]


def zeros(row: Mapping[str, str], code: str) -> None:
    for key, value in row.items():
        require(int_text(value, code) == 0, code, key)


def numbers(row: Mapping[str, str], fields: str, code: str) -> list[int]:
    return [int_text(row.get(field, ""), code) for field in fields.split()]


def q01_q09(
    root: Path, manifest: Mapping[str, Any], measured: list[dict[str, Any]]
) -> dict[str, str]:
    q01 = one(root, "Q01")
    expected_q01 = {
        "activity_id": str(manifest["activityId"]),
        "product_id": str(manifest["productId"]),
    }
    expected_q01.update({"state": "ACTIVE", "allocated_quota": "252"})
    expected_q01.update({"projection_version": "1", "stock_quantity": "252"})
    if q01 != expected_q01:
        reject("Q01_BASELINE", repr(q01))
    q02 = one(root, "Q02")
    q02_counts = numbers(
        q02,
        "total_reservations distinct_reservations distinct_user_activity "
        "duplicate_idempotency_groups",
        "Q02_CARDINALITY",
    )
    require(q02_counts == [502, 502, 502, 0], "Q02_CARDINALITY", repr(q02))
    q03 = one(root, "Q03")
    state_names = "PENDING ADMITTED REJECTED ORDERED CANCELLED".split()
    state_counts = numbers(
        q03,
        "pending_count admitted_count rejected_count ordered_count cancelled_count",
        "Q03_CLOSURE",
    )
    states = Counter(dict(zip(state_names, state_counts, strict=True)))
    closure = [states["PENDING"], states["ADMITTED"]]
    closure.extend(numbers(q03, "unknown_state overdue_nonterminal", "Q03_CLOSURE"))
    if any(closure) or states.total() != 502:
        reject("Q03_CLOSURE", repr(q03))
    q04 = one(root, "Q04")
    successful, orders = numbers(
        q04, "successful_reservations orders_for_activity", "Q04_DURABLE_BINDING"
    )
    closed_orders = states["ORDERED"] + states["CANCELLED"]
    require(successful == orders == closed_orders, "Q04_DURABLE_BINDING")
    for key in ("missing_orders", "orphan_orders", "duplicate_orders", "binding_mismatches"):
        require(not int_text(q04.get(key, ""), "Q04_DURABLE_BINDING"), "Q04_DURABLE_BINDING")
    controls = jsonl(root / "raw/controls/q04.jsonl")
    require(len(controls) == 502, "Q04_CONTROL_COVERAGE", str(len(controls)))
    hashes: set[str] = set()
    public_states: Counter[str] = Counter()
    for control in controls:
        control = closed(control, {"public", "durable"}, "Q04_CONTROL_SCHEMA")
        body = public(control["public"], str(manifest["activityId"]), "Q04_PUBLIC_BINDING")
        durable = public(control["durable"], str(manifest["activityId"]), "Q04_DURABLE_BINDING")
        require(body == durable, "Q04_PUBLIC_DURABLE")
        locator = str(body["reservationLocatorHash"])
        require(locator not in hashes, "Q04_CONTROL_DUPLICATE", locator)
        hashes.add(locator)
        public_states[str(body["state"])] += 1
    require(public_states == states, "Q04_CONTROL_COVERAGE", "public/durable state mismatch")
    zeros(one(root, "Q05"), "Q05_LEDGER")
    q06 = {key: int_text(value, "Q06_STOCK_QUOTA") for key, value in one(root, "Q06").items()}
    if (
        q06.get("final_stock") != q06.get("expected_final_stock")
        or q06.get("final_allocated_quota") != 252
        or q06.get("baseline_allocated_quota") != 252
        or q06.get("net_consumed_quota") != q06.get("active_quantity")
        or not 0 <= q06.get("net_consumed_quota", -1) <= 252
    ):
        reject("Q06_STOCK_QUOTA", repr(q06))
    replay_rows = jsonl(root / "raw/controls/q07.jsonl")
    require(len(replay_rows) >= 3, "Q07_REPLAY_COUNT", str(len(replay_rows)))
    initial = closed(replay_rows[0], {"case", "observedAt", "status", "body"}, "Q07_SCHEMA")
    require(initial["case"] == "initial", "Q07_SCHEMA", "initial first")
    initial_body = public(initial["body"], str(manifest["activityId"]), "Q07_PUBLIC_BINDING")
    initial_class = classification(
        int(initial["status"]), initial_body, str(manifest["activityId"])
    )
    require(initial_class == "accepted", "Q07_INITIAL", "control was rejected")
    terminal: list[dict[str, Any]] = []
    for row in replay_rows[1:]:
        row = closed(row, {"case", "observedAt", "status", "body"}, "Q07_SCHEMA")
        if row["case"] != "replay" or str(row["observedAt"]) < str(manifest["settleCutoff"]):
            reject("Q07_REPLAY_TIMING", repr(row["observedAt"]))
        body = public(row["body"], str(manifest["activityId"]), "Q07_PUBLIC_BINDING")
        classification(int(row["status"]), body, str(manifest["activityId"]))
        require(bool(body["replay"]), "Q07_REPLAY_FLAG", "false")
        terminal.append(body)
    stable = "reservationLocatorHash activityId quantity activityProjectionVersion".split()
    frozen = "state decisionCode projectionVersion durableOrderCreated replay".split()
    frozen.append("orderLocatorHash")
    if any(body[key] != initial_body[key] for body in terminal for key in stable):
        reject("Q07_IDENTITY_BINDING", "initial/replay mismatch")
    if any(body[key] != terminal[0][key] for body in terminal[1:] for key in frozen):
        reject("Q07_TERMINAL_REPLAY", "terminal fields changed")
    detail = one(root, "Q07a")
    if detail.get("reservation_locator_hash") != terminal[0]["reservationLocatorHash"]:
        reject("Q07_IDENTITY_BINDING", "durable reservation mismatch")
    require(detail.get("activity_id") == manifest["activityId"], "Q07_ACTIVITY_BINDING", "mismatch")
    require(
        int_text(detail.get("quantity", ""), "Q07_QUANTITY_BINDING") == 1, "Q07_QUANTITY_BINDING"
    )
    require(
        int_text(detail.get("activity_projection_version", ""), "Q07_VERSION_BINDING") == 1,
        "Q07_VERSION_BINDING",
    )
    pairs = {"state": "state", "decision_code": "decisionCode"}
    pairs.update(
        {"projection_version": "projectionVersion", "order_locator_hash": "orderLocatorHash"}
    )
    for durable_key, public_key in pairs.items():
        value: Any = detail.get(durable_key) or None
        if durable_key == "projection_version":
            value = int_text(str(detail.get(durable_key)), "Q07_DURABLE_BINDING")
        require(value == terminal[0][public_key], "Q07_DURABLE_BINDING", durable_key)
    effect_fields = "order_count create_movement_count cancel_movement_count"
    effect_fields += " movement_linkage_mismatches"
    effects = numbers(detail, effect_fields, "Q07_DURABLE_BINDING")
    if terminal[0]["state"] != "CANCELLED" or effects != [1, 1, 1, 0]:
        reject("Q07_DURABLE_BINDING", "effect binding")
    zeros(one(root, "Q07b"), "Q07_DUPLICATE_EFFECT")
    ownership = jsonl(root / "raw/controls/q08.jsonl")
    require(len(ownership) == 3, "Q08_CONTROL_COUNT", str(len(ownership)))
    ownership_fields = set("case status reservationLocatorHash body".split())
    owner = closed(ownership[0], ownership_fields, "Q08_SCHEMA")
    require(owner["case"] == "owner" and owner["status"] == 200, "Q08_OWNER", repr(owner))
    hash_value(owner["reservationLocatorHash"], "Q08_LOCATOR_HASH")
    owner_body = public(owner["body"], str(manifest["activityId"]), "Q08_OWNER")
    require(owner_body["reservationLocatorHash"] == owner["reservationLocatorHash"], "Q08_OWNER")
    errors = []
    for case, control in zip(("unknown", "other-owner"), ownership[1:], strict=True):
        row = closed(control, ownership_fields, "Q08_SCHEMA")
        if row["case"] != case or row["status"] != 404:
            reject("Q08_404_STATUS", repr(row))
        hash_value(row["reservationLocatorHash"], "Q08_LOCATOR_HASH")
        body = closed(row["body"], {"category", "message"}, "Q08_ERROR_KEYSET")
        text(body["category"], "Q08_ERROR_STRING")
        text(body["message"], "Q08_ERROR_STRING")
        errors.append(body)
    require(errors[0] == errors[1], "Q08_DISCLOSURE_EQUALITY", repr(errors))
    q08 = closed(
        json_file(root / "raw/reconciliation/q08.json"),
        {"beforeDigest", "afterDigest"},
        "Q08_DIGEST_SCHEMA",
    )
    if hash_value(q08["beforeDigest"], "Q08_DIGEST") != hash_value(
        q08["afterDigest"], "Q08_DIGEST"
    ):
        reject("Q08_MUTATION", "canonical row changed")
    zeros(one(root, "Q09"), "Q09_DURABLE_WORK")
    residue = closed(
        json_file(root / "raw/residue.json"),
        set("projectDigest cleanupStatus containers networks volumes children paths".split()),
        "Q09_RESIDUE_SCHEMA",
    )
    hash_value(residue["projectDigest"], "Q09_PROJECT_HASH")
    if residue["cleanupStatus"] != "PASS" or any(
        residue[key] != 0 for key in ("containers", "networks", "volumes")
    ):
        reject("Q09_RESIDUE", repr(residue))
    for name in ("children", "paths"):
        entries = residue[name]
        if not isinstance(entries, list) or not entries:
            reject("Q09_EMPTY_INVENTORY", name)
        kinds: set[str] = set()
        for item in entries:
            item = closed(item, {"kind", "absent"}, "Q09_INVENTORY_SCHEMA")
            kind = text(item["kind"], "Q09_INVENTORY_SCHEMA", 80)
            require(kind not in kinds and item["absent"] is True, "Q09_INVENTORY_SCHEMA", kind)
            kinds.add(kind)
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
    if seconds <= 0:
        reject("MEASURED_DURATION", repr(seconds))
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
    markers = (b"Authorization:", b"Bearer ", b"BEGIN PRIVATE KEY", b'"accessToken"')
    for relative in files:
        if relative == "checksums.sha256":
            continue
        payload = bounded(root / relative, 50 * 1024 * 1024)
        if any(marker in payload for marker in markers):
            reject("SECRET_SCAN", relative)
        if relative.startswith("raw/"):
            content = payload.decode("utf-8")
            if UUID.search(content) or "/Users/" in content or "/private/" in content:
                reject("RAW_LOCATOR_OR_PATH", relative)


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
    if manifest["measuredDuration"] != duration:
        reject("MANIFEST_DURATION", repr(manifest["measuredDuration"]))
    return rebuilt


if __name__ == "__main__":
    require(len(sys.argv) == 2, "CLI_USAGE")
    print(json.dumps(verify_bundle(Path(sys.argv[1])), sort_keys=True, separators=(",", ":")))
