import csv
import hashlib
import json
import sys
import urllib.request
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any, cast

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import check_cb155_measurement as checker  # noqa: E402
import measure_cb155 as runner  # noqa: E402


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def public_body(
    locator: str, activity: str, *, state: str = "REJECTED", replay: bool = False
) -> dict[str, Any]:
    admitted = state in {"ADMITTED", "CANCELLED"}
    cancelled = state == "CANCELLED"
    return {
        "activityId": activity,
        "quantity": 1,
        "activityProjectionVersion": 1,
        "state": state,
        "decisionCode": "ADMITTED" if admitted else "EXHAUSTED",
        "projectionVersion": 4 if cancelled else 2,
        "replay": replay,
        "durableOrderCreated": cancelled,
        "reservationLocatorHash": digest(locator),
        "orderLocatorHash": digest(f"{locator}-order") if cancelled else None,
    }


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n" for row in rows)
    )


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source))


def record_count(path: Path) -> int:
    if path.suffix == ".csv":
        return len(read_csv(path))
    if path.suffix == ".jsonl":
        return len(path.read_text().splitlines())
    return 1


def write_checksums(bundle: Path) -> None:
    paths = sorted(
        path for path in bundle.rglob("*") if path.is_file() and path.name != "checksums.sha256"
    )
    payload = "".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(bundle).as_posix()}\n"
        for path in paths
    )
    (bundle / "checksums.sha256").write_text(payload)


def refresh(bundle: Path) -> None:
    manifest_path = bundle / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    inventory = []
    for path in sorted((bundle / "raw").rglob("*")):
        if not path.is_file():
            continue
        media = (
            "text/csv"
            if path.suffix == ".csv"
            else "application/x-ndjson"
            if path.suffix == ".jsonl"
            else "application/json"
        )
        inventory.append(
            {
                "path": path.relative_to(bundle).as_posix(),
                "bytes": path.stat().st_size,
                "records": record_count(path),
                "mediaType": media,
            }
        )
    manifest["artifactInventory"] = inventory
    write_json(manifest_path, manifest)
    write_checksums(bundle)


def sample_row(index: int, activity: str, locator: str, *, warmup: bool = False) -> dict[str, Any]:
    state = "REJECTED" if warmup or index > 250 else "ADMITTED"
    body = public_body(locator, activity, state=state)
    accepted = state == "ADMITTED"
    return {
        "sampleIndex": index,
        "startTimestampMs": (900_000 if warmup else 1_000_000) + (index - 1) * 10,
        "elapsedMs": 10,
        "latencyMs": 8,
        "connectMs": 1,
        "responseCode": 201 if accepted else 409,
        "jmeterSuccess": "true" if accepted else "false",
        "producerClassification": "accepted" if accepted else "business_rejected",
        "state": body["state"],
        "decisionCode": body["decisionCode"],
        "activityProjectionVersion": 1,
        "projectionVersion": 2,
        "durableOrderCreated": str(body["durableOrderCreated"]).lower(),
        "replay": "false",
        "reservationLocatorHash": body["reservationLocatorHash"],
        "orderLocatorHash": "",
        "responseBytes": 200,
    }


