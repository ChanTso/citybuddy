from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import check_cb152_bundle as checker  # noqa: E402
import measure_cb152 as measure  # noqa: E402


def write_valid_bundle(tmp_path: Path, *, q05_failure: bool = False, residue: int = 0) -> Path:
    bundle = tmp_path / "CB-152"
    raw: dict[str, bytes] = {
        "raw/measured.jsonl": measure.canonical_jsonl(
            [
                {
                    "sampleIndex": 1,
                    "startTimestampMs": 1_000,
                    "elapsedMs": 100,
                    "latencyMs": 90,
                    "connectTimeMs": 10,
                    "responseCode": 409,
                    "jmeterSuccess": False,
                    "classification": "BUSINESS",
                    "state": "REJECTED",
                    "decisionCode": "EXHAUSTED",
                    "projectionVersion": 2,
                    "durableOrderCreated": False,
                    "replay": False,
                    "reservationLocatorHash": "a" * 64,
                    "orderLocatorHash": None,
                    "responseBytes": 200,
                }
            ]
        ),
        "raw/fixture.json": measure.canonical_json(
            {
                "fixtureVersion": "cb152-seckill-v1",
                "activityId": "activity",
                "productId": "product",
                "activityProjectionVersion": 1,
                "baselineAllocatedQuota": 1,
                "baselineProductStock": 1,
                "warmupIsolation": "separate activity and product",
            }
        ),
        "raw/reconciliation/q01.csv": measure.canonical_csv(
            [
                "activity_id",
                "product_id",
                "state",
                "allocated_quota",
                "projection_version",
                "stock_quantity",
            ],
            [["activity", "product", "ACTIVE", 1, 1, 1]],
        ),
        "raw/reconciliation/q02.csv": measure.canonical_csv(
            [
                "total_reservations",
                "distinct_reservations",
                "distinct_user_activity",
                "duplicate_idempotency_groups",
            ],
            [[1, 1, 1, 0]],
        ),
        "raw/reconciliation/q03.csv": measure.canonical_csv(
            [
                "pending_count",
                "admitted_count",
                "rejected_count",
                "ordered_count",
                "cancelled_count",
                "unknown_state",
                "overdue_nonterminal",
            ],
            [[0, 0, 1, 0, 0, 0, 0]],
        ),
        "raw/reconciliation/q04.csv": measure.canonical_csv(
            [
                "successful_reservations",
                "orders_for_activity",
                "missing_orders",
                "orphan_orders",
                "duplicate_orders",
                "binding_mismatches",
                "public_binding_mismatches",
            ],
            [[0, 0, 0, 0, 0, 0, 0]],
        ),
        "raw/controls/q04.jsonl": measure.canonical_jsonl(
            [
                {
                    "reservationLocatorHash": "a" * 64,
                    "state": "REJECTED",
                    "durableOrderCreated": False,
                    "orderLocatorHash": None,
                }
            ]
        ),
        "raw/reconciliation/q05.csv": measure.canonical_csv(
            [
                "bad_create_count",
                "bad_cancel_count",
                "unexpected_cancel_count",
                "bad_quantity_count",
                "unexpected_movement_types",
                "orphan_movements",
                "binding_mismatches",
            ],
            [[int(q05_failure), 0, 0, 0, 0, 0, 0]],
        ),
        "raw/reconciliation/q06.csv": measure.canonical_csv(
            [
                "final_stock",
                "expected_final_stock",
                "net_consumed_quota",
                "active_quantity",
                "final_allocated_quota",
                "baseline_allocated_quota",
            ],
            [[1, 1, 0, 0, 1, 1]],
        ),
        "raw/controls/q07.jsonl": measure.canonical_jsonl(
            [
                {
                    "caseId": "case",
                    "phase": phase,
                    "reservationLocatorHash": "a" * 64,
                    "activityId": "activity",
                    "quantity": 1,
                    "activityProjectionVersion": 1,
                    "state": "REJECTED",
                    "decisionCode": "EXHAUSTED",
                    "projectionVersion": 2,
                    "durableOrderCreated": False,
                    "orderLocatorHash": None,
                    "replay": phase != "initial",
                }
                for phase in ("initial", "replay1", "replay2")
            ]
        ),
        "raw/reconciliation/q07-details.csv": measure.canonical_csv(
            [
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
            ],
            [["a" * 64, "activity", 1, 1, "REJECTED", "EXHAUSTED", 2, "", 0, "", 0, 0, 0]],
        ),
        "raw/reconciliation/q07-duplicates.csv": measure.canonical_csv(
            ["duplicate_reservation_keys", "duplicate_order_keys", "duplicate_ledger_keys"],
            [[0, 0, 0]],
        ),
        "raw/controls/q08.jsonl": measure.canonical_jsonl(
            [
                {
                    "kind": "owner",
                    "status": 200,
                    "category": None,
                    "message": None,
                    "reservationLocatorHash": "b" * 64,
                },
                {
                    "kind": "unknown",
                    "status": 404,
                    "category": "VALIDATION",
                    "message": "Reservation is missing or not owned",
                    "reservationLocatorHash": "c" * 64,
                },
                {
                    "kind": "other-owner",
                    "status": 404,
                    "category": "VALIDATION",
                    "message": "Reservation is missing or not owned",
                    "reservationLocatorHash": "b" * 64,
                },
            ]
        ),
        "raw/reconciliation/q08.json": measure.canonical_json(
            {"beforeDigest": "d" * 64, "afterDigest": "d" * 64}
        ),
        "raw/controls/q09.json": measure.canonical_json(
            {
                "unexpectedError": 0,
                "unknownClassification": 0,
                "measuredSampleCount": 1,
                "expectedSampleCount": 1,
            }
        ),
        "raw/reconciliation/q09.csv": measure.canonical_csv(
            [
                "overdue_reservation_resolution",
                "overdue_unpaid_orders",
                "overdue_timeout_dispatch",
                "failed_timeout_dispatch",
            ],
            [[0, 0, 0, 0]],
        ),
        "raw/residue.json": measure.canonical_json(
            {
                "project": "e" * 64,
                "containers": residue,
                "networks": 0,
                "volumes": 0,
                "childPids": [{"kind": "auth", "absent": True}],
                "absentPaths": [{"kind": "generatedEnvFile", "absent": True}],
            }
        ),
    }
    inventory = [
        {
            "path": path,
            "bytes": len(data),
            "records": measure.record_count(path, data),
        }
        for path, data in sorted(raw.items())
    ]
    manifest = {
        "schemaVersion": 1,
        "sliceId": "CB-152",
        "codeRevision": "f" * 40,
        "environment": "test",
        "machine": {},
        "containerResources": {},
        "fixtureOrDatasetVersion": "cb152-seckill-v1",
        "tool": "Apache JMeter",
        "toolVersion": "5.6.3",
        "toolArchiveUrl": measure.JMETER_URL,
        "toolArchiveSha512": measure.JMETER_SHA512,
        "warmup": {},
        "measuredDuration": {},
        "concurrencyOrWorkload": {},
        "sampleCount": 1,
        "commands": [],
        "artifactInventory": {
            "maxRawBytes": measure.MAX_RAW_BYTES,
            "maxFiles": measure.MAX_FILES,
            "maxRecordsPerFile": measure.MAX_RECORDS,
            "files": inventory,
        },
        "cleanupResult": "PASS",
        "activityId": "activity",
        "productId": "product",
        "activityProjectionVersion": 1,
        "baselineActivityState": "ACTIVE",
        "baselineAllocatedQuota": 1,
        "baselineProductStock": 1,
        "settleCutoff": "2026-08-09T00:00:00Z",
        "observationAt": "2026-08-09T00:00:01Z",
        "dispatchSettleCutoff": "2026-08-09T00:00:00Z",
        "runOrder": [],
        "percentileAlgorithm": "nearest-rank",
        "locatorHashAlgorithm": "per-run-domain-separated-sha256",
    }
    payload = raw | {
        "manifest.json": measure.canonical_json(manifest),
        "reconstruct.py": (ROOT / "scripts/check_cb152_bundle.py").read_bytes(),
    }
    measure.write_payload(bundle, payload)
    result = checker.reconstruct(bundle, verify_result=False, verify_integrity=False)
    (bundle / "result.json").write_bytes(measure.canonical_json(result))
    (bundle / "checksums.sha256").write_bytes(measure.checksum_bytes(bundle))
    return bundle


