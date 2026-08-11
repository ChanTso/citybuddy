from __future__ import annotations

import base64
import csv
import hashlib
import json
import math
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, NamedTuple, NoReturn, TypeAlias, cast

import bcrypt
import pymysql
import redis

PROFILE_ID = "cb155-formal-v1"
JMETER_VERSION = "5.6.3"
JMETER_SHA512 = (
    "5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e"
    "522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083"
)
JMETER_ARCHIVE = f"apache-jmeter-{JMETER_VERSION}.tgz"
JMETER_BASE = "https://downloads.apache.org/jmeter/binaries"
ROOT = Path(__file__).resolve().parents[1]
FINAL_BUNDLE = ROOT / "evidence/measurements/CB-155"
JMX = ROOT / "tests/performance/cb155_seckill.jmx"
SAMPLE_FIELDS = tuple(
    "sampleIndex startTimestampMs elapsedMs latencyMs connectMs responseCode jmeterSuccess "
    "producerClassification state decisionCode activityProjectionVersion projectionVersion "
    "durableOrderCreated replay reservationLocatorHash orderLocatorHash responseBytes".split()
)
PUBLIC_KEYS = set(
    "reservationId activityId quantity activityProjectionVersion state decisionCode "
    "projectionVersion replay durableOrderCreated orderId".split()
)
Database: TypeAlias = "pymysql.connections.Connection[pymysql.cursors.DictCursor]"
STATE_RULES = {"PENDING": (None, 1, False, False), "ADMITTED": ("ADMITTED", 2, False, False)}
STATE_RULES.update({"ORDERED": ("ADMITTED", 3, True, True)})
STATE_RULES.update({"CANCELLED": ("ADMITTED", 4, True, True)})
REJECTION_DECISIONS = {"EXHAUSTED", "NOT_STARTED", "ENDED", "INACTIVE", "STALE_VERSION"}
AUTH_FIXED = """
citybuddy.identity.enabled=true citybuddy.identity.issuer=https://identity.citybuddy.test
citybuddy.identity.user-audience=citybuddy-web citybuddy.identity.direct-ttl=20m
""".split()
COMMERCE_FIXED = """
citybuddy.catalog.enabled=true citybuddy.catalog.issuer=https://identity.citybuddy.test
citybuddy.catalog.user-audience=citybuddy-web citybuddy.catalog.worker-initial-delay-ms=3600000
citybuddy.seckill.enabled=true citybuddy.seckill.order.enabled=true
citybuddy.seckill.order.unpaid-timeout=120s citybuddy.seckill.order.worker-initial-delay-ms=0
citybuddy.seckill.order.worker-delay-ms=50 citybuddy.seckill.order.receive-await=1s
citybuddy.seckill.order.receive-invisible-duration=10s
citybuddy.seckill.timeout.dispatch-worker-initial-delay-ms=0
citybuddy.seckill.timeout.dispatch-worker-delay-ms=100
citybuddy.seckill.timeout.consumer-worker-initial-delay-ms=0
citybuddy.seckill.timeout.consumer-worker-delay-ms=100
citybuddy.seckill.timeout.receive-await=1s
citybuddy.seckill.timeout.receive-invisible-duration=10s
""".split()

