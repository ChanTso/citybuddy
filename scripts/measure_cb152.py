#!/usr/bin/env python3
"""One-process, one-command CB-152 seckill measurement lifecycle."""

from __future__ import annotations

import csv
import datetime as dt
import hashlib
import io
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from http.client import IncompleteRead
from pathlib import Path
from typing import Any

from check_cb152_bundle import BundleError, reconstruct, strict_json_text

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE_ROOT = ROOT / "evidence/measurements"
FINAL_BUNDLE = EVIDENCE_ROOT / "CB-152"
JMX_PLAN = ROOT / "scripts/cb152_seckill.jmx"
CHECKER = ROOT / "scripts/check_cb152_bundle.py"
JMETER_VERSION = "5.6.3"
JMETER_ARCHIVE = f"apache-jmeter-{JMETER_VERSION}.tgz"
JMETER_URL = f"https://downloads.apache.org/jmeter/binaries/{JMETER_ARCHIVE}"
JMETER_CHECKSUM_URL = f"{JMETER_URL}.sha512"
JMETER_SHA512 = (
    "5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093"
    "e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083"
)
JAVA_RUNTIME_IMAGE = (
    "maven:3.9.11-eclipse-temurin-21@"
    "sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237"
)
FIXTURE_VERSION = "cb152-seckill-v1"
ISSUER = "https://identity.citybuddy.test"
AUDIENCE = "citybuddy-web"
PROJECTION_VERSION = 1
MAX_RAW_BYTES = 50 * 1024 * 1024
MAX_FILES = 32
MAX_RECORDS = 10_000
UUID_RE = re.compile(
    rb"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b"
)


@dataclass(frozen=True)
class Profile:
    name: str
    sample_count: int
    threads: int
    loops: int
    ramp_seconds: int
    warmup_samples: int
    warmup_threads: int
    warmup_ramp_seconds: int
    unpaid_timeout_seconds: int
    settlement_timeout_seconds: int
    connect_timeout_ms: int = 5_000
    response_timeout_ms: int = 15_000


PROFILES = {
    "smoke": Profile("smoke", 8, 2, 4, 1, 2, 1, 1, 30, 120),
    "formal": Profile("formal", 200, 20, 10, 5, 4, 2, 1, 120, 240),
}


@dataclass
class Child:
    kind: str
    process: subprocess.Popen[bytes]
    log_path: Path
    container_name: str | None = None


@dataclass
class HttpResult:
    status: int
    body: bytes


@dataclass
class PublicRecord:
    sanitized: dict[str, Any]
    reservation_id: str | None
    order_id: str | None


@dataclass
class RunState:
    profile: Profile
    temp_dir: Path
    env_file: Path
    project: str
    code_revision: str
    docker_config_dir: Path | None = None
    first_failure: BaseException | None = None
    children: list[Child] = field(default_factory=list)
    secrets: list[bytes] = field(default_factory=list)
    absent_paths: list[tuple[str, Path]] = field(default_factory=list)
    mysql_port: int = 0
    redis_port: int = 0
    rocketmq_port: int = 0
    auth_port: int = 0
    commerce_port: int = 0
    env: dict[str, str] = field(default_factory=dict)
    hash_salt: bytes = b""
    env_created: bool = False
    init_local_started: bool = False
    init_local_completed: bool = False
    compose_up_started: bool = False


@dataclass(frozen=True)
class JMeterTransferDiagnostics:
    http_status: int
    final_url: str
    content_length: int | None
    actual_bytes: int
    actual_sha512: str
    official_sha512: str
    pinned_sha512: str


def run(
    args: Sequence[str],
    *,
    cwd: Path = ROOT,
    env: dict[str, str] | None = None,
    input_text: str | None = None,
    capture: bool = True,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        list(args),
        cwd=cwd,
        env=env,
        input=input_text,
        text=True,
        capture_output=capture,
        check=False,
    )
    if check and completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        raise RuntimeError(f"command failed ({completed.returncode}): {' '.join(args)}\n{detail}")
    return completed


def compose(state: RunState, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return run(
        [
            "docker",
            "compose",
            "--project-name",
            state.project,
            "--env-file",
            str(state.env_file),
            "--file",
            "compose.yaml",
            *args,
        ],
        check=check,
    )


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.UTC)