def test_jmeter_checksum_mismatch_is_rejected() -> None:
    with pytest.raises(RuntimeError, match="checksum"):
        measure.validate_jmeter_archive_digest("0" * 128)
    with pytest.raises(RuntimeError, match="checksum"):
        measure.validate_jmeter_checksum(b"0" * 128 + b" *apache-jmeter-5.6.3.tgz\n")


def test_jtl_stream_preserves_every_sample_and_marks_parse_failure(tmp_path: Path) -> None:
    valid = json.dumps(
        {
            "reservationId": "00000000-0000-4000-8000-000000000001",
            "activityId": "activity",
            "quantity": 1,
            "activityProjectionVersion": 1,
            "state": "REJECTED",
            "decisionCode": "EXHAUSTED",
            "projectionVersion": 2,
            "replay": False,
            "durableOrderCreated": False,
            "orderId": None,
        }
    )
    jtl = tmp_path / "raw.jtl"
    jtl.write_text(
        '<?xml version="1.0"?>\n<testResults>\n'
        '<httpSample t="10" lt="9" ct="1" ts="1000" s="false" '
        f'lb="cb152-1" rc="409" by="10"><responseData>{valid}</responseData></httpSample>\n'
        '<httpSample t="11" lt="10" ct="1" ts="1010" s="false" '
        'lb="cb152-2" rc="500" by="4"><responseData>{bad</responseData></httpSample>\n'
        "</testResults>\n",
        encoding="utf-8",
    )
    state = measure.RunState(
        measure.PROFILES["smoke"],
        tmp_path,
        tmp_path / "env",
        "project",
        "f" * 40,
        hash_salt=b"salt",
    )
    records = measure.parse_jtl(state, jtl, 2)
    assert [record.sanitized["sampleIndex"] for record in records] == [1, 2]
    assert records[0].sanitized["classification"] == "BUSINESS"
    assert records[1].sanitized["classification"] == "PARSE_ERROR"