SQL_BLOCKS = {
    "Q01": """-- Q01, captured before warm-up.
SELECT a.activity_id, a.product_id, a.state, a.allocated_quota,
       a.projection_version, p.stock_quantity
FROM seckill_activity AS a
JOIN product AS p ON p.product_id = a.product_id
WHERE a.activity_id = :activityId AND p.product_id = :productId;""",
    "Q02": """-- Q02.
SELECT COUNT(*) AS total_reservations,
       COUNT(DISTINCT reservation_id) AS distinct_reservations,
       COUNT(DISTINCT user_subject, activity_id) AS distinct_user_activity,
       (SELECT COUNT(*) FROM (
          SELECT user_subject, activity_id, idempotency_key
          FROM seckill_reservation WHERE activity_id = :activityId
          GROUP BY user_subject, activity_id, idempotency_key HAVING COUNT(*) > 1
        ) AS duplicate_keys) AS duplicate_idempotency_groups
FROM seckill_reservation WHERE activity_id = :activityId;""",
    "Q03": """-- Q03, executed only after the runner has waited until :settleCutoff.
SELECT SUM(state = 'PENDING') AS pending_count,
       SUM(state = 'ADMITTED') AS admitted_count,
       SUM(state = 'REJECTED') AS rejected_count,
       SUM(state = 'ORDERED') AS ordered_count,
       SUM(state = 'CANCELLED') AS cancelled_count,
       SUM(state NOT IN ('PENDING','ADMITTED','REJECTED','ORDERED','CANCELLED')) AS unknown_state,
       SUM(state IN ('PENDING','ADMITTED')
           AND transaction_resolution_due_at <= :settleCutoff) AS overdue_nonterminal
FROM seckill_reservation WHERE activity_id = :activityId;""",
    "Q04": """-- Q04.
SELECT
  (SELECT COUNT(*) FROM seckill_reservation
   WHERE activity_id = :activityId AND state IN ('ORDERED','CANCELLED'))
     AS successful_reservations,
  (SELECT COUNT(*) FROM seckill_order WHERE activity_id = :activityId) AS orders_for_activity,
  (SELECT COUNT(*) FROM seckill_reservation r
   LEFT JOIN seckill_order o ON o.reservation_id = r.reservation_id
   WHERE r.activity_id = :activityId AND r.state IN ('ORDERED','CANCELLED')
     AND o.order_id IS NULL) AS missing_orders,
  (SELECT COUNT(*) FROM seckill_order o
   LEFT JOIN seckill_reservation r ON r.reservation_id = o.reservation_id
   WHERE o.activity_id = :activityId AND r.reservation_id IS NULL) AS orphan_orders,
  (SELECT COUNT(*) FROM (
     SELECT reservation_id FROM seckill_order WHERE activity_id = :activityId
     GROUP BY reservation_id HAVING COUNT(*) > 1
   ) AS duplicate_order_groups) AS duplicate_orders,
  (SELECT COUNT(*) FROM seckill_order o
   JOIN seckill_reservation r ON r.reservation_id = o.reservation_id
   WHERE o.activity_id = :activityId AND
     (r.state NOT IN ('ORDERED','CANCELLED') OR r.order_id <> o.order_id
      OR r.user_subject <> o.user_subject OR r.activity_id <> o.activity_id
      OR r.quantity <> o.quantity)) AS binding_mismatches;""",
    "Q05": """-- Q05: the checker requires every returned scalar to be zero.
SELECT
  (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = :activityId
   AND (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id = o.order_id
        AND l.movement_type = 'SECKILL_ORDER_CREATE') <> 1) AS bad_create_count,
  (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = :activityId
   AND o.status = 'CANCELLED'
   AND (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id = o.order_id
        AND l.movement_type = 'SECKILL_UNPAID_CANCEL') <> 1) AS bad_cancel_count,
  (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = :activityId
   AND o.status <> 'CANCELLED'
   AND (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id = o.order_id
        AND l.movement_type = 'SECKILL_UNPAID_CANCEL') <> 0) AS unexpected_cancel_count,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   WHERE (l.activity_id = :activityId OR o.activity_id = :activityId
          OR r.activity_id = :activityId)
     AND l.movement_type IN ('SECKILL_ORDER_CREATE','SECKILL_UNPAID_CANCEL')
     AND o.order_id IS NOT NULL
     AND ((l.movement_type = 'SECKILL_ORDER_CREATE' AND
           (l.inventory_delta <> -o.quantity OR l.activity_quota_delta <> -o.quantity))
       OR (l.movement_type = 'SECKILL_UNPAID_CANCEL' AND
           (l.inventory_delta <> o.quantity OR l.activity_quota_delta <> o.quantity))))
    AS bad_quantity_count,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   WHERE (l.activity_id = :activityId OR o.activity_id = :activityId
          OR r.activity_id = :activityId)
     AND l.movement_type NOT IN ('SECKILL_ORDER_CREATE','SECKILL_UNPAID_CANCEL'))
    AS unexpected_movement_types,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   WHERE (l.activity_id = :activityId OR o.activity_id = :activityId
          OR r.activity_id = :activityId)
     AND (o.order_id IS NULL OR r.reservation_id IS NULL)) AS orphan_movements,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   LEFT JOIN seckill_activity a ON a.activity_id = o.activity_id
   WHERE (l.activity_id = :activityId OR o.activity_id = :activityId
          OR r.activity_id = :activityId)
     AND o.order_id IS NOT NULL AND r.reservation_id IS NOT NULL
     AND (l.reservation_id <> o.reservation_id OR l.activity_id <> o.activity_id
       OR l.product_id <> o.product_id OR r.reservation_id <> o.reservation_id
       OR r.activity_id <> o.activity_id OR NOT (r.order_id <=> o.order_id)
       OR r.user_subject <> o.user_subject OR r.quantity <> o.quantity
       OR a.activity_id IS NULL OR a.product_id <> o.product_id)) AS binding_mismatches;""",
    "Q06": """-- Q06.
SELECT p.stock_quantity AS final_stock,
       :baselineProductStock + COALESCE(SUM(l.inventory_delta), 0) AS expected_final_stock,
       -COALESCE(SUM(l.activity_quota_delta), 0) AS net_consumed_quota,
       COALESCE((SELECT SUM(r.quantity) FROM seckill_reservation r
                 WHERE r.activity_id = :activityId
                   AND r.state IN ('ADMITTED','ORDERED')), 0) AS active_quantity,
       a.allocated_quota AS final_allocated_quota,
       :baselineAllocatedQuota AS baseline_allocated_quota
FROM seckill_activity a
JOIN product p ON p.product_id = a.product_id
LEFT JOIN inventory_ledger l ON l.activity_id = a.activity_id
WHERE a.activity_id = :activityId AND p.product_id = :productId
GROUP BY p.stock_quantity, a.allocated_quota;""",
    "Q07a": """-- Q07a: executed once per runtime replay reservation before sanitizing its locators.
SELECT r.reservation_id, r.activity_id, r.quantity, r.activity_projection_version,
       r.state, r.decision_code, r.projection_version, r.order_id,
       (SELECT COUNT(*) FROM seckill_order o
        WHERE o.reservation_id = r.reservation_id) AS order_count,
       (SELECT MIN(o.order_id) FROM seckill_order o
        WHERE o.reservation_id = r.reservation_id) AS canonical_order_id,
       (SELECT COUNT(*) FROM inventory_ledger l
        WHERE l.order_id = r.order_id
          AND l.movement_type = 'SECKILL_ORDER_CREATE') AS create_movement_count,
       (SELECT COUNT(*) FROM inventory_ledger l
        WHERE l.order_id = r.order_id
          AND l.movement_type = 'SECKILL_UNPAID_CANCEL') AS cancel_movement_count,
       (SELECT COUNT(*) FROM inventory_ledger l
        LEFT JOIN seckill_order o ON o.order_id = l.order_id
        LEFT JOIN seckill_activity a ON a.activity_id = o.activity_id
        WHERE (l.reservation_id = r.reservation_id OR l.order_id = r.order_id)
          AND (o.order_id IS NULL OR l.reservation_id <> r.reservation_id
            OR l.order_id <> r.order_id OR l.activity_id <> r.activity_id
            OR o.reservation_id <> r.reservation_id OR o.activity_id <> r.activity_id
            OR o.user_subject <> r.user_subject OR o.quantity <> r.quantity
            OR l.product_id <> o.product_id OR a.activity_id IS NULL
            OR a.product_id <> o.product_id)) AS movement_linkage_mismatches
FROM seckill_reservation r
WHERE r.reservation_id = :replayReservationId AND r.activity_id = :activityId;""",
    "Q07b": """-- Q07b durable global uniqueness; the public half is defined below.
SELECT
  (SELECT COUNT(*) FROM (
    SELECT user_subject, activity_id, idempotency_key
    FROM seckill_reservation WHERE activity_id = :activityId
    GROUP BY user_subject, activity_id, idempotency_key HAVING COUNT(*) > 1
  ) d) AS duplicate_reservation_keys,
  (SELECT COUNT(*) FROM (
    SELECT reservation_id FROM seckill_order WHERE activity_id = :activityId
    GROUP BY reservation_id HAVING COUNT(*) > 1
  ) d) AS duplicate_order_keys,
  (SELECT COUNT(*) FROM (
    SELECT order_id, movement_type FROM inventory_ledger
    WHERE activity_id = :activityId
      AND movement_type IN ('SECKILL_ORDER_CREATE','SECKILL_UNPAID_CANCEL')
    GROUP BY order_id, movement_type HAVING COUNT(*) > 1
  ) d) AS duplicate_ledger_keys;""",
    "Q08": """-- Q08 canonical row selected before and after the public ownership controls.
SELECT reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity,
       activity_projection_version, state, decision_code, projection_version, order_id,
       transaction_resolution_due_at
FROM seckill_reservation WHERE reservation_id = :ownershipReservationId;""",
    "Q09": """-- Q09 durable work closure; each scalar must be zero.
SELECT
  (SELECT COUNT(*) FROM seckill_reservation
   WHERE activity_id = :activityId AND state IN ('PENDING','ADMITTED')
     AND transaction_resolution_due_at <= :observationAt) AS overdue_reservation_resolution,
  (SELECT COUNT(*) FROM seckill_order
   WHERE activity_id = :activityId AND status = 'UNPAID'
     AND unpaid_deadline <= :observationAt) AS overdue_unpaid_orders,
  (SELECT COUNT(*) FROM seckill_order
   WHERE activity_id = :activityId AND timeout_dispatch_state = 'PENDING'
     AND created_at <= :dispatchSettleCutoff) AS overdue_timeout_dispatch,
  (SELECT COUNT(*) FROM seckill_order
   WHERE activity_id = :activityId AND timeout_dispatch_state = 'FAILED')
     AS failed_timeout_dispatch;""",
}


def fail(code: str, detail: str) -> NoReturn:
    raise RuntimeError(f"{code}: {detail}")


def ensure(condition: bool, code: str, detail: str = "mismatch") -> None:
    if not condition:
        fail(code, detail)


class Child(NamedTuple):
    kind: str
    process: subprocess.Popen[bytes]


@dataclass
class State:
    temp: Path | None = None
    env: Path | None = None
    project: str = ""
    env_created: bool = False
    children: list[Child] = field(default_factory=list)
    owned_paths: dict[str, Path] = field(default_factory=dict)


class Response(NamedTuple):
    status: int
    payload: dict[str, Any]


class RunData(NamedTuple):
    manifest: dict[str, Any]
    result: dict[str, Any]
    files: dict[str, Any]


def command(
    arguments: list[str],
    *,
    timeout: int = 600,
    env: dict[str, str] | None = None,
    capture: bool = False,
) -> subprocess.CompletedProcess[str]:
    merged = os.environ.copy()
    if env:
        merged.update(env)
    return subprocess.run(
        arguments,
        cwd=ROOT,
        env=merged,
        check=True,
        timeout=timeout,
        text=True,
        capture_output=capture,
    )


def output(arguments: list[str], timeout: int = 60) -> str:
    return command(arguments, timeout=timeout, capture=True).stdout.strip()


def iso(value: datetime) -> str:
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"


def named_fields(names: str, values: tuple[Any, ...]) -> dict[str, Any]:
    return dict(zip(names.split(), values, strict=True))


def source_gate() -> str:
    if output(["git", "status", "--porcelain"]):
        fail("CANDIDATE_DIRTY", "formal run requires a clean candidate")
    revision = output(["git", "rev-parse", "HEAD"])
    expected = ["Q01", "Q02", "Q03", "Q04", "Q05", "Q06", "Q07a", "Q07b", "Q08", "Q09"]
    ensure(list(SQL_BLOCKS) == expected, "SQL_ORDER", repr(list(SQL_BLOCKS)))
    frozen = sum(
        sum(bool(line.strip()) for line in sql.splitlines()) for sql in SQL_BLOCKS.values()
    )
    ensure(frozen == 174, "SQL_FROZEN_LOC", str(frozen))
    paths = [ROOT / "scripts/measure_cb155.py", ROOT / "scripts/check_cb155_measurement.py"]
    total = sum(sum(bool(line.strip()) for line in path.read_text().splitlines()) for path in paths)
    authored = total - frozen
    ensure(authored <= 1800 and total <= 1974, "SURFACE_LOC", f"total={total} authored={authored}")
    functional_names = (
        "Makefile scripts/measure_cb155.py tests/performance/cb155_seckill.jmx "
        "scripts/check_cb155_measurement.py tests/test_cb155_measurement.py"
    )
    functional = [ROOT / name for name in functional_names.split()]
    ensure(
        len(functional) == 5 and all(path.is_file() for path in functional), "FUNCTIONAL_FILE_GATE"
    )
    return revision