def iso(value: dt.datetime) -> str:
    return value.astimezone(dt.UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def sql_time(value: dt.datetime) -> str:
    return value.astimezone(dt.UTC).strftime("%Y-%m-%d %H:%M:%S.%f")


def canonical_json(value: Any) -> bytes:
    return (
        json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"
    ).encode()


def canonical_jsonl(rows: Iterable[dict[str, Any]]) -> bytes:
    return b"".join(canonical_json(row) for row in rows)


def canonical_csv(header: Sequence[str], rows: Iterable[Sequence[Any]]) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(header)
    writer.writerows(rows)
    return output.getvalue().encode()


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            name, value = line.split("=", 1)
            values[name] = value
    return values


def exact_port(state: RunState, service: str, container_port: int) -> int:
    binding = compose(state, "port", service, str(container_port)).stdout.strip()
    match = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", binding)
    if not match or not (0 < int(match.group(1)) <= 65535):
        raise RuntimeError(f"invalid dynamic port for {service}:{container_port}: {binding}")
    return int(match.group(1))


def jmeter_paths(state: RunState) -> tuple[Path, Path, Path, Path]:
    archive = state.temp_dir / JMETER_ARCHIVE
    partial_archive = state.temp_dir / f"{JMETER_ARCHIVE}.part"
    checksum_file = state.temp_dir / f"{JMETER_ARCHIVE}.sha512"
    install = state.temp_dir / "jmeter-install"
    state.absent_paths.extend(
        [("jmeterArchive", archive), ("jmeterChecksum", checksum_file), ("jmeterInstall", install)]
    )
    return archive, partial_archive, checksum_file, install


def sanitized_public_url(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    hostname = parsed.hostname or ""
    if ":" in hostname and not hostname.startswith("["):
        hostname = f"[{hostname}]"
    netloc = hostname
    if parsed.port is not None:
        netloc = f"{netloc}:{parsed.port}"
    return urllib.parse.urlunsplit((parsed.scheme, netloc, parsed.path, "", ""))


def transfer_diagnostic_text(
    *,
    http_status: int | str,
    final_url: str,
    content_length: int | str | None,
    actual_bytes: int,
    actual_sha512: str,
    official_sha512: str,
    pinned_sha512: str,
) -> str:
    content = "absent" if content_length is None else str(content_length)
    expected = content if isinstance(content_length, int) else "absent"
    return (
        f"httpStatus={http_status} finalUrl={final_url} contentLength={content} "
        f"expectedBytes={expected} actualBytes={actual_bytes} "
        f"actualSha512={actual_sha512} officialSha512={official_sha512} "
        f"pinnedSha512={pinned_sha512}"
    )


def acquire_jmeter_archive(
    url: str,
    final_archive: Path,
    partial_archive: Path,
    *,
    official_sha512: str,
    pinned_sha512: str,
    timeout_seconds: int = 60,
) -> JMeterTransferDiagnostics:
    digest = hashlib.sha512()
    actual_bytes = 0
    http_status: int | str = "unavailable"
    final_url = sanitized_public_url(url)
    content_length: int | str | None = None
    response: Any = None
    output: Any = None
    failure: BaseException | None = None

    def diagnostic_error(label: str, exc: BaseException) -> RuntimeError:
        detail = transfer_diagnostic_text(
            http_status=http_status,
            final_url=final_url,
            content_length=content_length,
            actual_bytes=actual_bytes,
            actual_sha512=digest.hexdigest(),
            official_sha512=official_sha512,
            pinned_sha512=pinned_sha512,
        )
        return RuntimeError(f"{label}: {detail} errorType={type(exc).__name__}")

    def retain_cleanup_failure(
        primary: BaseException | None, *, kind: str, error: BaseException
    ) -> BaseException:
        cleanup = RuntimeError(
            f"JMeter archive cleanup failed: kind={kind} errorType={type(error).__name__}"
        )
        if primary is None:
            return cleanup
        primary.add_note(str(cleanup))
        return primary

    for kind, path in (("partialArchive", partial_archive), ("finalArchive", final_archive)):
        try:
            path.unlink(missing_ok=True)
        except BaseException as exc:
            failure = retain_cleanup_failure(failure, kind=kind, error=exc)
    if failure is not None:
        raise failure

    try:
        try:
            response = urllib.request.urlopen(url, timeout=timeout_seconds)
        except BaseException as exc:
            if isinstance(exc, urllib.error.HTTPError):
                response = exc
                http_status = exc.code
                final_url = sanitized_public_url(exc.geturl())
                raw_content_length = exc.headers.get("Content-Length")
                if raw_content_length is not None:
                    content_length = (
                        int(raw_content_length)
                        if re.fullmatch(r"[0-9]+", raw_content_length)
                        else "invalid"
                    )
            raise diagnostic_error("JMeter archive request failed", exc) from exc
        response_status = getattr(response, "status", None)
        if response_status is None:
            response_status = response.getcode()
        http_status = int(response_status)
        final_url = sanitized_public_url(response.geturl())
        raw_content_length = response.headers.get("Content-Length")
        if raw_content_length is not None:
            if not re.fullmatch(r"[0-9]+", raw_content_length):
                content_length = "invalid"
                detail = transfer_diagnostic_text(
                    http_status=http_status,
                    final_url=final_url,
                    content_length=content_length,
                    actual_bytes=actual_bytes,
                    actual_sha512=digest.hexdigest(),
                    official_sha512=official_sha512,
                    pinned_sha512=pinned_sha512,
                )
                raise RuntimeError(f"JMeter archive invalid Content-Length: {detail}")
            content_length = int(raw_content_length)
        if http_status != 200:
            detail = transfer_diagnostic_text(
                http_status=http_status,
                final_url=final_url,
                content_length=content_length,
                actual_bytes=actual_bytes,
                actual_sha512=digest.hexdigest(),
                official_sha512=official_sha512,
                pinned_sha512=pinned_sha512,
            )
            raise RuntimeError(f"JMeter archive HTTP status rejected: {detail}")
        try:
            output = partial_archive.open("wb")
        except BaseException as exc:
            raise diagnostic_error("JMeter archive transfer failed", exc) from exc
        while True:
            try:
                chunk = response.read(1024 * 1024)
            except BaseException as exc:
                partial = getattr(exc, "partial", b"")
                if isinstance(partial, bytes) and partial:
                    digest.update(partial)
                    actual_bytes += len(partial)
                if isinstance(exc, IncompleteRead) and isinstance(content_length, int):
                    raise diagnostic_error("JMeter archive transfer incomplete", exc) from exc
                raise diagnostic_error("JMeter archive transfer failed", exc) from exc
            if not chunk:
                break
            digest.update(chunk)
            actual_bytes += len(chunk)
            try:
                output.write(chunk)
            except BaseException as exc:
                raise diagnostic_error("JMeter archive transfer failed", exc) from exc
        try:
            output.close()
        except BaseException as exc:
            raise diagnostic_error("JMeter archive transfer failed", exc) from exc
        output = None
        try:
            response.close()
        except BaseException as exc:
            raise diagnostic_error("JMeter archive transfer failed", exc) from exc
        response = None
        actual_sha512 = digest.hexdigest()
        detail = transfer_diagnostic_text(
            http_status=http_status,
            final_url=final_url,
            content_length=content_length,
            actual_bytes=actual_bytes,
            actual_sha512=actual_sha512,
            official_sha512=official_sha512,
            pinned_sha512=pinned_sha512,
        )
        if isinstance(content_length, int) and actual_bytes != content_length:
            raise RuntimeError(f"JMeter archive transfer incomplete: {detail}")
        if actual_sha512 != pinned_sha512 or official_sha512 != pinned_sha512:
            raise RuntimeError(f"JMeter archive checksum mismatch: {detail}")
        try:
            os.replace(partial_archive, final_archive)
        except BaseException as exc:
            raise diagnostic_error("JMeter archive publication failed", exc) from exc
        return JMeterTransferDiagnostics(
            http_status=int(http_status),
            final_url=final_url,
            content_length=content_length if isinstance(content_length, int) else None,
            actual_bytes=actual_bytes,
            actual_sha512=actual_sha512,
            official_sha512=official_sha512,
            pinned_sha512=pinned_sha512,
        )
    except BaseException as exc:
        failure = exc
    finally:
        if output is not None:
            try:
                output.close()
            except BaseException as exc:
                failure = retain_cleanup_failure(failure, kind="partialArchiveHandle", error=exc)
        if response is not None:
            try:
                response.close()
            except BaseException as exc:
                failure = retain_cleanup_failure(failure, kind="responseHandle", error=exc)
        if failure is not None:
            for kind, path in (
                ("partialArchive", partial_archive),
                ("finalArchive", final_archive),
            ):
                try:
                    path.unlink(missing_ok=True)
                except BaseException as exc:
                    failure = retain_cleanup_failure(failure, kind=kind, error=exc)
    if failure is not None:
        raise failure
    raise AssertionError("JMeter acquisition ended without a result or failure")


def download_jmeter(state: RunState) -> Path:
    archive, partial_archive, checksum_file, install = jmeter_paths(state)
    with urllib.request.urlopen(JMETER_CHECKSUM_URL, timeout=30) as response:
        checksum_bytes = response.read(1024)
    checksum_file.write_bytes(checksum_bytes)
    official_sha512 = validate_jmeter_checksum(checksum_bytes)
    diagnostics = acquire_jmeter_archive(
        JMETER_URL,
        archive,
        partial_archive,
        official_sha512=official_sha512,
        pinned_sha512=JMETER_SHA512,
    )
    print(
        "CB-152 JMeter acquisition: PASS "
        + transfer_diagnostic_text(
            http_status=diagnostics.http_status,
            final_url=diagnostics.final_url,
            content_length=diagnostics.content_length,
            actual_bytes=diagnostics.actual_bytes,
            actual_sha512=diagnostics.actual_sha512,
            official_sha512=diagnostics.official_sha512,
            pinned_sha512=diagnostics.pinned_sha512,
        )
    )
    install.mkdir(mode=0o700)
    with tarfile.open(archive, mode="r:gz") as source:
        members = source.getmembers()
        for member in members:
            member_target = (install / member.name).resolve()
            if (
                install.resolve() not in member_target.parents
                and member_target != install.resolve()
            ):
                raise RuntimeError("unsafe path in JMeter archive")
            if member.issym() or member.islnk():
                raise RuntimeError("links are forbidden in JMeter archive")
        source.extractall(install, members=members, filter="data")
    binary = install / f"apache-jmeter-{JMETER_VERSION}/bin/jmeter"
    if not binary.is_file():
        raise RuntimeError("pinned JMeter binary is missing after extraction")
    return binary


def validate_jmeter_checksum(checksum_bytes: bytes) -> str:
    fields = checksum_bytes.decode("ascii").strip().split()
    if (
        len(fields) != 2
        or fields[0].lower() != JMETER_SHA512
        or fields[1].lstrip("*") != JMETER_ARCHIVE
    ):
        raise RuntimeError("official JMeter checksum file does not match the pinned release")
    return fields[0].lower()


def preserve_first_failure(
    primary: BaseException | None, cleanup_failure: BaseException | None
) -> BaseException | None:
    return primary if primary is not None else cleanup_failure


def mysql_command(state: RunState, user: str, password: str, sql: str) -> str:
    completed = compose(
        state,
        "exec",
        "-T",
        "-e",
        f"MYSQL_PWD={password}",
        "mysql",
        "mysql",
        "--protocol=tcp",
        "--host=127.0.0.1",
        "--port=3306",
        f"--user={user}",
        "--database=commerce_db",
        "--batch",
        "--raw",
        "--execute",
        sql,
    )
    return completed.stdout


def prepared_query(
    state: RunState, sql: str, values: Sequence[str | int]
) -> tuple[list[str], list[list[str]]]:
    assignments: list[str] = []
    names: list[str] = []
    for index, value in enumerate(values):
        name = f"@cb152_p{index}"
        names.append(name)
        if isinstance(value, int):
            assignments.append(f"SET {name} = {value};")
        else:
            assignments.append(f"SET {name} = CONVERT(0x{value.encode().hex()} USING utf8mb4);")
    statement = sql.encode().hex()
    script = "\n".join(
        [
            *assignments,
            f"SET @cb152_sql = CONVERT(0x{statement} USING utf8mb4);",
            "PREPARE cb152_stmt FROM @cb152_sql;",
            f"EXECUTE cb152_stmt USING {','.join(names)};" if names else "EXECUTE cb152_stmt;",
            "DEALLOCATE PREPARE cb152_stmt;",
        ]
    )
    output = mysql_command(state, "commerce_app", state.env["MYSQL_COMMERCE_APP_PASSWORD"], script)
    lines = output.splitlines()
    if not lines:
        raise RuntimeError("prepared query returned no header")
    return lines[0].split("\t"), [line.split("\t") for line in lines[1:]]


def mysql_scalar(state: RunState, sql: str, values: Sequence[str | int]) -> int:
    header, rows = prepared_query(state, sql, values)
    if len(header) != 1 or len(rows) != 1 or len(rows[0]) != 1:
        raise RuntimeError("scalar query returned an unexpected shape")
    return int(rows[0][0])


def http(
    method: str,
    url: str,
    *,
    token: str | None = None,
    headers: dict[str, str] | None = None,
    body: dict[str, Any] | None = None,
    timeout: float = 20,
) -> HttpResult:
    request_headers = dict(headers or {})
    data = None
    if token is not None:
        request_headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        request_headers["Content-Type"] = "application/json"
        data = json.dumps(body, separators=(",", ":")).encode()
    request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return HttpResult(response.status, response.read(1024 * 1024))
    except urllib.error.HTTPError as exc:
        return HttpResult(exc.code, exc.read(1024 * 1024))


def wait_http(url: str, child: Child, *, expected: set[int], timeout: float = 60) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if child.process.poll() is not None:
            log_text = child.log_path.read_text(errors="replace")
            raise RuntimeError(f"{child.kind} exited before readiness: {log_text}")
        try:
            if http("GET", url, timeout=2).status in expected:
                return
        except OSError:
            pass
        time.sleep(0.25)
    raise RuntimeError(f"timed out waiting for {url}: {child.log_path.read_text(errors='replace')}")


def published_port(child: Child, timeout: float = 60) -> int:
    deadline = time.monotonic() + timeout
    assert child.container_name is not None
    while time.monotonic() < deadline:
        if child.process.poll() is not None:
            log_text = child.log_path.read_text(errors="replace")
            raise RuntimeError(f"{child.kind} exited before port publication: {log_text}")
        completed = run(["docker", "port", child.container_name, "8080/tcp"], check=False)
        if completed.returncode == 0 and completed.stdout.strip():
            match = re.fullmatch(r"127\.0\.0\.1:([0-9]+)", completed.stdout.strip())
            if match and 0 < int(match.group(1)) <= 65535:
                return int(match.group(1))
        time.sleep(0.1)
    raise RuntimeError(f"timed out reading {child.kind} dynamic port")


def spawn_child(state: RunState, kind: str, args: Sequence[str], env: dict[str, str]) -> Child:
    log = state.temp_dir / f"{kind}.log"
    handle = log.open("wb")
    process = subprocess.Popen(
        list(args), cwd=ROOT, env=env, stdout=handle, stderr=subprocess.STDOUT
    )
    handle.close()
    child = Child(kind, process, log)
    state.children.append(child)
    return child


def spawn_application_child(
    state: RunState,
    kind: str,
    args: Sequence[str],
    env: dict[str, str],
    *,
    network_alias: str,
) -> Child:
    container_name = f"{state.project}-{kind}"
    home = state.temp_dir / f"{kind}-home"
    home.mkdir(mode=0o700)
    child = spawn_child(
        state,
        kind,
        [
            "docker",
            "run",
            "--rm",
            "--name",
            container_name,
            "--label",
            f"com.docker.compose.project={state.project}",
            "--network",
            f"{state.project}_default",
            "--network-alias",
            network_alias,
            "--publish",
            "127.0.0.1::8080",
            "--user",
            f"{os.getuid()}:{os.getgid()}",
            "--env",
            "SPRING_DATASOURCE_PASSWORD",
            "--env",
            f"HOME=/run/cb152/{kind}-home",
            "--volume",
            f"{ROOT}:/workspace:ro",
            "--volume",
            f"{state.temp_dir}:/run/cb152:rw",
            "--workdir",
            "/workspace",
            JAVA_RUNTIME_IMAGE,
            *args,
        ],
        env,
    )
    child.container_name = container_name
    return child


def stop_children(state: RunState) -> None:
    for child in reversed(state.children):
        if child.process.poll() is None:
            if child.container_name is not None:
                run(["docker", "stop", "--time", "15", child.container_name], check=False)
            else:
                child.process.send_signal(signal.SIGTERM)
    deadline = time.monotonic() + 20
    for child in reversed(state.children):
        remaining = max(0.0, deadline - time.monotonic())
        try:
            child.process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            child.process.kill()
            child.process.wait(timeout=5)


def hash_locator(state: RunState, kind: str, value: str | None) -> str | None:
    if value is None:
        return None
    return hashlib.sha256(state.hash_salt + f"cb152:{kind}:".encode() + value.encode()).hexdigest()


def canonical_uuid(value: Any) -> str | None:
    if not isinstance(value, str) or len(value) != 36:
        return None
    try:
        return value if str(uuid.UUID(value)) == value else None
    except ValueError:
        return None


def classify_public(
    state: RunState,
    response: HttpResult,
    *,
    operation: str,
    expected_activity_id: str,
) -> PublicRecord:
    empty = {
        "classification": "UNKNOWN",
        "responseCode": response.status,
        "activityId": None,
        "quantity": None,
        "activityProjectionVersion": None,
        "state": None,
        "decisionCode": None,
        "projectionVersion": None,
        "durableOrderCreated": None,
        "replay": None,
        "reservationLocatorHash": None,
        "orderLocatorHash": None,
    }
    try:
        body = strict_json_text(
            response.body.decode("utf-8"), source=f"public {operation} response"
        )
    except (UnicodeDecodeError, BundleError):
        empty["classification"] = (
            "TRANSPORT_ERROR"
            if response.status <= 0
            else "PARSE_ERROR"
            if response.status in {200, 201, 202, 409}
            else "UNEXPECTED_ERROR"
        )
        return PublicRecord(empty, None, None)
    classification = (
        "TRANSPORT_ERROR"
        if response.status <= 0
        else "PARSE_ERROR"
        if response.status in {200, 201, 202, 409}
        else "UNEXPECTED_ERROR"
    )
    reservation_id = order_id = None
    if not isinstance(body, dict):
        pass
    elif set(body) == {"category", "message"}:
        if all(isinstance(body[key], str) and body[key] for key in ("category", "message")):
            classification = "UNEXPECTED_ERROR"
    else:
        expected = {
            "reservationId",
            "activityId",
            "quantity",
            "activityProjectionVersion",
            "state",
            "decisionCode",
            "projectionVersion",
            "replay",
            "durableOrderCreated",
            "orderId",
        }
        if set(body) == expected:
            reservation_id = canonical_uuid(body["reservationId"])
            order_id = None if body["orderId"] is None else canonical_uuid(body["orderId"])
            state_name = body["state"]
            decision = body["decisionCode"]
            projection = body["projectionVersion"]
            durable = body["durableOrderCreated"]
            replay = body["replay"]
            quantity = body["quantity"]
            activity_version = body["activityProjectionVersion"]
            try:
                if reservation_id is None or (body["orderId"] is not None and order_id is None):
                    raise ValueError
                if body["activityId"] != expected_activity_id:
                    raise ValueError
                if quantity != 1 or isinstance(quantity, bool):
                    raise ValueError
                if activity_version != PROJECTION_VERSION or isinstance(activity_version, bool):
                    raise ValueError
                if operation not in {"submit", "poll"}:
                    raise ValueError
                if not isinstance(durable, bool) or not isinstance(replay, bool):
                    raise ValueError
                if state_name not in {"PENDING", "ADMITTED", "REJECTED", "ORDERED", "CANCELLED"}:
                    raise ValueError
                if decision not in {
                    None,
                    "ADMITTED",
                    "ACTIVITY_INACTIVE",
                    "NOT_OPEN",
                    "EXPIRED",
                    "STALE_VERSION",
                    "EXHAUSTED",
                    "DUPLICATE_USER",
                    "TRANSACTION_TIMEOUT",
                }:
                    raise ValueError
                if not isinstance(projection, int) or isinstance(projection, bool):
                    raise ValueError
                expected_projection = {
                    "PENDING": 1,
                    "ADMITTED": 2,
                    "REJECTED": 2,
                    "ORDERED": 3,
                    "CANCELLED": 4,
                }[state_name]
                if projection != expected_projection:
                    raise ValueError
                if state_name == "PENDING" and decision is not None:
                    raise ValueError
                if state_name == "REJECTED" and (decision is None or decision == "ADMITTED"):
                    raise ValueError
                if state_name in {"ADMITTED", "ORDERED", "CANCELLED"} and decision != "ADMITTED":
                    raise ValueError
                if durable != (state_name in {"ORDERED", "CANCELLED"}) or durable != (
                    order_id is not None
                ):
                    raise ValueError
                allowed_status = {
                    "submit": {
                        "PENDING": {202} if replay is False else set(),
                        "ADMITTED": {200} if replay else {201},
                        "REJECTED": {409},
                        "ORDERED": {200} if replay else set(),
                        "CANCELLED": {200} if replay else set(),
                    },
                    "poll": {
                        state: {200}
                        for state in ("PENDING", "ADMITTED", "REJECTED", "ORDERED", "CANCELLED")
                    },
                }
                if response.status not in allowed_status[operation][state_name]:
                    raise ValueError
            except (KeyError, ValueError):
                reservation_id = order_id = None
            else:
                classification = "BUSINESS"
    sanitized = dict(empty)
    sanitized["classification"] = classification
    if classification == "BUSINESS":
        for key in (
            "activityId",
            "quantity",
            "activityProjectionVersion",
            "state",
            "decisionCode",
            "projectionVersion",
            "durableOrderCreated",
            "replay",
        ):
            sanitized[key] = body[key]
        sanitized["reservationLocatorHash"] = hash_locator(state, "reservation", reservation_id)
        sanitized["orderLocatorHash"] = hash_locator(state, "order", order_id)
    return PublicRecord(sanitized, reservation_id, order_id)


def submit(
    state: RunState,
    token: str,
    activity_id: str,
    idempotency_key: str,
) -> PublicRecord:
    response = http(
        "POST",
        f"http://127.0.0.1:{state.commerce_port}/api/seckill/activities/{activity_id}/reservations",
        token=token,
        headers={"Idempotency-Key": idempotency_key},
        body={"quantity": 1, "expectedActivityVersion": PROJECTION_VERSION},
    )
    return classify_public(state, response, operation="submit", expected_activity_id=activity_id)


def poll(
    state: RunState, token: str, reservation_id: str, activity_id: str
) -> tuple[HttpResult, PublicRecord]:
    response = http(
        "GET",
        f"http://127.0.0.1:{state.commerce_port}/api/reservations/{reservation_id}",
        token=token,
    )
    return response, classify_public(
        state, response, operation="poll", expected_activity_id=activity_id
    )


def wait_terminal(
    state: RunState,
    token: str,
    reservation_id: str,
    activity_id: str,
    *,
    require_cancelled: bool = False,
) -> PublicRecord:
    deadline = time.monotonic() + state.profile.settlement_timeout_seconds
    last: PublicRecord | None = None
    while time.monotonic() < deadline:
        _, last = poll(state, token, reservation_id, activity_id)
        if last.sanitized["classification"] != "BUSINESS":
            raise RuntimeError("public poll failed closed classification")
        state_name = last.sanitized["state"]
        if require_cancelled and state_name == "CANCELLED":
            return last
        if not require_cancelled and state_name in {"REJECTED", "ORDERED", "CANCELLED"}:
            return last
        time.sleep(0.25)
    raise RuntimeError(f"reservation did not reach required terminal state: {last}")


def start_services(state: RunState, private_key: Path, public_key: Path) -> None:
    common_env = os.environ.copy()
    auth_env = common_env | {"SPRING_DATASOURCE_PASSWORD": state.env["MYSQL_AUTH_APP_PASSWORD"]}
    auth = spawn_application_child(
        state,
        "auth",
        [
            "java",
            "-jar",
            "auth-service/target/auth-service-0.0.1-SNAPSHOT.jar",
            "--server.port=8080",
            "--spring.datasource.url=jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true",
            "--spring.datasource.username=auth_app",
            "--citybuddy.identity.enabled=true",
            f"--citybuddy.identity.issuer={ISSUER}",
            f"--citybuddy.identity.user-audience={AUDIENCE}",
            "--citybuddy.identity.current-kid=cb152-current",
            f"--citybuddy.identity.current-private-key-path=/run/cb152/{private_key.name}",
            f"--citybuddy.identity.current-public-key-path=/run/cb152/{public_key.name}",
            "--citybuddy.identity.direct-ttl=15m",
        ],
        auth_env,
        network_alias="cb152-auth",
    )
    state.auth_port = published_port(auth)
    wait_http(f"http://127.0.0.1:{state.auth_port}/auth/jwks", auth, expected={200})

    catalog_topic = f"cb152-catalog-{os.getpid()}"
    catalog_group = f"cb152-catalog-group-{os.getpid()}"
    transaction_topic = f"cb152-seckill-transaction-{os.getpid()}"
    transaction_group = f"cb152-seckill-order-{os.getpid()}"
    timeout_topic = f"cb152-seckill-timeout-{os.getpid()}"
    timeout_group = f"cb152-seckill-timeout-group-{os.getpid()}"
    for topic, topic_type in (
        (catalog_topic, None),
        (transaction_topic, "TRANSACTION"),
        (timeout_topic, "DELAY"),
    ):
        args = [
            "run",
            "--rm",
            "--no-deps",
            "rocketmq-admin",
            "updateTopic",
            "--namesrvAddr",
            "rocketmq-namesrv:9876",
            "--clusterName",
            "DefaultCluster",
            "--topic",
            topic,
            "--readQueueNums",
            "4",
            "--writeQueueNums",
            "4",
        ]
        if topic_type:
            args.extend(["-a", f"+message.type={topic_type}"])
        compose(state, *args)
    for group in (catalog_group, transaction_group, timeout_group):
        compose(
            state,
            "run",
            "--rm",
            "--no-deps",
            "rocketmq-admin",
            "updateSubGroup",
            "--namesrvAddr",
            "rocketmq-namesrv:9876",
            "--clusterName",
            "DefaultCluster",
            "--groupName",
            group,
            "--consumeEnable",
            "true",
        )

    commerce_env = common_env | {
        "SPRING_DATASOURCE_PASSWORD": state.env["MYSQL_COMMERCE_APP_PASSWORD"]
    }
    commerce = spawn_application_child(
        state,
        "commerce",
        [
            "java",
            "-jar",
            "commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar",
            "--server.port=8080",
            "--spring.datasource.url=jdbc:mysql://mysql:3306/commerce_db?useSSL=false&allowPublicKeyRetrieval=true",
            "--spring.datasource.username=commerce_app",
            "--spring.datasource.hikari.connection-timeout=5000",
            f"--spring.data.redis.url=redis://:{state.env['REDIS_COMMERCE_PASSWORD']}@redis-commerce:6379/0",
            "--citybuddy.catalog.enabled=true",
            f"--citybuddy.catalog.issuer={ISSUER}",
            f"--citybuddy.catalog.user-audience={AUDIENCE}",
            "--citybuddy.catalog.jwks-url=http://cb152-auth:8080/auth/jwks",
            "--citybuddy.catalog.jwks-cache-ttl=30s",
            "--citybuddy.catalog.required-permission=catalog:read",
            "--citybuddy.catalog.rocketmq-endpoints=rocketmq-broker-proxy:8081",
            f"--citybuddy.catalog.rocketmq-topic={catalog_topic}",
            f"--citybuddy.catalog.rocketmq-consumer-group={catalog_group}",
            "--citybuddy.catalog.worker-initial-delay-ms=3600000",
            "--citybuddy.catalog.worker-delay-ms=3600000",
            "--citybuddy.seckill.enabled=true",
            "--citybuddy.seckill.order.enabled=true",
            "--citybuddy.seckill.order.rocketmq-endpoints=rocketmq-broker-proxy:8081",
            f"--citybuddy.seckill.order.rocketmq-topic={transaction_topic}",
            f"--citybuddy.seckill.order.rocketmq-consumer-group={transaction_group}",
            f"--citybuddy.seckill.order.unpaid-timeout={state.profile.unpaid_timeout_seconds}s",
            "--citybuddy.seckill.order.receive-await=1s",
            "--citybuddy.seckill.order.receive-invisible-duration=30s",
            "--citybuddy.seckill.order.worker-initial-delay-ms=250",
            "--citybuddy.seckill.order.worker-delay-ms=250",
            "--citybuddy.seckill.order.resolution-worker-initial-delay=250",
            "--citybuddy.seckill.order.resolution-worker-delay=250",
            "--citybuddy.seckill.timeout.rocketmq-endpoints=rocketmq-broker-proxy:8081",
            f"--citybuddy.seckill.timeout.rocketmq-topic={timeout_topic}",
            f"--citybuddy.seckill.timeout.rocketmq-consumer-group={timeout_group}",
            "--citybuddy.seckill.timeout.receive-await=1s",
            "--citybuddy.seckill.timeout.receive-invisible-duration=30s",
            "--citybuddy.seckill.timeout.dispatch-worker-initial-delay-ms=250",
            "--citybuddy.seckill.timeout.dispatch-worker-delay-ms=250",
            "--citybuddy.seckill.timeout.consumer-worker-initial-delay-ms=250",
            "--citybuddy.seckill.timeout.consumer-worker-delay-ms=250",
        ],
        commerce_env,
        network_alias="cb152-commerce",
    )
    state.commerce_port = published_port(commerce)
    wait_http(f"http://127.0.0.1:{state.commerce_port}/api/products", commerce, expected={401})


def seed_fixture(state: RunState) -> dict[str, Any]:
    profile = state.profile
    measured_users = [f"cb152-load-{index:04d}" for index in range(1, profile.sample_count + 1)]
    warm_users = [f"cb152-warm-{index:03d}" for index in range(1, profile.warmup_samples + 1)]
    control_users = ["cb152-q07-owner", "cb152-q08-owner", "cb152-q08-other"]
    users = measured_users + warm_users + control_users
    password = hashlib.sha256(state.hash_salt + b"password").hexdigest()[:48]
    password_hash = run(
        ["uv", "run", "python", "scripts/hash_test_credential.py", password]
    ).stdout.strip()
    state.secrets.extend([password.encode(), password_hash.encode()])
    starts = utc_now() - dt.timedelta(minutes=2)
    ends = utc_now() + dt.timedelta(minutes=30)
    activity_id = f"cb152-measured-{os.getpid()}"
    warm_activity_id = f"cb152-warmup-{os.getpid()}"
    product_id = f"cb152-product-{os.getpid()}"
    warm_product_id = f"cb152-warm-product-{os.getpid()}"
    principal_rows = []
    credential_rows = []
    for name in users:
        principal_rows.append(
            f"('{uuid.uuid4()}','{name}','{name}','ACTIVE','catalog:read seckill:reserve')"
        )
        credential_rows.append(
            "((SELECT principal_id FROM auth_user_principal "
            f"WHERE login_identifier='{name}'),'{password_hash}')"
        )
    measured_quota = profile.sample_count + 2
    auth_script = f"""
INSERT INTO auth_user_principal (principal_id, subject, login_identifier, state, permissions) VALUES
{",".join(principal_rows)};
INSERT INTO auth_login_credential (principal_id, password_hash) VALUES
{",".join(credential_rows)};
"""
    mysql_command(state, "auth_app", state.env["MYSQL_AUTH_APP_PASSWORD"], auth_script)
    commerce_script = f"""
INSERT INTO product
  (product_id, name, description, price_minor, currency, stock_quantity, available,
   publication_state, publication_version)
VALUES
  ('{product_id}','CB-152 measured product','Synthetic local measurement fixture',
   100,'AUD',{measured_quota},TRUE,'PUBLISHED',1),
  ('{warm_product_id}','CB-152 warm-up product','Synthetic isolated warm-up fixture',
   100,'AUD',{profile.warmup_samples},TRUE,'PUBLISHED',1);
INSERT INTO seckill_activity
  (activity_id, product_id, starts_at, ends_at, state, allocated_quota, projection_version)
VALUES
  ('{activity_id}','{product_id}','{sql_time(starts)}','{sql_time(ends)}','ACTIVE',{measured_quota},1),
  ('{warm_activity_id}','{warm_product_id}','{sql_time(starts)}','{sql_time(ends)}','ACTIVE',{profile.warmup_samples},1);
"""
    mysql_command(state, "commerce_app", state.env["MYSQL_COMMERCE_APP_PASSWORD"], commerce_script)

    def projection(aid: str, quota: int) -> str:
        return json.dumps(
            {
                "activityId": aid,
                "projectionVersion": 1,
                "startsAt": iso(starts),
                "endsAt": iso(ends),
                "state": "ACTIVE",
                "remainingQuota": quota,
            },
            separators=(",", ":"),
        )

    for aid, quota in ((activity_id, measured_quota), (warm_activity_id, profile.warmup_samples)):
        compose(
            state,
            "exec",
            "-T",
            "redis-commerce",
            "redis-cli",
            "--no-auth-warning",
            "--pass",
            state.env["REDIS_COMMERCE_PASSWORD"],
            "SET",
            f"commerce:seckill:activity:{aid}",
            projection(aid, quota),
        )
    return {
        "password": password,
        "measuredUsers": measured_users,
        "warmUsers": warm_users,
        "controlUsers": control_users,
        "activityId": activity_id,
        "warmActivityId": warm_activity_id,
        "productId": product_id,
        "warmProductId": warm_product_id,
        "quota": measured_quota,
        "starts": starts,
        "ends": ends,
    }


def login(state: RunState, identifier: str, password: str) -> str:
    response = http(
        "POST",
        f"http://127.0.0.1:{state.auth_port}/auth/login",
        body={"loginIdentifier": identifier, "password": password},
    )
    if response.status != 200:
        raise RuntimeError(f"login failed for synthetic user: {response.status}")
    value = strict_json_text(response.body.decode(), source="login response")
    if not isinstance(value, dict) or set(value) != {"accessToken", "tokenType", "expiresIn"}:
        raise RuntimeError("login response schema mismatch")
    token = value["accessToken"]
    if not isinstance(token, str) or value["tokenType"] != "Bearer" or value["expiresIn"] < 600:
        raise RuntimeError("direct-user token lacks the required safety margin")
    state.secrets.append(token.encode())
    return token


def seed_signing_key(state: RunState) -> tuple[Path, Path]:
    private_key = state.temp_dir / "cb152-private.pem"
    public_key = state.temp_dir / "cb152-public.pem"
    state.absent_paths.extend([("rsaPrivateKey", private_key), ("rsaPublicKey", public_key)])
    run(
        [
            "openssl",
            "genpkey",
            "-algorithm",
            "RSA",
            "-pkeyopt",
            "rsa_keygen_bits:2048",
            "-out",
            str(private_key),
        ]
    )
    run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)])
    private_key.chmod(0o600)
    mysql_command(
        state,
        "auth_app",
        state.env["MYSQL_AUTH_APP_PASSWORD"],
        "INSERT INTO auth_signing_key_metadata (kid, state, activated_at, retire_after) "
        "VALUES ('cb152-current','CURRENT',CURRENT_TIMESTAMP(6),NULL);",
    )
    state.secrets.extend([private_key.read_bytes(), public_key.read_bytes()])
    return private_key, public_key