def build_bundle(root: Path) -> Path:
    bundle = root / "bundle"
    activity = "cb155-activity"
    warmup_activity = "cb155-warmup"
    measured = [sample_row(index, activity, f"measured-{index}") for index in range(1, 501)]
    warmup = [
        sample_row(index, warmup_activity, f"warmup-{index}", warmup=True) for index in range(1, 33)
    ]
    write_csv(bundle / "raw/performance/measured.csv", measured)
    write_csv(bundle / "raw/performance/warmup.csv", warmup)
    write_csv(
        bundle / "raw/reconciliation/q01.csv",
        [
            {
                "activity_id": activity,
                "product_id": "cb155-product",
                "state": "ACTIVE",
                "allocated_quota": 252,
                "projection_version": 1,
                "stock_quantity": 252,
            }
        ],
    )
    write_csv(
        bundle / "raw/reconciliation/q02.csv",
        [
            {
                "total_reservations": 502,
                "distinct_reservations": 502,
                "distinct_user_activity": 502,
                "duplicate_idempotency_groups": 0,
            }
        ],
    )
    write_csv(
        bundle / "raw/reconciliation/q03.csv",
        [
            {
                "pending_count": 0,
                "admitted_count": 0,
                "rejected_count": 250,
                "ordered_count": 0,
                "cancelled_count": 252,
                "unknown_state": 0,
                "overdue_nonterminal": 0,
            }
        ],
    )
    write_csv(
        bundle / "raw/reconciliation/q04.csv",
        [
            {
                "successful_reservations": 252,
                "orders_for_activity": 252,
                "missing_orders": 0,
                "orphan_orders": 0,
                "duplicate_orders": 0,
                "binding_mismatches": 0,
            }
        ],
    )
    write_csv(
        bundle / "raw/reconciliation/q05.csv",
        [
            {
                "bad_create_count": 0,
                "bad_cancel_count": 0,
                "unexpected_cancel_count": 0,
                "bad_quantity_count": 0,
                "unexpected_movement_types": 0,
                "orphan_movements": 0,
                "binding_mismatches": 0,
            }
        ],
    )
    write_csv(
        bundle / "raw/reconciliation/q06.csv",
        [
            {
                "final_stock": 252,
                "expected_final_stock": 252,
                "net_consumed_quota": 0,
                "active_quantity": 0,
                "final_allocated_quota": 252,
                "baseline_allocated_quota": 252,
            }
        ],
    )
    write_csv(
        bundle / "raw/reconciliation/q07-details.csv",
        [
            {
                "activity_id": activity,
                "quantity": 1,
                "activity_projection_version": 1,
                "state": "CANCELLED",
                "decision_code": "ADMITTED",
                "projection_version": 4,
                "order_count": 1,
                "create_movement_count": 1,
                "cancel_movement_count": 1,
                "movement_linkage_mismatches": 0,
                "reservation_locator_hash": digest("q07"),
                "order_locator_hash": digest("q07-order"),
            }
        ],
    )
    write_csv(
        bundle / "raw/reconciliation/q07-duplicates.csv",
        [{"duplicate_reservation_keys": 0, "duplicate_order_keys": 0, "duplicate_ledger_keys": 0}],
    )
    write_json(
        bundle / "raw/reconciliation/q08.json",
        {"beforeDigest": digest("q08-row"), "afterDigest": digest("q08-row")},
    )
    write_csv(
        bundle / "raw/reconciliation/q09.csv",
        [
            {
                "overdue_reservation_resolution": 0,
                "overdue_unpaid_orders": 0,
                "overdue_timeout_dispatch": 0,
                "failed_timeout_dispatch": 0,
            }
        ],
    )
    public_rows = [
        public_body(
            f"measured-{index}", activity, state="CANCELLED" if index <= 250 else "REJECTED"
        )
        for index in range(1, 501)
    ]
    public_rows.extend(
        [
            public_body("q07", activity, state="CANCELLED"),
            public_body("q08", activity, state="CANCELLED"),
        ]
    )
    q04 = [{"public": row, "durable": dict(row)} for row in public_rows]
    write_jsonl(bundle / "raw/controls/q04.jsonl", q04)
    write_jsonl(
        bundle / "raw/controls/q07.jsonl",
        [
            {
                "case": "initial",
                "observedAt": "2026-01-01T00:00:00.000000Z",
                "status": 201,
                "body": public_body("q07", activity, state="ADMITTED"),
            },
            {
                "case": "replay",
                "observedAt": "2026-01-01T00:02:00.000000Z",
                "status": 200,
                "body": public_body("q07", activity, state="CANCELLED", replay=True),
            },
            {
                "case": "replay",
                "observedAt": "2026-01-01T00:02:01.000000Z",
                "status": 200,
                "body": public_body("q07", activity, state="CANCELLED", replay=True),
            },
        ],
    )
    error = {"category": "NOT_FOUND", "message": "Reservation not found"}
    write_jsonl(
        bundle / "raw/controls/q08.jsonl",
        [
            {
                "case": "owner",
                "status": 200,
                "reservationLocatorHash": digest("q08"),
                "body": public_body("q08", activity, state="CANCELLED"),
            },
            {
                "case": "unknown",
                "status": 404,
                "reservationLocatorHash": digest("unknown"),
                "body": error,
            },
            {
                "case": "other-owner",
                "status": 404,
                "reservationLocatorHash": digest("q08"),
                "body": error,
            },
        ],
    )
    write_json(
        bundle / "raw/controls/q09.json",
        {
            "expectedSamples": 500,
            "actualSamples": 500,
            "unexpectedError": 0,
            "unknownClassification": 0,
            "lostSample": 0,
        },
    )
    write_json(
        bundle / "raw/residue.json",
        {
            "projectDigest": digest("project"),
            "cleanupStatus": "PASS",
            "containers": 0,
            "networks": 0,
            "volumes": 0,
            "children": [{"kind": "auth", "absent": True}, {"kind": "commerce", "absent": True}],
            "paths": [
                {"kind": "auth_log", "absent": True},
                {"kind": "commerce_log", "absent": True},
                {"kind": "docker_client_config", "absent": True},
                {"kind": "jmeter_archive", "absent": True},
                {"kind": "jmeter_checksum", "absent": True},
                {"kind": "jmeter_install", "absent": True},
                {"kind": "measured_temporary_jtl", "absent": True},
                {"kind": "measured_token_input", "absent": True},
                {"kind": "rsa_private_key", "absent": True},
                {"kind": "rsa_public_key", "absent": True},
                {"kind": "run_env", "absent": True},
                {"kind": "warmup_temporary_jtl", "absent": True},
                {"kind": "warmup_token_input", "absent": True},
                {"kind": "temporary_directory", "absent": True},
            ],
        },
    )
    manifest = {
        "schemaVersion": "cb155-manifest-v1",
        "sliceId": "CB-155",
        "codeRevision": "a" * 40,
        "environment": {
            "scope": "local-docker-compose",
            "operatingSystem": "test",
            "architecture": "test",
            "runtime": "test",
        },
        "machine": {"cpuCount": 1, "memoryBytes": 1, "dockerVersion": "test"},
        "containerResources": {
            "composeVersion": "test",
            "declaredCpuLimit": "none",
            "declaredMemoryLimit": "none",
        },
        "fixtureOrDatasetVersion": "cb155-fixture-v1",
        "tool": "Apache JMeter",
        "toolVersion": "5.6.3",
        "warmup": {
            "activityId": warmup_activity,
            "productId": "cb155-warmup-product",
            "baselineQuota": 1,
            "baselineStock": 1,
            "sampleCount": 32,
            "threads": 8,
            "loopsPerThread": 4,
            "rampSeconds": 2,
            "quantity": 2,
            "expectedActivityVersion": 1,
        },
        "measuredDuration": {
            "startTimestampMs": 1_000_000,
            "endTimestampMs": 1_005_000,
            "seconds": 5.0,
        },
        "concurrencyOrWorkload": {
            "profileId": "cb155-formal-v1",
            "samples": 500,
            "threads": 64,
            "loopsPerThread": 8,
            "csvRows": 500,
            "rampSeconds": 5,
            "quantity": 1,
            "expectedActivityVersion": 1,
        },
        "sampleCount": 500,
        "commands": ["make measure-cb155"],
        "artifactInventory": [],
        "cleanupResult": {
            "status": "PASS",
            "containers": 0,
            "networks": 0,
            "volumes": 0,
            "children": 0,
            "pathsAbsent": True,
        },
        "activityId": activity,
        "productId": "cb155-product",
        "activityProjectionVersion": 1,
        "baselineActivityState": "ACTIVE",
        "baselineAllocatedQuota": 252,
        "baselineProductStock": 252,
        "settleCutoff": "2026-01-01T00:01:59.000000Z",
        "observationAt": "2026-01-01T00:02:02.000000Z",
        "dispatchSettleCutoff": "2026-01-01T00:02:02.000000Z",
        "unpaidTimeoutSeconds": 120,
        "settlementTimeoutSeconds": 300,
        "jmeterConnectTimeoutMs": 2000,
        "jmeterResponseTimeoutMs": 10000,
        "runOrder": (
            "build acquire-jmeter init-local up fixtures auth commerce q01 warmup controls "
            "measured settlement q02-q09 cleanup reconstruct publish"
        ).split(),
    }
    result = {
        "schemaVersion": "cb155-result-v1",
        "sliceId": "CB-155",
        "profileId": "cb155-formal-v1",
        "codeRevision": "a" * 40,
        "valid": True,
        "sampleCount": 500,
        "measuredDurationSeconds": 5.0,
        "achievedQps": 100.0,
        "latencyMs": {"p50": 10, "p95": 10, "p99": 10},
        "httpStatusDistribution": {"201": 250, "409": 250},
        "stateDecisionDistribution": {"ADMITTED/ADMITTED": 250, "REJECTED/EXHAUSTED": 250},
        "errorDistribution": {
            "transport": 0,
            "parse": 0,
            "contract": 0,
            "unexpectedError": 0,
            "unknownClassification": 0,
            "lostSample": 0,
        },
        "q01Q09": {f"Q{index:02d}": "PASS" for index in range(1, 10)},
    }
    write_json(bundle / "manifest.json", manifest)
    write_json(bundle / "result.json", result)
    refresh(bundle)
    return bundle