def download(url: str, destination: Path, maximum: int | None = None) -> str:
    part = destination.with_suffix(destination.suffix + ".part")
    digest = hashlib.sha512()
    count = 0
    status = 0
    final_url = url
    length_text = "absent"
    try:
        with urllib.request.urlopen(url, timeout=30) as response:  # noqa: S310
            status = response.status
            final_url = response.geturl()
            raw_length = response.headers.get("Content-Length")
            if raw_length is not None:
                if re.fullmatch(r"0|[1-9][0-9]*", raw_length) is None:
                    fail("DOWNLOAD_CONTENT_LENGTH", raw_length)
                length_text = raw_length
            ensure(status == 200, "DOWNLOAD_STATUS", str(status))
            with part.open("wb") as target:
                while chunk := response.read(1024 * 1024):
                    count += len(chunk)
                    if maximum is not None and count > maximum:
                        fail("DOWNLOAD_SIZE", str(count))
                    digest.update(chunk)
                    target.write(chunk)
            if raw_length is not None and count != int(raw_length):
                fail("DOWNLOAD_EOF", f"declared={raw_length} actual={count}")
        os.replace(part, destination)
        return digest.hexdigest()
    except Exception as error:
        part.unlink(missing_ok=True)
        destination.unlink(missing_ok=True)
        actual = digest.hexdigest()
        raise RuntimeError(
            "DOWNLOAD_FAILED: "
            f"status={status} url={final_url} contentLength={length_text} "
            f"actualBytes={count} actualSha512={actual} cause={error}"
        ) from error


def acquire_jmeter(state: State) -> Path:
    if state.temp is None:
        fail("TEMP_STATE", "missing")
    checksum = state.temp / f"{JMETER_ARCHIVE}.sha512"
    archive = state.temp / JMETER_ARCHIVE
    install = state.temp / "jmeter-install"
    state.owned_paths.update(
        {"jmeter_checksum": checksum, "jmeter_archive": archive, "jmeter_install": install}
    )
    download(f"{JMETER_BASE}/{JMETER_ARCHIVE}.sha512", checksum, 16 * 1024)
    text = checksum.read_text(encoding="ascii")
    match = re.fullmatch(rf"([0-9a-f]{{128}})  {re.escape(JMETER_ARCHIVE)}\n?", text)
    if match is None:
        fail("JMETER_OFFICIAL_CHECKSUM", "one exact sha512sum record required")
    actual = download(f"{JMETER_BASE}/{JMETER_ARCHIVE}", archive)
    if match.group(1) != JMETER_SHA512 or actual != JMETER_SHA512:
        fail("JMETER_DIGEST", f"official={match.group(1)} pinned={JMETER_SHA512} actual={actual}")
    install.mkdir(mode=0o700)
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        for member in members:
            target = (install / member.name).resolve()
            if install.resolve() not in target.parents or not (member.isfile() or member.isdir()):
                fail("JMETER_ARCHIVE_MEMBER", member.name)
        tar.extractall(install, members=members)  # noqa: S202
    executable = install / f"apache-jmeter-{JMETER_VERSION}/bin/jmeter"
    ensure(executable.is_file(), "JMETER_INSTALL", "executable missing")
    return executable


def env_values(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith("#"):
            key, separator, value = line.partition("=")
            if not separator or key in values:
                fail("RUN_ENV", "malformed")
            values[key] = value
    return values


def compose(
    state: State, *arguments: str, capture: bool = False
) -> subprocess.CompletedProcess[str]:
    if state.env is None:
        fail("RUN_ENV", "missing")
    return command(
        [
            "docker",
            "compose",
            "--project-name",
            state.project,
            "--env-file",
            str(state.env),
            "--file",
            "compose.yaml",
            *arguments,
        ],
        timeout=600,
        capture=capture,
    )


def dependency_port(state: State, service: str, container_port: int) -> int:
    binding = compose(state, "port", service, str(container_port), capture=True).stdout.strip()
    match = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", binding)
    if match is None or not 0 < int(match.group(1)) <= 65535:
        fail("DEPENDENCY_PORT", f"{service}:{container_port}")
    return int(match.group(1))


def admin(state: State, *arguments: str) -> None:
    compose(state, "run", "--rm", "--no-deps", "rocketmq-admin", *arguments)


def create_messaging(state: State, run_id: str) -> dict[str, str]:
    names = {"catalogTopic": f"cb155-catalog-{run_id}", "catalogGroup": f"cb155-catalog-g-{run_id}"}
    names.update({"transactionTopic": f"cb155-transaction-{run_id}"})
    names.update({"transactionGroup": f"cb155-transaction-g-{run_id}"})
    names.update(
        {"timeoutTopic": f"cb155-timeout-{run_id}", "timeoutGroup": f"cb155-timeout-g-{run_id}"}
    )
    topics = (
        (names["catalogTopic"], None),
        (names["transactionTopic"], "+message.type=TRANSACTION"),
        (names["timeoutTopic"], "+message.type=DELAY"),
    )
    base = ["--namesrvAddr", "rocketmq-namesrv:9876", "--clusterName", "DefaultCluster"]
    for topic, attribute in topics:
        args = ["updateTopic", *base, "--topic", topic, "--readQueueNums", "8"]
        args.extend(["--writeQueueNums", "8"])
        if attribute:
            args.extend(["-a", attribute])
        admin(state, *args)
    for group in (names["catalogGroup"], names["transactionGroup"], names["timeoutGroup"]):
        admin(state, "updateSubGroup", *base, "--groupName", group, "--consumeEnable", "true")
    return names


def database(port: int, user: str, password: str) -> Database:
    return pymysql.connect(
        host="127.0.0.1",
        port=port,
        user=user,
        password=password,
        database="commerce_db",
        autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )


def execute_many(connection: Database, statement: str, rows: list[tuple[Any, ...]]) -> None:
    with connection.cursor() as cursor:
        cursor.executemany(statement, rows)


def seed(
    mysql_port: int, redis_port: int, values: dict[str, str], run_id: str
) -> tuple[dict[str, Any], str]:
    starts = datetime.now(UTC) - timedelta(minutes=5)
    ends = starts + timedelta(minutes=30)
    fixtures: dict[str, Any] = {"warmupActivity": f"cb155-warmup-{run_id}"}
    fixtures["activity"] = f"cb155-measured-{run_id}"
    fixtures.update({"warmupProduct": f"cb155-warmup-product-{run_id}"})
    fixtures.update({"product": f"cb155-measured-product-{run_id}"})
    password = uuid.uuid4().hex + uuid.uuid4().hex[:16]
    hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12)).decode()
    principals = [
        (
            str(uuid.uuid4()),
            f"cb155-subject-{index:03d}",
            f"cb155-login-{index:03d}",
            "ACTIVE",
            "seckill:reserve",
            hashed,
        )
        for index in range(503)
    ]
    with database(mysql_port, "auth_app", values["MYSQL_AUTH_APP_PASSWORD"]) as connection:
        execute_many(
            connection,
            "INSERT INTO auth_user_principal "
            "(principal_id,subject,login_identifier,state,permissions) VALUES (%s,%s,%s,%s,%s)",
            [row[:5] for row in principals],
        )
        execute_many(
            connection,
            "INSERT INTO auth_login_credential (principal_id,password_hash) VALUES (%s,%s)",
            [(row[0], row[5]) for row in principals],
        )
        with connection.cursor() as cursor:
            cursor.execute(
                "INSERT INTO auth_signing_key_metadata "
                "(kid,state,activated_at,retire_after) "
                "VALUES (%s,'CURRENT',CURRENT_TIMESTAMP(6),NULL)",
                (f"cb155-key-{run_id}",),
            )
    with database(mysql_port, "commerce_app", values["MYSQL_COMMERCE_APP_PASSWORD"]) as connection:
        execute_many(
            connection,
            "INSERT INTO product "
            "(product_id,name,description,price_minor,currency,stock_quantity,available,"
            "publication_state,publication_version) "
            "VALUES (%s,%s,%s,100,'CNY',%s,TRUE,'PUBLISHED',1)",
            [
                (fixtures["warmupProduct"], "CB155 warmup", "synthetic", 1),
                (fixtures["product"], "CB155 measured", "synthetic", 252),
            ],
        )
        common = (starts, ends)
        execute_many(
            connection,
            "INSERT INTO seckill_activity "
            "(activity_id,product_id,starts_at,ends_at,state,allocated_quota,projection_version) "
            "VALUES (%s,%s,%s,%s,'ACTIVE',%s,1)",
            [
                (fixtures["warmupActivity"], fixtures["warmupProduct"], *common, 1),
                (fixtures["activity"], fixtures["product"], *common, 252),
            ],
        )
    cache = redis.Redis(
        host="127.0.0.1",
        port=redis_port,
        password=values["REDIS_COMMERCE_PASSWORD"],
        decode_responses=True,
    )
    for activity_key, quota in (("warmupActivity", 1), ("activity", 252)):
        payload = {"activityId": fixtures[activity_key], "projectionVersion": 1}
        payload.update({"startsAt": iso(starts), "endsAt": iso(ends)})
        payload.update({"state": "ACTIVE", "remainingQuota": quota})
        cache.set(
            f"commerce:seckill:activity:{fixtures[activity_key]}",
            json.dumps(payload, separators=(",", ":")),
        )
    return fixtures, password