def write_token_csv(path: Path, rows: Sequence[tuple[int, str, str]]) -> None:
    path.write_bytes(canonical_csv(["sampleIndex", "token", "idempotencyKey"], rows))
    path.chmod(0o600)


def run_jmeter(
    state: RunState,
    jmeter: Path,
    *,
    activity_id: str,
    token_rows: Sequence[tuple[int, str, str]],
    threads: int,
    loops: int,
    ramp_seconds: int,
    label: str,
) -> Path:
    data_file = state.temp_dir / f"{label}-tokens.csv"
    jtl = state.temp_dir / f"{label}.jtl"
    log = state.temp_dir / f"{label}-jmeter.log"
    write_token_csv(data_file, token_rows)
    state.absent_paths.extend([(f"{label}TokenFile", data_file), (f"{label}Jtl", jtl)])
    run(
        [
            str(jmeter),
            "-n",
            "-t",
            str(JMX_PLAN),
            "-l",
            str(jtl),
            "-j",
            str(log),
            "-Jhost=127.0.0.1",
            f"-Jport={state.commerce_port}",
            f"-JactivityId={activity_id}",
            f"-JexpectedVersion={PROJECTION_VERSION}",
            f"-JdataFile={data_file}",
            f"-Jthreads={threads}",
            f"-Jloops={loops}",
            f"-JrampSeconds={ramp_seconds}",
            f"-JconnectTimeoutMs={state.profile.connect_timeout_ms}",
            f"-JresponseTimeoutMs={state.profile.response_timeout_ms}",
            "-Jjmeter.save.saveservice.output_format=xml",
            "-Jjmeter.save.saveservice.response_data=true",
            "-Jjmeter.save.saveservice.samplerData=false",
            "-Jjmeter.save.saveservice.requestHeaders=false",
            "-Jjmeter.save.saveservice.responseHeaders=false",
            "-Jjmeter.save.saveservice.url=false",
            "-Jjmeter.save.saveservice.bytes=true",
            "-Jjmeter.save.saveservice.connect_time=true",
            "-Jjmeter.save.saveservice.latency=true",
            "-Jjmeter.save.saveservice.timestamp_format=ms",
        ],
        capture=True,
    )
    if not jtl.is_file():
        raise RuntimeError("JMeter did not produce its temporary JTL")
    return jtl