def test_closed_public_classification_rejects_status_mismatch_and_unknown_field(
    tmp_path: Path,
) -> None:
    state = measure.RunState(
        measure.PROFILES["smoke"],
        tmp_path,
        tmp_path / "env",
        "project",
        "f" * 40,
        hash_salt=b"salt",
    )
    body = {
        "reservationId": "00000000-0000-4000-8000-000000000001",
        "activityId": "activity",
        "quantity": 1,
        "activityProjectionVersion": 1,
        "state": "REJECTED",
        "decisionCode": "EXHAUSTED",
        "projectionVersion": 2,
        "replay": False,
        "durableOrderCreated": False,
        "orderId": None,
    }
    assert (
        measure.classify_public(
            state, measure.HttpResult(200, json.dumps(body).encode()), operation="submit"
        ).sanitized["classification"]
        == "UNKNOWN"
    )
    body["extra"] = True
    assert (
        measure.classify_public(
            state, measure.HttpResult(409, json.dumps(body).encode()), operation="submit"
        ).sanitized["classification"]
        == "UNKNOWN"
    )


def test_sanitization_rejects_secret_and_raw_locator(tmp_path: Path) -> None:
    state = measure.RunState(
        measure.PROFILES["smoke"],
        tmp_path,
        tmp_path / "env",
        "project",
        "f" * 40,
        hash_salt=b"salt",
    )
    state.secrets.append(b"super-secret-value")
    artifact = tmp_path / "artifact.json"
    artifact.write_text('{"value":"super-secret-value"}\n', encoding="utf-8")
    with pytest.raises(RuntimeError, match="secret"):
        measure.scan_sanitized(tmp_path, state)
    artifact.write_text('{"value":"00000000-0000-4000-8000-000000000001"}\n', encoding="utf-8")
    with pytest.raises(RuntimeError, match="UUID"):
        measure.scan_sanitized(tmp_path, state)