def start_child(state: State, kind: str, arguments: list[str], env: dict[str, str]) -> int:
    if state.temp is None:
        fail("TEMP_STATE", "missing")
    log_path = state.temp / f"{kind}.log"
    state.owned_paths[f"{kind}_log"] = log_path
    with log_path.open("wb") as log:
        child = subprocess.Popen(
            arguments, cwd=ROOT, env=os.environ | env, stdout=log, stderr=subprocess.STDOUT
        )
    state.children.append(Child(kind, child))
    pattern = re.compile(rb"Tomcat started on port ([0-9]+)")
    for _ in range(600):
        if child.poll() is not None:
            fail("CHILD_START", f"{kind} exited")
        match = pattern.search(log_path.read_bytes())
        if match and 0 < int(match.group(1)) <= 65535:
            return int(match.group(1))
        time.sleep(0.1)
    fail("CHILD_START", f"{kind} port timeout")


def request(
    method: str, url: str, body: dict[str, Any] | None = None, headers: dict[str, str] | None = None
) -> Response:
    encoded = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    all_headers = {} if headers is None else dict(headers)
    if encoded is not None:
        all_headers["Content-Type"] = "application/json"
    operation = urllib.request.Request(url, data=encoded, headers=all_headers, method=method)
    try:
        with urllib.request.urlopen(operation, timeout=10) as response:  # noqa: S310
            status = response.status
            raw = response.read(64 * 1024 + 1)
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read(64 * 1024 + 1)
    ensure(len(raw) <= 64 * 1024, "HTTP_BODY_BOUND", url.rsplit("/", 1)[-1])
    try:
        parsed = json.loads(raw, object_pairs_hook=_unique_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail("HTTP_JSON", str(error))
    ensure(isinstance(parsed, dict), "HTTP_JSON", "object required")
    return Response(status, parsed)


def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            fail("HTTP_DUPLICATE_KEY", key)
        value[key] = item
    return value


def wait_http(url: str, expected: set[int]) -> None:
    for _ in range(120):
        try:
            response = request("GET", url)
            if response.status in expected:
                return
        except (OSError, RuntimeError):
            pass
        time.sleep(0.5)
    fail("HTTP_READINESS", url.rsplit("/", 1)[-1])


def canonical_uuid(value: Any, code: str) -> str:
    if not isinstance(value, str) or len(value) != 36:
        fail(code, repr(value))
    try:
        canonical = str(uuid.UUID(value))
    except ValueError:
        fail(code, repr(value))
    ensure(canonical == value, code, repr(value))
    return value


def public_body(payload: dict[str, Any], activity: str, quantity: int, code: str) -> dict[str, Any]:
    ensure(set(payload) == PUBLIC_KEYS, code, f"keyset={sorted(payload)}")
    reservation = canonical_uuid(payload["reservationId"], code)
    order = payload["orderId"]
    if order is not None:
        canonical_uuid(order, code)
    integer_fields = "quantity activityProjectionVersion projectionVersion".split()
    if any(type(payload[key]) is not int for key in integer_fields) or (
        payload["activityId"] != activity
        or payload["quantity"] != quantity
        or payload["activityProjectionVersion"] != 1
    ):
        fail(code, "intent mismatch")
    if type(payload["replay"]) is not bool or type(payload["durableOrderCreated"]) is not bool:
        fail(code, "boolean type")
    state_value = payload["state"]
    actual = (
        payload["decisionCode"],
        payload["projectionVersion"],
        payload["durableOrderCreated"],
        order is not None,
    )
    expected = (
        (payload["decisionCode"], 2, False, False)
        if state_value == "REJECTED" and payload["decisionCode"] in REJECTION_DECISIONS
        else STATE_RULES.get(state_value)
    )
    ensure(actual == expected, code, "contradictory body")
    public = named_fields(
        "activityId quantity activityProjectionVersion state decisionCode projectionVersion",
        (activity, quantity, 1, state_value, payload["decisionCode"], payload["projectionVersion"]),
    )
    public.update(
        {"replay": payload["replay"], "durableOrderCreated": payload["durableOrderCreated"]}
    )
    public.update({"reservationLocatorHash": locator_hash(reservation)})
    public.update({"orderLocatorHash": None if order is None else locator_hash(order)})
    return public


def classify(
    status: int, payload: dict[str, Any], activity: str, quantity: int
) -> tuple[str, dict[str, Any]]:
    body = public_body(payload, activity, quantity, "PUBLIC_CONTRACT")
    state_value = body["state"]
    allowed = (
        (status == 201 and state_value == "ADMITTED" and not body["replay"])
        or (status == 202 and state_value == "PENDING" and not body["replay"])
        or (status == 409 and state_value == "REJECTED")
        or (status == 200 and (body["replay"] or state_value in {"ORDERED", "CANCELLED"}))
    )
    ensure(allowed, "PUBLIC_STATUS_BODY", f"status={status} state={state_value}")
    return ("business_rejected" if status == 409 else "accepted"), body


def login(auth_port: int, index: int, password: str) -> str:
    response = request(
        "POST",
        f"http://127.0.0.1:{auth_port}/auth/login",
        {"loginIdentifier": f"cb155-login-{index:03d}", "password": password},
    )
    if response.status != 200 or set(response.payload) != {"accessToken", "tokenType", "expiresIn"}:
        fail("AUTH_LOGIN", str(index))
    token = response.payload["accessToken"]
    ensure(isinstance(token, str) and bool(token), "AUTH_LOGIN", str(index))
    return str(token)


def jwt_expiry(token: str) -> int:
    try:
        payload = token.split(".")[1]
        decoded = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
        value = json.loads(decoded, object_pairs_hook=_unique_pairs)
    except (IndexError, ValueError, json.JSONDecodeError) as error:
        fail("JWT_EXPIRY", str(error))
    if (
        not isinstance(value, dict)
        or isinstance(value.get("exp"), bool)
        or not isinstance(value.get("exp"), int)
    ):
        fail("JWT_EXPIRY", "integer exp required")
    return int(value["exp"])


def bearer(token: str, idempotency: str | None = None) -> dict[str, str]:
    headers = {"Authorization": f"Bearer {token}"}
    if idempotency:
        headers["Idempotency-Key"] = idempotency
    return headers


def locator_hash(locator: str) -> str:
    return hashlib.sha256(locator.encode()).hexdigest()


def query(connection: Database, name: str, values: dict[str, Any]) -> list[dict[str, Any]]:
    sql = SQL_BLOCKS[name]
    bindings: list[Any] = []

    def placeholder(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in values:
            fail("SQL_BINDING", f"{name}:{key}")
        bindings.append(values[key])
        return "%s"

    prepared = re.sub(r":([A-Za-z][A-Za-z0-9]*)", placeholder, sql)
    with connection.cursor() as cursor:
        cursor.execute(prepared, bindings)
        rows = cursor.fetchall()
    return [dict(row) for row in rows]


def sql_scalar(connection: Database, statement: str, arguments: tuple[Any, ...] = ()) -> int:
    with connection.cursor() as cursor:
        cursor.execute(statement, arguments)
        row = cursor.fetchone()
    if not row or len(row) != 1:
        fail("SQL_SCALAR", statement[:40])
    return int(next(iter(row.values())))


def expect_scalar(db: Database, sql: str, args: tuple[Any, ...], expected: int, code: str) -> None:
    actual = sql_scalar(db, sql, args)
    ensure(actual == expected, code, f"expected={expected} actual={actual}")


def jmeter(
    executable: Path,
    state: State,
    phase: str,
    port: int,
    activity: str,
    tokens: list[str],
) -> list[dict[str, Any]]:
    if state.temp is None:
        fail("TEMP_STATE", "missing")
    csv_path = state.temp / f"{phase}-tokens.csv"
    jtl = state.temp / f"{phase}.jtl"
    state.owned_paths[f"{phase}_token_input"] = csv_path
    state.owned_paths[f"{phase}_temporary_jtl"] = jtl
    with csv_path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, lineterminator="\n")
        for index, token in enumerate(tokens):
            writer.writerow((token, f"cb155-{phase}-{index:03d}"))
    threads, loops, ramp = (8, 4, 2) if phase == "warmup" else (64, 8, 5)
    quantity = 2 if phase == "warmup" else 1
    body = json.dumps({"quantity": quantity, "expectedActivityVersion": 1}, separators=(",", ":"))
    properties = {"cb155_phase": phase, "cb155_threads": threads, "cb155_loops": loops}
    properties.update({"cb155_ramp": ramp, "cb155_csv": csv_path, "cb155_port": port})
    properties.update({"cb155_activity": activity, "cb155_body": body})
    properties.update({"cb155_connect_timeout_ms": 2000, "cb155_response_timeout_ms": 10000})
    properties.update({"jmeter.save.saveservice.output_format": "xml"})
    properties.update({"jmeter.save.saveservice.response_data": "true"})
    for key in "samplerData requestHeaders responseHeaders".split():
        properties[f"jmeter.save.saveservice.{key}"] = "false"
    args = [str(executable), "-n", "-t", str(JMX), "-l", str(jtl)]
    args.extend(f"-J{key}={value}" for key, value in properties.items())
    command(args, timeout=60)
    samples: list[dict[str, Any]] = []
    for _, element in ET.iterparse(jtl, events=("end",)):
        if element.tag not in {"httpSample", "sample"}:
            continue
        response_node = element.find("responseData")
        response_text = (
            "" if response_node is None or response_node.text is None else response_node.text
        )
        raw = response_text.encode()
        try:
            payload = json.loads(raw, object_pairs_hook=_unique_pairs)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            fail("JMETER_RESPONSE_JSON", str(error))
        ensure(isinstance(payload, dict), "JMETER_RESPONSE_JSON", "object required")
        status = int(element.attrib.get("rc", "0"))
        classification, body_value = classify(status, payload, activity, quantity)
        timing_values = (
            len(samples) + 1,
            *(int(element.attrib.get(key, "0")) for key in ("ts", "t", "lt", "ct")),
            status,
            element.attrib.get("s") == "true",
            classification,
        )
        timing = named_fields(
            "sampleIndex startTimestampMs elapsedMs latencyMs connectMs responseCode "
            "jmeterSuccess producerClassification",
            timing_values,
        )
        timing.update({key: body_value[key] for key in SAMPLE_FIELDS if key in body_value})
        timing["responseBytes"] = len(raw)
        samples.append(timing)
        element.clear()
    expected = 32 if phase == "warmup" else 500
    ensure(len(samples) == expected, "JMETER_SAMPLE_COUNT", f"{phase}={len(samples)}")
    return samples


def submit(port: int, activity: str, token: str, key: str) -> tuple[Response, dict[str, Any]]:
    response = request(
        "POST",
        f"http://127.0.0.1:{port}/api/seckill/activities/{activity}/reservations",
        {"quantity": 1, "expectedActivityVersion": 1},
        bearer(token, key),
    )
    _, body = classify(response.status, response.payload, activity, 1)
    return response, body


def get_reservation(port: int, token: str, locator: str) -> Response:
    url = f"http://127.0.0.1:{port}/api/reservations/{locator}"
    return request("GET", url, headers=bearer(token))


def poll(port: int, token: str, locator: str, activity: str) -> tuple[Response, dict[str, Any]]:
    response = get_reservation(port, token, locator)
    ensure(response.status == 200, "POLL_STATUS", str(response.status))
    return response, public_body(response.payload, activity, 1, "POLL_CONTRACT")


def wait_ordered(port: int, token: str, locator: str, activity: str) -> dict[str, Any]:
    for _ in range(300):
        _, body = poll(port, token, locator, activity)
        if body["state"] == "ORDERED":
            return body
        if body["state"] in {"REJECTED", "CANCELLED"}:
            fail("CONTROL_ORDER", str(body["state"]))
        time.sleep(0.1)
    fail("CONTROL_ORDER", "timeout")


def public_error(response: Response, code: str) -> dict[str, str]:
    if response.status != 404 or set(response.payload) != {"category", "message"}:
        fail(code, f"status={response.status} keys={sorted(response.payload)}")
    category = response.payload["category"]
    message = response.payload["message"]
    if not isinstance(category, str) or not category or not isinstance(message, str) or not message:
        fail(code, "category/message strings required")
    return {"category": category, "message": message}


def row_digest(row: dict[str, Any]) -> str:
    encoded = bytearray()
    for value in row.values():
        if value is None:
            encoded.extend(b"N;")
        else:
            if isinstance(value, datetime):
                value = iso(value)
            data = str(value).encode()
            encoded.extend(str(len(data)).encode() + b":" + data + b";")
    return hashlib.sha256(encoded).hexdigest()


def csv_payload(rows: list[dict[str, Any]]) -> str:
    ensure(bool(rows), "CSV_ROWSET", "empty")
    from io import StringIO

    target = StringIO(newline="")
    writer = csv.DictWriter(target, fieldnames=list(rows[0]), lineterminator="\n")
    writer.writeheader()
    for row in rows:
        writer.writerow({key: "" if value is None else value for key, value in row.items()})
    return target.getvalue()


def sample_csv(samples: list[dict[str, Any]]) -> str:
    return csv_payload([{key: sample[key] for key in SAMPLE_FIELDS} for sample in samples])


def metrics(samples: list[dict[str, Any]], revision: str, q: dict[str, str]) -> dict[str, Any]:
    start = min(sample["startTimestampMs"] for sample in samples)
    end = max(sample["startTimestampMs"] + sample["elapsedMs"] for sample in samples)
    seconds = (end - start) / 1000
    elapsed = sorted(sample["elapsedMs"] for sample in samples)

    def percentile(value: float) -> int:
        return int(elapsed[math.ceil(value * len(elapsed)) - 1])

    errors = dict.fromkeys(
        "transport parse contract unexpectedError unknownClassification lostSample".split(), 0
    )
    result: dict[str, Any] = {"schemaVersion": "cb155-result-v1", "sliceId": "CB-155"}
    result["profileId"] = PROFILE_ID
    result.update({"codeRevision": revision, "valid": True, "sampleCount": 500})
    result.update(
        {"measuredDurationSeconds": round(seconds, 6), "achievedQps": round(500 / seconds, 6)}
    )
    result["latencyMs"] = {
        "p50": percentile(0.50),
        "p95": percentile(0.95),
        "p99": percentile(0.99),
    }
    result["httpStatusDistribution"] = dict(
        sorted(Counter(str(row["responseCode"]) for row in samples).items())
    )
    decisions = Counter(f"{row['state']}/{row['decisionCode']}" for row in samples)
    result.update(
        {"stateDecisionDistribution": dict(sorted(decisions.items())), "errorDistribution": errors}
    )
    result["q01Q09"] = q
    return result


def machine() -> tuple[dict[str, Any], dict[str, Any]]:
    machine_values = (
        os.cpu_count() or 1,
        int(output(["sysctl", "-n", "hw.memsize"])),
        output(["docker", "version", "--format", "{{.Server.Version}}"]),
    )
    host = named_fields("cpuCount memoryBytes dockerVersion", machine_values)
    resources = named_fields(
        "composeVersion declaredCpuLimit declaredMemoryLimit",
        (output(["docker", "compose", "version", "--short"]), "none", "none"),
    )
    return host, resources


def spring_environment(password: str, fixed: list[str], dynamic: dict[str, Any]) -> dict[str, str]:
    properties = dict(item.split("=", 1) for item in fixed)
    properties.update(dynamic)
    environment = {"SPRING_DATASOURCE_PASSWORD": password}
    environment["SPRING_APPLICATION_JSON"] = canonical_json(properties)
    return environment


def control(case: str, status: int, locator: str, body: dict[str, Any]) -> dict[str, Any]:
    return named_fields(
        "case status reservationLocatorHash body", (case, status, locator_hash(locator), body)
    )


def timed_control(
    case: str, observed: datetime, status: int, body: dict[str, Any]
) -> dict[str, Any]:
    return named_fields("case observedAt status body", (case, iso(observed), status, body))


def execute_formal(
    state: State, revision: str, executable: Path, values: dict[str, str]
) -> RunData:
    if state.temp is None or state.env is None:
        fail("RUN_STATE", "not initialized")
    mysql_port = dependency_port(state, "mysql", 3306)
    redis_port = dependency_port(state, "redis-commerce", 6379)
    rocketmq_port = dependency_port(state, "rocketmq-broker-proxy", 8081)
    run_id = state.project.rsplit("-", 1)[-1]
    names = create_messaging(state, run_id)
    fixtures, password = seed(mysql_port, redis_port, values, run_id)
    jdbc_url = f"jdbc:mysql://127.0.0.1:{mysql_port}/commerce_db"
    jdbc_url += "?useSSL=false&allowPublicKeyRetrieval=true"
    private_key = state.temp / "current-private.pem"
    public_key = state.temp / "current-public.pem"
    state.owned_paths.update({"rsa_private_key": private_key, "rsa_public_key": public_key})
    keygen = ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"]
    command([*keygen, "-out", str(private_key)])
    command(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)])
    auth_dynamic = {"spring.datasource.username": "auth_app"}
    auth_dynamic.update({"spring.datasource.url": jdbc_url})
    auth_dynamic.update({"citybuddy.identity.current-kid": f"cb155-key-{run_id}"})
    auth_dynamic.update({"citybuddy.identity.current-private-key-path": str(private_key)})
    auth_dynamic.update({"citybuddy.identity.current-public-key-path": str(public_key)})
    auth_port = start_child(
        state,
        "auth",
        ["java", "-jar", "auth-service/target/auth-service-0.0.1-SNAPSHOT.jar", "--server.port=0"],
        spring_environment(values["MYSQL_AUTH_APP_PASSWORD"], AUTH_FIXED, auth_dynamic),
    )
    wait_http(f"http://127.0.0.1:{auth_port}/auth/jwks", {200})
    commerce_dynamic = {
        "spring.datasource.url": jdbc_url,
        "spring.datasource.username": "commerce_app",
        "spring.data.redis.url": f"redis://:{values['REDIS_COMMERCE_PASSWORD']}@127.0.0.1:{redis_port}/0",
        "citybuddy.catalog.jwks-url": f"http://127.0.0.1:{auth_port}/auth/jwks",
    }
    for prefix, topic, group in (
        ("catalog", "catalogTopic", "catalogGroup"),
        ("seckill.order", "transactionTopic", "transactionGroup"),
        ("seckill.timeout", "timeoutTopic", "timeoutGroup"),
    ):
        commerce_dynamic[f"citybuddy.{prefix}.rocketmq-endpoints"] = f"127.0.0.1:{rocketmq_port}"
        commerce_dynamic[f"citybuddy.{prefix}.rocketmq-topic"] = names[topic]
        commerce_dynamic[f"citybuddy.{prefix}.rocketmq-consumer-group"] = names[group]
    commerce_jar = "commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar"
    commerce_port = start_child(
        state,
        "commerce",
        ["java", "-jar", commerce_jar, "--server.port=0"],
        spring_environment(values["MYSQL_COMMERCE_APP_PASSWORD"], COMMERCE_FIXED, commerce_dynamic),
    )
    wait_http(f"http://127.0.0.1:{commerce_port}/api/products", {401})
    with ThreadPoolExecutor(max_workers=32) as pool:
        tokens = list(pool.map(lambda index: login(auth_port, index, password), range(503)))
    expiries = [jwt_expiry(token) for token in tokens]
    activity = str(fixtures["activity"])
    warm_activity = str(fixtures["warmupActivity"])
    sql_values = {"activityId": activity, "productId": fixtures["product"]}
    sql_values.update({"baselineProductStock": 252, "baselineAllocatedQuota": 252})
    with database(mysql_port, "commerce_app", values["MYSQL_COMMERCE_APP_PASSWORD"]) as db:
        q01 = query(db, "Q01", sql_values)
        warmup = jmeter(executable, state, "warmup", commerce_port, warm_activity, tokens[:32])
        if any(
            row["responseCode"] != 409
            or row["state"] != "REJECTED"
            or row["decisionCode"] != "EXHAUSTED"
            for row in warmup
        ):
            fail("WARMUP_PUBLIC", "all samples must be durable EXHAUSTED")
        for table, condition, expected_count in (
            ("seckill_reservation", "", 32),
            ("seckill_reservation", "AND state='REJECTED'", 32),
            ("seckill_order", "", 0),
            ("inventory_ledger", "", 0),
        ):
            statement = f"SELECT COUNT(*) FROM {table} WHERE activity_id=%s {condition}"
            expect_scalar(db, statement, (warm_activity,), expected_count, "WARMUP_DURABLE")
        q07_submitted_at = datetime.now(UTC)
        q07_initial_response, q07_initial = submit(
            commerce_port, activity, tokens[500], "cb155-q07-control"
        )
        q08_initial_response, q08_initial = submit(
            commerce_port, activity, tokens[501], "cb155-q08-control"
        )
        q07_locator = canonical_uuid(q07_initial_response.payload["reservationId"], "Q07_CONTROL")
        q08_locator = canonical_uuid(q08_initial_response.payload["reservationId"], "Q08_CONTROL")
        wait_ordered(commerce_port, tokens[500], q07_locator, activity)
        wait_ordered(commerce_port, tokens[501], q08_locator, activity)
        cache_password = values["REDIS_COMMERCE_PASSWORD"]
        cache = redis.Redis(
            host="127.0.0.1", port=redis_port, password=cache_password, decode_responses=True
        )
        projection_raw = cache.get(f"commerce:seckill:activity:{activity}")
        projection = json.loads(str(projection_raw or "{}"))
        ensure(projection.get("remainingQuota") == 250, "PRELOAD_QUOTA", repr(projection))
        expect_scalar(
            db,
            "SELECT stock_quantity FROM product WHERE product_id=%s",
            (fixtures["product"],),
            250,
            "PRELOAD_STOCK",
        )
        expect_scalar(
            db,
            "SELECT COUNT(*) FROM inventory_ledger WHERE activity_id=%s AND "
            "movement_type='SECKILL_ORDER_CREATE'",
            (fixtures["activity"],),
            2,
            "PRELOAD_MOVEMENT",
        )
        earliest = sql_scalar(
            db,
            "SELECT UNIX_TIMESTAMP(MIN(unpaid_deadline)) FROM seckill_order WHERE activity_id=%s",
            (fixtures["activity"],),
        )
        ensure(earliest >= int(time.time()) + 90, "CONTROL_DEADLINE_MARGIN")
        preload_done = time.monotonic()
        preload_epoch_ms = int(time.time() * 1000)
        measured = jmeter(executable, state, "measured", commerce_port, activity, tokens[:500])
        measured_hashes = {str(row["reservationLocatorHash"]) for row in measured}
        ensure(len(measured_hashes) == 500, "MEASURED_OWNER_CARDINALITY")
        ensure(time.monotonic() - preload_done <= 70, "MEASURED_HARD_END")
        if min(row["startTimestampMs"] for row in measured) - preload_epoch_ms > 10_000:
            fail("MEASURED_START_BOUND", "JMeter started more than 10 seconds after precheck")
        projection_raw = cache.get(f"commerce:seckill:activity:{activity}")
        projection = json.loads(str(projection_raw or "{}"))
        ensure(projection.get("remainingQuota") == 0, "POSTLOAD_QUOTA", repr(projection))
        for statement in (
            "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id=%s AND state='CANCELLED'",
            "SELECT COUNT(*) FROM seckill_order WHERE activity_id=%s AND status='CANCELLED'",
            "SELECT COUNT(*) FROM inventory_ledger WHERE activity_id=%s AND "
            "movement_type='SECKILL_UNPAID_CANCEL'",
        ):
            if sql_scalar(db, statement, (fixtures["activity"],)) != 0:
                fail("POSTLOAD_EARLY_CANCELLATION", statement[:50])
        settle_deadline = time.monotonic() + 300
        while True:
            observed = datetime.now(UTC)
            settled_values = sql_values | {
                "settleCutoff": observed,
                "observationAt": observed,
                "dispatchSettleCutoff": observed,
            }
            q03 = query(db, "Q03", settled_values)
            q09 = query(db, "Q09", settled_values)
            if time.time() >= earliest and (
                q03
                and q09
                and all(
                    int(value or 0) == 0
                    for key, value in q03[0].items()
                    if key
                    in {"pending_count", "admitted_count", "unknown_state", "overdue_nonterminal"}
                )
                and all(int(value or 0) == 0 for value in q09[0].values())
            ):
                break
            if time.monotonic() >= settle_deadline:
                fail("SETTLEMENT_TIMEOUT", "Q03/Q09 did not close in 300 seconds")
            time.sleep(1)
        settle_cutoff = observed
        q04_controls: list[dict[str, Any]] = []
        with db.cursor() as cursor:
            cursor.execute(
                "SELECT user_subject,reservation_id AS reservationId,activity_id AS activityId,"
                "quantity,activity_projection_version AS activityProjectionVersion,state,"
                "decision_code AS decisionCode,projection_version AS projectionVersion,"
                "order_id AS orderId FROM seckill_reservation "
                "WHERE activity_id=%s",
                (fixtures["activity"],),
            )
            owner_rows = {str(row.pop("user_subject")): dict(row) for row in cursor}
        for index, token in enumerate(tokens[:502]):
            if index < 500:
                durable = owner_rows.get(f"cb155-subject-{index:03d}")
                raw_locator = None if durable is None else str(durable["reservationId"])
                if (
                    not raw_locator
                    or hashlib.sha256(raw_locator.encode()).hexdigest() not in measured_hashes
                ):
                    fail("Q04_OWNER_COLLECTION", str(index))
            else:
                raw_locator = q07_locator if index == 500 else q08_locator
                durable = owner_rows.get(f"cb155-subject-{index:03d}")
            _, terminal = poll(commerce_port, token, str(raw_locator), activity)
            ensure(durable is not None, "Q04_DURABLE_COLLECTION", str(index))
            durable_body = dict(cast(dict[str, Any], durable))
            durable_body["replay"] = terminal["replay"]
            durable_body["durableOrderCreated"] = durable_body["orderId"] is not None
            durable_body = public_body(durable_body, activity, 1, "Q04_DURABLE")
            ensure(terminal == durable_body, "Q04_PUBLIC_DURABLE", str(index))
            q04_controls.append({"public": terminal, "durable": durable_body})
        q07_controls = [
            timed_control("initial", q07_submitted_at, q07_initial_response.status, q07_initial)
        ]
        for _ in range(2):
            response, body = submit(commerce_port, activity, tokens[500], "cb155-q07-control")
            ensure(bool(body["replay"]), "Q07_REPLAY", "replay flag false")
            q07_controls.append(timed_control("replay", datetime.now(UTC), response.status, body))
        q08_before = query(db, "Q08", {"ownershipReservationId": q08_locator})
        ensure(len(q08_before) == 1, "Q08_DURABLE_ROW", str(len(q08_before)))
        _, owner_body = poll(commerce_port, tokens[501], q08_locator, activity)
        unknown_locator = str(uuid.uuid4())
        unknown = get_reservation(commerce_port, tokens[501], unknown_locator)
        other = get_reservation(commerce_port, tokens[502], q08_locator)
        unknown_error = public_error(unknown, "Q08_UNKNOWN")
        other_error = public_error(other, "Q08_OTHER_OWNER")
        ensure(unknown_error == other_error, "Q08_DISCLOSURE", "404 bodies differ")
        q08_after = query(db, "Q08", {"ownershipReservationId": q08_locator})
        before_digest = row_digest(q08_before[0])
        after_digest = row_digest(q08_after[0])
        ensure(before_digest == after_digest, "Q08_MUTATION", "durable row changed")
        observation = datetime.now(UTC)
        token_margin = min(expiries) - int(observation.timestamp())
        ensure(token_margin >= 60, "TOKEN_EXPIRY_MARGIN", str(token_margin))
        final_values = sql_values | {
            "settleCutoff": settle_cutoff,
            "observationAt": observation,
            "dispatchSettleCutoff": observation,
            "replayReservationId": q07_locator,
        }
        q_rows = {
            name: query(db, name, final_values)
            for name in ("Q02", "Q03", "Q04", "Q05", "Q06", "Q07a", "Q07b", "Q09")
        }
        detail = q_rows["Q07a"][0]
        detail["reservation_locator_hash"] = locator_hash(str(detail.pop("reservation_id")))
        order_id = detail.pop("order_id")
        detail.pop("canonical_order_id")
        detail["order_locator_hash"] = None if order_id is None else locator_hash(str(order_id))
        files: dict[str, Any] = {"raw/performance/warmup.csv": sample_csv(warmup)}
        files["raw/performance/measured.csv"] = sample_csv(measured)
        for name in ("Q01", "Q02", "Q03", "Q04", "Q05", "Q06", "Q09"):
            files[f"raw/reconciliation/{name.lower()}.csv"] = csv_payload(
                q01 if name == "Q01" else q_rows[name]
            )
        files["raw/reconciliation/q07-details.csv"] = csv_payload([detail])
        files["raw/reconciliation/q07-duplicates.csv"] = csv_payload(q_rows["Q07b"])
        files["raw/reconciliation/q08.json"] = named_fields(
            "beforeDigest afterDigest", (before_digest, after_digest)
        )
        files.update(
            {"raw/controls/q04.jsonl": q04_controls, "raw/controls/q07.jsonl": q07_controls}
        )
        files["raw/controls/q08.jsonl"] = [
            control("owner", 200, q08_locator, owner_body),
            control("unknown", 404, unknown_locator, unknown_error),
            control("other-owner", 404, q08_locator, other_error),
        ]
        errors = {"expectedSamples": 500, "actualSamples": len(measured)}
        errors.update(dict.fromkeys("unexpectedError unknownClassification lostSample".split(), 0))
        files["raw/controls/q09.json"] = errors
    result = metrics(measured, revision, {f"Q{index:02d}": "PASS" for index in range(1, 10)})
    machine_value, container_value = machine()
    measured_start = min(row["startTimestampMs"] for row in measured)
    measured_end = max(row["startTimestampMs"] + row["elapsedMs"] for row in measured)
    environment = named_fields(
        "scope operatingSystem architecture runtime",
        ("local-docker-compose", platform.system(), platform.machine(), "host-java-compose"),
    )
    warmup_profile = named_fields(
        "activityId productId baselineQuota baselineStock sampleCount threads loopsPerThread "
        "rampSeconds quantity expectedActivityVersion",
        (fixtures["warmupActivity"], fixtures["warmupProduct"], 1, 1, 32, 8, 4, 2, 2, 1),
    )
    workload = named_fields(
        "profileId samples threads loopsPerThread csvRows rampSeconds quantity "
        "expectedActivityVersion",
        (PROFILE_ID, 500, 64, 8, 500, 5, 1, 1),
    )
    duration = named_fields(
        "startTimestampMs endTimestampMs seconds",
        (measured_start, measured_end, result["measuredDurationSeconds"]),
    )
    run_order = (
        "build acquire-jmeter init-local up fixtures auth commerce q01 warmup controls measured "
        "settlement q02-q09 cleanup reconstruct publish"
    ).split()
    manifest: dict[str, Any] = {"schemaVersion": "cb155-manifest-v1", "sliceId": "CB-155"}
    manifest["codeRevision"] = revision
    manifest.update({"environment": environment, "machine": machine_value})
    manifest.update(
        {"containerResources": container_value, "fixtureOrDatasetVersion": "cb155-fixture-v1"}
    )
    manifest.update({"tool": "Apache JMeter", "toolVersion": JMETER_VERSION})
    manifest.update({"warmup": warmup_profile, "concurrencyOrWorkload": workload})
    manifest.update({"measuredDuration": duration, "sampleCount": 500})
    manifest.update(
        {"commands": ["make measure-cb155"], "artifactInventory": [], "cleanupResult": {}}
    )
    manifest.update({"activityId": activity, "productId": fixtures["product"]})
    manifest.update({"activityProjectionVersion": 1, "baselineActivityState": "ACTIVE"})
    manifest.update({"baselineAllocatedQuota": 252, "baselineProductStock": 252})
    manifest.update({"settleCutoff": iso(settle_cutoff), "observationAt": iso(observation)})
    manifest.update({"dispatchSettleCutoff": iso(observation), "unpaidTimeoutSeconds": 120})
    manifest.update({"settlementTimeoutSeconds": 300, "jmeterConnectTimeoutMs": 2000})
    manifest.update({"jmeterResponseTimeoutMs": 10000, "runOrder": run_order})
    return RunData(manifest, result, files)