def parse_jtl(
    state: RunState, path: Path, expected_count: int, expected_activity_id: str
) -> list[PublicRecord]:
    records: list[PublicRecord] = []
    indexes: set[int] = set()
    for _, element in ET.iterparse(path, events=("end",)):
        if element.tag not in {"httpSample", "sample"}:
            continue
        label = element.attrib.get("lb", "")
        match = re.fullmatch(r"cb152-([0-9]+)", label)
        if not match:
            raise RuntimeError(f"unexpected JMeter sample label: {label}")
        sample_index = int(match.group(1))
        response_data = element.find("responseData")
        body_text = (
            response_data.text
            if response_data is not None and response_data.text is not None
            else ""
        )
        response = HttpResult(int(element.attrib.get("rc", "0")), body_text.encode())
        public = classify_public(
            state,
            response,
            operation="submit",
            expected_activity_id=expected_activity_id,
        )
        public.sanitized.update(
            {
                "sampleIndex": sample_index,
                "startTimestampMs": int(element.attrib["ts"]),
                "elapsedMs": int(element.attrib["t"]),
                "latencyMs": int(element.attrib.get("lt", "0")),
                "connectTimeMs": int(element.attrib.get("ct", "0")),
                "responseCode": response.status,
                "jmeterSuccess": element.attrib.get("s") == "true",
                "responseBytes": int(element.attrib.get("by", str(len(response.body)))),
            }
        )
        records.append(public)
        indexes.add(sample_index)
        element.clear()
        if len(records) > expected_count:
            raise RuntimeError("JMeter produced more samples than declared")
    if len(records) != expected_count or indexes != set(range(1, expected_count + 1)):
        raise RuntimeError("JMeter sample set is not exact and complete")
    return sorted(records, key=lambda record: record.sanitized["sampleIndex"])