def test_reconstruction_qps_percentiles_checksums_and_strict_schema(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    result = checker.reconstruct(bundle)
    assert result["seckillQps"] == 10.0
    assert result["latencyMs"] == {"p50": 100, "p95": 100, "p99": 100}
    with pytest.raises(checker.BundleError, match="duplicate JSON key"):
        checker.strict_json_text('{"a":1,"a":2}', source="test")
    manifest = checker.strict_json(bundle / "manifest.json")
    manifest["unknown"] = True
    with pytest.raises(checker.BundleError, match="unknown"):
        checker.validate_manifest(manifest)


@pytest.mark.parametrize(("q05_failure", "residue"), [(True, 0), (False, 1)])
def test_any_correctness_or_residue_failure_suppresses_performance(
    tmp_path: Path, q05_failure: bool, residue: int
) -> None:
    bundle = write_valid_bundle(tmp_path, q05_failure=q05_failure, residue=residue)
    result = checker.reconstruct(bundle)
    assert result["concurrencyCorrectnessValid"] is False
    assert "seckillQps" not in result
    assert "latencyMs" not in result


def test_checksum_mismatch_and_inventory_bounds_fail_closed(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    (bundle / "raw/fixture.json").write_text('{"changed":true}\n', encoding="utf-8")
    with pytest.raises(checker.BundleError, match="checksum mismatch"):
        checker.reconstruct(bundle)
    manifest = checker.strict_json(bundle / "manifest.json")
    manifest["artifactInventory"]["maxRawBytes"] = measure.MAX_RAW_BYTES + 1
    with pytest.raises(checker.BundleError, match="hard bound"):
        checker.validate_inventory(bundle, manifest)


def test_canonical_text_and_first_failure_preservation(tmp_path: Path) -> None:
    canonical = tmp_path / "artifact.jsonl"
    canonical.write_bytes(b'{"ok":true}\n')
    checker.canonical_text(canonical)
    canonical.write_bytes(b'{"ok":true} \r\n')
    with pytest.raises(checker.BundleError, match="canonical"):
        checker.canonical_text(canonical)
    primary = RuntimeError("primary")
    cleanup = RuntimeError("cleanup")
    assert measure.preserve_first_failure(primary, cleanup) is primary
    assert measure.preserve_first_failure(None, cleanup) is cleanup


def test_application_children_use_internal_rocketmq_route() -> None:
    source = (ROOT / "scripts/measure_cb152.py").read_text()
    assert "maven:3.9.11-eclipse-temurin-21@" in source
    assert 'f"{state.project}_default"' in source
    assert source.count("rocketmq-broker-proxy:8081") >= 3
    assert "rocketmq-endpoints=127.0.0.1" not in source
    assert "-Jjmeter.save.saveservice.output_format=xml" in source
    assert "-Jjmeter.save.saveservice.requestHeaders=false" in source


def test_prepared_query_binds_sql_and_values(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    state = measure.RunState(
        measure.PROFILES["smoke"],
        tmp_path,
        tmp_path / "env",
        "project",
        "f" * 40,
        env={"MYSQL_COMMERCE_APP_PASSWORD": "temporary"},
    )
    captured: list[str] = []

    def fake_mysql_command(_state: measure.RunState, _user: str, _password: str, sql: str) -> str:
        captured.append(sql)
        return "value\n1\n"

    monkeypatch.setattr(measure, "mysql_command", fake_mysql_command)
    assert measure.prepared_query(state, "SELECT ?", ["x'; DROP TABLE product;"]) == (
        ["value"],
        [["1"]],
    )
    script = captured[0]
    assert "PREPARE cb152_stmt FROM @cb152_sql;" in script
    assert "EXECUTE cb152_stmt USING @cb152_p0;" in script
    assert "DROP TABLE" not in script