def resource_count(arguments: list[str], project: str) -> int:
    text = output([*arguments, "--filter", f"label=com.docker.compose.project={project}"])
    return len([line for line in text.splitlines() if line])


def cleanup(state: State) -> tuple[dict[str, Any], Exception | None]:
    error: Exception | None = None
    child_rows: list[dict[str, Any]] = []
    for child in reversed(state.children):
        try:
            if child.process.poll() is None:
                child.process.terminate()
                try:
                    child.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.process.kill()
                    child.process.wait(timeout=5)
            absent = (
                subprocess.run(["ps", "-p", str(child.process.pid)], capture_output=True).returncode
                != 0
            )
            child_rows.append({"kind": child.kind, "absent": absent})
        except Exception as caught:
            error = error or caught
            child_rows.append({"kind": child.kind, "absent": False})
    if state.env_created and state.env is not None:
        try:
            command(
                [
                    "make",
                    "reset-local",
                    "CONFIRM_RESET_LOCAL=1",
                    f"ENV_FILE={state.env}",
                    f"COMPOSE_PROJECT_NAME={state.project}",
                ],
                timeout=600,
            )
        except Exception as caught:
            error = error or caught
    try:
        containers = resource_count(["docker", "ps", "-aq"], state.project)
        networks = resource_count(["docker", "network", "ls", "-q"], state.project)
        volumes = resource_count(["docker", "volume", "ls", "-q"], state.project)
    except Exception as caught:
        error = error or caught
        containers = networks = volumes = -1
    if state.temp is not None:
        try:
            shutil.rmtree(state.temp)
        except Exception as caught:
            error = error or caught
    path_rows = [
        {"kind": kind, "absent": not path.exists()}
        for kind, path in sorted(state.owned_paths.items())
    ]
    if state.temp is not None:
        path_rows.append({"kind": "temporary_directory", "absent": not state.temp.exists()})
    if not state.env_created:
        path_rows.append({"kind": "run_env_never_created", "absent": True})
    clean = error is None and containers == networks == volumes == 0
    clean = clean and all(bool(row["absent"]) for row in child_rows + path_rows)
    residue: dict[str, Any] = {}
    residue["projectDigest"] = hashlib.sha256(state.project.encode()).hexdigest()
    residue.update({"cleanupStatus": "PASS" if clean else "FAIL", "containers": containers})
    residue.update({"networks": networks, "volumes": volumes})
    residue.update({"children": child_rows, "paths": path_rows})
    if residue["cleanupStatus"] != "PASS" and error is None:
        error = RuntimeError("CLEANUP_RESIDUE: nonzero owned resource")
    return residue, error