def mutate_json(bundle: Path, relative: str, mutation: Callable[[Any], None]) -> None:
    path = bundle / relative
    value = (
        json.loads(path.read_text())
        if path.suffix == ".json"
        else [json.loads(line) for line in path.read_text().splitlines()]
    )
    mutation(value)
    if path.suffix == ".json":
        write_json(path, value)
    else:
        write_jsonl(path, value)
    refresh(bundle)


def mutate_csv(
    bundle: Path, relative: str, mutation: Callable[[list[dict[str, str]]], None]
) -> None:
    path = bundle / relative
    rows = read_csv(path)
    mutation(rows)
    write_csv(path, rows)
    refresh(bundle)


def assert_rejected(bundle: Path, code: str) -> None:
    with pytest.raises(checker.BundleError, match=rf"^{code}:"):
        checker.verify_bundle(bundle)


def test_valid_bundle_reconstructs(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    assert checker.verify_bundle(bundle)["achievedQps"] == 100.0


def test_frozen_sql_matches_specification_exactly() -> None:
    source = (Path(__file__).parents[1] / "docs/slices/CB-155.md").read_text()
    blocks = source.split("```sql\n")[1:]
    expected = [block.split("\n```", 1)[0] for block in blocks]
    assert list(runner.SQL_BLOCKS) == [
        "Q01",
        "Q02",
        "Q03",
        "Q04",
        "Q05",
        "Q06",
        "Q07a",
        "Q07b",
        "Q08",
        "Q09",
    ]
    assert list(runner.SQL_BLOCKS.values()) == expected
    assert sum(sum(bool(line.strip()) for line in sql.splitlines()) for sql in expected) == 174


@pytest.mark.parametrize(
    ("mutation", "code"),
    [
        (lambda value: value.pop("jmeterResponseTimeoutMs"), "MANIFEST_SCHEMA"),
        (lambda value: value["environment"].update({"unknown": True}), "MANIFEST_ENVIRONMENT"),
        (lambda value: value.update({"commands": []}), "MANIFEST_RUNTIME_PARAMETER"),
        (lambda value: value.update({"runOrder": []}), "MANIFEST_RUNTIME_PARAMETER"),
        (lambda value: value.update({"settleCutoff": "zzzz"}), "MANIFEST_TIMESTAMP"),
        (lambda value: value["environment"].update({"scope": True}), "MANIFEST_FACT"),
        (lambda value: value["machine"].update({"cpuCount": True}), "MANIFEST_FACT"),
        (
            lambda value: value["containerResources"].update({"composeVersion": True}),
            "MANIFEST_FACT",
        ),
    ],
)
def test_manifest_false_green_rejections(
    tmp_path: Path, mutation: Callable[[Any], None], code: str
) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(bundle, "manifest.json", mutation)
    assert_rejected(bundle, code)


def test_contradictory_public_sample_is_rejected(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_csv(
        bundle,
        "raw/performance/measured.csv",
        lambda rows: rows[0].update({"projectionVersion": "3"}),
    )
    assert_rejected(bundle, "PUBLIC_SAMPLE_CONTRACT")


def test_malformed_response_bytes_is_rejected(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_csv(
        bundle,
        "raw/performance/measured.csv",
        lambda rows: rows[0].update({"responseBytes": "not-an-integer"}),
    )
    assert_rejected(bundle, "SAMPLE_BODY")


def test_noncanonical_uuid_is_rejected_by_producer() -> None:
    value = str(uuid.uuid4()).upper()
    with pytest.raises(RuntimeError, match=r"^NON_CANONICAL_UUID:"):
        runner.canonical_uuid(value, "NON_CANONICAL_UUID")


def test_nonhex_sample_locator_is_rejected(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_csv(
        bundle,
        "raw/performance/measured.csv",
        lambda rows: rows[0].update({"reservationLocatorHash": "z" * 64}),
    )
    assert_rejected(bundle, "PUBLIC_SAMPLE_CONTRACT")


def test_malformed_public_primitive_is_rejected(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(
        bundle,
        "raw/controls/q04.jsonl",
        lambda rows: rows[0]["public"].update({"quantity": True}),
    )
    assert_rejected(bundle, "Q04_PUBLIC_BINDING")


@pytest.mark.parametrize(
    ("mutation", "code"),
    [
        (lambda rows: rows.pop(), "Q04_CONTROL_COVERAGE"),
        (lambda rows: rows.__setitem__(1, rows[0]), "Q04_CONTROL_DUPLICATE"),
    ],
)
def test_q04_missing_and_duplicate_controls(
    tmp_path: Path, mutation: Callable[[Any], None], code: str
) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(bundle, "raw/controls/q04.jsonl", mutation)
    assert_rejected(bundle, code)


def test_q04_incomplete_durable_coverage(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_csv(
        bundle,
        "raw/reconciliation/q03.csv",
        lambda rows: rows[0].update(
            {"rejected_count": "250", "ordered_count": "1", "cancelled_count": "251"}
        ),
    )
    assert_rejected(bundle, "Q04_CONTROL_COVERAGE")


def test_q04_public_durable_binding_mismatch(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(
        bundle,
        "raw/controls/q04.jsonl",
        lambda rows: rows[0]["durable"].update({"reservationLocatorHash": "0" * 64}),
    )
    assert_rejected(bundle, "Q04_PUBLIC_DURABLE")


def test_q04_locator_set_must_cover_measured_and_controls(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)

    def replace_locator(rows: list[dict[str, Any]]) -> None:
        replacement = digest("unrelated-reservation")
        rows[0]["public"]["reservationLocatorHash"] = replacement
        rows[0]["durable"]["reservationLocatorHash"] = replacement

    mutate_json(bundle, "raw/controls/q04.jsonl", replace_locator)
    assert_rejected(bundle, "Q04_CONTROL_COVERAGE")


@pytest.mark.parametrize(
    ("field", "value", "code"),
    [
        ("reservation_locator_hash", "0" * 64, "Q07_IDENTITY_BINDING"),
        ("activity_id", "wrong-activity", "Q07_ACTIVITY_BINDING"),
        ("quantity", "2", "Q07_QUANTITY_BINDING"),
        ("activity_projection_version", "2", "Q07_VERSION_BINDING"),
        ("create_movement_count", "0", "Q07_DURABLE_BINDING"),
        ("cancel_movement_count", "0", "Q07_DURABLE_BINDING"),
    ],
)
def test_q07_durable_binding_mutations(tmp_path: Path, field: str, value: str, code: str) -> None:
    bundle = build_bundle(tmp_path)
    mutate_csv(
        bundle, "raw/reconciliation/q07-details.csv", lambda rows: rows[0].update({field: value})
    )
    assert_rejected(bundle, code)


def test_q07_replay_before_settlement(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(
        bundle,
        "raw/controls/q07.jsonl",
        lambda rows: rows[1].update({"observedAt": "2026-01-01T00:01:58.000000Z"}),
    )
    assert_rejected(bundle, "Q07_REPLAY_TIMING")


@pytest.mark.parametrize(
    "observed_at", ["zzzz", "2026-01-01T00:02:00.1Z", "2099-01-01T00:00:00.000000Z"]
)
def test_q07_malformed_or_future_replay_timestamp_is_rejected(
    tmp_path: Path, observed_at: str
) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(
        bundle,
        "raw/controls/q07.jsonl",
        lambda rows: rows[1].update({"observedAt": observed_at}),
    )
    assert_rejected(bundle, "Q07_REPLAY_TIMING")


def test_q07_string_status_is_rejected(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(bundle, "raw/controls/q07.jsonl", lambda rows: rows[1].update({"status": "200"}))
    assert_rejected(bundle, "Q07_STATUS")


def test_runner_rejects_q07_canonical_order_mismatch() -> None:
    detail = {
        "reservation_id": "reservation",
        "order_id": "order",
        "canonical_order_id": "different-order",
    }
    with pytest.raises(RuntimeError, match=r"^Q07_CANONICAL_ORDER:"):
        runner.sanitize_q07_detail(detail)


@pytest.mark.parametrize(
    ("mutation", "code"),
    [
        (lambda rows: rows[1]["body"].pop("message"), "Q08_ERROR_KEYSET"),
        (lambda rows: rows[1]["body"].update({"category": None}), "Q08_ERROR_STRING"),
        (lambda rows: rows[2].update({"status": 403}), "Q08_404_STATUS"),
        (lambda rows: rows[2]["body"].update({"category": "DIFFERENT"}), "Q08_DISCLOSURE_EQUALITY"),
        (lambda rows: rows[1].update({"reservationLocatorHash": "g" * 64}), "Q08_LOCATOR_HASH"),
    ],
)
def test_q08_disclosure_mutations(
    tmp_path: Path, mutation: Callable[[Any], None], code: str
) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(bundle, "raw/controls/q08.jsonl", mutation)
    assert_rejected(bundle, code)


@pytest.mark.parametrize("case", ["unknown", "unknown-existing", "other-owner"])
def test_q08_locator_ownership_binding(tmp_path: Path, case: str) -> None:
    bundle = build_bundle(tmp_path)

    def break_binding(rows: list[dict[str, Any]]) -> None:
        owner = rows[0]["reservationLocatorHash"]
        target = 1 if case.startswith("unknown") else 2
        replacements = {"unknown": owner, "unknown-existing": digest("measured-1")}
        rows[target]["reservationLocatorHash"] = replacements.get(case, digest("wrong"))

    mutate_json(bundle, "raw/controls/q08.jsonl", break_binding)
    assert_rejected(bundle, "Q08_LOCATOR_BINDING")


def test_q08_owner_status_body_classification(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(
        bundle,
        "raw/controls/q08.jsonl",
        lambda rows: rows[0].update({"body": public_body("q08", "cb155-activity")}),
    )
    assert_rejected(bundle, "PUBLIC_SAMPLE_CONTRACT")


def test_q08_owner_must_equal_q04_terminal_body(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(
        bundle,
        "raw/controls/q08.jsonl",
        lambda rows: rows[0]["body"].update({"orderLocatorHash": digest("different-order")}),
    )
    assert_rejected(bundle, "Q08_OWNER_BINDING")


@pytest.mark.parametrize(
    "relative",
    [
        "raw/reconciliation/q05.csv",
        "raw/reconciliation/q06.csv",
        "raw/reconciliation/q07-duplicates.csv",
        "raw/reconciliation/q09.csv",
    ],
)
def test_reconciliation_requires_exact_headers(tmp_path: Path, relative: str) -> None:
    bundle = build_bundle(tmp_path)
    mutate_csv(bundle, relative, lambda rows: rows[0].update({"unexpected": "0"}))
    assert_rejected(bundle, "CSV_HEADER")


def test_q06_missing_stock_columns_is_rejected(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)

    def remove_stock(rows: list[dict[str, str]]) -> None:
        rows[0].pop("final_stock")
        rows[0].pop("expected_final_stock")

    mutate_csv(bundle, "raw/reconciliation/q06.csv", remove_stock)
    assert_rejected(bundle, "CSV_HEADER")


@pytest.mark.parametrize("field", ["children", "paths"])
def test_q09_empty_inventory(tmp_path: Path, field: str) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(bundle, "raw/residue.json", lambda value: value.update({field: []}))
    assert_rejected(bundle, "Q09_EMPTY_INVENTORY")


@pytest.mark.parametrize("field", ["children", "paths"])
def test_q09_inventory_requires_exact_owned_kinds(tmp_path: Path, field: str) -> None:
    bundle = build_bundle(tmp_path)
    mutate_json(bundle, "raw/residue.json", lambda value: value[field].pop())
    assert_rejected(bundle, "Q09_INVENTORY_SET")


def test_q09_inventory_rejects_arbitrary_path_kind(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    replacement = [{"kind": "fake", "absent": True}]
    mutate_json(bundle, "raw/residue.json", lambda value: value.update({"paths": replacement}))
    assert_rejected(bundle, "Q09_INVENTORY_SET")


@pytest.mark.parametrize(
    ("message", "code"),
    [
        (
            ".".join(("eyJhbGciOiJIUzI1NiJ9", "eyJzdWIiOiJjYjE1NSJ9", "signature123")),
            "SECRET_SCAN",
        ),
        ('{"password":"secret"}', "SECRET_SCAN"),
        ('{"Authorization":"secret"}', "SECRET_SCAN"),
        ("private endpoint http://127.0.0.1:8080/internal", "SECRET_SCAN"),
        ("raw identity cb155-subject-001", "RAW_LOCATOR_OR_PATH"),
        ("temporary file /tmp/cb155-secret", "RAW_LOCATOR_OR_PATH"),
        ("temporary file /var/folders/aa/cb155-secret", "RAW_LOCATOR_OR_PATH"),
        ("temporary file /var/tmp/cb155-secret", "RAW_LOCATOR_OR_PATH"),
    ],
)
def test_q08_error_payload_sanitization(tmp_path: Path, message: str, code: str) -> None:
    bundle = build_bundle(tmp_path)

    def inject(rows: list[dict[str, Any]]) -> None:
        rows[1]["body"]["message"] = message
        rows[2]["body"]["message"] = message

    mutate_json(bundle, "raw/controls/q08.jsonl", inject)
    assert_rejected(bundle, code)


@pytest.mark.parametrize(
    "escaped", [rb"p\u0061ssword=secret", rb"http:\/\/127.0.0.1:8080/internal"]
)
def test_q08_noncanonical_escape_cannot_hide_secret(tmp_path: Path, escaped: bytes) -> None:
    bundle = build_bundle(tmp_path)
    path = bundle / "raw/controls/q08.jsonl"
    path.write_bytes(path.read_bytes().replace(b"Reservation not found", escaped))
    refresh(bundle)
    assert_rejected(bundle, "SECRET_SCAN")


def test_declared_actual_record_mismatch_reaches_record_guard(tmp_path: Path) -> None:
    bundle = build_bundle(tmp_path)
    manifest = json.loads((bundle / "manifest.json").read_text())
    row = next(item for item in manifest["artifactInventory"] if item["path"].endswith("q09.csv"))
    row["records"] = 2
    write_json(bundle / "manifest.json", manifest)
    write_checksums(bundle)
    assert_rejected(bundle, "INVENTORY_RECORD_COUNT")


def test_stat_bound_precedes_payload_materialization(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    bundle = tmp_path / "oversized"
    (bundle / "raw").mkdir(parents=True)
    with (bundle / "raw/huge.bin").open("wb") as target:
        target.seek(55 * 1024 * 1024)
        target.write(b"x")
    monkeypatch.setattr(checker, "bounded", lambda *_args, **_kwargs: pytest.fail("materialized"))
    assert_rejected(bundle, "BUNDLE_STAT_LIMIT")


class FakeResponse:
    def __init__(self, payload: bytes, length: int | None = None) -> None:
        self.status = 200
        self.headers = {} if length is None else {"Content-Length": str(length)}
        self.payload = payload
        self.offset = 0

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def geturl(self) -> str:
        return "https://downloads.apache.org/final"

    def read(self, size: int) -> bytes:
        chunk = self.payload[self.offset : self.offset + size]
        self.offset += len(chunk)
        return chunk


def test_download_rejects_early_eof(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        urllib.request, "urlopen", lambda *_args, **_kwargs: FakeResponse(b"short", 10)
    )
    with pytest.raises(RuntimeError, match=r"DOWNLOAD_FAILED: .*DOWNLOAD_EOF"):
        runner.download("https://downloads.apache.org/test", tmp_path / "archive")


def test_acquisition_rejects_complete_wrong_digest(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    checksum = f"{runner.JMETER_SHA512}  {runner.JMETER_ARCHIVE}\n".encode()

    def response(url: str, **_kwargs: Any) -> FakeResponse:
        payload = checksum if str(url).endswith(".sha512") else b"complete-wrong-archive"
        return FakeResponse(payload, len(payload))

    monkeypatch.setattr(urllib.request, "urlopen", response)
    state = runner.State(temp=tmp_path, project="cb155-test")
    with pytest.raises(RuntimeError, match=r"^JMETER_DIGEST:"):
        runner.acquire_jmeter(state)


def test_download_accepts_absent_length_with_correct_digest(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    payload = b"complete-body"
    monkeypatch.setattr(urllib.request, "urlopen", lambda *_args, **_kwargs: FakeResponse(payload))
    destination = tmp_path / "archive"
    assert (
        runner.download("https://downloads.apache.org/test", destination)
        == hashlib.sha512(payload).hexdigest()
    )
    assert destination.read_bytes() == payload


def test_preinit_failure_never_calls_missing_env_reset(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    temporary = tmp_path / "run"
    temporary.mkdir()
    state = runner.State(temp=temporary, env=temporary / "run.env", project="cb155-preinit")
    state.owned_paths["run_env"] = cast(Path, state.env)
    commands: list[list[str]] = []
    monkeypatch.setattr(runner, "command", lambda arguments, **_kwargs: commands.append(arguments))
    monkeypatch.setattr(runner, "resource_count", lambda *_args: 0)
    residue, error = runner.cleanup(state)
    assert error is None
    assert commands == []
    assert {row["kind"] for row in residue["paths"]} >= {"run_env", "run_env_never_created"}