def q01_artifact(state: RunState, fixture: dict[str, Any]) -> bytes:
    sql = """SELECT a.activity_id, a.product_id, a.state, a.allocated_quota,
       a.projection_version, p.stock_quantity
FROM seckill_activity AS a
JOIN product AS p ON p.product_id = a.product_id
WHERE a.activity_id = ? AND p.product_id = ?"""
    header, rows = prepared_query(state, sql, [fixture["activityId"], fixture["productId"]])
    return canonical_csv(header, rows)


def query_csv(
    state: RunState, sql: str, values: Sequence[str | int]
) -> tuple[bytes, list[str], list[list[str]]]:
    header, rows = prepared_query(state, sql, values)
    return canonical_csv(header, rows), header, rows


Q02_SQL = """SELECT COUNT(*) AS total_reservations,
       COUNT(DISTINCT reservation_id) AS distinct_reservations,
       COUNT(DISTINCT user_subject, activity_id) AS distinct_user_activity,
       (SELECT COUNT(*) FROM (
          SELECT user_subject, activity_id, idempotency_key
          FROM seckill_reservation WHERE activity_id = ?
          GROUP BY user_subject, activity_id, idempotency_key HAVING COUNT(*) > 1
        ) AS duplicate_keys) AS duplicate_idempotency_groups
FROM seckill_reservation WHERE activity_id = ?"""

Q03_SQL = """SELECT SUM(state = 'PENDING') AS pending_count,
       SUM(state = 'ADMITTED') AS admitted_count,
       SUM(state = 'REJECTED') AS rejected_count,
       SUM(state = 'ORDERED') AS ordered_count,
       SUM(state = 'CANCELLED') AS cancelled_count,
       SUM(state NOT IN ('PENDING','ADMITTED','REJECTED','ORDERED','CANCELLED')) AS unknown_state,
       SUM(state IN ('PENDING','ADMITTED')
           AND transaction_resolution_due_at <= ?) AS overdue_nonterminal
FROM seckill_reservation WHERE activity_id = ?"""

Q04_SQL = """SELECT
  (SELECT COUNT(*) FROM seckill_reservation
   WHERE activity_id = ? AND state IN ('ORDERED','CANCELLED'))
     AS successful_reservations,
  (SELECT COUNT(*) FROM seckill_order WHERE activity_id = ?) AS orders_for_activity,
  (SELECT COUNT(*) FROM seckill_reservation r
   LEFT JOIN seckill_order o ON o.reservation_id = r.reservation_id
   WHERE r.activity_id = ? AND r.state IN ('ORDERED','CANCELLED')
     AND o.order_id IS NULL) AS missing_orders,
  (SELECT COUNT(*) FROM seckill_order o
   LEFT JOIN seckill_reservation r ON r.reservation_id = o.reservation_id
   WHERE o.activity_id = ? AND r.reservation_id IS NULL) AS orphan_orders,
  (SELECT COUNT(*) FROM (
     SELECT reservation_id FROM seckill_order WHERE activity_id = ?
     GROUP BY reservation_id HAVING COUNT(*) > 1
   ) AS duplicate_order_groups) AS duplicate_orders,
  (SELECT COUNT(*) FROM seckill_order o
   JOIN seckill_reservation r ON r.reservation_id = o.reservation_id
   WHERE o.activity_id = ? AND
     (r.state NOT IN ('ORDERED','CANCELLED') OR r.order_id <> o.order_id
      OR r.user_subject <> o.user_subject OR r.activity_id <> o.activity_id
      OR r.quantity <> o.quantity)) AS binding_mismatches"""

Q05_SQL = """SELECT
  (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = ?
   AND (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id = o.order_id
        AND l.movement_type = 'SECKILL_ORDER_CREATE') <> 1) AS bad_create_count,
  (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = ?
   AND o.status = 'CANCELLED'
   AND (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id = o.order_id
        AND l.movement_type = 'SECKILL_UNPAID_CANCEL') <> 1) AS bad_cancel_count,
  (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = ?
   AND o.status <> 'CANCELLED'
   AND (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id = o.order_id
        AND l.movement_type = 'SECKILL_UNPAID_CANCEL') <> 0) AS unexpected_cancel_count,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   WHERE (l.activity_id = ? OR o.activity_id = ? OR r.activity_id = ?)
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
   WHERE (l.activity_id = ? OR o.activity_id = ? OR r.activity_id = ?)
     AND l.movement_type NOT IN ('SECKILL_ORDER_CREATE','SECKILL_UNPAID_CANCEL'))
    AS unexpected_movement_types,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   WHERE (l.activity_id = ? OR o.activity_id = ? OR r.activity_id = ?)
     AND (o.order_id IS NULL OR r.reservation_id IS NULL)) AS orphan_movements,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   LEFT JOIN seckill_activity a ON a.activity_id = o.activity_id
   WHERE (l.activity_id = ? OR o.activity_id = ? OR r.activity_id = ?)
     AND o.order_id IS NOT NULL AND r.reservation_id IS NOT NULL
     AND (l.reservation_id <> o.reservation_id OR l.activity_id <> o.activity_id
       OR l.product_id <> o.product_id OR r.reservation_id <> o.reservation_id
       OR r.activity_id <> o.activity_id OR NOT (r.order_id <=> o.order_id)
       OR r.user_subject <> o.user_subject OR r.quantity <> o.quantity
       OR a.activity_id IS NULL OR a.product_id <> o.product_id)) AS binding_mismatches"""

Q06_SQL = """SELECT p.stock_quantity AS final_stock,
       ? + COALESCE(SUM(l.inventory_delta), 0) AS expected_final_stock,
       -COALESCE(SUM(l.activity_quota_delta), 0) AS net_consumed_quota,
       COALESCE((SELECT SUM(r.quantity) FROM seckill_reservation r
                 WHERE r.activity_id = ?
                   AND r.state IN ('ADMITTED','ORDERED')), 0) AS active_quantity,
       a.allocated_quota AS final_allocated_quota,
       ? AS baseline_allocated_quota
FROM seckill_activity a
JOIN product p ON p.product_id = a.product_id
LEFT JOIN inventory_ledger l ON l.activity_id = a.activity_id
WHERE a.activity_id = ? AND p.product_id = ?
GROUP BY p.stock_quantity, a.allocated_quota"""

Q07A_SQL = """SELECT r.reservation_id, r.activity_id, r.quantity, r.activity_projection_version,
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
WHERE r.reservation_id = ? AND r.activity_id = ?"""

Q07B_SQL = """SELECT
  (SELECT COUNT(*) FROM (
    SELECT user_subject, activity_id, idempotency_key
    FROM seckill_reservation WHERE activity_id = ?
    GROUP BY user_subject, activity_id, idempotency_key HAVING COUNT(*) > 1
  ) d) AS duplicate_reservation_keys,
  (SELECT COUNT(*) FROM (
    SELECT reservation_id FROM seckill_order WHERE activity_id = ?
    GROUP BY reservation_id HAVING COUNT(*) > 1
  ) d) AS duplicate_order_keys,
  (SELECT COUNT(*) FROM (
    SELECT order_id, movement_type FROM inventory_ledger
    WHERE activity_id = ?
      AND movement_type IN ('SECKILL_ORDER_CREATE','SECKILL_UNPAID_CANCEL')
    GROUP BY order_id, movement_type HAVING COUNT(*) > 1
  ) d) AS duplicate_ledger_keys"""

Q08_SQL = """SELECT reservation_id, user_subject, activity_id, idempotency_key,
       intent_hash, quantity,
       activity_projection_version, state, decision_code, projection_version, order_id,
       transaction_resolution_due_at
FROM seckill_reservation WHERE reservation_id = ?"""

Q09_SQL = """SELECT
  (SELECT COUNT(*) FROM seckill_reservation
   WHERE activity_id = ? AND state IN ('PENDING','ADMITTED')
     AND transaction_resolution_due_at <= ?) AS overdue_reservation_resolution,
  (SELECT COUNT(*) FROM seckill_order
   WHERE activity_id = ? AND status = 'UNPAID'
     AND unpaid_deadline <= ?) AS overdue_unpaid_orders,
  (SELECT COUNT(*) FROM seckill_order
   WHERE activity_id = ? AND timeout_dispatch_state = 'PENDING'
     AND created_at <= ?) AS overdue_timeout_dispatch,
  (SELECT COUNT(*) FROM seckill_order
   WHERE activity_id = ? AND timeout_dispatch_state = 'FAILED')
     AS failed_timeout_dispatch"""


def q08_digest(row: Sequence[str]) -> str:
    encoded = bytearray(b"cb152:q08:canonical-row:v1;")
    for value in row:
        if value == "NULL" or value == "\\N":
            encoded.extend(b"N;")
        else:
            raw = value.encode("utf-8")
            encoded.extend(str(len(raw)).encode() + b":" + raw + b";")
    return hashlib.sha256(encoded).hexdigest()