def write_payload(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(value, list):
        text = "".join(canonical_json(row) for row in value)
    else:
        text = value if isinstance(value, str) else canonical_json(value)
    path.write_text(text, encoding="utf-8", newline="\n")


def record_count(path: Path) -> int:
    if path.suffix == ".csv":
        return max(0, sum(1 for _ in path.open(encoding="utf-8")) - 1)
    if path.suffix == ".jsonl":
        return sum(1 for _ in path.open(encoding="utf-8"))
    return 1


def canonical_guard(paths: list[Path], message: str) -> None:
    before = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
    hook_env = {"UV_CACHE_DIR": "/private/tmp/cb155-uv-cache"}
    for hook in ("trailing-whitespace", "end-of-file-fixer", "mixed-line-ending"):
        command(["uv", "run", "pre-commit", "run", hook, "--files", *map(str, paths)], env=hook_env)
    after = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
    if before != after:
        fail("CANONICALIZATION_REWRITE", message)


def publish(data: RunData, residue: dict[str, Any], state: State) -> None:
    parent = FINAL_BUNDLE.parent
    parent.mkdir(parents=True, exist_ok=True)
    staging = parent / f".CB-155-staging-{os.getpid()}"
    if staging.exists() or FINAL_BUNDLE.exists():
        fail("ATOMIC_PUBLICATION", "target or owned staging already exists")
    staging.mkdir(mode=0o700)
    try:
        data.files["raw/residue.json"] = residue
        for relative, value in data.files.items():
            write_payload(staging / relative, value)
        inventory: list[dict[str, Any]] = []
        for path in sorted((staging / "raw").rglob("*")):
            if path.is_file():
                relative = path.relative_to(staging).as_posix()
                media = {".csv": "text/csv", ".jsonl": "application/x-ndjson"}.get(
                    path.suffix, "application/json"
                )
                inventory.append(
                    named_fields(
                        "path bytes records mediaType",
                        (relative, path.stat().st_size, record_count(path), media),
                    )
                )
        ensure(sum(item["bytes"] for item in inventory) <= 50 * 1024 * 1024, "RAW_SIZE_LIMIT")
        data.manifest["artifactInventory"] = inventory
        cleanup_values = (
            "PASS",
            residue["containers"],
            residue["networks"],
            residue["volumes"],
            sum(not row["absent"] for row in residue["children"]),
            all(row["absent"] for row in residue["paths"]),
        )
        data.manifest["cleanupResult"] = named_fields(
            "status containers networks volumes children pathsAbsent", cleanup_values
        )
        write_payload(staging / "manifest.json", data.manifest)
        write_payload(staging / "result.json", data.result)
        payloads = sorted(path for path in staging.rglob("*") if path.is_file())
        canonical_guard(payloads, "producer emitted noncanonical text")
        checksums = "".join(
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  "
            f"{path.relative_to(staging).as_posix()}\n"
            for path in payloads
        )
        write_payload(staging / "checksums.sha256", checksums)
        complete = [*payloads, staging / "checksums.sha256"]
        canonical_guard(complete, "checksum pass rewrote payload")
        from check_cb155_measurement import verify_bundle

        reconstructed = verify_bundle(staging)
        ensure(reconstructed == data.result, "RECONSTRUCTION", "checker result mismatch")
        os.replace(staging, FINAL_BUNDLE)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main() -> None:
    revision = source_gate()
    ensure(not FINAL_BUNDLE.exists(), "RESULT_SELECTION", "CB-155 final bundle already exists")
    state = State(project=f"citybuddy-cb155-{os.getpid()}")
    data: RunData | None = None
    primary: Exception | None = None
    try:
        build = ["./mvnw", "-q", "-pl", "auth-service,commerce-service", "-am", "-DskipTests"]
        command([*build, "package"], timeout=900)
        state.temp = Path(tempfile.mkdtemp(prefix="citybuddy-cb155-"))
        state.temp.chmod(0o700)
        docker_config = state.temp / "docker-config"
        docker_config.mkdir(mode=0o700)
        state.owned_paths["docker_client_config"] = docker_config
        os.environ["DOCKER_CONFIG"] = str(docker_config)
        state.env = state.temp / "run.env"
        state.owned_paths["run_env"] = state.env
        executable = acquire_jmeter(state)
        command(["make", "init-local", f"ENV_FILE={state.env}"])
        state.env_created = True
        values = env_values(state.env)
        command(
            ["make", "up", f"ENV_FILE={state.env}", f"COMPOSE_PROJECT_NAME={state.project}"],
            timeout=1200,
        )
        data = execute_formal(state, revision, executable, values)
    except Exception as error:
        primary = error
    residue, cleanup_error = cleanup(state)
    if primary is not None:
        if cleanup_error is not None:
            print(f"cleanup_after_primary_failure={cleanup_error}", file=sys.stderr)
        raise primary
    if cleanup_error is not None:
        raise cleanup_error
    if data is None:
        fail("RUN_RESULT", "missing")
    publish(data, residue, state)


if __name__ == "__main__":
    main()
