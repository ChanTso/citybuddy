from __future__ import annotations

import contextlib
import email.message
import hashlib
import http.server
import json
import os
import re
import sys
import threading
import urllib.error
import urllib.request
from collections.abc import Iterator
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import check_cb152_bundle as checker  # noqa: E402
import measure_cb152 as measure  # noqa: E402

FROZEN_CHECKER_BLOB = "630fd75fba7d8fd5a65818663d33378f5a97f8f3"


@contextlib.contextmanager
def serve_payload(payload: bytes, *, content_length: int | None) -> Iterator[str]:
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            self.send_response(200)
            if content_length is not None:
                self.send_header("Content-Length", str(content_length))
            self.end_headers()
            self.wfile.write(payload)
            self.wfile.flush()
            self.close_connection = True

        def log_message(self, _format: str, *_args: object) -> None:
            return

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        address = server.server_address
        assert isinstance(address, tuple)
        host, port = address[0], address[1]
        assert isinstance(host, str)
        assert isinstance(port, int)
        yield f"http://{host}:{port}/apache-jmeter-test.tgz"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


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
                    "activityId": "activity",
                    "quantity": 1,
                    "activityProjectionVersion": 1,
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
                    "observedAt": (
                        "2026-08-08T23:59:59Z" if phase == "initial" else "2026-08-09T00:00:01Z"
                    ),
                    "responseCode": 409,
                    "classification": "BUSINESS",
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
                "childPids": [
                    {"kind": kind, "absent": True} for kind in sorted(checker.CHILD_KINDS)
                ],
                "absentPaths": [
                    {"kind": kind, "absent": True} for kind in sorted(checker.ABSENT_PATH_KINDS)
                ],
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
        "machine": {
            "os": "TestOS",
            "architecture": "arm64",
            "logicalCpuCount": 8,
            "memoryBytes": 16_000_000_000,
            "dockerServerVersion": "test",
        },
        "containerResources": {
            "limits": "no explicit limits",
            "applicationProcesses": "foreground Auth and Commerce application containers",
        },
        "fixtureOrDatasetVersion": "cb152-seckill-v1",
        "tool": "Apache JMeter",
        "toolVersion": "5.6.3",
        "toolArchiveUrl": measure.JMETER_URL,
        "toolArchiveSha512": measure.JMETER_SHA512,
        "unpaidTimeoutSeconds": 120,
        "settlementTimeoutSeconds": 240,
        "jmeterConnectTimeoutMs": 5000,
        "jmeterResponseTimeoutMs": 15000,
        "warmup": {
            "samples": 1,
            "threads": 1,
            "rampSeconds": 1,
            "isolation": "separate activity and product",
        },
        "measuredDuration": {"model": "fixed sample count", "wallClockSeconds": 0.1},
        "concurrencyOrWorkload": {
            "threads": 1,
            "loopsPerThread": 1,
            "rampSeconds": 1,
            "quantityPerSubmission": 1,
        },
        "sampleCount": 1,
        "commands": [
            "Apache JMeter -JconnectTimeoutMs=5000 -JresponseTimeoutMs=15000",
            "Commerce unpaid timeout 120s",
            "bounded settlement timeout 240s",
        ],
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
        "runOrder": checker.RUN_ORDER,
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


def sync_upstream(bundle: Path, *, update_records: bool = True) -> None:
    manifest = checker.strict_json(bundle / "manifest.json")
    for entry in manifest["artifactInventory"]["files"]:
        data = (bundle / entry["path"]).read_bytes()
        entry["bytes"] = len(data)
        if update_records:
            entry["records"] = measure.record_count(entry["path"], data)
    (bundle / "manifest.json").write_bytes(measure.canonical_json(manifest))
    (bundle / "checksums.sha256").write_bytes(measure.checksum_bytes(bundle))


def rebuild_valid_result(bundle: Path) -> None:
    sync_upstream(bundle)
    result = checker.reconstruct(bundle, verify_result=False)
    (bundle / "result.json").write_bytes(measure.canonical_json(result))
    (bundle / "checksums.sha256").write_bytes(measure.checksum_bytes(bundle))
    checker.reconstruct(bundle)