def collect_reconciliation(
    state: RunState,
    fixture: dict[str, Any],
    measured: list[PublicRecord],
    measured_tokens: dict[str, str],
    q07_initial: PublicRecord,
    q07_initial_at: dt.datetime,
    q07_token: str,
    q07_intent: str,
    q08_initial: PublicRecord,
    q08_token: str,
    other_token: str,
    q01_bytes: bytes,
) -> tuple[dict[str, bytes], dict[str, Any]]:
    if q07_initial.reservation_id is None or q08_initial.reservation_id is None:
        raise RuntimeError("control submission did not return reservation locators")
    all_records = [q07_initial, q08_initial, *measured]
    locator_tokens: dict[str, str] = {}
    for record in measured:
        if record.reservation_id is None:
            raise RuntimeError("measured response lacks reservation locator")
        locator_tokens[record.reservation_id] = measured_tokens[record.reservation_id]
    locator_tokens[q07_initial.reservation_id] = q07_token
    locator_tokens[q08_initial.reservation_id] = q08_token

    deadline = time.monotonic() + state.profile.settlement_timeout_seconds
    while True:
        terminal_count = mysql_scalar(
            state,
            "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ? "
            "AND state IN ('REJECTED','CANCELLED')",
            [fixture["activityId"]],
        )
        total_count = mysql_scalar(
            state,
            "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ?",
            [fixture["activityId"]],
        )
        if total_count == state.profile.sample_count + 2 and terminal_count == total_count:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError(
                f"bounded settlement did not close: terminal={terminal_count} total={total_count}"
            )
        time.sleep(0.5)
    settle_cutoff = utc_now()

    q07_replay1 = submit(state, q07_token, fixture["activityId"], q07_intent)
    q07_replay1_at = utc_now()
    q07_replay2 = submit(state, q07_token, fixture["activityId"], q07_intent)
    q07_replay2_at = utc_now()
    q07_rows = []
    for phase, record, observed_at in (
        ("initial", q07_initial, q07_initial_at),
        ("replay1", q07_replay1, q07_replay1_at),
        ("replay2", q07_replay2, q07_replay2_at),
    ):
        q07_rows.append(
            {
                "caseId": "same-intent-control-1",
                "phase": phase,
                "observedAt": iso(observed_at),
                "responseCode": record.sanitized["responseCode"],
                "classification": record.sanitized["classification"],
                "reservationLocatorHash": record.sanitized["reservationLocatorHash"],
                "activityId": fixture["activityId"],
                "quantity": 1,
                "activityProjectionVersion": PROJECTION_VERSION,
                "state": record.sanitized["state"],
                "decisionCode": record.sanitized["decisionCode"],
                "projectionVersion": record.sanitized["projectionVersion"],
                "durableOrderCreated": record.sanitized["durableOrderCreated"],
                "orderLocatorHash": record.sanitized["orderLocatorHash"],
                "replay": record.sanitized["replay"],
            }
        )

    q08_before_header, q08_before_rows = prepared_query(
        state, Q08_SQL, [q08_initial.reservation_id]
    )
    if len(q08_before_rows) != 1 or len(q08_before_rows[0]) != len(q08_before_header):
        raise RuntimeError("Q08 before rowset is not exactly one row")
    owner_response, owner_poll = poll(
        state, q08_token, q08_initial.reservation_id, fixture["activityId"]
    )
    unknown_id = str(uuid.uuid4())
    unknown_response, _ = poll(state, q08_token, unknown_id, fixture["activityId"])
    other_response, _ = poll(state, other_token, q08_initial.reservation_id, fixture["activityId"])
    q08_controls: list[dict[str, Any]] = []
    for kind, response, locator in (
        ("owner", owner_response, q08_initial.reservation_id),
        ("unknown", unknown_response, unknown_id),
        ("other-owner", other_response, q08_initial.reservation_id),
    ):
        body = strict_json_text(response.body.decode(), source=f"Q08 {kind}")
        if kind == "owner":
            category = message = None
        elif not isinstance(body, dict) or set(body) != {"category", "message"}:
            category = message = None
        else:
            category, message = body["category"], body["message"]
        q08_controls.append(
            {
                "kind": kind,
                "status": response.status,
                "category": category,
                "message": message,
                "reservationLocatorHash": hash_locator(state, "reservation", locator),
            }
        )
    if owner_poll.sanitized["classification"] != "BUSINESS":
        raise RuntimeError("Q08 owner poll did not classify as business")
    _, q08_after_rows = prepared_query(state, Q08_SQL, [q08_initial.reservation_id])
    if len(q08_after_rows) != 1:
        raise RuntimeError("Q08 after rowset is not exactly one row")

    terminal_public: list[dict[str, Any]] = []
    public_by_hash: dict[str, PublicRecord] = {}
    for record in all_records:
        assert record.reservation_id is not None
        terminal = wait_terminal(
            state,
            locator_tokens[record.reservation_id],
            record.reservation_id,
            fixture["activityId"],
            require_cancelled=True,
        )
        public_by_hash[terminal.sanitized["reservationLocatorHash"]] = terminal
        terminal_public.append(
            {
                "reservationLocatorHash": terminal.sanitized["reservationLocatorHash"],
                "state": terminal.sanitized["state"],
                "durableOrderCreated": terminal.sanitized["durableOrderCreated"],
                "orderLocatorHash": terminal.sanitized["orderLocatorHash"],
            }
        )

    artifacts: dict[str, bytes] = {}
    artifacts["raw/reconciliation/q01.csv"] = q01_bytes
    artifacts["raw/reconciliation/q02.csv"], _, _ = query_csv(
        state, Q02_SQL, [fixture["activityId"], fixture["activityId"]]
    )
    artifacts["raw/reconciliation/q03.csv"], _, _ = query_csv(
        state, Q03_SQL, [sql_time(settle_cutoff), fixture["activityId"]]
    )
    q04_csv, q04_header, q04_rows = query_csv(state, Q04_SQL, [fixture["activityId"]] * 6)
    if len(q04_rows) != 1:
        raise RuntimeError("Q04 did not return exactly one scalar row")
    durable_header, durable_rows = prepared_query(
        state,
        "SELECT reservation_id, state, order_id FROM seckill_reservation "
        "WHERE activity_id = ? ORDER BY reservation_id",
        [fixture["activityId"]],
    )
    public_mismatches = 0
    for reservation_id, durable_state, order_id in durable_rows:
        reservation_hash = hash_locator(state, "reservation", reservation_id)
        public = public_by_hash.get(reservation_hash or "")
        durable_order = None if order_id in {"NULL", "\\N"} else order_id
        if (
            public is None
            or public.sanitized["state"] != durable_state
            or public.sanitized["orderLocatorHash"] != hash_locator(state, "order", durable_order)
        ):
            public_mismatches += 1
    artifacts["raw/reconciliation/q04.csv"] = canonical_csv(
        [*q04_header, "public_binding_mismatches"], [[*q04_rows[0], public_mismatches]]
    )
    artifacts["raw/controls/q04.jsonl"] = canonical_jsonl(
        sorted(terminal_public, key=lambda row: row["reservationLocatorHash"])
    )
    q05_values = [fixture["activityId"]] * 15
    artifacts["raw/reconciliation/q05.csv"], _, _ = query_csv(state, Q05_SQL, q05_values)
    artifacts["raw/reconciliation/q06.csv"], _, _ = query_csv(
        state,
        Q06_SQL,
        [
            fixture["quota"],
            fixture["activityId"],
            fixture["quota"],
            fixture["activityId"],
            fixture["productId"],
        ],
    )
    artifacts["raw/controls/q07.jsonl"] = canonical_jsonl(q07_rows)
    q07_header, q07_detail_rows = prepared_query(
        state, Q07A_SQL, [q07_initial.reservation_id, fixture["activityId"]]
    )
    if len(q07_detail_rows) != 1:
        raise RuntimeError("Q07a did not return exactly one detail row")
    raw_detail = dict(zip(q07_header, q07_detail_rows[0], strict=True))
    nulls = {"NULL", "\\N"}
    detail_order_id = None if raw_detail["order_id"] in nulls else raw_detail["order_id"]
    canonical_order = (
        None if raw_detail["canonical_order_id"] in nulls else raw_detail["canonical_order_id"]
    )
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
    q07_detail = [
        hash_locator(state, "reservation", raw_detail["reservation_id"]),
        raw_detail["activity_id"],
        raw_detail["quantity"],
        raw_detail["activity_projection_version"],
        raw_detail["state"],
        raw_detail["decision_code"],
        raw_detail["projection_version"],
        hash_locator(state, "order", detail_order_id) or "",
        raw_detail["order_count"],
        hash_locator(state, "order", canonical_order) or "",
        raw_detail["create_movement_count"],
        raw_detail["cancel_movement_count"],
        raw_detail["movement_linkage_mismatches"],
    ]
    artifacts["raw/reconciliation/q07-details.csv"] = canonical_csv(q07_detail_header, [q07_detail])
    artifacts["raw/reconciliation/q07-duplicates.csv"], _, _ = query_csv(
        state, Q07B_SQL, [fixture["activityId"]] * 3
    )
    artifacts["raw/controls/q08.jsonl"] = canonical_jsonl(q08_controls)
    artifacts["raw/reconciliation/q08.json"] = canonical_json(
        {
            "beforeDigest": q08_digest(q08_before_rows[0]),
            "afterDigest": q08_digest(q08_after_rows[0]),
        }
    )
    observation_at = utc_now()
    artifacts["raw/reconciliation/q09.csv"], _, _ = query_csv(
        state,
        Q09_SQL,
        [
            fixture["activityId"],
            sql_time(observation_at),
            fixture["activityId"],
            sql_time(observation_at),
            fixture["activityId"],
            sql_time(settle_cutoff),
            fixture["activityId"],
        ],
    )
    unexpected = sum(
        record.sanitized["classification"] in {"TRANSPORT_ERROR", "PARSE_ERROR", "UNEXPECTED_ERROR"}
        for record in measured
    )
    unknown = sum(record.sanitized["classification"] == "UNKNOWN" for record in measured)
    artifacts["raw/controls/q09.json"] = canonical_json(
        {
            "unexpectedError": unexpected,
            "unknownClassification": unknown,
            "measuredSampleCount": len(measured),
            "expectedSampleCount": state.profile.sample_count,
        }
    )
    return artifacts, {
        "settleCutoff": settle_cutoff,
        "observationAt": observation_at,
        "dispatchSettleCutoff": settle_cutoff,
    }


def record_count(path: str, data: bytes) -> int:
    if path.endswith(".jsonl"):
        return len(data.splitlines())
    if path.endswith(".csv"):
        return max(0, len(data.splitlines()) - 1)
    return 1


def format_snapshot(paths: Sequence[Path]) -> dict[Path, str]:
    return {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def run_format_hooks(paths: Sequence[Path]) -> None:
    before = format_snapshot(paths)
    relative = [str(path.relative_to(ROOT)) for path in paths]
    for hook in ("trailing-whitespace", "end-of-file-fixer", "mixed-line-ending"):
        run(["uv", "run", "pre-commit", "run", hook, "--files", *relative], capture=True)
    after = format_snapshot(paths)
    if before != after:
        raise RuntimeError("pre-commit format hook rewrote producer output")


def write_payload(directory: Path, payload: dict[str, bytes]) -> list[Path]:
    written: list[Path] = []
    for relative, data in sorted(payload.items()):
        path = directory / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        written.append(path)
    return written


def checksum_bytes(directory: Path) -> bytes:
    lines = []
    for path in sorted(
        path for path in directory.rglob("*") if path.is_file() and path.name != "checksums.sha256"
    ):
        lines.append(
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(directory)}\n"
        )
    return "".join(lines).encode()


def machine_metadata() -> dict[str, Any]:
    memory = run(["sysctl", "-n", "hw.memsize"], check=False).stdout.strip()
    docker = run(
        ["docker", "version", "--format", "{{.Server.Version}}"], check=False
    ).stdout.strip()
    if not memory.isdigit() or not docker or not isinstance(os.cpu_count(), int):
        raise RuntimeError("machine metadata is incomplete")
    return {
        "os": platform.system(),
        "architecture": platform.machine(),
        "logicalCpuCount": os.cpu_count(),
        "memoryBytes": int(memory),
        "dockerServerVersion": docker,
    }