def jsonl_rows(path: Path) -> list[dict[str, object]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def test_jmeter_official_checksum_mismatch_is_rejected() -> None:
    with pytest.raises(
        RuntimeError,
        match=re.escape("official JMeter checksum file does not match the pinned release"),
    ):
        measure.validate_jmeter_checksum(b"0" * 128 + b" *apache-jmeter-5.6.3.tgz\n")


def test_jmeter_incomplete_transfer_reports_bytes_digest_and_removes_partial(
    tmp_path: Path,
) -> None:
    payload = b"truncated-archive"
    expected_bytes = len(payload) + 19
    actual_digest = hashlib.sha512(payload).hexdigest()
    final_archive = tmp_path / "apache-jmeter-test.tgz"
    partial_archive = tmp_path / "apache-jmeter-test.tgz.part"
    with serve_payload(payload, content_length=expected_bytes) as url:
        with pytest.raises(
            RuntimeError, match=re.escape("JMeter archive transfer incomplete:")
        ) as raised:
            measure.acquire_jmeter_archive(
                url,
                final_archive,
                partial_archive,
                official_sha512="f" * 128,
                pinned_sha512="f" * 128,
                timeout_seconds=5,
            )
    message = str(raised.value)
    assert f"expectedBytes={expected_bytes}" in message
    assert f"actualBytes={len(payload)}" in message
    assert f"actualSha512={actual_digest}" in message
    assert "checksum mismatch" not in message
    assert not partial_archive.exists()
    assert not final_archive.exists()


def test_jmeter_complete_transfer_atomically_publishes_final_archive(tmp_path: Path) -> None:
    payload = b"complete-archive"
    digest = hashlib.sha512(payload).hexdigest()
    final_archive = tmp_path / "apache-jmeter-test.tgz"
    partial_archive = tmp_path / "apache-jmeter-test.tgz.part"
    with serve_payload(payload, content_length=len(payload)) as url:
        diagnostics = measure.acquire_jmeter_archive(
            url,
            final_archive,
            partial_archive,
            official_sha512=digest,
            pinned_sha512=digest,
            timeout_seconds=5,
        )
    assert diagnostics.content_length == len(payload)
    assert diagnostics.actual_bytes == len(payload)
    assert diagnostics.actual_sha512 == digest
    assert final_archive.read_bytes() == payload
    assert not partial_archive.exists()


def test_jmeter_complete_wrong_digest_is_not_reported_as_truncation(tmp_path: Path) -> None:
    payload = b"complete-but-wrong"
    actual_digest = hashlib.sha512(payload).hexdigest()
    final_archive = tmp_path / "apache-jmeter-test.tgz"
    partial_archive = tmp_path / "apache-jmeter-test.tgz.part"
    with serve_payload(payload, content_length=len(payload)) as url:
        with pytest.raises(
            RuntimeError, match=re.escape("JMeter archive checksum mismatch:")
        ) as raised:
            measure.acquire_jmeter_archive(
                url,
                final_archive,
                partial_archive,
                official_sha512="f" * 128,
                pinned_sha512="f" * 128,
                timeout_seconds=5,
            )
    message = str(raised.value)
    assert f"actualBytes={len(payload)}" in message
    assert f"actualSha512={actual_digest}" in message
    assert f"officialSha512={'f' * 128}" in message
    assert f"pinnedSha512={'f' * 128}" in message
    assert "transfer incomplete" not in message
    assert not partial_archive.exists()
    assert not final_archive.exists()


def test_jmeter_absent_content_length_uses_checksum_closure(tmp_path: Path) -> None:
    payload = b"close-delimited-archive"
    digest = hashlib.sha512(payload).hexdigest()
    final_archive = tmp_path / "apache-jmeter-test.tgz"
    partial_archive = tmp_path / "apache-jmeter-test.tgz.part"
    with serve_payload(payload, content_length=None) as url:
        diagnostics = measure.acquire_jmeter_archive(
            url,
            final_archive,
            partial_archive,
            official_sha512=digest,
            pinned_sha512=digest,
            timeout_seconds=5,
        )
    assert diagnostics.content_length is None
    assert diagnostics.actual_bytes == len(payload)
    assert final_archive.read_bytes() == payload
    final_archive.unlink()
    with serve_payload(payload, content_length=None) as url:
        with pytest.raises(
            RuntimeError, match=re.escape("JMeter archive checksum mismatch:")
        ) as raised:
            measure.acquire_jmeter_archive(
                url,
                final_archive,
                partial_archive,
                official_sha512="f" * 128,
                pinned_sha512="f" * 128,
                timeout_seconds=5,
            )
    message = str(raised.value)
    assert "contentLength=absent expectedBytes=absent" in message
    assert f"actualBytes={len(payload)}" in message
    assert f"actualSha512={digest}" in message
    assert not partial_archive.exists()
    assert not final_archive.exists()


def test_jmeter_write_failure_reports_bytes_digest_and_error_type(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    payload = b"bytes-read-before-write-failure"
    digest = hashlib.sha512(payload).hexdigest()
    final_archive = tmp_path / "apache-jmeter-test.tgz"
    partial_archive = tmp_path / "apache-jmeter-test.tgz.part"

    class Response:
        status = 200
        headers = {"Content-Length": str(len(payload))}

        def __init__(self) -> None:
            self.pending = payload

        def geturl(self) -> str:
            return "https://archive.apache.org/jmeter.tgz?private=removed"

        def read(self, _size: int) -> bytes:
            value, self.pending = self.pending, b""
            return value

        def close(self) -> None:
            return

    class Output:
        def write(self, _chunk: bytes) -> None:
            raise OSError("controlled write failure")

        def close(self) -> None:
            return

    original_open = Path.open
    monkeypatch.setattr(urllib.request, "urlopen", lambda *_args, **_kwargs: Response())
    monkeypatch.setattr(
        Path,
        "open",
        lambda self, *args, **kwargs: (
            Output() if self == partial_archive else original_open(self, *args, **kwargs)
        ),
    )
    with pytest.raises(RuntimeError, match=re.escape("JMeter archive transfer failed:")) as raised:
        measure.acquire_jmeter_archive(
            "https://archive.apache.org/jmeter.tgz",
            final_archive,
            partial_archive,
            official_sha512=digest,
            pinned_sha512=digest,
        )
    message = str(raised.value)
    assert f"actualBytes={len(payload)}" in message
    assert f"actualSha512={digest}" in message
    assert "errorType=OSError" in message
    assert "?private=removed" not in message
    assert not partial_archive.exists()
    assert not final_archive.exists()


def test_jmeter_http_error_retains_declared_content_length(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    headers = email.message.Message()
    headers["Content-Length"] = "17"
    error = urllib.error.HTTPError(
        "https://archive.apache.org/jmeter.tgz?private=removed",
        503,
        "unavailable",
        headers,
        None,
    )
    monkeypatch.setattr(
        urllib.request,
        "urlopen",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(error),
    )
    with pytest.raises(RuntimeError, match=re.escape("JMeter archive request failed:")) as raised:
        measure.acquire_jmeter_archive(
            "https://archive.apache.org/jmeter.tgz",
            tmp_path / "archive.tgz",
            tmp_path / "archive.tgz.part",
            official_sha512="f" * 128,
            pinned_sha512="f" * 128,
        )
    message = str(raised.value)
    assert "httpStatus=503" in message
    assert "contentLength=17 expectedBytes=17 actualBytes=0" in message
    assert "errorType=HTTPError" in message
    assert "?private=removed" not in message


def test_jmeter_atomic_publication_failure_is_diagnostic_and_cleans_partial(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    payload = b"complete-before-rename"
    digest = hashlib.sha512(payload).hexdigest()
    final_archive = tmp_path / "archive.tgz"
    partial_archive = tmp_path / "archive.tgz.part"
    monkeypatch.setattr(
        os,
        "replace",
        lambda *_args: (_ for _ in ()).throw(OSError("controlled replace failure")),
    )
    with serve_payload(payload, content_length=len(payload)) as url:
        with pytest.raises(
            RuntimeError, match=re.escape("JMeter archive publication failed:")
        ) as raised:
            measure.acquire_jmeter_archive(
                url,
                final_archive,
                partial_archive,
                official_sha512=digest,
                pinned_sha512=digest,
            )
    message = str(raised.value)
    assert f"actualBytes={len(payload)}" in message
    assert f"actualSha512={digest}" in message
    assert "errorType=OSError" in message
    assert not partial_archive.exists()
    assert not final_archive.exists()


def test_jmeter_response_close_failure_is_diagnostic_and_preserves_primary(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    payload = b"complete-before-response-close"
    digest = hashlib.sha512(payload).hexdigest()
    final_archive = tmp_path / "archive.tgz"
    partial_archive = tmp_path / "archive.tgz.part"

    class Response:
        status = 200
        headers = {"Content-Length": str(len(payload))}

        def __init__(self) -> None:
            self.pending = payload

        def geturl(self) -> str:
            return "https://archive.apache.org/jmeter.tgz"

        def read(self, _size: int) -> bytes:
            value, self.pending = self.pending, b""
            return value

        def close(self) -> None:
            raise OSError("controlled response close failure")

    monkeypatch.setattr(urllib.request, "urlopen", lambda *_args, **_kwargs: Response())
    with pytest.raises(RuntimeError, match=re.escape("JMeter archive transfer failed:")) as raised:
        measure.acquire_jmeter_archive(
            "https://archive.apache.org/jmeter.tgz",
            final_archive,
            partial_archive,
            official_sha512=digest,
            pinned_sha512=digest,
        )
    message = str(raised.value)
    assert f"actualBytes={len(payload)}" in message
    assert f"actualSha512={digest}" in message
    assert "errorType=OSError" in message
    assert any(
        "JMeter archive cleanup failed: kind=responseHandle errorType=OSError" in note
        for note in getattr(raised.value, "__notes__", [])
    )
    assert not partial_archive.exists()
    assert not final_archive.exists()


def test_jmeter_cleanup_failure_preserves_primary_acquisition_failure(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    payload = b"complete-but-wrong-with-cleanup-failure"
    actual_digest = hashlib.sha512(payload).hexdigest()
    final_archive = tmp_path / "archive.tgz"
    partial_archive = tmp_path / "archive.tgz.part"
    original_unlink = Path.unlink

    def controlled_unlink(path: Path, *, missing_ok: bool = False) -> None:
        if path == partial_archive and path.exists():
            raise OSError("controlled cleanup failure")
        original_unlink(path, missing_ok=missing_ok)

    monkeypatch.setattr(Path, "unlink", controlled_unlink)
    with serve_payload(payload, content_length=len(payload)) as url:
        with pytest.raises(
            RuntimeError, match=re.escape("JMeter archive checksum mismatch:")
        ) as raised:
            measure.acquire_jmeter_archive(
                url,
                final_archive,
                partial_archive,
                official_sha512="f" * 128,
                pinned_sha512="f" * 128,
            )
    assert f"actualSha512={actual_digest}" in str(raised.value)
    assert any(
        "JMeter archive cleanup failed: kind=partialArchive errorType=OSError" in note
        for note in getattr(raised.value, "__notes__", [])
    )
    assert partial_archive.exists()
    original_unlink(partial_archive)
    assert not final_archive.exists()


def test_pre_init_cleanup_skips_reset_but_still_queries_exact_residue(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    captured: list[list[str]] = []

    def fake_run(args: list[str], **_kwargs: object) -> SimpleNamespace:
        captured.append(list(args))
        return SimpleNamespace(returncode=0, stdout="", stderr="")

    monkeypatch.setattr(measure, "run", fake_run)
    pre_init = tmp_path / "pre-init"
    pre_init.mkdir()
    partial = pre_init / "apache-jmeter-test.tgz.part"
    partial.write_bytes(b"partial")
    state = measure.RunState(
        measure.PROFILES["smoke"],
        pre_init,
        pre_init / "run.env",
        "cb152-pre-init",
        "f" * 40,
    )
    residue = measure.cleanup(state)
    assert not any(command[:2] == ["make", "reset-local"] for command in captured)
    assert [command[:3] for command in captured] == [
        ["docker", "ps", "-aq"],
        ["docker", "network", "ls"],
        ["docker", "volume", "ls"],
    ]
    assert residue["containers"] == residue["networks"] == residue["volumes"] == 0
    assert not partial.exists()
    assert not pre_init.exists()

    captured.clear()
    initialized = tmp_path / "initialized"
    initialized.mkdir()
    env_file = initialized / "run.env"
    env_file.write_text("CB152_SYNTHETIC=1\n", encoding="utf-8")
    state = measure.RunState(
        measure.PROFILES["smoke"],
        initialized,
        env_file,
        "cb152-initialized",
        "f" * 40,
        env_created=True,
        init_local_started=True,
        init_local_completed=True,
    )
    measure.cleanup(state)
    reset = next(command for command in captured if command[:2] == ["make", "reset-local"])
    assert reset == [
        "make",
        "reset-local",
        "CONFIRM_RESET_LOCAL=1",
        f"ENV_FILE={env_file}",
        "COMPOSE_PROJECT_NAME=cb152-initialized",
    ]


def test_frozen_checker_blob_schema_and_nonpublic_transfer_diagnostics(tmp_path: Path) -> None:
    data = (ROOT / "scripts/check_cb152_bundle.py").read_bytes()
    header = f"blob {len(data)}\0".encode("ascii")
    blob = hashlib.sha1(header + data, usedforsecurity=False).hexdigest()
    assert blob == FROZEN_CHECKER_BLOB
    assert len(checker.ABSENT_PATH_KINDS) == 12
    assert len(checker.MANIFEST_KEYS) == 34

    state = measure.RunState(
        measure.PROFILES["smoke"],
        tmp_path,
        tmp_path / "run.env",
        "project",
        "f" * 40,
    )
    archive, partial, checksum, install = measure.jmeter_paths(state)
    assert state.absent_paths == [
        ("jmeterArchive", archive),
        ("jmeterChecksum", checksum),
        ("jmeterInstall", install),
    ]
    assert partial == tmp_path / f"{measure.JMETER_ARCHIVE}.part"
    assert all(kind != "jmeterArchivePart" for kind, _path in state.absent_paths)

    bundle = write_valid_bundle(tmp_path / "payload")
    diagnostic_fields = {
        "httpStatus",
        "finalUrl",
        "contentLength",
        "expectedBytes",
        "actualBytes",
        "actualSha512",
        "officialSha512",
        "pinnedSha512",
    }
    committed_payloads = [
        bundle / "manifest.json",
        bundle / "result.json",
        *bundle.glob("raw/**/*"),
    ]
    for path in committed_payloads:
        if path.is_file():
            payload = path.read_bytes()
            assert all(f'"{field}"'.encode() not in payload for field in diagnostic_fields)


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
    records = measure.parse_jtl(state, jtl, 2, "activity")
    assert [record.sanitized["sampleIndex"] for record in records] == [1, 2]
    assert records[0].sanitized["classification"] == "BUSINESS"
    assert records[1].sanitized["classification"] == "UNEXPECTED_ERROR"


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
            state,
            measure.HttpResult(200, json.dumps(body).encode()),
            operation="submit",
            expected_activity_id="activity",
        ).sanitized["classification"]
        != "BUSINESS"
    )
    body["extra"] = True
    assert (
        measure.classify_public(
            state,
            measure.HttpResult(409, json.dumps(body).encode()),
            operation="submit",
            expected_activity_id="activity",
        ).sanitized["classification"]
        != "BUSINESS"
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
    fixture_path = bundle / "raw/fixture.json"
    fixture_path.write_bytes(fixture_path.read_bytes().replace(b"cb152", b"xb152", 1))
    with pytest.raises(checker.BundleError, match="checksum mismatch"):
        checker.reconstruct(bundle)
    manifest = checker.strict_json(bundle / "manifest.json")
    manifest["artifactInventory"]["maxRawBytes"] = measure.MAX_RAW_BYTES + 1
    with pytest.raises(checker.BundleError, match="hard bound"):
        checker.validate_inventory(bundle, manifest, checker.bounded_walk(bundle))


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


def valid_business_body() -> dict[str, object]:
    return {
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


@pytest.mark.parametrize(
    "field",
    [
        "unpaidTimeoutSeconds",
        "settlementTimeoutSeconds",
        "jmeterConnectTimeoutMs",
        "jmeterResponseTimeoutMs",
    ],
)
def test_manifest_missing_timeout_hits_manifest_guard(tmp_path: Path, field: str) -> None:
    bundle = write_valid_bundle(tmp_path)
    manifest = checker.strict_json(bundle / "manifest.json")
    del manifest[field]
    (bundle / "manifest.json").write_bytes(measure.canonical_json(manifest))
    message = f"manifest schema mismatch: unknown=[] missing=['{field}']"
    with pytest.raises(checker.BundleError, match=re.escape(message)):
        checker.reconstruct(bundle)


def test_manifest_nested_unknown_field_hits_closed_schema(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    manifest = checker.strict_json(bundle / "manifest.json")
    manifest["machine"]["unknown"] = True
    (bundle / "manifest.json").write_bytes(measure.canonical_json(manifest))
    message = "machine schema mismatch: unknown=['unknown'] missing=[]"
    with pytest.raises(checker.BundleError, match=re.escape(message)):
        checker.reconstruct(bundle)


def test_nonhex_locator_hash_reaches_locator_guard(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    path = bundle / "raw/measured.jsonl"
    row = jsonl_rows(path)[0]
    row["reservationLocatorHash"] = "g" * 64
    path.write_bytes(measure.canonical_jsonl([row]))
    sync_upstream(bundle)
    message = "reservationLocatorHash must be 64 lowercase hexadecimal characters or null"
    with pytest.raises(checker.BundleError, match=re.escape(message)):
        checker.reconstruct(bundle)


def test_producer_rejects_noncanonical_uuid(tmp_path: Path) -> None:
    state = measure.RunState(
        measure.PROFILES["smoke"],
        tmp_path,
        tmp_path / "env",
        "project",
        "f" * 40,
        hash_salt=b"salt",
    )
    body = valid_business_body()
    body["reservationId"] = "{00000000-0000-4000-8000-000000000001}"
    record = measure.classify_public(
        state,
        measure.HttpResult(409, json.dumps(body).encode()),
        operation="submit",
        expected_activity_id="activity",
    )
    assert record.sanitized["classification"] != "BUSINESS"
    assert record.reservation_id is None
    assert record.sanitized["reservationLocatorHash"] is None


def test_producer_rejects_status_body_contradiction(tmp_path: Path) -> None:
    state = measure.RunState(
        measure.PROFILES["smoke"],
        tmp_path,
        tmp_path / "env",
        "project",
        "f" * 40,
        hash_salt=b"salt",
    )
    record = measure.classify_public(
        state,
        measure.HttpResult(200, json.dumps(valid_business_body()).encode()),
        operation="submit",
        expected_activity_id="activity",
    )
    assert record.sanitized["classification"] != "BUSINESS"
    assert all(
        record.sanitized[key] is None
        for key in (
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
    )


def test_checker_rejects_state_projection_mismatch_after_integrity(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    path = bundle / "raw/measured.jsonl"
    row = jsonl_rows(path)[0]
    row["projectionVersion"] = 3
    path.write_bytes(measure.canonical_jsonl([row]))
    sync_upstream(bundle)
    with pytest.raises(
        checker.BundleError, match=re.escape("public state/projectionVersion mismatch")
    ):
        checker.reconstruct(bundle)


def test_checker_rejects_status_body_contradiction_after_integrity(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    path = bundle / "raw/measured.jsonl"
    row = jsonl_rows(path)[0]
    row["responseCode"] = 200
    path.write_bytes(measure.canonical_jsonl([row]))
    sync_upstream(bundle)
    with pytest.raises(
        checker.BundleError,
        match=re.escape("measured sample status/state/replay mismatch"),
    ):
        checker.reconstruct(bundle)


def make_two_q04_controls(bundle: Path) -> None:
    q02 = bundle / "raw/reconciliation/q02.csv"
    q02.write_bytes(
        measure.canonical_csv(
            [
                "total_reservations",
                "distinct_reservations",
                "distinct_user_activity",
                "duplicate_idempotency_groups",
            ],
            [[2, 2, 2, 0]],
        )
    )
    q03 = bundle / "raw/reconciliation/q03.csv"
    q03.write_bytes(
        measure.canonical_csv(
            [
                "pending_count",
                "admitted_count",
                "rejected_count",
                "ordered_count",
                "cancelled_count",
                "unknown_state",
                "overdue_nonterminal",
            ],
            [[0, 0, 2, 0, 0, 0, 0]],
        )
    )
    controls_path = bundle / "raw/controls/q04.jsonl"
    rows = jsonl_rows(controls_path)
    second = dict(rows[0])
    second["reservationLocatorHash"] = "b" * 64
    controls_path.write_bytes(measure.canonical_jsonl([rows[0], second]))
    rebuild_valid_result(bundle)


def test_q04_missing_control_reaches_coverage_guard(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    make_two_q04_controls(bundle)
    path = bundle / "raw/controls/q04.jsonl"
    path.write_bytes(measure.canonical_jsonl(jsonl_rows(path)[:1]))
    sync_upstream(bundle)
    with pytest.raises(
        checker.BundleError, match=re.escape("q04 public control coverage mismatch")
    ):
        checker.reconstruct(bundle)


def test_q04_duplicate_hash_reaches_uniqueness_guard(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    make_two_q04_controls(bundle)
    path = bundle / "raw/controls/q04.jsonl"
    rows = jsonl_rows(path)
    rows[1]["reservationLocatorHash"] = rows[0]["reservationLocatorHash"]
    path.write_bytes(measure.canonical_jsonl(rows))
    sync_upstream(bundle)
    with pytest.raises(
        checker.BundleError, match=re.escape("q04 duplicate reservation locator hash")
    ):
        checker.reconstruct(bundle)


def mutate_q07_detail(bundle: Path, column: str, value: str) -> None:
    path = bundle / "raw/reconciliation/q07-details.csv"
    header, rows = checker.read_csv(path, maximum=checker.MAX_RECORDS)
    rows[0][column] = value
    path.write_bytes(measure.canonical_csv(header, [[row[key] for key in header] for row in rows]))
    sync_upstream(bundle)


def test_q07_wrong_reservation_hash_reaches_identity_join(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    mutate_q07_detail(bundle, "reservation_locator_hash", "b" * 64)
    with pytest.raises(
        checker.BundleError,
        match=re.escape("q07 public/durable reservation identity mismatch"),
    ):
        checker.reconstruct(bundle)


def test_q07_activity_mismatch_reaches_durable_binding(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    mutate_q07_detail(bundle, "activity_id", "other-activity")
    with pytest.raises(checker.BundleError, match=re.escape("q07 durable activityId mismatch")):
        checker.reconstruct(bundle)


def test_q07_replay_before_settle_cutoff_reaches_time_guard(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    path = bundle / "raw/controls/q07.jsonl"
    rows = jsonl_rows(path)
    rows[1]["observedAt"] = "2026-08-08T23:59:59Z"
    path.write_bytes(measure.canonical_jsonl(rows))
    sync_upstream(bundle)
    with pytest.raises(
        checker.BundleError,
        match=re.escape("q07 replay observedAt precedes settleCutoff"),
    ):
        checker.reconstruct(bundle)


@pytest.mark.parametrize(
    ("field", "message"),
    [
        ("childPids", "q09 childPids inventory must be nonempty"),
        ("absentPaths", "q09 absentPaths inventory must be nonempty"),
    ],
)
def test_q09_empty_inventory_reaches_exact_guard(tmp_path: Path, field: str, message: str) -> None:
    bundle = write_valid_bundle(tmp_path)
    path = bundle / "raw/residue.json"
    residue = checker.strict_json(path)
    residue[field] = []
    path.write_bytes(measure.canonical_json(residue))
    sync_upstream(bundle)
    with pytest.raises(checker.BundleError, match=re.escape(message)):
        checker.reconstruct(bundle)


def test_inventory_declared_record_mismatch_reaches_record_guard(tmp_path: Path) -> None:
    bundle = write_valid_bundle(tmp_path)
    manifest = checker.strict_json(bundle / "manifest.json")
    entry = next(
        item
        for item in manifest["artifactInventory"]["files"]
        if item["path"] == "raw/measured.jsonl"
    )
    entry["records"] = 2
    (bundle / "manifest.json").write_bytes(measure.canonical_json(manifest))
    (bundle / "checksums.sha256").write_bytes(measure.checksum_bytes(bundle))
    message = "inventory record count mismatch: raw/measured.jsonl"
    with pytest.raises(checker.BundleError, match=re.escape(message)):
        checker.reconstruct(bundle)


def test_hard_cap_rejected_before_full_payload_read(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    bundle = write_valid_bundle(tmp_path)
    oversized = bundle / "reconstruct.py"
    with oversized.open("r+b") as handle:
        handle.truncate(checker.RECONSTRUCT_MAX_BYTES + 1)
    original = checker.read_bounded
    read_paths: list[str] = []

    def observed_read(path: Path, maximum: int, *, label: str) -> bytes:
        read_paths.append(path.name)
        if path == oversized:
            raise AssertionError("oversized payload was materialized")
        return original(path, maximum, label=label)

    monkeypatch.setattr(checker, "read_bounded", observed_read)
    with pytest.raises(
        checker.BundleError, match=re.escape("reconstruct.py exceeds hard byte cap")
    ):
        checker.reconstruct(bundle)
    assert "reconstruct.py" not in read_paths