def build_bundle_payload(
    state: RunState,
    fixture: dict[str, Any],
    artifacts: dict[str, bytes],
    timings: dict[str, Any],
) -> dict[str, bytes]:
    measured_rows = [
        strict_json_text(line.decode(), source="in-memory measured record")
        for line in artifacts["raw/measured.jsonl"].splitlines()
    ]
    earliest = min(row["startTimestampMs"] for row in measured_rows)
    latest = max(row["startTimestampMs"] + row["elapsedMs"] for row in measured_rows)
    inventory = [
        {"path": path, "bytes": len(data), "records": record_count(path, data)}
        for path, data in sorted(artifacts.items())
    ]
    manifest = {
        "schemaVersion": 1,
        "sliceId": "CB-152",
        "codeRevision": state.code_revision,
        "environment": "local Docker Compose on macOS; achieved load, not capacity or SLO",
        "machine": machine_metadata(),
        "containerResources": {
            "limits": "compose services have no explicit CPU/memory limits",
            "applicationProcesses": (
                "foreground Auth and Commerce application containers launched as "
                "direct child processes with kernel-assigned host ports"
            ),
        },
        "fixtureOrDatasetVersion": FIXTURE_VERSION,
        "tool": "Apache JMeter",
        "toolVersion": JMETER_VERSION,
        "toolArchiveUrl": JMETER_URL,
        "toolArchiveSha512": JMETER_SHA512,
        "unpaidTimeoutSeconds": state.profile.unpaid_timeout_seconds,
        "settlementTimeoutSeconds": state.profile.settlement_timeout_seconds,
        "jmeterConnectTimeoutMs": state.profile.connect_timeout_ms,
        "jmeterResponseTimeoutMs": state.profile.response_timeout_ms,
        "warmup": {
            "samples": state.profile.warmup_samples,
            "threads": state.profile.warmup_threads,
            "rampSeconds": state.profile.warmup_ramp_seconds,
            "isolation": "separate activity and product",
        },
        "measuredDuration": {
            "model": "fixed sample count",
            "wallClockSeconds": round((latest - earliest) / 1000, 6),
        },
        "concurrencyOrWorkload": {
            "threads": state.profile.threads,
            "loopsPerThread": state.profile.loops,
            "rampSeconds": state.profile.ramp_seconds,
            "quantityPerSubmission": 1,
        },
        "sampleCount": state.profile.sample_count,
        "commands": [
            "make init-local ENV_FILE=<run-env>",
            "make up ENV_FILE=<run-env> COMPOSE_PROJECT_NAME=<unique-project>",
            "Apache JMeter non-GUI fixed-sample warm-up and measured runs "
            f"-JconnectTimeoutMs={state.profile.connect_timeout_ms} "
            f"-JresponseTimeoutMs={state.profile.response_timeout_ms}",
            f"Commerce unpaid timeout {state.profile.unpaid_timeout_seconds}s",
            f"bounded settlement timeout {state.profile.settlement_timeout_seconds}s",
            "make reset-local CONFIRM_RESET_LOCAL=1 ENV_FILE=<run-env> "
            "COMPOSE_PROJECT_NAME=<unique-project>",
            "python reconstruct.py <bundle>",
        ],
        "artifactInventory": {
            "maxRawBytes": MAX_RAW_BYTES,
            "maxFiles": MAX_FILES,
            "maxRecordsPerFile": MAX_RECORDS,
            "files": inventory,
        },
        "cleanupResult": "PASS",
        "activityId": fixture["activityId"],
        "productId": fixture["productId"],
        "activityProjectionVersion": PROJECTION_VERSION,
        "baselineActivityState": "ACTIVE",
        "baselineAllocatedQuota": fixture["quota"],
        "baselineProductStock": fixture["quota"],
        "settleCutoff": iso(timings["settleCutoff"]),
        "observationAt": iso(timings["observationAt"]),
        "dispatchSettleCutoff": iso(timings["dispatchSettleCutoff"]),
        "runOrder": [
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
        ],
        "percentileAlgorithm": "nearest-rank",
        "locatorHashAlgorithm": "per-run-domain-separated-sha256",
    }
    payload = dict(artifacts)
    payload["manifest.json"] = canonical_json(manifest)
    payload["reconstruct.py"] = CHECKER.read_bytes()
    return payload


def scan_sanitized(directory: Path, state: RunState) -> None:
    for path in directory.rglob("*"):
        if not path.is_file():
            continue
        data = path.read_bytes()
        if UUID_RE.search(data):
            raise RuntimeError(
                f"raw UUID leaked into committed artifact: {path.relative_to(directory)}"
            )
        for marker in (
            b"Authorization",
            b"Bearer ",
            b"BEGIN PRIVATE KEY",
            b"accessToken",
            str(state.temp_dir).encode(),
        ):
            if marker in data:
                raise RuntimeError(
                    f"forbidden sensitive marker in artifact: {path.relative_to(directory)}"
                )
        for secret in state.secrets:
            if len(secret) >= 8 and secret in data:
                raise RuntimeError(
                    f"runtime secret leaked into artifact: {path.relative_to(directory)}"
                )


def finalize_candidate(directory: Path, payload: dict[str, bytes]) -> list[Path]:
    written = write_payload(directory, payload)
    result = reconstruct(directory, verify_result=False, verify_integrity=False)
    result_path = directory / "result.json"
    result_path.write_bytes(canonical_json(result))
    written.append(result_path)
    checksum = directory / "checksums.sha256"
    checksum.write_bytes(checksum_bytes(directory))
    written.append(checksum)
    reconstruct(directory)
    return sorted(written)


def publish_formal(state: RunState, payload: dict[str, bytes]) -> Path:
    if FINAL_BUNDLE.exists():
        raise RuntimeError(
            "formal CB-152 bundle already exists; refusing to replace the first result"
        )
    staging = EVIDENCE_ROOT / f".CB-152-staging-{os.getpid()}"
    if staging.exists():
        raise RuntimeError("CB-152 publication staging path already exists")
    EVIDENCE_ROOT.mkdir(parents=True, exist_ok=True)
    staging.mkdir(mode=0o700)
    try:
        initial_paths = write_payload(staging, payload)
        provisional = reconstruct(staging, verify_result=False, verify_integrity=False)
        result_path = staging / "result.json"
        result_path.write_bytes(canonical_json(provisional))
        initial_paths.append(result_path)
        run(["git", "add", "--", str(staging.relative_to(ROOT))])
        run_format_hooks(sorted(initial_paths))
        if (
            run(
                ["git", "diff", "--exit-code", "--", str(staging.relative_to(ROOT))], check=False
            ).returncode
            != 0
        ):
            raise RuntimeError("payload format hooks changed the worktree")
        checksum_path = staging / "checksums.sha256"
        checksum_path.write_bytes(checksum_bytes(staging))
        run(["git", "add", "--", str(checksum_path.relative_to(ROOT))])
        complete_paths = sorted([*initial_paths, checksum_path])
        run_format_hooks(complete_paths)
        run(["uv", "run", "pre-commit", "run", "--all-files"], capture=True)
        if (
            run(
                ["git", "diff", "--exit-code", "--", str(staging.relative_to(ROOT))], check=False
            ).returncode
            != 0
        ):
            raise RuntimeError("full pre-commit changed the staged bundle")
        reconstruct(staging)
        scan_sanitized(staging, state)
        os.replace(staging, FINAL_BUNDLE)
        if staging.exists():
            raise RuntimeError("atomic publication left the staging directory behind")
        run(["git", "add", "-A", "--", "evidence/measurements"])
        final_paths = sorted(path for path in FINAL_BUNDLE.rglob("*") if path.is_file())
        run_format_hooks(final_paths)
        run(["uv", "run", "pre-commit", "run", "--all-files"], capture=True)
        reconstruct(FINAL_BUNDLE)
        scan_sanitized(FINAL_BUNDLE, state)
        return FINAL_BUNDLE
    except BaseException:
        if staging.exists():
            shutil.rmtree(staging)
        run(["git", "add", "-A", "--", str(staging.relative_to(ROOT))], check=False)
        raise


def smoke_canonicalization(state: RunState, payload: dict[str, bytes]) -> None:
    scratch = EVIDENCE_ROOT / f".CB-152-smoke-{os.getpid()}"
    if scratch.exists():
        raise RuntimeError("smoke artifact path already exists")
    EVIDENCE_ROOT.mkdir(parents=True, exist_ok=True)
    scratch.mkdir(mode=0o700)
    try:
        paths = finalize_candidate(scratch, payload)
        representative_jtl = scratch / "representative.jtl"
        representative_jtl.write_bytes(
            b'<?xml version="1.0" encoding="UTF-8"?>\n<testResults version="1.2">\n</testResults>\n'
        )
        explicit = [
            next(path for path in paths if path.suffix == ".csv"),
            scratch / "manifest.json",
            scratch / "raw/measured.jsonl",
            representative_jtl,
            scratch / "checksums.sha256",
        ]
        run_format_hooks(explicit)
        representative_jtl.unlink()
        reconstruct(scratch)
        scan_sanitized(scratch, state)
    finally:
        if scratch.exists():
            shutil.rmtree(scratch)


def cleanup(state: RunState) -> dict[str, Any]:
    cleanup_error: BaseException | None = None
    try:
        stop_children(state)
    except BaseException as exc:
        cleanup_error = exc
    if state.env_file.is_file():
        state.env_created = True
        print(
            "CB-152 cleanup phase: runEnv=created resetLocal=required "
            f"initLocalStarted={str(state.init_local_started).lower()} "
            f"initLocalCompleted={str(state.init_local_completed).lower()} "
            f"composeUpStarted={str(state.compose_up_started).lower()}"
        )
        try:
            run(
                [
                    "make",
                    "reset-local",
                    "CONFIRM_RESET_LOCAL=1",
                    f"ENV_FILE={state.env_file}",
                    f"COMPOSE_PROJECT_NAME={state.project}",
                ]
            )
        except BaseException as exc:
            if cleanup_error is None:
                cleanup_error = exc
    elif state.compose_up_started or state.children:
        print(
            "CB-152 cleanup phase: runEnv=missing resetLocal=unavailable "
            f"composeUpStarted={str(state.compose_up_started).lower()}"
        )
        if cleanup_error is None:
            cleanup_error = RuntimeError("run env missing after runtime startup")
    else:
        print(
            "CB-152 cleanup phase: runEnv=never-created resetLocal=skipped "
            "runtimeResources=never-created exactResidueQueries=required"
        )
    for child in state.children:
        ps = run(["ps", "-p", str(child.process.pid)], check=False)
        if ps.returncode == 0 and cleanup_error is None:
            cleanup_error = RuntimeError(f"owned child remains after cleanup: {child.kind}")
    filters = {
        "containers": [
            "docker",
            "ps",
            "-aq",
            "--filter",
            f"label=com.docker.compose.project={state.project}",
        ],
        "networks": [
            "docker",
            "network",
            "ls",
            "-q",
            "--filter",
            f"label=com.docker.compose.project={state.project}",
        ],
        "volumes": [
            "docker",
            "volume",
            "ls",
            "-q",
            "--filter",
            f"label=com.docker.compose.project={state.project}",
        ],
    }
    counts: dict[str, int] = {}
    for kind, command in filters.items():
        completed = run(command, check=False)
        if completed.returncode != 0:
            if cleanup_error is None:
                cleanup_error = RuntimeError(f"residue query failed: {kind}")
            counts[kind] = -1
        else:
            counts[kind] = len([line for line in completed.stdout.splitlines() if line.strip()])
            if counts[kind] != 0 and cleanup_error is None:
                cleanup_error = RuntimeError(f"nonzero exact-project {kind} residue")
    try:
        shutil.rmtree(state.temp_dir)
    except BaseException as exc:
        if cleanup_error is None:
            cleanup_error = exc
    absent_entries = []
    for kind, path in [*state.absent_paths, ("temporaryDirectory", state.temp_dir)]:
        absent = not path.exists()
        absent_entries.append({"kind": kind, "absent": absent})
        if not absent and cleanup_error is None:
            cleanup_error = RuntimeError(f"explicit temporary path remains: {kind}")
    residue = {
        "project": hashlib.sha256(f"cb152:project:{state.project}".encode()).hexdigest(),
        "containers": counts.get("containers", -1),
        "networks": counts.get("networks", -1),
        "volumes": counts.get("volumes", -1),
        "childPids": [
            {
                "kind": child.kind,
                "absent": run(["ps", "-p", str(child.process.pid)], check=False).returncode != 0,
            }
            for child in state.children
        ],
        "absentPaths": absent_entries,
    }
    if cleanup_error is not None:
        raise cleanup_error
    return residue


def execute_measurement(
    state: RunState, jmeter: Path
) -> tuple[dict[str, bytes], dict[str, Any], dict[str, Any]]:
    private_key, public_key = seed_signing_key(state)
    start_services(state, private_key, public_key)
    fixture = seed_fixture(state)
    password = fixture["password"]

    tokens = {
        name: login(state, name, password)
        for name in [*fixture["measuredUsers"], *fixture["warmUsers"], *fixture["controlUsers"]]
    }
    readiness = http(
        "GET",
        f"http://127.0.0.1:{state.commerce_port}/api/products",
        token=tokens[fixture["measuredUsers"][0]],
    )
    if readiness.status != 200:
        raise RuntimeError("authenticated Commerce readiness failed")
    q01_bytes = q01_artifact(state, fixture)

    warm_rows = [
        (index, tokens[name], f"cb152-warm-{index:04d}")
        for index, name in enumerate(fixture["warmUsers"], start=1)
    ]
    warm_jtl = run_jmeter(
        state,
        jmeter,
        activity_id=fixture["warmActivityId"],
        token_rows=warm_rows,
        threads=state.profile.warmup_threads,
        loops=state.profile.warmup_samples // state.profile.warmup_threads,
        ramp_seconds=state.profile.warmup_ramp_seconds,
        label="warmup",
    )
    warm_records = parse_jtl(
        state, warm_jtl, state.profile.warmup_samples, fixture["warmActivityId"]
    )
    for record, row in zip(warm_records, warm_rows, strict=True):
        if record.sanitized["classification"] != "BUSINESS" or record.reservation_id is None:
            raise RuntimeError("warm-up produced an unclassified or missing reservation")
        wait_terminal(state, row[1], record.reservation_id, fixture["warmActivityId"])
    warm_open = mysql_scalar(
        state,
        "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ? "
        "AND state IN ('PENDING','ADMITTED')",
        [fixture["warmActivityId"]],
    )
    if warm_open != 0:
        raise RuntimeError("isolated warm-up did not close its admission/order work")

    q07_token = tokens["cb152-q07-owner"]
    q08_token = tokens["cb152-q08-owner"]
    other_token = tokens["cb152-q08-other"]
    q07_intent = "cb152-q07-same-intent"
    q08_intent = "cb152-q08-ownership"
    q07_initial = submit(state, q07_token, fixture["activityId"], q07_intent)
    q07_initial_at = utc_now()
    q08_initial = submit(state, q08_token, fixture["activityId"], q08_intent)
    if q07_initial.reservation_id is None or q08_initial.reservation_id is None:
        raise RuntimeError("control initial submission lacks a reservation locator")
    wait_terminal(state, q07_token, q07_initial.reservation_id, fixture["activityId"])
    wait_terminal(state, q08_token, q08_initial.reservation_id, fixture["activityId"])

    measured_rows = [
        (index, tokens[name], f"cb152-load-{index:04d}")
        for index, name in enumerate(fixture["measuredUsers"], start=1)
    ]
    measured_jtl = run_jmeter(
        state,
        jmeter,
        activity_id=fixture["activityId"],
        token_rows=measured_rows,
        threads=state.profile.threads,
        loops=state.profile.loops,
        ramp_seconds=state.profile.ramp_seconds,
        label="measured",
    )
    measured = parse_jtl(state, measured_jtl, state.profile.sample_count, fixture["activityId"])
    token_by_index = {index: token for index, token, _ in measured_rows}
    measured_tokens: dict[str, str] = {}
    for record in measured:
        if record.reservation_id is not None:
            measured_tokens[record.reservation_id] = token_by_index[record.sanitized["sampleIndex"]]

    cancellation_count = mysql_scalar(
        state,
        "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ? AND state = 'CANCELLED'",
        [fixture["activityId"]],
    )
    cancel_movement_count = mysql_scalar(
        state,
        "SELECT COUNT(*) FROM inventory_ledger WHERE activity_id = ? "
        "AND movement_type = 'SECKILL_UNPAID_CANCEL'",
        [fixture["activityId"]],
    )
    if cancellation_count != 0 or cancel_movement_count != 0:
        raise RuntimeError(
            "unpaid cancellation or quota recycling occurred before measured completion"
        )

    artifacts, timings = collect_reconciliation(
        state,
        fixture,
        measured,
        measured_tokens,
        q07_initial,
        q07_initial_at,
        q07_token,
        q07_intent,
        q08_initial,
        q08_token,
        other_token,
        q01_bytes,
    )
    artifacts["raw/measured.jsonl"] = canonical_jsonl(record.sanitized for record in measured)
    artifacts["raw/fixture.json"] = canonical_json(
        {
            "fixtureVersion": FIXTURE_VERSION,
            "activityId": fixture["activityId"],
            "productId": fixture["productId"],
            "activityProjectionVersion": PROJECTION_VERSION,
            "baselineAllocatedQuota": fixture["quota"],
            "baselineProductStock": fixture["quota"],
            "warmupIsolation": "separate activity and product",
        }
    )
    return artifacts, timings, fixture


def main() -> int:
    profile_name = os.environ.get("CB152_PROFILE", "formal")
    if profile_name not in PROFILES:
        raise SystemExit("CB152_PROFILE must be smoke or formal")
    profile = PROFILES[profile_name]
    branch = run(["git", "branch", "--show-current"]).stdout.strip()
    if branch != "codex/cb152-seckill-measurement":
        raise SystemExit(f"CB-152 measurement must run on its feature branch, found {branch}")
    code_revision = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    if (
        profile.name == "formal"
        and run(["git", "status", "--porcelain"], check=False).stdout.strip()
    ):
        raise SystemExit("formal evidence requires the clean implementation candidate commit")
    if profile.name == "formal" and FINAL_BUNDLE.exists():
        raise SystemExit(
            "formal CB-152 bundle already exists; refusing result selection or overwrite"
        )

    temp_dir = Path(tempfile.mkdtemp(prefix="cb152-measure-"))
    temp_dir.chmod(0o700)
    state = RunState(
        profile=profile,
        temp_dir=temp_dir,
        env_file=temp_dir / "run.env",
        docker_config_dir=temp_dir / "docker-client",
        project=f"citybuddy-cb152-{os.getpid()}",
        code_revision=code_revision,
        hash_salt=os.urandom(32),
    )
    state.absent_paths.append(("generatedEnvFile", state.env_file))
    assert state.docker_config_dir is not None
    state.docker_config_dir.mkdir(mode=0o700)
    (state.docker_config_dir / "config.json").write_bytes(canonical_json({"auths": {}}))
    plugin_dir = state.docker_config_dir / "cli-plugins"
    plugin_dir.mkdir(mode=0o700)
    for plugin_name in ("docker-compose", "docker-buildx"):
        plugin = Path.home() / ".docker" / "cli-plugins" / plugin_name
        if not plugin.is_file():
            raise RuntimeError(f"required Docker plugin is unavailable: {plugin}")
        (plugin_dir / plugin_name).symlink_to(plugin.resolve())
    os.environ["DOCKER_CONFIG"] = str(state.docker_config_dir)
    state.absent_paths.append(("dockerClientConfig", state.docker_config_dir))
    artifacts: dict[str, bytes] | None = None
    timings: dict[str, Any] | None = None
    fixture: dict[str, Any] | None = None
    primary: BaseException | None = None
    residue: dict[str, Any] | None = None
    try:
        jmeter = download_jmeter(state)
        run(
            [
                "./mvnw",
                "-q",
                "-pl",
                "auth-service,commerce-service",
                "-am",
                "-DskipTests",
                "package",
            ]
        )
        state.init_local_started = True
        run(["make", "init-local", f"ENV_FILE={state.env_file}"])
        state.env_created = state.env_file.is_file()
        state.init_local_completed = True
        state.env = read_env(state.env_file)
        state.secrets.extend(value.encode() for value in state.env.values())
        state.compose_up_started = True
        run(["make", "up", f"ENV_FILE={state.env_file}", f"COMPOSE_PROJECT_NAME={state.project}"])
        state.mysql_port = exact_port(state, "mysql", 3306)
        state.redis_port = exact_port(state, "redis-commerce", 6379)
        state.rocketmq_port = exact_port(state, "rocketmq-broker-proxy", 8081)
        artifacts, timings, fixture = execute_measurement(state, jmeter)
    except BaseException as exc:
        primary = exc
    try:
        residue = cleanup(state)
    except BaseException as exc:
        if primary is not None:
            print(f"CB-152 cleanup also failed: {exc}", file=sys.stderr)
        primary = preserve_first_failure(primary, exc)
    if primary is not None:
        raise primary
    assert (
        artifacts is not None
        and timings is not None
        and fixture is not None
        and residue is not None
    )
    artifacts["raw/residue.json"] = canonical_json(residue)
    payload = build_bundle_payload(state, fixture, artifacts, timings)
    if (
        sum(len(data) for path, data in artifacts.items() if path.startswith("raw/"))
        > MAX_RAW_BYTES
    ):
        raise RuntimeError("CB-152 raw artifact budget exceeded")
    if profile.name == "smoke":
        smoke_canonicalization(state, payload)
        print("CB-152 smoke lifecycle, canonicalization, cleanup, and reconstruction: PASS")
    else:
        bundle = publish_formal(state, payload)
        result = strict_json_text((bundle / "result.json").read_text(), source="formal result")
        if not result.get("concurrencyCorrectnessValid"):
            raise RuntimeError("formal run failed Q01-Q09 and was not valid for publication")
        print(f"CB-152 formal evidence published from {code_revision}: {bundle}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
